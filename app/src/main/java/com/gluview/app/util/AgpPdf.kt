package com.gluview.app.util

import android.content.Context
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.pdf.PdfDocument
import com.gluview.app.R
import com.gluview.app.domain.agp.AgpReport
import com.gluview.app.domain.agp.HourlyPercentiles
import com.gluview.app.util.graph.AgpPalette
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * PT-4 — PDF do AGP para levar ao médico: uma página A4 com métricas, tempo nas faixas, o gráfico
 * de percentis por hora e o aviso médico. Mesmas cores da tela ([com.gluview.app.ui.screen.agp.AgpColors]).
 */
internal object AgpPdf {

    private const val W = 595
    private const val H = 842
    private const val MARGIN = 40f

    fun write(report: AgpReport, ctx: Context, locale: Locale, unit: GlucoseUnit, file: File) {
        val doc = PdfDocument()
        val page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, 1).create())
        val c = page.canvas
        val title = Paint().apply { textSize = 18f; color = Color.BLACK; isFakeBoldText = true; isAntiAlias = true }
        val head = Paint().apply { textSize = 13f; color = Color.BLACK; isFakeBoldText = true; isAntiAlias = true }
        val text = Paint().apply { textSize = 11f; color = Color.BLACK; isAntiAlias = true }
        val small = Paint().apply { textSize = 9f; color = Color.DKGRAY; isAntiAlias = true }
        fun pct(v: Double) = String.format(locale, "%.1f%%", v)
        fun g(v: Double) = with(unit) { v.toDisplay() }

        var y = 56f
        c.drawText(ctx.getString(R.string.agp_title), MARGIN, y, title); y += 20f
        val dates = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
        val zone = ZoneId.systemDefault()
        c.drawText(ctx.getString(R.string.agp_pdf_period,
            dates.format(Instant.ofEpochMilli(report.fromMs).atZone(zone)),
            dates.format(Instant.ofEpochMilli(report.toMs - 1).atZone(zone)),
            Math.round(report.periodDays).toInt()), MARGIN, y, text); y += 16f

        if (report.periodTooShort) { c.drawText(ctx.getString(R.string.agp_warn_short_period), MARGIN, y, small); y += 13f }
        if (report.lowSensorTime) { c.drawText(ctx.getString(R.string.agp_warn_low_active, pct(report.activePercent)), MARGIN, y, small); y += 13f }
        y += 8f

        // Métricas (coluna esquerda) e tempo nas faixas (coluna direita).
        val colR = W / 2f + 10f
        var yl = y; var yr = y
        c.drawText(ctx.getString(R.string.agp_metrics_title), MARGIN, yl, head); yl += 18f
        fun metric(label: String, value: String) {
            c.drawText(label, MARGIN, yl, text); c.drawText(value, MARGIN + 170f, yl, text); yl += 15f
        }
        metric(ctx.getString(R.string.agp_active_time), pct(report.activePercent))
        metric(ctx.getString(R.string.agp_days_with_data), report.dataDays.toString())
        report.meanMgDl?.let { metric(ctx.getString(R.string.agp_mean), "${g(it)} ${unit.label}") }
        report.gmiPercent?.let { metric(ctx.getString(R.string.agp_gmi), pct(it)) }
        report.cvPercent?.let { metric(ctx.getString(R.string.agp_cv), pct(it)) }

        c.drawText(ctx.getString(R.string.agp_tir_title), colR, yr, head); yr += 18f
        val r = report.ranges
        val t = report.timeInRanges
        fun range(color: Int, label: String, value: Double) {
            val sw = Paint().apply { this.color = color }
            c.drawRect(colR, yr - 9f, colR + 9f, yr, sw)
            c.drawText(label, colR + 14f, yr, text); c.drawText(pct(value), colR + 190f, yr, text); yr += 15f
        }
        range(AgpPalette.VERY_HIGH, ctx.getString(R.string.agp_range_very_high, g(r.veryHighAbove)), t.veryHighPercent)
        range(AgpPalette.HIGH, ctx.getString(R.string.agp_range_high, g(r.targetHigh + 1), g(r.veryHighAbove)), t.highPercent)
        range(AgpPalette.TARGET, ctx.getString(R.string.agp_range_target, g(r.targetLow), g(r.targetHigh)), t.targetPercent)
        range(AgpPalette.LOW, ctx.getString(R.string.agp_range_low, g(r.veryLowBelow), g(r.targetLow - 1)), t.lowPercent)
        range(AgpPalette.VERY_LOW, ctx.getString(R.string.agp_range_very_low, g(r.veryLowBelow)), t.veryLowPercent)
        if (!r.isConsensus) { c.drawText(ctx.getString(R.string.agp_custom_target), colR, yr, small); yr += 13f }

        y = maxOf(yl, yr) + 18f
        c.drawText(ctx.getString(R.string.agp_chart_title), MARGIN, y, head); y += 10f
        drawChart(c, report, unit, MARGIN, y, W - 2 * MARGIN, 300f, small)
        y += 300f + 24f
        c.drawText(ctx.getString(R.string.agp_chart_legend), MARGIN, y, small); y += 22f

        // Aviso médico, quebrado em linhas.
        wrap(ctx.getString(R.string.agp_disclaimer), small, W - 2 * MARGIN).forEach {
            c.drawText(it, MARGIN, y, small); y += 12f
        }
        c.drawText(ctx.getString(R.string.agp_pdf_footer), MARGIN, H - 24f, small)

        doc.finishPage(page)
        FileOutputStream(file).use { doc.writeTo(it) }
        doc.close()
    }

    private fun drawChart(c: android.graphics.Canvas, report: AgpReport, unit: GlucoseUnit,
                          x0: Float, y0: Float, w0: Float, h0: Float, label: Paint) {
        val yMin = 40.0; val yMax = 350.0
        val left = x0 + 30f; val w = w0 - 30f; val h = h0 - 16f
        fun x(hour: Double) = left + (hour / 24.0 * w).toFloat()
        fun y(mg: Double) = y0 + ((1 - (mg.coerceIn(yMin, yMax) - yMin) / (yMax - yMin)) * h).toFloat()
        val r = report.ranges
        c.drawRect(left, y(r.targetHigh), left + w, y(r.targetLow), Paint().apply { color = AgpPalette.withAlpha(AgpPalette.TARGET, 0x14) })
        val dash = Paint().apply { strokeWidth = 0.8f; style = Paint.Style.STROKE; pathEffect = DashPathEffect(floatArrayOf(4f, 4f), 0f) }
        listOf(r.veryLowBelow, r.targetLow, r.targetHigh, r.veryHighAbove).forEach { v ->
            dash.color = if (v == r.targetLow || v == r.targetHigh) AgpPalette.TARGET else Color.LTGRAY
            c.drawLine(left, y(v), left + w, y(v), dash)
            val s = with(unit) { v.toDisplay() }
            c.drawText(s, left - 4f - label.measureText(s), y(v) + 3f, label)
        }
        for (hh in 0..24 step 3) {
            val s = String.format(Locale.ROOT, "%02d", hh % 24)
            c.drawText(s, x(hh.toDouble()) - label.measureText(s) / 2, y0 + h + 12f, label)
        }
        c.drawRect(left, y0, left + w, y0 + h, Paint().apply { style = Paint.Style.STROKE; color = Color.LTGRAY; strokeWidth = 0.5f })

        band(c, report.hourly, ::x, ::y, { it.p5 }, { it.p95 }, AgpPalette.withAlpha(AgpPalette.BAND_95, 0x8C))
        band(c, report.hourly, ::x, ::y, { it.p25 }, { it.p75 }, AgpPalette.withAlpha(AgpPalette.BAND_75, 0x8C))
        val line = Paint().apply { color = AgpPalette.MEDIAN; style = Paint.Style.STROKE; strokeWidth = 2f; isAntiAlias = true }
        segments(report.hourly) { it.p50 != null }.forEach { seg ->
            val p = Path()
            seg.forEachIndexed { i, s -> if (i == 0) p.moveTo(x(s.hour + 0.5), y(s.p50!!)) else p.lineTo(x(s.hour + 0.5), y(s.p50!!)) }
            c.drawPath(p, line)
        }
    }

    private fun band(c: android.graphics.Canvas, hourly: List<HourlyPercentiles>, x: (Double) -> Float, y: (Double) -> Float,
                     lo: (HourlyPercentiles) -> Double?, hi: (HourlyPercentiles) -> Double?, color: Int) {
        val paint = Paint().apply { this.color = color; style = Paint.Style.FILL; isAntiAlias = true }
        segments(hourly) { lo(it) != null && hi(it) != null }.forEach { seg ->
            val p = Path()
            seg.forEachIndexed { i, s -> if (i == 0) p.moveTo(x(s.hour + 0.5), y(hi(s)!!)) else p.lineTo(x(s.hour + 0.5), y(hi(s)!!)) }
            seg.asReversed().forEach { s -> p.lineTo(x(s.hour + 0.5), y(lo(s)!!)) }
            p.close()
            c.drawPath(p, paint)
        }
    }

    private fun segments(hourly: List<HourlyPercentiles>, has: (HourlyPercentiles) -> Boolean): List<List<HourlyPercentiles>> {
        val out = mutableListOf<List<HourlyPercentiles>>()
        var cur = mutableListOf<HourlyPercentiles>()
        hourly.sortedBy { it.hour }.forEach { if (has(it)) cur.add(it) else { if (cur.isNotEmpty()) out += cur; cur = mutableListOf() } }
        if (cur.isNotEmpty()) out += cur
        return out
    }

    private fun wrap(s: String, paint: Paint, maxWidth: Float): List<String> {
        val out = mutableListOf<String>()
        var cur = ""
        s.split(' ').forEach { word ->
            val cand = if (cur.isEmpty()) word else "$cur $word"
            if (paint.measureText(cand) <= maxWidth || cur.isEmpty()) cur = cand else { out += cur; cur = word }
        }
        if (cur.isNotEmpty()) out += cur
        return out
    }
}
