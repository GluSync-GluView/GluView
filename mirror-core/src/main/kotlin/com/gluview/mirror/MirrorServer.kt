package com.gluview.mirror

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Lado do PACIENTE: espera os seguidores pareados (TCP), manda o histórico que faltar e depois
 * cada leitura nova. Funciona na mesma rede Wi-Fi e também pela internet quando o endereço do
 * paciente é alcançável (IPv6, VPN, redirecionamento de porta). Não guarda nada em servidor.
 *
 * @param keyFor chave do par pelo id; null = par desconhecido ou revogado (conexão recusada).
 * @param history leituras medidas depois de `sinceMs` (o servidor limita a 24 h e 2.000 itens).
 */
class MirrorServer(
    private val port: Int,
    private val keyFor: (ByteArray) -> ByteArray?,
    private val history: (sinceMs: Long) -> List<MirrorReading>,
    private val meta: () -> MirrorMeta,
    private val clock: () -> Long = System::currentTimeMillis,
    private val listener: Listener = object : Listener {},
    private val bindAddress: InetAddress? = null,
    private val pingIntervalMs: Long = 30_000L,
    /** Endereços atuais do paciente (F2.4), mandados no "bem-vindo" para o seguidor guardar. */
    private val candidates: () -> List<MirrorCandidate> = { emptyList() }
) {
    interface Listener {
        fun onFollowerConnected(pairIdHex: String, remote: String) {}
        fun onFollowerDisconnected(pairIdHex: String, reason: String) {}
        fun onError(message: String) {}
    }

    private class Session(val pairId: ByteArray, val key: ByteArray, val socket: Socket, val out: DataOutputStream) {
        val seq = AtomicLong(0)
        val pairHex: String = MirrorCrypto.hex(pairId)
        @Volatile var lastSentEpoch = 0L
    }

    private val sessions = ConcurrentHashMap<Session, Unit>()
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "gluview-mirror").apply { isDaemon = true } }
    private val pinger = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "gluview-mirror-ping").apply { isDaemon = true } }
    @Volatile private var server: ServerSocket? = null

    val boundPort: Int get() = server?.localPort ?: -1
    val connectedPairs: Set<String> get() = sessions.keys.map { it.pairHex }.toSet()

    fun start() {
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(InetSocketAddress(bindAddress, port))
        server = s
        pool.execute { acceptLoop(s) }
        pinger.scheduleWithFixedDelay({ sessions.keys.forEach { send(it, mapOf("t" to "ping", "now" to clock())) } },
            pingIntervalMs, pingIntervalMs, TimeUnit.MILLISECONDS)
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
        ArrayList(sessions.keys).forEach { close(it, "parado") }
        pinger.shutdownNow(); pool.shutdownNow()
    }

    /** Nova leitura do paciente: vai para todos os seguidores conectados (sem repetir). */
    fun publish(reading: MirrorReading) {
        sessions.keys.forEach { s ->
            if (reading.epochMs > s.lastSentEpoch) {
                if (send(s, mapOf("t" to "readings", "items" to listOf(reading.toMap()), "now" to clock())))
                    s.lastSentEpoch = reading.epochMs
            }
        }
    }

    /** Revogou o seguidor: avisa e derruba na hora. Os outros pares não são afetados. */
    fun revoke(pairId: ByteArray) {
        sessions.keys.filter { it.pairId.contentEquals(pairId) }.forEach {
            send(it, mapOf("t" to "bye", "reason" to "revoked"))
            close(it, "revogado")
        }
    }

    private fun acceptLoop(s: ServerSocket) {
        while (!s.isClosed) {
            val sock = try { s.accept() } catch (e: IOException) { break }
            pool.execute { handle(sock) }
        }
    }

    private fun handle(sock: Socket) {
        var session: Session? = null
        try {
            sock.soTimeout = 15_000 // o hello tem de chegar logo
            sock.tcpNoDelay = true
            val input = DataInputStream(BufferedInputStream(sock.getInputStream()))
            val out = DataOutputStream(BufferedOutputStream(sock.getOutputStream()))
            val first = MirrorProtocol.readRaw(input)
            val key = keyFor(first.pairId) ?: run { sock.close(); return } // par desconhecido/revogado: sem resposta
            val hello = MirrorProtocol.open(first, key)
            if (first.seq != 1L || hello.str("t") != "hello") throw IOException("início inválido")
            val challenge = hello.str("c") ?: throw IOException("sem desafio")
            val since = (hello.long("since") ?: 0L).coerceAtLeast(clock() - 24 * 3_600_000L)

            val s = Session(first.pairId, key, sock, out)
            session = s
            val m = meta()
            val addrs = runCatching { MirrorCandidate.ordered(candidates()).map { it.encode() } }.getOrDefault(emptyList())
            if (!send(s, mapOf("t" to "welcome", "c" to challenge, "name" to m.patientName,
                    "th" to m.thresholds.toMap(), "unit" to m.unit, "addrs" to addrs, "now" to clock()))) return
            val backlog = history(since).filter { it.epochMs > since }.sortedBy { it.epochMs }.takeLast(2_000)
            backlog.chunked(200).forEach { chunk ->
                if (!send(s, mapOf("t" to "readings", "items" to chunk.map { it.toMap() }, "now" to clock()))) return
            }
            s.lastSentEpoch = backlog.lastOrNull()?.epochMs ?: since
            sessions[s] = Unit
            listener.onFollowerConnected(s.pairHex, sock.remoteSocketAddress?.toString().orEmpty())

            sock.soTimeout = (pingIntervalMs * 3).toInt()
            var lastSeq = first.seq
            while (!sock.isClosed) {
                val raw = MirrorProtocol.readRaw(input)
                if (!raw.pairId.contentEquals(s.pairId) || raw.seq <= lastSeq) throw IOException("quadro fora de ordem")
                lastSeq = raw.seq
                val msg = MirrorProtocol.open(raw, key)
                if (msg.str("t") == "bye") break
            }
            close(s, "seguidor saiu")
        } catch (e: SocketTimeoutException) {
            session?.let { close(it, "sem resposta") } ?: runCatching { sock.close() }
        } catch (e: Exception) {
            session?.let { close(it, e.message ?: "erro") } ?: runCatching { sock.close() }
            if (session == null) listener.onError("conexão recusada: ${e.message}")
        }
    }

    private fun send(s: Session, msg: Map<String, Any?>): Boolean = try {
        MirrorProtocol.writeFrame(s.out, s.pairId, s.key, s.seq.incrementAndGet(), msg); true
    } catch (e: Exception) {
        close(s, "falha ao enviar"); false
    }

    private fun close(s: Session, reason: String) {
        val was = sessions.remove(s) != null
        runCatching { s.socket.close() }
        if (was) listener.onFollowerDisconnected(s.pairHex, reason)
    }
}
