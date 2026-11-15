package com.gluview.app.overlay

import com.gluview.app.domain.model.GlucoseReading
import com.gluview.app.domain.model.GlucoseZone
import com.gluview.app.util.GlucoseUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FloatingFormatTest {
    private val now = 1_800_000_000_000L
    private fun reading(sgv: Double = 120.0, zone: GlucoseZone = GlucoseZone.NORMAL, ageMin: Int = 2,
                        stale: Boolean = false, delta: Int = 3, arrow: String = "→") =
        GlucoseReading(sgv, "Flat", arrow, "", zone, delta, stale, epochMs = now - ageMin * 60_000L)

    @Test fun leituraAtualMostraValorSetaDeltaIdade() {
        val s = FloatingFormat.build(reading(), now, 15, GlucoseUnit.MG_DL)
        assertEquals("120", s.value); assertEquals("→", s.arrow); assertEquals("+3", s.delta)
        assertEquals(2, s.ageMinutes); assertFalse(s.stale); assertEquals(FloatingTone.NORMAL, s.tone)
    }

    @Test fun leituraVelhaNuncaApareceComoAtual() {
        val s = FloatingFormat.build(reading(ageMin = 15), now, 15, GlucoseUnit.MG_DL)
        assertNull(s.value); assertNull(s.delta); assertEquals("", s.arrow)
        assertTrue(s.stale); assertEquals(15, s.ageMinutes); assertEquals(FloatingTone.STALE, s.tone)
    }

    @Test fun marcadaComoSemSinalTambemEscondeValor() {
        assertNull(FloatingFormat.build(reading(stale = true), now, 15, GlucoseUnit.MG_DL).value)
        assertNull(FloatingFormat.build(reading(zone = GlucoseZone.STALE), now, 15, GlucoseUnit.MG_DL).value)
    }

    @Test fun semLeituraNenhuma() {
        val s = FloatingFormat.build(null, now, 15, GlucoseUnit.MG_DL)
        assertNull(s.value); assertNull(s.ageMinutes); assertTrue(s.stale)
    }

    @Test fun relogioAdiantadoNaoDaIdadeNegativa() {
        val s = FloatingFormat.build(reading(ageMin = -3), now, 15, GlucoseUnit.MG_DL)
        assertEquals(0, s.ageMinutes)
    }

    @Test fun mmolEOpcoesDeEsconder() {
        val s = FloatingFormat.build(reading(sgv = 180.0, delta = -9), now, 15, GlucoseUnit.MMOL_L,
            showArrow = false, showDelta = false)
        assertEquals(String.format("%.1f", 10.0), s.value); assertEquals("", s.arrow); assertNull(s.delta)
    }

    @Test fun tons() {
        assertEquals(FloatingTone.URGENT, FloatingFormat.toneOf(GlucoseZone.URGENT_LOW))
        assertEquals(FloatingTone.WARN, FloatingFormat.toneOf(GlucoseZone.HIGH))
        assertEquals(FloatingTone.QUICK_DROP, FloatingFormat.toneOf(GlucoseZone.QUICK_DROP))
    }

    @Test fun configIdaEVolta() {
        val c = FloatingConfig(enabled = true, scale = 1.4f, alpha = 0.5f, showDelta = false, locked = true,
            hideMinutes = 10, x = 33, y = 400)
        assertEquals(c, FloatingConfig.decode(c.encode()))
    }

    @Test fun configInvalidaCaiNoPadraoELimita() {
        assertEquals(FloatingConfig(), FloatingConfig.decode(null))
        assertEquals(FloatingConfig(), FloatingConfig.decode("lixo;;=;enabled=talvez"))
        val c = FloatingConfig.decode("scale=99;alpha=0;hideMinutes=0;x=abc")
        assertEquals(FloatingConfig.MAX_SCALE, c.scale)
        assertEquals(FloatingConfig.MIN_ALPHA, c.alpha)
        assertEquals(1, c.hideMinutes)
        assertEquals(-1, c.x)
    }
}
