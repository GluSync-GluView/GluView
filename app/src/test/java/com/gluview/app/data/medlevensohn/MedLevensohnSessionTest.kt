package com.gluview.app.data.medlevensohn

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Máquina de estados do Smart 2.0 / AiDEX X. Vetores REAIS citados no Juggluco 11.5.7
 * (`aidexx/java.cpp` e `glucose.h`, GPLv3): os comandos fixos com CRC, a hora de início
 * `21 01 ea 07 …` e a última leitura `11 01 94 4b …` (decifrada de um sensor de verdade).
 */
class MedLevensohnSessionTest {

    private fun hex(s: String) = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private val sp: ZoneId = ZoneId.of("America/Sao_Paulo")
    private val now = LocalDateTime.of(2026, 11, 7, 15, 0, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
    private val start2026 = LocalDateTime.of(2026, 2, 15, 11, 33, 54).toEpochSecond(ZoneOffset.UTC)

    // ── Comandos ────────────────────────────────────────────────────────────────

    @Test
    fun `comandos fixos batem byte a byte com os do Juggluco`() {
        assertArrayEquals(hex("10 c1 f3"), MedLevensohnCommands.GREETING)
        assertArrayEquals(hex("35 01 4e f7"), MedLevensohnCommands.RESUME)
        assertArrayEquals(hex("21 b3 d5"), MedLevensohnCommands.ASK_START_TIME)
        assertArrayEquals(hex("22 d0 e5"), MedLevensohnCommands.ASK_LAST_ID)
        assertArrayEquals(hex("31 01 8a 3b"), MedLevensohnCommands.SENSOR_WITHOUT_TIME)
        assertArrayEquals(hex("11 e0 e3"), MedLevensohnCommands.ANSWER_134)
        assertArrayEquals(hex("34 01 7f c4"), MedLevensohnCommands.ANSWER_135)
        assertArrayEquals(hex("f2 ad 2e"), MedLevensohnCommands.UNPAIR)
        assertArrayEquals(hex("f3 8c 3e"), MedLevensohnCommands.CLEAR)
    }

    @Test
    fun `pedido de historico usa id minimo 1 e CRC`() {
        val c = MedLevensohnCommands.askHistory(0)
        assertEquals(0x23, c[0].toInt())
        assertEquals(1, c[1].toInt()); assertEquals(0, c[2].toInt())
        assertTrue(MedLevensohnSession.crcOk(c))
        val c2 = MedLevensohnCommands.askHistory(0x4b94)
        assertEquals(0x94.toByte(), c2[1]); assertEquals(0x4b.toByte(), c2[2])
    }

    @Test
    fun `hora local de Sao Paulo no comando 0x20`() {
        val ms = LocalDateTime.of(2026, 11, 7, 12, 30, 5).atZone(sp).toInstant().toEpochMilli()
        val c = MedLevensohnCommands.writeTime(ms, sp)
        assertEquals(12, c.size)
        assertArrayEquals(hex("20 ea 07 0b 07 0c 1e 05 f4 00"), c.copyOfRange(0, 10)) // fuso −12 quartos, sem verão
        assertTrue(MedLevensohnSession.crcOk(c))
        assertEquals(ms / 1000, LocalStartTime.parse(c, 1)!!.epochSeconds())
    }

    // ── Hora de início (0x121) ──────────────────────────────────────────────────

    @Test
    fun `hora de inicio real do Juggluco`() {
        val plain = hex("21 01 ea 07 02 0f 0c 21 36 04 00 9e 72")
        assertTrue(MedLevensohnSession.crcOk(plain))
        val lst = LocalStartTime.parse(plain, 2)!!
        assertEquals(4, lst.timeZoneQuarterHours) // CET
        assertEquals(start2026, lst.epochSeconds())
    }

    @Test
    fun `0x121 nova hora responde 0x35 e guarda o inicio`() {
        val step = MedLevensohnSession.onMain(hex("21 01 ea 07 02 0f 0c 21 36 04 00 9e 72"),
            MedLevState(unbonded = false), bondedNow = true, now, sp)
        assertTrue(step.state.hasTime)
        assertEquals(start2026, step.state.startSec)
        assertArrayEquals(MedLevensohnCommands.RESUME, step.reply)
    }

    @Test
    fun `0x121 com a mesma hora pede o ultimo id`() {
        val s = MedLevState(hasTime = true, startSec = start2026, unbonded = false, lastReceivedId = 10)
        val step = MedLevensohnSession.onMain(hex("21 01 ea 07 02 0f 0c 21 36 04 00 9e 72"), s, true, now, sp)
        assertArrayEquals(MedLevensohnCommands.ASK_LAST_ID, step.reply)
    }

    @Test
    fun `0x121 na primeira sessao sem pareamento e ja pareado desconecta para voltar pareado`() {
        val step = MedLevensohnSession.onMain(hex("21 01 ea 07 02 0f 0c 21 36 04 00 9e 72"),
            MedLevState(unbonded = true), bondedNow = true, now, sp)
        assertTrue(step.disconnect)
        assertTrue(step.state.hasTime)
    }

    @Test
    fun `sensor sem hora - desconecta, depois responde 0x31 e acerta o relogio no 0x131`() {
        val noTime = MedLevensohnCommands.withCrc(hex("21 01 00 00 00 00 00 00 00 00 00"))
        val s1 = MedLevensohnSession.onMain(noTime, MedLevState(), true, now, sp)
        assertTrue(s1.disconnect); assertEquals(1, s1.state.time0)
        val s2 = MedLevensohnSession.onMain(MedLevensohnCommands.withCrc(hex("10 01")), s1.state, true, now, sp)
        assertArrayEquals(MedLevensohnCommands.SENSOR_WITHOUT_TIME, s2.reply)
        val s3 = MedLevensohnSession.onMain(MedLevensohnCommands.withCrc(hex("31 01")), s2.state, true, now, sp)
        assertEquals(0x20, s3.reply!![0].toInt())
        val s4 = MedLevensohnSession.onMain(MedLevensohnCommands.withCrc(hex("20 01")), s3.state, true, now, sp)
        assertEquals(2, s4.state.time0)
        assertArrayEquals(MedLevensohnCommands.ASK_START_TIME, s4.reply)
    }

    @Test
    fun `inicio mais novo desloca os ids ja recebidos`() {
        val s = MedLevState(hasTime = true, startSec = start2026 - 600, unbonded = false, lastReceivedId = 100, lastHistoricPos = 100)
        val step = MedLevensohnSession.onMain(hex("21 01 ea 07 02 0f 0c 21 36 04 00 9e 72"), s, true, now, sp)
        assertEquals(90, step.state.lastReceivedId)
        assertEquals(MedLevNotice.START_TIME_CHANGED, step.notice)
    }

    // ── Informação do aparelho (0x110) ──────────────────────────────────────────

    @Test
    fun `0x110 responde conforme o estado`() {
        val info = MedLevensohnCommands.withCrc(hex("10 01 00 00 01 08 02 00 0f 00"))
        assertArrayEquals(MedLevensohnCommands.ASK_START_TIME,
            MedLevensohnSession.onMain(info, MedLevState(), true, now, sp).reply)
        assertArrayEquals(MedLevensohnCommands.RESUME,
            MedLevensohnSession.onMain(info, MedLevState(hasTime = true, unbonded = false), true, now, sp).reply)
        assertArrayEquals(MedLevensohnCommands.SENSOR_WITHOUT_TIME,
            MedLevensohnSession.onMain(info, MedLevState(time0 = 1), true, now, sp).reply)
    }

    // ── Última leitura (0x111) e histórico (0x122/0x123) ────────────────────────

    @Test
    fun `ultima leitura real do Juggluco - 119 mg_dL no minuto 19348`() {
        val plain = hex("11 01 94 4b 00 00 fb 77 80 4d 00 00 e8 44")
        assertTrue(MedLevensohnSession.crcOk(plain))
        val s = MedLevState(hasTime = true, startSec = start2026, unbonded = false)
        val step = MedLevensohnSession.onMain(plain, s, true, now, sp)
        val r = step.readings.single()
        assertEquals(19348, r.id); assertEquals(119, r.mgdl); assertEquals(-5, r.trendTenths)
        assertEquals(19348, step.state.lastHistoricPos)
        assertArrayEquals(MedLevensohnCommands.askHistory(0), step.reply) // pede o histórico desde o começo
    }

    @Test
    fun `0x111 sem hora pede a hora e nao grava`() {
        val step = MedLevensohnSession.onMain(hex("11 01 94 4b 00 00 fb 77 80 4d 00 00 e8 44"), MedLevState(), true, now, sp)
        assertTrue(step.readings.isEmpty())
        assertArrayEquals(MedLevensohnCommands.ASK_START_TIME, step.reply)
    }

    @Test
    fun `historico paginado ate alcancar o ultimo id`() {
        var s = MedLevState(hasTime = true, startSec = start2026, unbonded = false)
        // 0x122: marcador 0x00010001 + … + último id 5 (antes do CRC)
        val last = MedLevensohnCommands.withCrc(hex("22 01 01 00 01 00 05 00"))
        val a = MedLevensohnSession.onMain(last, s, true, now, sp)
        assertEquals(5, a.state.lastHistoricPos)
        assertArrayEquals(MedLevensohnCommands.askHistory(0), a.reply)
        s = a.state
        // 0x123: startId 0, valores 100 (válido), inválido, 17 (abaixo de 18) → só o primeiro grava
        val chunk = MedLevensohnCommands.withCrc(hex("23 01 00 00 64 80 64 00 11 80"))
        val b = MedLevensohnSession.onMain(chunk, s, true, now, sp)
        assertEquals(listOf(0 to 100), b.readings.map { it.id to it.mgdl })
        assertEquals(2, b.state.lastReceivedId)
        assertArrayEquals(MedLevensohnCommands.askHistory(3), b.reply)
        val chunk2 = MedLevensohnCommands.withCrc(hex("23 01 03 00 6e 80 78 80"))
        val c = MedLevensohnSession.onMain(chunk2, b.state, true, now, sp)
        assertEquals(listOf(3 to 110, 4 to 120), c.readings.map { it.id to it.mgdl })
        assertNull(c.reply) // alcançou o último
    }

    @Test
    fun `historico repetido nao grava de novo`() {
        val s = MedLevState(hasTime = true, startSec = start2026, unbonded = false, lastReceivedId = 10)
        val step = MedLevensohnSession.onMain(MedLevensohnCommands.withCrc(hex("23 01 05 00 64 80")), s, true, now, sp)
        assertTrue(step.readings.isEmpty()); assertNull(step.reply); assertFalse(step.disconnect)
    }

    // ── Respostas fixas, despareamento e erros ──────────────────────────────────

    @Test
    fun `0x134 e 0x135 respondem os comandos do Juggluco`() {
        assertArrayEquals(MedLevensohnCommands.ANSWER_134,
            MedLevensohnSession.onMain(MedLevensohnCommands.withCrc(hex("34 01")), MedLevState(), true, now, sp).reply)
        assertArrayEquals(MedLevensohnCommands.ANSWER_135,
            MedLevensohnSession.onMain(MedLevensohnCommands.withCrc(hex("35 01")), MedLevState(), true, now, sp).reply)
    }

    @Test
    fun `desparear e limpar`() {
        val ok = MedLevensohnSession.onMain(MedLevensohnCommands.withCrc(hex("f2 01")), MedLevState(unpairPending = true), true, now, sp)
        assertEquals(MedLevNotice.UNPAIRED, ok.notice); assertTrue(ok.disconnect); assertFalse(ok.state.unpairPending)
        val cl = MedLevensohnSession.onMain(MedLevensohnCommands.withCrc(hex("f3 01")), MedLevState(clearPending = true, hasTime = true), true, now, sp)
        assertEquals(MedLevNotice.CLEARED, cl.notice); assertTrue(cl.forgetKey); assertFalse(cl.state.hasTime)
        assertEquals(MedLevNotice.UNPAIR_FAILED,
            MedLevensohnSession.onMain(MedLevensohnCommands.withCrc(hex("f2 00")), MedLevState(), true, now, sp).notice)
    }

    @Test
    fun `CRC errado desconecta`() {
        val bad = hex("11 01 94 4b 00 00 fb 77 80 4d 00 00 e8 45")
        assertTrue(MedLevensohnSession.onMain(bad, MedLevState(), true, now, sp).disconnect)
    }

    // ── Comando de início ───────────────────────────────────────────────────────

    @Test
    fun `comando de inicio conforme chave, hora e pedidos pendentes`() {
        assertArrayEquals(MedLevensohnCommands.GREETING, MedLevensohnSession.startCommand(MedLevState(), hasKey = false).second)
        assertArrayEquals(MedLevensohnCommands.GREETING, MedLevensohnSession.startCommand(MedLevState(), hasKey = true).second)
        assertArrayEquals(MedLevensohnCommands.RESUME, MedLevensohnSession.startCommand(MedLevState(hasTime = true), hasKey = true).second)
        assertArrayEquals(MedLevensohnCommands.UNPAIR, MedLevensohnSession.startCommand(MedLevState(unpairPending = true), true).second)
        assertArrayEquals(MedLevensohnCommands.CLEAR, MedLevensohnSession.startCommand(MedLevState(clearPending = true, unpairPending = true), true).second)
        assertFalse(MedLevensohnSession.startCommand(MedLevState(), hasKey = true).first.unbonded)
    }

    // ── Leitura ao vivo (f003) ──────────────────────────────────────────────────

    /** CurrentGlucose: tipo, 0, 0, tendência, minuto, palavra (10 bits + válido), 7 bytes. */
    private fun current(type: Int, minute: Int, mgdl: Int, trend: Int = 3, valid: Boolean = true): ByteArray {
        val word = mgdl or (if (valid) 0x8000 else 0)
        val body = byteArrayOf(type.toByte(), 0, 0, trend.toByte(), (minute and 0xFF).toByte(), (minute ushr 8).toByte(),
            (word and 0xFF).toByte(), (word ushr 8).toByte(), 0, 0, 0, 0, 0, 0, 0)
        return MedLevensohnCommands.withCrc(body)
    }

    @Test
    fun `leitura ao vivo valida grava com a tendencia`() {
        val s = MedLevState(hasTime = true, startSec = now / 1000 - 600 * 60, lastReceivedId = 599)
        val step = MedLevensohnSession.onCurrent(current(1, 600, 132, trend = -12), s, now)
        val r = step.readings.single()
        assertEquals(600, r.id); assertEquals(132, r.mgdl); assertEquals(-12, r.trendTenths)
        assertEquals(600, step.state.lastReceivedId)
        assertFalse(step.disconnect)
    }

    @Test
    fun `leitura ao vivo - sensor encerrado, sem hora, CRC errado e futuro`() {
        val s = MedLevState(hasTime = true, startSec = now / 1000 - 600 * 60)
        val ended = MedLevensohnSession.onCurrent(current(3, 600, 120), s, now)
        assertEquals(MedLevNotice.SENSOR_ENDED, ended.notice); assertTrue(ended.disconnect)
        assertTrue(MedLevensohnSession.onCurrent(current(1, 600, 120), MedLevState(), now).disconnect)
        val bad = current(1, 600, 120).also { it[6] = 0x7F }
        assertTrue(MedLevensohnSession.onCurrent(bad, s, now).disconnect)
        assertTrue(MedLevensohnSession.onCurrent(current(1, 700, 120), s, now).readings.isEmpty())
        assertTrue(MedLevensohnSession.onCurrent(current(4, 600, 120), s, now).readings.isEmpty())
    }
}
