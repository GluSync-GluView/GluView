package com.gluview.app.data.reminder

import com.gluview.app.data.local.entity.ReminderEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneId

/** Plano 17, Fase 2 — horários dos lembretes e aviso de refeição. */
class ReminderLogicTest {
    private val zone = ZoneId.of("America/Sao_Paulo")
    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int) = LocalDateTime.of(y, m, d, h, min).atZone(zone).toInstant().toEpochMilli()

    @Test fun `proximo horario no mesmo dia e no dia seguinte`() {
        val r = ReminderEntity(title = "", kind = "measure", timesCsv = "480,1320") // 08:00 e 22:00
        // 10/11/2026 é terça.
        assertEquals(at(2026, 11, 10, 22, 0), ReminderSchedule.nextTrigger(r, at(2026, 11, 10, 9, 0), zone))
        assertEquals(at(2026, 11, 11, 8, 0), ReminderSchedule.nextTrigger(r, at(2026, 11, 10, 22, 0), zone))
    }

    @Test fun `respeita os dias marcados`() {
        val onlyMonday = ReminderSchedule.dayBit(DayOfWeek.MONDAY)
        val r = ReminderEntity(title = "", kind = "measure", timesCsv = "480", daysMask = onlyMonday)
        assertEquals(at(2026, 11, 16, 8, 0), ReminderSchedule.nextTrigger(r, at(2026, 11, 10, 9, 0), zone))
    }

    @Test fun `desligado, sem horario ou sem dia nao agenda`() {
        assertNull(ReminderSchedule.nextTrigger(ReminderEntity(title = "", kind = "x", timesCsv = "480", enabled = false), 0, zone))
        assertNull(ReminderSchedule.nextTrigger(ReminderEntity(title = "", kind = "x", timesCsv = ""), 0, zone))
        assertNull(ReminderSchedule.nextTrigger(ReminderEntity(title = "", kind = "x", timesCsv = "480", daysMask = 0), 0, zone))
    }

    @Test fun `horarios ordenados, sem repeticao e validos`() {
        assertEquals(listOf(60, 480), ReminderSchedule.parseTimes("480, 60,480,abc,9999"))
        assertEquals("60,480", ReminderSchedule.encodeTimes(listOf(480, 60, 60)))
    }

    @Test fun `subida rapida sustentada dispara, ruido nao`() {
        val now = 10_000_000L
        val min = 60_000L
        val rise = listOf(now - 30 * min to 120.0, now - 20 * min to 135.0, now - 10 * min to 150.0, now to 172.0)
        assertTrue(SmartMealRule.isRapidRise(rise, now))
        val flat = listOf(now - 30 * min to 120.0, now - 15 * min to 125.0, now to 130.0)
        assertFalse(SmartMealRule.isRapidRise(flat, now))
        val spike = listOf(now - 30 * min to 120.0, now - 15 * min to 200.0, now to 170.0) // subiu e já está caindo
        assertFalse(SmartMealRule.isRapidRise(spike, now))
        val stale = rise.map { (t, v) -> t - 20 * min to v } // leitura mais nova há 20 min
        assertFalse(SmartMealRule.isRapidRise(stale, now))
    }

    @Test fun `aviso respeita refeicao registrada e intervalo`() {
        val now = 100_000_000L
        assertTrue(SmartMealRule.shouldPrompt(true, true, false, 0, now))
        assertFalse(SmartMealRule.shouldPrompt(false, true, false, 0, now))
        assertFalse(SmartMealRule.shouldPrompt(true, true, true, 0, now))
        assertFalse(SmartMealRule.shouldPrompt(true, true, false, now - 60 * 60_000L, now))
    }
}
