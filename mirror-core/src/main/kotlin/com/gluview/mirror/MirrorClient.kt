package com.gluview.mirror

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicLong

/**
 * Lado do SEGUIDOR: conecta no paciente, pede o que falta desde a última leitura que já tem e
 * fica recebendo. Reconecta sozinho (2 s, 4 s, ... até 60 s). Só aceita dados depois do
 * "bem-vindo" com o MESMO desafio que mandou (sessão nova, não uma gravação repetida).
 *
 * @param addresses endereços a tentar agora, EM ORDEM (F2.4: rede local → IPv6 → público informado
 *   → STUN); o primeiro que aceitar a conexão é usado. Lista vazia = espera e tenta de novo.
 * @param since a última leitura que o seguidor já tem (para receber só o que falta).
 */
class MirrorClient(
    private val pairId: ByteArray,
    private val key: ByteArray,
    private val addresses: () -> List<InetSocketAddress>,
    private val since: () -> Long,
    private val listener: Listener,
    private val clock: () -> Long = System::currentTimeMillis,
    private val connectTimeoutMs: Int = 6_000,
    private val readTimeoutMs: Int = 100_000,
    private val maxBackoffMs: Long = 60_000L
) {
    interface Listener {
        fun onConnected(meta: MirrorMeta) {}
        fun onReadings(items: List<MirrorReading>) {}
        /** Conectou por este endereço (antes do "bem-vindo"). */
        fun onLinkAddress(address: InetSocketAddress) {}
        /** Qualquer quadro válido do paciente (inclusive o "ping"): o link está vivo. */
        fun onAlive(nowMs: Long) {}
        fun onDisconnected(reason: String) {}
        fun onRevoked() {}
    }

    @Volatile private var running = false
    @Volatile private var socket: Socket? = null
    private var thread: Thread? = null
    private val random = SecureRandom()

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "gluview-mirror-client").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        runCatching { socket?.close() }
        thread?.interrupt()
    }

    /** Força uma tentativa já (ex.: a rede voltou). */
    fun reconnectNow() { runCatching { socket?.close() }; thread?.interrupt() }

    private fun loop() {
        var backoff = 2_000L
        while (running) {
            val addrs = runCatching { addresses() }.getOrDefault(emptyList())
            if (addrs.isNotEmpty()) {
                connectedOnce = false
                val reason = runCatching { session(addrs); "fim" }.getOrElse { it.message ?: it::class.simpleName.orEmpty() }
                if (!running) break
                listener.onDisconnected(reason)
                if (reason == REVOKED) { running = false; listener.onRevoked(); break }
                // Chegou a conectar: a queda é nova, volta a tentar rápido.
                if (connectedOnce) backoff = 1_000L
            }
            try { Thread.sleep(backoff) } catch (_: InterruptedException) { backoff = 500L }
            backoff = (backoff * 2).coerceAtMost(maxBackoffMs)
        }
    }

    @Volatile private var connectedOnce = false

    /** Tenta os endereços em ordem; devolve o primeiro socket conectado ou lança o último erro. */
    private fun connectFirst(addrs: List<InetSocketAddress>): Socket {
        var last: Exception? = null
        for (addr in addrs) {
            if (!running) break
            val sock = Socket()
            socket = sock
            try {
                sock.connect(addr, connectTimeoutMs)
                listener.onLinkAddress(addr)
                return sock
            } catch (e: Exception) {
                runCatching { sock.close() }
                last = e
            }
        }
        throw last ?: IOException("parado")
    }

    private fun session(addrs: List<InetSocketAddress>) {
        val sock = connectFirst(addrs)
        sock.use {
            sock.soTimeout = readTimeoutMs
            sock.tcpNoDelay = true
            val input = DataInputStream(BufferedInputStream(sock.getInputStream()))
            val out = DataOutputStream(BufferedOutputStream(sock.getOutputStream()))
            val seq = AtomicLong(0)
            val challenge = MirrorCrypto.b64(ByteArray(16).also { random.nextBytes(it) })
            MirrorProtocol.writeFrame(out, pairId, key, seq.incrementAndGet(),
                mapOf("t" to "hello", "c" to challenge, "since" to since(), "v" to 1))

            var lastSeq = 0L
            var welcomed = false
            while (running) {
                val raw = MirrorProtocol.readRaw(input)
                if (!raw.pairId.contentEquals(pairId) || raw.seq <= lastSeq) throw IOException("quadro fora de ordem")
                lastSeq = raw.seq
                val msg = MirrorProtocol.open(raw, key)
                when (msg.str("t")) {
                    "welcome" -> {
                        if (msg.str("c") != challenge) throw IOException("desafio não confere")
                        welcomed = true
                        connectedOnce = true
                        listener.onConnected(MirrorMeta(msg.str("name").orEmpty(),
                            MirrorThresholds.fromMap(msg["th"] as? Map<*, *>), msg.str("unit") ?: "MG_DL",
                            MirrorCandidate.decodeList(msg["addrs"] as? List<*>)))
                        listener.onAlive(clock())
                    }
                    "readings" -> {
                        if (!welcomed) throw IOException("dados antes do bem-vindo")
                        val items = (msg["items"] as? List<*>).orEmpty().mapNotNull { (it as? Map<*, *>)?.let(MirrorReading::fromMap) }
                        listener.onAlive(clock())
                        if (items.isNotEmpty()) listener.onReadings(items)
                    }
                    "ping" -> {
                        if (!welcomed) throw IOException("ping antes do bem-vindo")
                        listener.onAlive(clock())
                        MirrorProtocol.writeFrame(out, pairId, key, seq.incrementAndGet(), mapOf("t" to "ping", "now" to clock()))
                    }
                    "bye" -> if (msg.str("reason") == "revoked") throw IOException(REVOKED) else return
                }
            }
        }
    }

    private companion object { const val REVOKED = "revogado pelo paciente" }
}
