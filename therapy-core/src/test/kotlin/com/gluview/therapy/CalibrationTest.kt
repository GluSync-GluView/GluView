package com.gluview.therapy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationTest {
    private val now = 1_800_000_000_000L
    private fun sensor(vararg minAgoAndValue: Pair<Int, Double>) = minAgoAndValue.map { (m, v) -> now - m * 60_000L to v }

    @Test fun recusas() {
        val steady = sensor(15 to 120.0, 10 to 121.0, 5 to 120.0, 0 to 121.0)
        assertNull(Calibration.check(now, 110.0, now - 5 * 3_600_000L, steady))
        assertEquals(CalRejection.WARMUP, Calibration.check(now, 110.0, now - 60 * 60_000L, steady))
        assertEquals(CalRejection.NO_RECENT_SENSOR, Calibration.check(now, 110.0, null, sensor(30 to 120.0)))
        assertEquals(CalRejection.FAST_CHANGE, Calibration.check(now, 110.0, null, sensor(15 to 100.0, 0 to 160.0)))
        assertEquals(CalRejection.OUT_OF_RANGE, Calibration.check(now, 20.0, null, steady))
    }

    @Test fun precisaDeDoisPares() {
        assertEquals(Calibration.Fit.Rejected(CalFitProblem.TOO_FEW_PAIRS), Calibration.fit(listOf(CalPair(now, 100.0, 110.0))))
    }

    @Test fun ajusteSimples() {
        val f = Calibration.fit(listOf(CalPair(now, 100.0, 110.0), CalPair(now, 200.0, 210.0))) as Calibration.Fit.Ok
        assertEquals(1.0, f.calibration.slope, 1e-9)
        assertEquals(-10.0, f.calibration.offset, 1e-9)
        assertEquals(140.0, f.calibration.apply(150.0), 1e-9)
    }

    @Test fun inclinacaoPresaNoLimite() {
        // inclinação crua 2,0 → presa em 1,6; correção em 100 = 20 (dentro de ±30)
        val f = Calibration.fit(listOf(CalPair(now, 100.0, 100.0), CalPair(now, 300.0, 200.0))) as Calibration.Fit.Ok
        assertEquals(Calibration.MAX_SLOPE, f.calibration.slope, 1e-9)
    }

    @Test fun correcaoGrandeDemaisNaoAplica() {
        assertEquals(Calibration.Fit.Rejected(CalFitProblem.TOO_LARGE),
            Calibration.fit(listOf(CalPair(now, 160.0, 110.0), CalPair(now, 250.0, 200.0))))
    }

    @Test fun parMaisPerto() = assertEquals(121.0, Calibration.sensorAt(now, sensor(12 to 99.0, 1 to 121.0, 6 to 130.0))!!, 0.0)
}

class SmoothingTest {
    private val now = 1_800_000_000_000L
    private val noisy = (0 until 12).map { now + it * 300_000L to (120.0 + if (it % 2 == 0) 10.0 else -10.0) }

    @Test fun desligadoNaoMexe() = assertEquals(noisy, Smoothing.smooth(noisy, SmoothingMethod.OFF))

    @Test fun todosReduzemORuido() {
        for (m in listOf(SmoothingMethod.MOVING_AVERAGE, SmoothingMethod.EXPONENTIAL, SmoothingMethod.KALMAN)) {
            val s = Smoothing.smooth(noisy, m).drop(3).map { it.second }
            val spread = s.maxOrNull()!! - s.minOrNull()!!
            assertTrue("$m spread $spread", spread < 20.0)
            assertEquals(noisy.size, Smoothing.smooth(noisy, m).size)
        }
    }

    @Test fun buracoRecomecaOFiltro() {
        val withGap = listOf(now to 100.0, now + 300_000L to 100.0, now + 600_000L to 100.0, now + 3_600_000L to 200.0)
        val s = Smoothing.smooth(withGap, SmoothingMethod.EXPONENTIAL)
        assertEquals(200.0, s.last().second, 0.0)
    }
}
