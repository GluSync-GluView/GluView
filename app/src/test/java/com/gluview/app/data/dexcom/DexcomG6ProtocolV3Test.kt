package com.gluview.app.data.dexcom

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Revisão do G6 contra o xDrip (plano 14_, V3a, 15/11/2026): mensagens com o layout dos `*TxMessage`,
 * estados de calibração, backfill, relógio do transmissor e a regra de quando pedir backfill.
 */
class DexcomG6ProtocolV3Test {

    private fun le(vararg ints: Int): ByteArray {
        val b = ByteBuffer.allocate(ints.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        ints.forEach { b.putInt(it) }
        return b.array()
    }

    @Test fun mensagensDeControle_temOpcodeECrcDoXdrip() {
        // GlucoseTxMessage = init(0x30, 3) = opcode + CRC; TimeTxMessage = init(0x24, 3).
        val g = DexcomG6Tx.glucose()
        assertEquals(3, g.size); assertEquals(0x30, g[0].toInt()); assertTrue(DexcomCrc.isValid(g))
        val t = DexcomG6Tx.transmitterTime()
        assertEquals(3, t.size); assertEquals(0x24, t[0].toInt()); assertTrue(DexcomCrc.isValid(t))
        assertArrayEquals(byteArrayOf(0x06, 60), DexcomG6Tx.keepAlive(60))
        assertArrayEquals(byteArrayOf(0x07), DexcomG6Tx.bondRequest())
        assertArrayEquals(byteArrayOf(0x09), DexcomG6Tx.disconnect())
    }

    @Test fun pedidoDeBackfill_20Bytes_comJanelaLittleEndian() {
        val m = DexcomG6Tx.backfill(1000, 5000)
        assertEquals(20, m.size)
        assertEquals(0x50, m[0].toInt()); assertEquals(0x05, m[1].toInt()); assertEquals(0x02, m[2].toInt()); assertEquals(0, m[3].toInt())
        val b = ByteBuffer.wrap(m).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(1000, b.getInt(4)); assertEquals(5000, b.getInt(8))
        assertTrue(DexcomCrc.isValid(m))
    }

    @Test fun glicemia_estadoTendenciaEStatus() {
        val body = byteArrayOf(0x31, 0x81.toByte()) + le(42) + le(86_400) + byteArrayOf(0x7A, 0x00, 0x06, (-15).toByte())
        val r = DexcomG6Rx.parseGlucose(DexcomCrc.append(body))!!
        assertEquals(122, r.mgdl); assertEquals(86_400, r.timestampSec); assertEquals(0x81, r.status)
        assertEquals(DexcomCalibrationState.OK, r.calibrationState)
        assertEquals(-1.5f, r.trendPerMinute!!, 0.0001f)
        val noTrend = byteArrayOf(0x31, 0) + le(1) + le(1) + byteArrayOf(0x7A, 0x00, 0x02, 127)
        val w = DexcomG6Rx.parseGlucose(DexcomCrc.append(noTrend))!!
        assertTrue(w.calibrationState.warmingUp); assertFalse(w.calibrationState.usable); assertNull(w.trendPerMinute)
    }

    @Test fun estados_usavelFimEFalha() {
        assertTrue(DexcomCalibrationState.parse(0x06).usable)
        assertTrue(DexcomCalibrationState.parse(0x07).usable)
        assertFalse(DexcomCalibrationState.parse(0x02).usable)
        assertTrue(DexcomCalibrationState.parse(0x0F).ended)
        assertTrue(DexcomCalibrationState.parse(0x18).ended)
        assertTrue(DexcomCalibrationState.parse(0x0B).failed)
        assertFalse(DexcomCalibrationState.parse(0x0B).ended)
        assertEquals(DexcomCalibrationState.UNKNOWN, DexcomCalibrationState.parse(0xEE))
    }

    @Test fun respostaDoBackfill_soComCrcE20Bytes() {
        val ok = DexcomCrc.append(byteArrayOf(0x51) + ByteArray(17))
        assertTrue(DexcomG6Rx.isBackfillAccepted(ok))
        assertFalse(DexcomG6Rx.isBackfillAccepted(ok.copyOf(19)))
        val bad = ok.copyOf().also { it[19] = (it[19].toInt() xor 0xFF).toByte() }
        assertFalse(DexcomG6Rx.isBackfillAccepted(bad))
    }

    private fun record(time: Int, mgdl: Int, state: Int, trend: Int): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(time).putShort(mgdl.toShort())
            .put(state.toByte()).put(trend.toByte()).array()

    @Test fun backfill_remontaPacotesEmOrdem_eFicaSoComUsaveis() {
        val payload = le(3 * 8) + record(600, 110, 0x06, 2) + record(900, 115, 0x02, 0) + record(1200, 0x8000 or 120, 0x07, -3)
        val s = DexcomBackfillStream()
        s.push(byteArrayOf(1, 0) + payload.copyOfRange(0, 18))
        s.push(byteArrayOf(3, 0) + byteArrayOf(9, 9)) // fora de ordem: ignorado
        s.push(byteArrayOf(2, 0) + payload.copyOfRange(18, payload.size))
        val out = s.decode()
        assertEquals(listOf(600, 1200), out.map { it.dexTimeSec })
        assertEquals(listOf(110, 120), out.map { it.mgdl }) // máscara 0xFFF tira o bit alto
    }

    @Test fun relogio_horaRealDaLeitura_nuncaNoFuturo() {
        val now = 1_800_000_000_000L
        val txStart = DexcomG6Clock.transmitterStartMs(now, 864_000)
        assertEquals(now - 864_000_000L, txStart)
        assertEquals(now - 300_000L, DexcomG6Clock.readingTimeMs(txStart, 864_000 - 300, now))
        assertEquals(now, DexcomG6Clock.readingTimeMs(txStart, 864_000 + 100, now))
        val session = TransmitterTime(currentTimeSec = 864_000, sessionStartTimeSec = 864_000 - 7_200)
        assertEquals(now - 7_200_000L, DexcomG6Clock.sessionStartMs(txStart, session))
        assertNull(DexcomG6Clock.sessionStartMs(txStart, TransmitterTime(864_000, -1)))
    }

    @Test fun janelaDeBackfill_soComFuro_eNoMaximo3h() {
        val now = 1_800_000_000_000L
        val txStart = now - 10L * 86_400_000L
        assertNull(DexcomG6Backfill.window(now - 5 * 60_000L, now, txStart))      // sem furo
        assertNull(DexcomG6Backfill.window(now - 60 * 60_000L, now, 0L))         // relógio desconhecido
        val gap = DexcomG6Backfill.window(now - 60 * 60_000L, now, txStart)!!
        assertEquals(DexcomG6Clock.dexTime(txStart, now - 60 * 60_000L), gap.first)
        assertEquals(DexcomG6Clock.dexTime(txStart, now), gap.second)
        val never = DexcomG6Backfill.window(0L, now, txStart)!!
        assertEquals(DexcomG6Clock.dexTime(txStart, now - DexcomG6Backfill.MAX_PERIOD_MS), never.first)
        val old = DexcomG6Backfill.window(now - 10 * 60 * 60_000L, now, txStart)!!
        assertEquals(never.first, old.first)
    }
}
