package com.gluview.app.data.accuchek.cgm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SmartGuideClockTest {

    private val now = 1_762_520_400_000L // 07/11/2026 ~12:00 UTC
    private fun status(minutes: Int) = CgmStatus(minutes, SensorAnnunciation.NONE, CrcState.VALID)

    @Test
    fun `inicio = agora menos os minutos do status`() {
        assertEquals(now - 4762 * 60_000L, SmartGuideClock.startFromStatus(now, status(4762)))
    }

    @Test
    fun `inicio salvo e mantido dentro da tolerancia e trocado fora dela`() {
        val saved = now - 1000 * 60_000L
        assertEquals(saved, SmartGuideClock.reconcileStart(saved, saved + 59_000))   // arredondamento do minuto
        assertEquals(saved, SmartGuideClock.reconcileStart(saved, saved - 90_000))
        assertEquals(saved + 10 * 60_000L, SmartGuideClock.reconcileStart(saved, saved + 10 * 60_000L)) // sensor novo / relógio mudou
        assertEquals(saved, SmartGuideClock.reconcileStart(0L, saved))               // sem início salvo
    }

    @Test
    fun `hora da leitura nunca fica no futuro`() {
        val start = now - 100 * 60_000L
        assertEquals(start + 95 * 60_000L, SmartGuideClock.readingTimeMs(start, 95, now))
        assertEquals(now, SmartGuideClock.readingTimeMs(start, 101, now))
    }

    @Test
    fun `contagem que recomecou indica sensor novo`() {
        assertEquals(false, SmartGuideClock.isNewSession(-1, 30))       // nada salvo
        assertEquals(false, SmartGuideClock.isNewSession(18610, 18615)) // seguindo normal
        assertEquals(false, SmartGuideClock.isNewSession(18610, 18600)) // status um pouco atrás
        assertEquals(true, SmartGuideClock.isNewSession(18610, 65))     // sensor novo
    }

    @Test
    fun `historico pede depois do ultimo minuto salvo ou tudo`() {
        assertNull(SmartGuideClock.historyFromMinute(-1))
        assertEquals(18611, SmartGuideClock.historyFromMinute(18610))
    }

    @Test
    fun `pulo do relogio desconta o tempo que passou de verdade`() {
        // 10 min se passaram (monotônico) e a parede andou 2 h 10 min: pulo de +2 h.
        assertEquals(7_200_000L, SmartGuideClock.wallClockJumpMs(1_000_000L, 50_000L, 1_000_000L + 7_800_000L, 50_000L + 600_000L))
        // relógio atrasado 1 h
        assertEquals(-3_600_000L, SmartGuideClock.wallClockJumpMs(1_000_000L, 50_000L, 1_000_000L - 3_000_000L, 50_000L + 600_000L))
        // sem pulo
        assertEquals(0L, SmartGuideClock.wallClockJumpMs(1_000_000L, 50_000L, 1_600_000L, 650_000L))
    }
}
