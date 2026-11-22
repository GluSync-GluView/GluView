package com.gluview.app.assistant

import com.gluview.app.domain.model.GlucoseReading
import com.gluview.app.domain.model.GlucoseZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Falas do atalho do Assistente — mesmas regras da skill da Alexa no GluSync. */
class VoiceGlucoseTextTest {
    private val now = 1_800_000_000_000L
    private fun r(sgv: Double, dir: String, ageMin: Int, arrow: String = "") =
        GlucoseReading(sgv, dir, arrow, "", GlucoseZone.NORMAL, 0, false, ageMin, now - ageMin * 60_000L)

    @Test fun atualComSetaEFaixa() {
        assertEquals("Sua glicose está em 132 miligramas por decilitro, subindo devagar, medida há 2 minutos.",
            VoiceGlucoseText.speak(r(132.0, "FortyFiveUp", 2), false, 70f, 180f, now))
        assertEquals("Sua glicose está em 7,3 milimols por litro, subindo devagar, medida há 2 minutos.",
            VoiceGlucoseText.speak(r(132.0, "FortyFiveUp", 2), true, 70f, 180f, now))
        assertEquals("Sua glicose está em 65 miligramas por decilitro, caindo, medida agora há pouco. Abaixo da sua faixa.",
            VoiceGlucoseText.speak(r(65.0, "", 0, arrow = "↓"), false, 70f, 180f, now))
    }

    @Test fun velhaNuncaComoAtual() {
        val s = VoiceGlucoseText.speak(r(120.0, "Flat", 35), false, 70f, 180f, now)
        assertEquals("A última leitura do GluView é de há 35 minutos: 120 miligramas por decilitro. Pode estar sem sinal; confira o sensor.", s)
        assertFalse(s.startsWith("Sua glicose"))
    }

    @Test fun semLeitura() {
        assertEquals("Ainda não há leitura de glicose no GluView.", VoiceGlucoseText.speak(null, false, 70f, 180f, now))
    }
}
