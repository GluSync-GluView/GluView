package com.gluview.app.util

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import com.gluview.app.data.remote.dto.HbA1cResultDto
import com.gluview.app.data.remote.dto.MonthlyStatsDto
import com.gluview.app.data.remote.dto.PivotRowDto
import com.gluview.app.data.remote.dto.SummaryCabecalhoDto
import com.gluview.app.data.remote.dto.SummaryReportDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneId
import java.util.Locale

/** PDF/CSV do relatório no idioma do app, na unidade e no formato de hora do usuário. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ReportExportTextTest {

    private val report = SummaryReportDto(
        cabecalho = SummaryCabecalhoDto("10/2026", "2026-10-04T00:00:00Z", 18,
            HbA1cResultDto("últimos 90 dias", 145.0, 6.7, "Bom", 20_000, "good")),
        tabelaPivot = listOf(PivotRowDto("03/10/2026", 2026, 10, 120.0, 0.0, 150.0, 160.0, 170.0, 140.0, 130.0, 110.0, 142.0, 280, 6.6)),
        resumoMensal = listOf(MonthlyStatsDto(2026, 10, 142.0, 6.6, 71.5, 18, 5_120))
    )

    private fun text(tag: String, unit: GlucoseUnit, time: TimeFormat): ReportExportText {
        val locale = Locale.forLanguageTag(tag)
        val base = ApplicationProvider.getApplicationContext<Context>()
        val res = base.createConfigurationContext(Configuration(base.resources.configuration).apply { setLocale(locale) })
        return ReportExportText(res, locale, unit, time, ZoneId.of("UTC"))
    }

    @Test fun `pdf em portugues e mg-dL`() {
        val lines = text("pt-BR", GlucoseUnit.MG_DL, TimeFormat.H24).pdfLines(report).map { it.first }
        assertEquals("Relatório GluView", lines.first())
        assertTrue(lines.toString(), "Período: Outubro 2026" in lines)
        assertTrue(lines.toString(), "Gerado em: 04/10/2026 00:00" in lines)
        assertTrue(lines.toString(), "HbA1c estimada (90 dias): 6,7% — Bom" in lines)
        assertTrue(lines.toString(), "Glicemia média (90 dias): 145 mg/dL" in lines)
    }

    @Test fun `pdf em ingles, mmol e 12h`() {
        val lines = text("en-US", GlucoseUnit.MMOL_L, TimeFormat.H12).pdfLines(report).map { it.first }
        assertEquals("GluView report", lines.first())
        assertTrue(lines.toString(), "Period: October 2026" in lines)
        assertTrue(lines.toString(), lines.any { it.startsWith("Generated on: 10/4/26 12:00") && it.endsWith("AM") })
        assertTrue(lines.toString(), "Estimated HbA1c (90 days): 6.7% — Good" in lines)
        assertTrue(lines.toString(), "Average glucose (90 days): 8.1 mmol/L" in lines)
    }

    @Test fun `csv pt usa ponto e virgula e virgula decimal`() {
        val t = text("pt-BR", GlucoseUnit.MG_DL, TimeFormat.H24)
        assertEquals(';', t.csvSeparator)
        val csv = t.csv(report).lines()
        assertTrue(csv.toString(), "Outubro 2026;142;6,6;71,5;18;5120" in csv)
        // Faixa sem leitura (0) fica vazia; dia no formato do idioma.
        assertTrue(csv.toString(), "03/10/2026;120;;150;160;170;140;130;110;142;280;6,6" in csv)
        assertTrue(csv.toString(), csv.any { it.startsWith("Dia;00:00;03:00") })
    }

    @Test fun `csv en usa virgula e ponto decimal`() {
        val t = text("en-US", GlucoseUnit.MMOL_L, TimeFormat.H12)
        assertEquals(',', t.csvSeparator)
        val csv = t.csv(report).lines()
        assertTrue(csv.toString(), "October 2026,7.9,6.6,71.5,18,5120" in csv)
        assertTrue(csv.toString(), csv.any { it.startsWith("Day,12 AM,3 AM") })
    }
}
