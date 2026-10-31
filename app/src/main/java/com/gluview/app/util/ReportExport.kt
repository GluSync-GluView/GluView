package com.gluview.app.util

import android.content.Context
import androidx.annotation.StringRes
import com.gluview.app.R
import com.gluview.app.data.remote.dto.SummaryReportDto
import java.text.DecimalFormatSymbols
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

/** "Outubro 2026" / "October 2026": nome do mês no idioma. */
fun monthLabel(month: YearMonth, locale: Locale = Locale.getDefault()): String =
    month.month.getDisplayName(TextStyle.FULL_STANDALONE, locale)
        .replaceFirstChar { it.titlecase(locale) } + " " + month.year

/** Classificação da HbA1c pelo código estável do GluSync; null = código desconhecido/ausente
 *  (GluSync antigo) — aí quem chama usa o texto em português que veio junto. */
@StringRes
fun hba1cClassRes(code: String?): Int? = when (code) {
    "normal"    -> R.string.report_class_normal
    "optimal"   -> R.string.report_class_optimal
    "good"      -> R.string.report_class_good
    "attention" -> R.string.report_class_attention
    "high"      -> R.string.report_class_high
    else        -> null
}

/**
 * Texto do relatório exportado (PDF e CSV) no idioma de [res] — um contexto já no idioma do app,
 * não o do sistema — e na unidade e no formato de hora do usuário. Antes era português fixo, em
 * mg/dL e com datas "dd/MM/yyyy". Puro (sem I/O): o [ExportHelper] só desenha e grava.
 */
class ReportExportText(
    private val res: Context,
    private val locale: Locale,
    private val unit: GlucoseUnit,
    private val timeFormat: TimeFormat,
    private val zone: ZoneId = ZoneId.systemDefault()
) {
    private fun s(@StringRes id: Int) = res.getString(id)

    private fun glucoseNumber(mgdl: Double): String =
        if (unit == GlucoseUnit.MG_DL) mgdl.roundToInt().toString() else String.format(locale, "%.1f", mgdl / 18.0)

    private fun glucose(mgdl: Double) = "${glucoseNumber(mgdl)} ${unit.label}"
    private fun number(v: Double) = String.format(locale, "%.1f", v)
    private fun pct(v: Double) = "${number(v)}%"

    /** "10/2026" do servidor → "Outubro 2026"; outro formato passa como veio. */
    private fun period(p: String): String =
        runCatching { YearMonth.parse(p, DateTimeFormatter.ofPattern("MM/yyyy")) }
            .getOrNull()?.let { monthLabel(it, locale) } ?: p

    private fun generated(report: SummaryReportDto): String =
        ServerDates.toEpochMs(report.cabecalho.gerado)?.let { formatDateTime(it, timeFormat, locale, zone) }
            ?: report.cabecalho.gerado

    private fun classification(code: String?, fallback: String) = hba1cClassRes(code)?.let(::s) ?: fallback

    val title: String get() = s(R.string.export_title)
    val fileName: String get() = s(R.string.export_file_name)
    val chooserTitle: String get() = s(R.string.export_chooser)

    /** Linhas do PDF: (texto, é título). */
    fun pdfLines(report: SummaryReportDto): List<Pair<String, Boolean>> = buildList {
        val c = report.cabecalho
        add(title to true)
        add("${s(R.string.report_period)}: ${period(c.periodo)}" to false)
        add("${s(R.string.report_generated_label)}: ${generated(report)}" to false)
        add("${s(R.string.report_days_with_data)}: ${c.totalDias}" to false)
        c.hbA1c90dias?.let { h ->
            add("${s(R.string.report_hba1c_90days_label)}: ${pct(h.hbA1cEst)} — ${classification(h.classificacaoCode, h.classificacao)}" to false)
            add("${s(R.string.report_avg_glucose_90days)}: ${glucose(h.mediaGlicemia)}" to false)
        }
        if (report.resumoMensal.isNotEmpty()) {
            add(s(R.string.report_monthly_summary) to true)
            report.resumoMensal.forEach { m ->
                add(monthLabel(YearMonth.of(m.ano, m.mes), locale) to false)
                add("    ${s(R.string.report_avg_glucose_label)}: ${glucose(m.mediaMensal)}" to false)
                add("    ${s(R.string.report_time_in_range)}: ${pct(m.tirPercent)}" to false)
                add("    ${s(R.string.report_hba1c_est)}: ${pct(m.hbA1cEstMensal)}" to false)
                add("    ${s(R.string.report_days_with_data)}: ${m.totalDias}" to false)
            }
        }
    }

    /** Separador do CSV: ";" onde a vírgula é o decimal (pt), para o Excel abrir em colunas. */
    val csvSeparator: Char get() = if (DecimalFormatSymbols.getInstance(locale).decimalSeparator == ',') ';' else ','

    fun csv(report: SummaryReportDto): String = buildString {
        val sep = csvSeparator
        fun row(vararg cells: String) = appendLine(cells.joinToString(sep.toString()) { cell ->
            if (cell.contains(sep) || cell.contains('"') || cell.contains('\n')) "\"${cell.replace("\"", "\"\"")}\"" else cell
        })
        val u = unit.label
        row(s(R.string.report_period), period(report.cabecalho.periodo))
        row(s(R.string.report_generated_label), generated(report))
        appendLine()
        row(s(R.string.export_month), "${s(R.string.report_avg_glucose_label)} ($u)", "${s(R.string.report_hba1c_est)} (%)",
            "${s(R.string.report_time_in_range)} (%)", s(R.string.report_days_with_data), s(R.string.export_readings))
        report.resumoMensal.forEach { m ->
            row(monthLabel(YearMonth.of(m.ano, m.mes), locale), glucoseNumber(m.mediaMensal), number(m.hbA1cEstMensal),
                number(m.tirPercent), m.totalDias.toString(), m.totalLeituras.toString())
        }
        if (report.tabelaPivot.isNotEmpty()) {
            appendLine()
            row(s(R.string.export_daily_table) + " ($u)")
            val slots = listOf(0, 3, 6, 9, 12, 15, 18, 21).map { formatHourOfDay(it, timeFormat) }
            row(s(R.string.export_day), *slots.toTypedArray(), s(R.string.export_day_mean), s(R.string.export_readings),
                "${s(R.string.report_hba1c_est)} (%)")
            report.tabelaPivot.forEach { r ->
                val day = ServerDates.parseDay(r.dia)?.let {
                    formatDate(it.atStartOfDay(zone).toInstant().toEpochMilli(), locale, zone, FormatStyle.SHORT)
                } ?: r.dia
                // 0 = faixa sem leitura (o servidor manda 0): célula vazia, não "0 mg/dL".
                val cells = listOf(r.h00, r.h03, r.h06, r.h09, r.h12, r.h15, r.h18, r.h21).map { if (it > 0) glucoseNumber(it) else "" }
                row(day, *cells.toTypedArray(), glucoseNumber(r.mediaDia), r.totalLeituras.toString(), number(r.hbA1cEst))
            }
        }
    }
}

/** "00:00"/"12 AM" para uma hora cheia, no formato do usuário. */
private fun formatHourOfDay(hour: Int, format: TimeFormat): String =
    if (format == TimeFormat.H24) String.format(Locale.ROOT, "%02d:00", hour)
    else "${if (hour % 12 == 0) 12 else hour % 12} ${if (hour < 12) "AM" else "PM"}"
