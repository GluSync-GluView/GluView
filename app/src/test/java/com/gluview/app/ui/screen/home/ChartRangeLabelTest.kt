package com.gluview.app.ui.screen.home

import com.gluview.app.util.TimeFormat
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

/** Rótulo do intervalo do gráfico da Home ao arrastar (15/11/2026: antes "22:38 – 22:38" num intervalo de 24 h). */
class ChartRangeLabelTest {

    private val zone = ZoneId.of("America/Sao_Paulo")
    private fun ms(d: Int, h: Int, m: Int) = LocalDateTime.of(2026, 11, d, h, m).atZone(zone).toInstant().toEpochMilli()

    @Test fun `mesmo dia mostra so as horas`() {
        assertEquals("08:00 – 11:00", chartRangeLabel(ms(15, 8, 0), ms(15, 11, 0), TimeFormat.H24, Locale.forLanguageTag("pt-BR"), zone))
    }

    @Test fun `atravessando a meia-noite mostra o dia da semana`() {
        val label = chartRangeLabel(ms(14, 22, 38), ms(15, 22, 38), TimeFormat.H24, Locale.forLanguageTag("pt-BR"), zone)
        assertEquals("sáb. 22:38 – dom. 22:38", label)
    }

    @Test fun `em ingles com 12 h`() {
        val label = chartRangeLabel(ms(14, 22, 38), ms(15, 10, 38), TimeFormat.H12, Locale.US, zone)
        assertEquals("Sat 10:38 PM – Sun 10:38 AM", label)
    }
}
