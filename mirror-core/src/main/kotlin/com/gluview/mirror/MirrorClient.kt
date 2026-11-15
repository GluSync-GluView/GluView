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
 * @param address devolve o endereço a tentar agora (o app troca entre IP salvo, nome achado na
 *   rede local etc.); null = sem endereço conhecido, espera e tenta de novo.
 * @param since a última leitura que o seguidor já tem (para receber só o que falta).
 */
class MirrorClient(
    private val pairId: ByteArray,
    private val key: ByteArray,
    private val address: () -> InetSocketAddress?,
    private val since: () -> Long,
    private val listener: Listener,
    private val clock: () -> Long = System::currentTimeMillis,
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 100_000,
    private val maxBackoffMs: Long = 60_000L
) {
    interface Listener {
        fun onConnected(meta: MirrorMeta) {}
        fun onReadings(items: List<MirrorReading>) {}
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
            val addr = address()
            if (addr != null) {
                connectedOnce = false
                val reason = runCatching { session(addr); "fim" }.getOrElse { it.message ?: it::class.simpleName.orEmpty() }
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

    private fun session(addr: InetSocketAddress) {
        val sock = Socket()
        socket = sock
        sock.use {
            sock.connect(addr, connectTimeoutMs)
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
                            MirrorThresholds.fromMap(msg["th"] as? Map<*, *>), msg.str("unit") ?: "MG_DL"))
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
