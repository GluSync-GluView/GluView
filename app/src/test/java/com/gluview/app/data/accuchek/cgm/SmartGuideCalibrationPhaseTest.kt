package com.gluview.app.data.accuchek.cgm

import com.gluview.app.data.accuchek.cgm.SmartGuideCalibrationPhase.Phase
import org.junit.Assert.assertEquals
import org.junit.Test

class SmartGuideCalibrationPhaseTest {
    private val start = 1_795_131_927_000L   // 19/11/2026 20:45:27 (-03), sensor real
    private val min = 60_000L

    @Test
    fun `sem pedido nao mostra nada`() {
        assertEquals(Phase.None, SmartGuideCalibrationPhase.of(false, false, start, 0L, start + 200 * min))
    }

    @Test
    fun `modo tendencia mostra a hora das 12 h`() {
        // minuto 197 (00:02), como no sensor real: libera às 08:45
        assertEquals(Phase.WaitingFirst(start + 720 * min), SmartGuideCalibrationPhase.of(true, false, start, 0L, start + 197 * min))
    }

    @Test
    fun `passou das 12 h e ainda bloqueado vira em breve`() {
        assertEquals(Phase.WaitingFirst(null), SmartGuideCalibrationPhase.of(true, false, start, 0L, start + 730 * min))
        assertEquals(Phase.WaitingFirst(null), SmartGuideCalibrationPhase.of(true, false, 0L, 0L, start))
    }

    @Test
    fun `liberado sem calibracao recente`() {
        assertEquals(Phase.Ready, SmartGuideCalibrationPhase.of(true, true, start, 0L, start + 721 * min))
    }

    @Test
    fun `segundo passo da rotina`() {
        val first = start + 725 * min
        assertEquals(Phase.WaitingSecond(first + 30 * min), SmartGuideCalibrationPhase.of(true, false, start, first, first + 10 * min))
        assertEquals(Phase.ReadySecond(first + 180 * min), SmartGuideCalibrationPhase.of(true, true, start, first, first + 40 * min))
        // depois de 3 h a 1ª não conta mais
        assertEquals(Phase.Ready, SmartGuideCalibrationPhase.of(true, true, start, first, first + 181 * min))
    }
}
