package com.gluview.mirror

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MirrorCoreTest {
    @Test fun protocoloInicial() = assertEquals(1, MirrorCore.PROTOCOL_VERSION)

    @Test fun jsonIdaEVolta() {
        val v = mapOf("a" to 1L, "b" to "x\"y\\z\nç", "c" to listOf(true, null, 1.5), "d" to mapOf<String, Any?>())
        assertEquals(v, Json.parse(Json.write(v)))
        assertEquals(listOf(1L, -2L, 3.25), Json.parse(" [1, -2, 3.25] "))
        assertEquals("\u00e9", Json.parse("\"\\u00e9\""))
    }

    @Test fun jsonInvalidoLanca() {
        for (bad in listOf("{", "[1,]", "{\"a\" 1}", "tru", "\"abc", "{} x")) {
            try { Json.parse(bad); fail("aceitou $bad") } catch (_: Json.ParseException) {}
        }
    }

    @Test fun cifraAbreComAChaveCertaEFalhaComOutra() {
        val k = MirrorCrypto.newKey()
        val sealed = MirrorCrypto.seal(k, byteArrayOf(1, 2), "oi".toByteArray())
        assertEquals("oi", String(MirrorCrypto.open(k, byteArrayOf(1, 2), sealed)))
        assertFails { MirrorCrypto.open(MirrorCrypto.newKey(), byteArrayOf(1, 2), sealed) }
        assertFails { MirrorCrypto.open(k, byteArrayOf(1, 3), sealed) } // cabeçalho alterado
        val tampered = sealed.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertFails { MirrorCrypto.open(k, byteArrayOf(1, 2), tampered) }
    }

    @Test fun codigoDePareamentoIdaEVolta() {
        val c = PairingCode(MirrorCrypto.newPairId(), MirrorCrypto.newKey(), "fe80::1%wlan0", 17581, "Felipe Ção")
        val back = PairingCode.decode(c.encode())
        assertEquals(c, back)
        assertEquals(6, c.confirmation.length)
        assertEquals(c.confirmation, back!!.confirmation)
        assertNull(PairingCode.decode("gluview-mirror:2:aa:bb:cc:1:dd"))
        assertNull(PairingCode.decode("qualquer coisa"))
        assertNull(PairingCode.decode(c.encode().replace(":17581:", ":70000:")))
    }

    @Test fun codigoDeConferenciaMudaComAChave() {
        assertNotEquals(MirrorCrypto.confirmationCode(MirrorCrypto.newKey()), MirrorCrypto.confirmationCode(MirrorCrypto.newKey()) + "x")
    }

    @Test fun leituraInvalidaEDescartada() {
        assertNull(MirrorReading.fromMap(mapOf("t" to 0L, "v" to 100.0)))
        assertNull(MirrorReading.fromMap(mapOf("t" to 5L, "v" to 5.0)))
        assertNull(MirrorReading.fromMap(mapOf("v" to 100.0)))
        assertEquals(MirrorReading(5L, 100.0, "Flat", 2), MirrorReading.fromMap(MirrorReading(5L, 100.0, "Flat", 2).toMap()))
    }

    @Test fun limitesForaDeOrdemCaemNoPadrao() {
        assertEquals(MirrorThresholds(), MirrorThresholds.fromMap(mapOf("ul" to 90.0, "l" to 70.0, "h" to 180.0, "uh" to 250.0)))
        assertEquals(MirrorThresholds(60.0, 80.0, 160.0, 240.0),
            MirrorThresholds.fromMap(MirrorThresholds(60.0, 80.0, 160.0, 240.0).toMap()))
    }

    private fun assertFails(block: () -> Unit) {
        try { block(); fail("devia falhar") } catch (_: AssertionError) { throw AssertionError("devia falhar") } catch (_: Exception) {}
    }
}

class FollowerStateTest {
    private val now = 1_800_000_000_000L
    private fun r(minAgo: Int, v: Double = 120.0) = MirrorReading(now - minAgo * 60_000L, v, "Flat")

    @Test fun naoRepeteEOrdena() {
        val s = FollowerState()
        assertEquals(2, s.merge(listOf(r(10), r(5)), now).size)
        val added = s.merge(listOf(r(5), r(0), r(10)), now)
        assertEquals(listOf(r(0)), added)
        assertEquals(listOf(r(10), r(5), r(0)), s.all())
    }

    @Test fun recusaFuturoEVelhoDemais() {
        val s = FollowerState()
        assertTrue(s.merge(listOf(r(-10), r(25 * 60)), now).isEmpty())
        assertEquals(1, s.merge(listOf(r(-4)), now).size) // tolerância de relógio de 5 min
    }

    @Test fun atualSomeDepoisDoLimiar() {
        val s = FollowerState()
        s.merge(listOf(r(16)), now)
        assertNull(s.current(now, 15))
        assertEquals(r(16), s.latest())
        s.merge(listOf(r(1)), now)
        assertEquals(r(1), s.current(now, 15))
    }

    @Test fun saudeDoLink() {
        assertEquals(LinkStatus.NEVER, FollowerState.linkStatus(now, 0L, 5))
        assertEquals(LinkStatus.OK, FollowerState.linkStatus(now, now - 4 * 60_000L, 5))
        assertEquals(LinkStatus.DOWN, FollowerState.linkStatus(now, now - 5 * 60_000L, 5))
    }
}

class LocalHttpServerTest {
    private val now = 1_800_000_000_000L
    private val data = object : LocalHttpServer.DataSource {
        val list = (0 until 30).map { MirrorReading(now - it * 300_000L, 100.0 + it, "Flat", 1) }
        override fun readings(count: Int, sinceMs: Long?) = list.filter { sinceMs == null || it.epochMs > sinceMs }.take(count)
        override fun thresholds() = MirrorThresholds()
        override fun unit() = "MG_DL"
    }

    @Test fun somenteLocalSemSenhaFunciona() {
        val s = LocalHttpServer(0, localOnly = true, secret = null, data = data) { now }
        val r = s.route("GET", "/sgv.json?count=2", emptyMap())
        assertEquals(200, r.status)
        val list = Json.parse(r.body) as List<*>
        assertEquals(2, list.size)
        assertEquals(100L, (list[0] as Map<*, *>)["sgv"])
        assertEquals(now, (list[0] as Map<*, *>)["date"])
    }

    @Test fun naRedeExigeSenha() {
        try { LocalHttpServer(0, localOnly = false, secret = null, data = data); fail() } catch (_: IllegalArgumentException) {}
        val s = LocalHttpServer(0, localOnly = false, secret = "segredo123", data = data) { now }
        assertEquals(401, s.route("GET", "/sgv.json", emptyMap()).status)
        assertEquals(401, s.route("GET", "/sgv.json", mapOf("api-secret" to "errada")).status)
        assertEquals(200, s.route("GET", "/sgv.json", mapOf("api-secret" to "segredo123")).status)
        assertEquals(200, s.route("GET", "/sgv.json", mapOf("api-secret" to LocalHttpServer.sha1("segredo123"))).status)
        assertEquals(200, s.route("GET", "/api/v1/entries.json?token=segredo123", emptyMap()).status)
    }

    @Test fun watchJsonParaMostradores() {
        val s = LocalHttpServer(0, localOnly = true, secret = null, data = data) { now + 2 * 60_000L }
        val m = Json.parse(s.route("GET", "/watch.json?minutes=60&step=10", emptyMap()).body) as Map<*, *>
        assertEquals(100L, m["sgv"])
        assertEquals("100", m["txt"])
        assertEquals(2L, m["age"])           // pela hora do celular
        assertEquals("-1", m["dtxt"])        // 100 agora, 101 há 5 min
        assertEquals(70L, (m["th"] as Map<*, *>)["l"])
        val pts = (m["pts"] as List<*>).map { (it as Number).toInt() }
        // 60 min com leituras a cada 5 min e um ponto a cada 10 min: 6 pontos, do mais velho ao mais novo.
        assertEquals(12, pts.size)
        assertEquals(listOf(2, 100), pts.takeLast(2))
        assertTrue(pts[0] > pts[pts.size - 2])
    }

    @Test fun watchJsonSemLeituraEEmMmol() {
        val vazio = object : LocalHttpServer.DataSource {
            override fun readings(count: Int, sinceMs: Long?) = emptyList<MirrorReading>()
            override fun thresholds() = MirrorThresholds()
            override fun unit() = "MMOL_L"
        }
        val m = Json.parse(LocalHttpServer(0, true, null, vazio) { now }.route("GET", "/watch.json", emptyMap()).body) as Map<*, *>
        assertEquals(null, m["sgv"])
        assertEquals(emptyList<Any>(), m["pts"])
        val mmol = object : LocalHttpServer.DataSource by data { override fun unit() = "MMOL_L" }
        val m2 = Json.parse(LocalHttpServer(0, true, null, mmol) { now }.route("GET", "/watch.json", emptyMap()).body) as Map<*, *>
        assertEquals("5.6", m2["txt"])
        assertEquals("-0.1", m2["dtxt"])
    }

    @Test fun rotasEErros() {
        val s = LocalHttpServer(0, localOnly = true, secret = null, data = data) { now }
        assertEquals(404, s.route("GET", "/nada", emptyMap()).status)
        assertEquals(405, s.route("POST", "/sgv.json", emptyMap()).status)
        val st = Json.parse(s.route("GET", "/status.json", emptyMap()).body) as Map<*, *>
        assertEquals("ok", st["status"])
        val since = now - 3 * 300_000L
        val l = Json.parse(s.route("GET", "/api/v1/entries.json?count=100&find%5Bdate%5D%5B%24gt%5D=$since", emptyMap()).body) as List<*>
        assertEquals(3, l.size)
        val peb = Json.parse(s.route("GET", "/pebble", emptyMap()).body) as Map<*, *>
        assertEquals("100", ((peb["bgs"] as List<*>)[0] as Map<*, *>)["sgv"])
        assertEquals("-1", ((peb["bgs"] as List<*>)[0] as Map<*, *>)["bgdelta"])
        assertTrue(s.route("GET", "/", emptyMap()).body.contains("100 mg/dL"))
    }

    @Test fun socketDeVerdade() {
        val s = LocalHttpServer(0, localOnly = true, secret = null, data = data)
        s.start()
        try {
            val conn = java.net.URL("http://127.0.0.1:${s.boundPort}/sgv.json?count=1").openConnection() as java.net.HttpURLConnection
            assertEquals(200, conn.responseCode)
            assertTrue(conn.inputStream.bufferedReader().readText().contains("\"sgv\":100"))
            assertFalse(s.recentRequests().isEmpty())
        } finally { s.stop() }
    }
}
