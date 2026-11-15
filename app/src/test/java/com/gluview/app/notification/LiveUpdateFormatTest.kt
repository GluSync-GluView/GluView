package com.gluview.app.notification

import com.gluview.app.util.GlucoseUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** PT-6 — formatação do Live Update (valor, seta, delta, estado velho). */
class LiveUpdateFormatTest {

    private fun fmt(sgv: Double, arrow: String = "↗", delta: Int = 3, stale: Boolean = false,
                    time: String? = "14:05", unit: GlucoseUnit = GlucoseUnit.MG_DL) =
        LiveUpdateFormat.format(sgv, arrow, delta, stale, time, unit, 70f, 180f, "Sem sinal") { "Última leitura $it" }

    @Test fun `leitura atual em mg por dL`() {
        val t = fmt(135.0)
        assertEquals("135↗", t.chip)
        assertEquals("135 ↗ mg/dL", t.title)
        assertEquals("+3 mg/dL · 14:05", t.text)
        assertEquals(LiveUpdateFormat.position(135.0), t.progress)
    }

    @Test fun `delta negativo e zero`() {
        assertEquals("-12 mg/dL · 14:05", fmt(100.0, delta = -12).text)
        assertEquals("0 mg/dL · 14:05", fmt(100.0, delta = 0).text)
    }

    @Test fun `mmol por L`() {
        val t = fmt(135.0, delta = 9, unit = GlucoseUnit.MMOL_L)
        assertTrue(t.chip == "7.5↗" || t.chip == "7,5↗")
        assertTrue(t.title.endsWith("mmol/L"))
        assertTrue(t.text.startsWith("+0.5") || t.text.startsWith("+0,5"))
    }

    @Test fun `dado velho nunca aparece como atual`() {
        val t = fmt(135.0, stale = true)
        assertEquals("--", t.chip)
        assertEquals("Sem sinal", t.title)
        assertEquals("Última leitura 14:05", t.text)
        assertNull(t.progress)
    }

    @Test fun `leitura invalida vira sem sinal`() {
        assertEquals("--", fmt(0.0).chip)
        assertEquals("--", fmt(Double.NaN).chip)
        assertEquals("Sem sinal", fmt(0.0, time = null).text)
    }

    @Test fun `chip curto mesmo com seta dupla`() {
        val t = fmt(399.0, arrow = "⇈")
        assertTrue(t.chip.length <= LiveUpdateFormat.CHIP_MAX_CHARS)
    }

    @Test fun `barra com tres segmentos que somam o maximo e posicao limitada`() {
        val s = LiveUpdateFormat.segments(70.0, 180.0)
        assertEquals(3, s.size)
        assertEquals(LiveUpdateFormat.PROGRESS_MAX, s.sumOf { it.length })
        assertTrue(s.all { it.length > 0 })
        assertEquals(0, LiveUpdateFormat.position(20.0))
        assertEquals(LiveUpdateFormat.PROGRESS_MAX, LiveUpdateFormat.position(600.0))
        // Faixa absurda não gera segmento vazio nem negativo.
        val odd = LiveUpdateFormat.segments(500.0, 10.0)
        assertEquals(LiveUpdateFormat.PROGRESS_MAX, odd.sumOf { it.length })
        assertTrue(odd.all { it.length > 0 })
    }
}
