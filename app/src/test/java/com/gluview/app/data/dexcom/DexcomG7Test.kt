package com.gluview.app.data.dexcom

import com.eveningoutpost.dexdrip.plugin.IPluginDA
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** PT-3 — Dexcom G7: mensagens, estados do sensor, histórico e tradução da autenticação. */
class DexcomG7Test {

    /** Registro 0x4E no layout do `EGlucoseRxMessage2` do xDrip. */
    private fun egv(secsSinceStart: Int, seq: Int, ageSec: Int, mgdl: Int, state: Int, trend: Int, predicted: Int = 0x3FF): ByteArray =
        ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN)
            .put(0x4E).put(0).putInt(secsSinceStart).putShort(seq.toShort()).putShort(0)
            .putShort(ageSec.toShort()).putShort(mgdl.toShort()).put(state.toByte()).put(trend.toByte())
            .putShort(predicted.toShort()).put(0).array()

    @Test fun `pedidos de glicemia e de historico`() {
        assertArrayEquals(byteArrayOf(0x4E), DexcomG7Tx.eGlucose())
        val b = DexcomG7Tx.backfill(0x01020304, 0x0A0B0C0D)
        assertArrayEquals(byteArrayOf(0x59, 4, 3, 2, 1, 0x0D, 0x0C, 0x0B, 0x0A), b)
    }

    @Test fun `leitura valida com horario pela idade`() {
        val now = 1_800_000_000_000L
        val r = DexcomG7Data.reading(egv(secsSinceStart = 2 * 86_400, seq = 576, ageSec = 30, mgdl = 123, state = 0x06, trend = -15), now)!!
        assertEquals(now - 30_000, r.atMs)
        assertEquals(now - 30_000 - 2 * 86_400_000L, r.sensorStartMs)
        assertEquals(123, r.mgdl)
        assertEquals(-1.5, r.ratePerMin!!, 1e-9)
        assertEquals("↘", DexcomG7Data.arrow(r.ratePerMin))
        assertTrue(r.valid)
        assertNull(r.predictedMgdl)
    }

    @Test fun `aquecimento, expirado e falha nunca viram glicemia`() {
        val now = 1_800_000_000_000L
        // Sensor diz aquecendo.
        assertFalse(DexcomG7Data.reading(egv(600, 2, 10, 110, 0x02, 0), now)!!.valid)
        // Sensor diz OK, mas com 10 min de vida: ainda é aquecimento.
        assertEquals(G7SensorState.WARMING_UP, DexcomG7Data.reading(egv(600, 2, 10, 110, 0x06, 0), now)!!.state)
        // Além de 10 dias + 12 h: expirado, mesmo que o sensor mande OK.
        val late = DexcomG7Data.reading(egv(10 * 86_400 + 12 * 3_600 + 60, 9, 10, 110, 0x06, 0), now)!!
        assertEquals(G7SensorState.EXPIRED, late.state); assertFalse(late.valid)
        // Na tolerância de 12 h ainda vale.
        assertTrue(DexcomG7Data.reading(egv(10 * 86_400 + 3_600, 9, 10, 110, 0x06, 0), now)!!.valid)
        assertTrue(G7Lifecycle.inGrace(10 * 86_400 + 3_600))
        // Expirado, fim e falhas informados pelo sensor.
        assertEquals(G7SensorState.EXPIRED, G7SensorState.fromByte(0x18))
        assertEquals(G7SensorState.ENDED, G7SensorState.fromByte(0x0f))
        listOf(0x0b, 0x0c, 0x10, 0x16, 0x19, 0x1e, 0x03).forEach { assertEquals(G7SensorState.FAILED, G7SensorState.fromByte(it)) }
        listOf(0x00, 0x04, 0x0e, 0xC1).forEach { assertFalse(G7SensorState.fromByte(it).usable) }
        // Valor fora de 40–400 não vale.
        assertFalse(DexcomG7Data.reading(egv(86_400, 2, 10, 30, 0x06, 0), now)!!.valid)
    }

    @Test fun `tendencia invalida e setas`() {
        assertNull(DexcomG7Data.trendRate(127))
        assertEquals("", DexcomG7Data.arrow(null))
        assertEquals("→", DexcomG7Data.arrow(0.5))
        assertEquals("↑↑", DexcomG7Data.arrow(3.5))
        assertEquals("↓↓", DexcomG7Data.arrow(-3.0))
    }

    @Test fun `historico em registros de 9 bytes so com estado usavel`() {
        val start = 1_800_000_000_000L
        fun rec(dexTime: Int, mgdl: Int, state: Int, trend: Int) =
            ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN).putInt(dexTime).putShort(mgdl.toShort())
                .put(state.toByte()).put(0).put(trend.toByte()).array()
        val stream = rec(3_600, 100, 0x06, 0) + rec(3_900, 105, 0x02, 0) + rec(4_200, 110, 0x06, 10) + rec(0, 120, 0x06, 0) + byteArrayOf(1, 2)
        val out = DexcomG7Data.backfill(stream, start)
        assertEquals(listOf(100, 110), out.map { it.mgdl })
        assertEquals(start + 3_600_000L, out[0].atMs)
        assertEquals(1.0, out[1].ratePerMin!!, 1e-9)
        assertEquals(3_600, DexcomG7Data.dexTime(start, start + 3_600_000L))
    }

    @Test fun `codigo de pareamento tem 4 digitos`() {
        assertTrue(DexcomG7Auth.isValidPairingCode("1234"))
        listOf("123", "12345", "12a4", "").forEach { assertFalse(DexcomG7Auth.isValidPairingCode(it)) }
    }

    /** Plugin falso: devolve as respostas programadas. */
    private class FakePlugin(var next: Array<ByteArray?>?, var respond: Boolean = true, var throwOnResponse: Exception? = null,
                             var bond: Boolean = false, var key: ByteArray? = null) : IPluginDA {
        var connected = false
        override fun aNext() = next
        override fun bNext() = arrayOf<ByteArray>()
        override fun cNext() = arrayOf<ByteArray>()
        override fun amConnected() { connected = true }
        override fun bondNow(data: ByteArray?) = bond
        override fun receivedResponse(data: ByteArray?): Boolean { throwOnResponse?.let { throw it }; return respond }
        override fun receivedResponse2(data: ByteArray?) = false
        override fun receivedResponse3(data: ByteArray?) = false
        override fun receivedData(data: ByteArray?) = respond
        override fun receivedData2(data: ByteArray?) = false
        override fun receivedData3(data: ByteArray?) = false
        override fun getPersistence(channel: Int) = if (channel == 1) key else ByteArray(0)
        override fun setPersistence(channel: Int, data: ByteArray?) = true
        override fun getStatus() = ""
        override fun getName() = "fake"
    }

    @Test fun `dados longos vao em pedacos de 20 na ExtraData e depois o comando`() {
        val data = ByteArray(45) { it.toByte() }
        val plugin = FakePlugin(arrayOf(byteArrayOf(0x0A, 0), data))
        val ops = DexcomG7Auth(plugin).onReady()
        assertTrue(plugin.connected)
        assertEquals(listOf(20, 20, 5), ops.filterIsInstance<G7AuthOp.WriteExtraData>().map { it.chunk.size })
        assertArrayEquals(byteArrayOf(0x0A, 0), (ops.last() as G7AuthOp.WriteAuth).command)
    }

    @Test fun `autenticado devolve a chave para guardar`() {
        val key = ByteArray(16) { 7 }
        val ops = DexcomG7Auth(FakePlugin(arrayOf(byteArrayOf(0x4E)), key = key)).onAuthentication(byteArrayOf(5, 1, 1))
        assertArrayEquals(key, (ops.single() as G7AuthOp.Authenticated).sharedKey)
    }

    @Test fun `bond pedido pelo sensor`() {
        val ops = DexcomG7Auth(FakePlugin(null, respond = false, bond = true)).onAuthentication(byteArrayOf(6, 0x19))
        assertEquals(listOf(G7AuthOp.CreateBond), ops)
    }

    @Test fun `sem pareamento do app oficial pede o app oficial`() {
        val missingCert = DexcomG7Auth(FakePlugin(null, throwOnResponse = java.security.InvalidParameterException("Missing QR code")))
        assertEquals(listOf(G7AuthOp.NeedsOfficialPairing), missingCert.onAuthentication(byteArrayOf(5, 1, 0)))
        val bondFailure = DexcomG7Auth(FakePlugin(arrayOf(byteArrayOf(1), null, null)))
        assertEquals(listOf(G7AuthOp.NeedsOfficialPairing), bondFailure.onAuthentication(byteArrayOf(5, 0, 0)))
    }

    @Test fun `codigo errado`() {
        val ops = DexcomG7Auth(FakePlugin(null, throwOnResponse = SecurityException("Mismatch"))).onAuthentication(byteArrayOf(3))
        assertEquals(listOf(G7AuthOp.WrongCode), ops)
    }

    @Test fun `libkeks de verdade abre a primeira rodada do EC-JPAKE`() {
        val plugin = jamorham.keks.Plugin("1234")
        val first = DexcomG7Auth(plugin).onReady()
        // Rodada 1: só o comando de chave (0x0A, 0x00), sem dados.
        val cmd = first.filterIsInstance<G7AuthOp.WriteAuth>().single().command
        assertEquals(2, cmd.size)
        assertEquals(0, cmd[1].toInt())
        // Pacote da rodada vindo do sensor ainda incompleto: nada a fazer.
        assertTrue(DexcomG7Auth(plugin).onExtraData(ByteArray(20)).isEmpty())
    }
}
