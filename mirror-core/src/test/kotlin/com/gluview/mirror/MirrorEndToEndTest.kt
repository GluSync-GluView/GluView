package com.gluview.mirror

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Paciente e seguidor de verdade, por TCP no próprio computador. */
class MirrorEndToEndTest {
    private val pairId = MirrorCrypto.newPairId()
    private val key = MirrorCrypto.newKey()
    private val now = System.currentTimeMillis()
    private val stored = CopyOnWriteArrayList((1..6).map { MirrorReading(now - it * 300_000L, 100.0 + it, "Flat") })
    @Volatile private var revoked = false
    private val server = MirrorServer(
        port = 0,
        keyFor = { id -> if (!revoked && id.contentEquals(pairId)) key else null },
        history = { since -> stored.filter { it.epochMs > since } },
        meta = { MirrorMeta("Paciente", MirrorThresholds(60.0, 75.0, 170.0, 240.0), "MG_DL") },
        bindAddress = InetAddress.getLoopbackAddress(),
        pingIntervalMs = 300L,
        candidates = { listOf(
            MirrorCandidate(MirrorCandidate.Kind.STUN, "203.0.113.7", 17581),
            MirrorCandidate(MirrorCandidate.Kind.LAN, "192.168.0.10", 17581),
            MirrorCandidate(MirrorCandidate.Kind.IPV6, "2001:db8::5", 17581)) }
    ).also { it.start() }
    private val clients = ArrayList<MirrorClient>()

    @After fun tearDown() { clients.forEach { it.stop() }; server.stop() }

    private inner class Collector(val expected: Int) : MirrorClient.Listener {
        val state = FollowerState()
        val got = CountDownLatch(expected)
        val connected = CountDownLatch(1)
        val revokedLatch = CountDownLatch(1)
        @Volatile var meta: MirrorMeta? = null
        @Volatile var alive = 0L
        @Volatile var via: InetSocketAddress? = null
        override fun onLinkAddress(address: InetSocketAddress) { via = address }
        override fun onConnected(meta: MirrorMeta) { this.meta = meta; connected.countDown() }
        override fun onReadings(items: List<MirrorReading>) {
            state.merge(items, System.currentTimeMillis()).forEach { _ -> got.countDown() }
        }
        override fun onAlive(nowMs: Long) { alive = nowMs }
        override fun onRevoked() { revokedLatch.countDown() }
    }

    private fun client(c: Collector, since: () -> Long = { c.state.lastEpoch() }, k: ByteArray = key,
                       addrs: (() -> List<InetSocketAddress>)? = null) =
        MirrorClient(pairId, k, addrs ?: { listOf(InetSocketAddress(InetAddress.getLoopbackAddress(), server.boundPort)) }, since, c,
            readTimeoutMs = 3_000, maxBackoffMs = 500L).also { clients.add(it); it.start() }

    @Test fun recebeHistoricoMetaEAoVivo() {
        val c = Collector(7)
        client(c)
        assertTrue(c.connected.await(5, TimeUnit.SECONDS))
        assertEquals("Paciente", c.meta!!.patientName)
        assertEquals(75.0, c.meta!!.thresholds.low, 0.0)
        waitFor { server.connectedPairs.isNotEmpty() }
        val live = MirrorReading(now, 150.0, "SingleUp", 5)
        stored.add(live)
        server.publish(live)
        server.publish(live) // repetido: não pode chegar duas vezes
        assertTrue(c.got.await(5, TimeUnit.SECONDS))
        assertEquals(7, c.state.all().size)
        assertEquals(live, c.state.latest())
        Thread.sleep(800)
        assertTrue("ping mantém o link vivo", System.currentTimeMillis() - c.alive < 1_000)
    }

    @Test fun reconectaEPegaSoOQueFaltou() {
        val c = Collector(6)
        val cl = client(c)
        assertTrue(c.got.await(5, TimeUnit.SECONDS))
        cl.stop()
        waitFor { server.connectedPairs.isEmpty() }
        val gap = listOf(MirrorReading(now + 60_000L, 130.0, "Flat"), MirrorReading(now + 120_000L, 131.0, "Flat"))
        stored.addAll(gap) // chegaram enquanto o seguidor estava fora
        val c2 = Collector(2)
        c2.state.merge(c.state.all(), System.currentTimeMillis())
        client(c2)
        assertTrue(c2.got.await(5, TimeUnit.SECONDS))
        assertEquals(8, c2.state.all().size)
    }

    @Test fun chaveErradaNaoConecta() {
        val c = Collector(1)
        client(c, k = MirrorCrypto.newKey())
        assertTrue(!c.connected.await(1500, TimeUnit.MILLISECONDS))
        assertTrue(server.connectedPairs.isEmpty())
    }

    @Test fun revogarDerrubaEAvisa() {
        val c = Collector(6)
        client(c)
        assertTrue(c.got.await(5, TimeUnit.SECONDS))
        waitFor { server.connectedPairs.isNotEmpty() }
        revoked = true
        server.revoke(pairId)
        assertTrue(c.revokedLatch.await(5, TimeUnit.SECONDS))
    }

    @Test fun quadroRepetidoDerrubaAConexao() {
        // Um "seguidor" falso que manda o hello certo e depois repete o mesmo quadro (replay).
        val sock = Socket(InetAddress.getLoopbackAddress(), server.boundPort)
        sock.soTimeout = 3_000
        val out = DataOutputStream(BufferedOutputStream(sock.getOutputStream()))
        MirrorProtocol.writeFrame(out, pairId, key, 1, mapOf("t" to "hello", "c" to "x", "since" to now))
        val input = DataInputStream(sock.getInputStream())
        val welcome = MirrorProtocol.open(MirrorProtocol.readRaw(input), key)
        assertEquals("x", welcome["c"])
        waitFor { server.connectedPairs.isNotEmpty() }
        MirrorProtocol.writeFrame(out, pairId, key, 1, mapOf("t" to "ping"))
        waitFor { server.connectedPairs.isEmpty() }
        sock.close()
    }

    @Test fun bemVindoTrazOsEnderecosEmOrdem() {
        val c = Collector(1)
        client(c)
        assertTrue(c.connected.await(5, TimeUnit.SECONDS))
        assertEquals(listOf(MirrorCandidate.Kind.LAN, MirrorCandidate.Kind.IPV6, MirrorCandidate.Kind.STUN),
            c.meta!!.candidates.map { it.kind })
        assertEquals("2001:db8::5", c.meta!!.candidates[1].host)
    }

    @Test fun primeiroEnderecoMortoUsaOProximo() {
        // Porta fechada no primeiro endereço (como o IP de casa quando a pessoa está na rua).
        val dead = java.net.ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        val c = Collector(6)
        client(c, addrs = { listOf(InetSocketAddress(InetAddress.getLoopbackAddress(), dead),
            InetSocketAddress(InetAddress.getLoopbackAddress(), server.boundPort)) })
        assertTrue(c.got.await(5, TimeUnit.SECONDS))
        assertEquals(server.boundPort, c.via!!.port)
    }

    private fun waitFor(cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (!cond()) { if (System.currentTimeMillis() > end) throw AssertionError("tempo esgotado"); Thread.sleep(20) }
    }
}
