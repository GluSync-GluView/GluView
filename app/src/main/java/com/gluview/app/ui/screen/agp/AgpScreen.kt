package com.gluview.app.ui.screen.agp

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.gluview.app.R
import com.gluview.app.domain.agp.AgpReport
import com.gluview.app.domain.agp.HourlyPercentiles
import com.gluview.app.ui.components.chart.rememberChartAppearance
import com.gluview.app.ui.components.kit.ErrorState
import com.gluview.app.ui.components.kit.Fact
import com.gluview.app.ui.components.kit.FactList
import com.gluview.app.ui.components.kit.GluCallout
import com.gluview.app.ui.components.kit.GluCard
import com.gluview.app.ui.components.kit.ListSkeleton
import com.gluview.app.ui.components.kit.Page
import com.gluview.app.ui.components.kit.Tone
import com.gluview.app.ui.theme.Spacing
import com.gluview.app.util.GlucoseUnit
import com.gluview.app.util.formatGlucose
import com.gluview.app.util.formatGlucoseWithUnit
import com.gluview.app.util.graph.AgpPalette
import java.util.Locale

@Composable
fun AgpScreen(onBack: () -> Unit, vm: AgpViewModel = hiltViewModel()) {
    val state by vm.ui.collectAsState()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.exportMsg) {
        state.exportMsg?.let { snackbar.showSnackbar(context.getString(it)); vm.clearExportMsg() }
    }
    AgpContent(state, rememberChartAppearance().unit, vm::onPeriod, vm::retry, vm::exportPdf, onBack, snackbar)
}

/**
 * PT-4 — Perfil Ambulatorial de Glicose. Período 7/14/30 dias; com menos de 14 dias ou menos de
 * 70% de sensor ativo o AGP aparece com o aviso, nunca escondido. Não é diagnóstico.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AgpContent(
    state: AgpUiState,
    unit: GlucoseUnit,
    onPeriod: (AgpPeriod) -> Unit,
    onRetry: () -> Unit,
    onExportPdf: () -> Unit,
    onBack: () -> Unit,
    snackbarHostState: SnackbarHostState? = null
) {
    Page(title = stringResource(R.string.agp_title), onBack = onBack, snackbarHostState = snackbarHostState) {
        Text(stringResource(R.string.agp_intro), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            AgpPeriod.entries.forEach { p ->
                FilterChip(selected = state.period == p, onClick = { onPeriod(p) },
                    label = { Text(stringResource(R.string.agp_period_days, p.days)) })
            }
        }
        if (state.followerOnly) GluCallout(stringResource(R.string.agp_follower_note), tone = Tone.Info)

        val report = state.report
        when {
            state.isLoading -> ListSkeleton(rows = 3)
            state.error != null -> ErrorState(stringResource(R.string.history_load_error_body), onRetry = onRetry, detail = state.error)
            report == null || !report.hasData -> GluCallout(stringResource(R.string.agp_no_data), tone = Tone.Info)
            else -> AgpBody(report, unit, state.exporting, onExportPdf)
        }
        GluCallout(stringResource(R.string.agp_disclaimer), tone = Tone.Neutral)
    }
}

private fun pct(v: Double) = String.format(Locale.getDefault(), "%.1f%%", v)

@Composable
private fun AgpBody(report: AgpReport, unit: GlucoseUnit, exporting: Boolean, onExportPdf: () -> Unit) {
    // Avisos de suficiência: o consenso pede ≥ 14 dias e ≥ 70% de sensor ativo.
    if (report.periodTooShort) GluCallout(stringResource(R.string.agp_warn_short_period), tone = Tone.Warning)
    if (report.lowSensorTime) GluCallout(stringResource(R.string.agp_warn_low_active, pct(report.activePercent)), tone = Tone.Warning)

    GluCard {
        Text(stringResource(R.string.agp_metrics_title), style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() })
        FactList(buildList {
            add(Fact(stringResource(R.string.agp_active_time), pct(report.activePercent)))
            add(Fact(stringResource(R.string.agp_days_with_data), report.dataDays.toString()))
            report.meanMgDl?.let { add(Fact(stringResource(R.string.agp_mean), formatGlucoseWithUnit(it, unit))) }
            report.gmiPercent?.let { add(Fact(stringResource(R.string.agp_gmi), pct(it))) }
            report.cvPercent?.let {
                add(Fact(stringResource(R.string.agp_cv), stringResource(
                    if (it <= 36.0) R.string.agp_cv_stable else R.string.agp_cv_unstable, pct(it))))
            }
        })
    }

    GluCard {
        Text(stringResource(R.string.agp_tir_title), style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() })
        val r = report.ranges
        val t = report.timeInRanges
        FactList(listOf(
            Fact(stringResource(R.string.agp_range_very_high, formatGlucose(r.veryHighAbove, unit)), pct(t.veryHighPercent), AgpColors.veryHigh),
            Fact(stringResource(R.string.agp_range_high, formatGlucose(r.targetHigh + 1, unit), formatGlucose(r.veryHighAbove, unit)), pct(t.highPercent), AgpColors.high),
            Fact(stringResource(R.string.agp_range_target, formatGlucose(r.targetLow, unit), formatGlucose(r.targetHigh, unit)), pct(t.targetPercent), AgpColors.target),
            Fact(stringResource(R.string.agp_range_low, formatGlucose(r.veryLowBelow, unit), formatGlucose(r.targetLow - 1, unit)), pct(t.lowPercent), AgpColors.low),
            Fact(stringResource(R.string.agp_range_very_low, formatGlucose(r.veryLowBelow, unit)), pct(t.veryLowPercent), AgpColors.veryLow)
        ))
        if (!r.isConsensus) Text(stringResource(R.string.agp_custom_target), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    GluCard {
        Text(stringResource(R.string.agp_chart_title), style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() })
        val description = stringResource(R.string.agp_chart_description,
            report.hourly.mapNotNull { it.p50 }.minOrNull()?.let { formatGlucose(it, unit) } ?: "–",
            report.hourly.mapNotNull { it.p50 }.maxOrNull()?.let { formatGlucose(it, unit) } ?: "–")
        AgpChart(report, unit, Modifier.fillMaxWidth().height(240.dp).semantics { contentDescription = description })
        Text(stringResource(R.string.agp_chart_legend), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    Button(onClick = onExportPdf, enabled = !exporting, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
            if (exporting) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
            Text(stringResource(R.string.agp_export_pdf))
        }
    }
}

/** Cores do AGP (o mesmo esquema no app e no PDF). */
internal object AgpColors {
    val veryLow  = Color(AgpPalette.VERY_LOW)
    val low      = Color(AgpPalette.LOW)
    val target   = Color(AgpPalette.TARGET)
    val high     = Color(AgpPalette.HIGH)
    val veryHigh = Color(AgpPalette.VERY_HIGH)
    val band95   = Color(AgpPalette.BAND_95)
    val band75   = Color(AgpPalette.BAND_75)
    val median   = Color(AgpPalette.MEDIAN)

    /** Escala vertical do gráfico, em mg/dL. */
    const val Y_MIN = 40.0
    const val Y_MAX = 350.0
}

@Composable
internal fun AgpChart(report: AgpReport, unit: GlucoseUnit, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    // Mediana em contraste com o fundo (azul-escuro do AGP some no tema escuro).
    val medianColor = if (androidx.compose.foundation.isSystemInDarkTheme() || MaterialTheme.colorScheme.surface.luminanceIsDark())
        MaterialTheme.colorScheme.onSurface else AgpColors.median
    val labelStyle = TextStyle(fontSize = 11.sp, color = labelColor)
    Box(modifier) {
        Canvas(Modifier.fillMaxWidth().height(240.dp)) {
            val left = 36.dp.toPx(); val bottom = 20.dp.toPx(); val top = 8.dp.toPx(); val right = 8.dp.toPx()
            val w = size.width - left - right
            val h = size.height - top - bottom
            fun x(hour: Double) = left + (hour / 24.0 * w).toFloat()
            fun y(mg: Double) = top + ((1 - (mg.coerceIn(AgpColors.Y_MIN, AgpColors.Y_MAX) - AgpColors.Y_MIN) /
                (AgpColors.Y_MAX - AgpColors.Y_MIN)) * h).toFloat()

            // Faixa-alvo e limites de referência.
            val r = report.ranges
            drawRect(AgpColors.target.copy(alpha = 0.08f), Offset(left, y(r.targetHigh)), Size(w, y(r.targetLow) - y(r.targetHigh)))
            listOf(r.veryLowBelow, r.targetLow, r.targetHigh, r.veryHighAbove).forEach { v ->
                val color = if (v == r.targetLow || v == r.targetHigh) AgpColors.target else gridColor
                drawLine(color, Offset(left, y(v)), Offset(left + w, y(v)), 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
                val text = measurer.measure(formatGlucose(v, unit), labelStyle)
                drawText(text, topLeft = Offset(left - text.size.width - 4.dp.toPx(), y(v) - text.size.height / 2f))
            }
            for (hh in 0..24 step 6) {
                val text = measurer.measure(String.format(Locale.ROOT, "%02d:00", hh % 24), labelStyle)
                val cx = (x(hh.toDouble()) - text.size.width / 2f).coerceIn(left - text.size.width / 2f, size.width - text.size.width.toFloat())
                drawText(text, topLeft = Offset(cx, top + h + 4.dp.toPx()))
            }

            drawBand(report.hourly, ::x, ::y, { it.p5 }, { it.p95 }, AgpColors.band95.copy(alpha = 0.55f))
            drawBand(report.hourly, ::x, ::y, { it.p25 }, { it.p75 }, AgpColors.band75.copy(alpha = 0.55f))
            drawLineSeries(report.hourly, ::x, ::y, { it.p50 }, medianColor, 2.5.dp.toPx())
        }
    }
}

/** Ponto de cada hora no meio do intervalo (hora + 0,5); horas sem dado interrompem a faixa. */
private fun DrawScope.drawBand(
    hourly: List<HourlyPercentiles>, x: (Double) -> Float, y: (Double) -> Float,
    lower: (HourlyPercentiles) -> Double?, upper: (HourlyPercentiles) -> Double?, color: Color
) {
    segments(hourly) { lower(it) != null && upper(it) != null }.forEach { seg ->
        val path = Path()
        seg.forEachIndexed { i, p ->
            val px = x(p.hour + 0.5); val py = y(upper(p)!!)
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        seg.asReversed().forEach { p -> path.lineTo(x(p.hour + 0.5), y(lower(p)!!)) }
        path.close()
        drawPath(path, color)
    }
}

private fun DrawScope.drawLineSeries(
    hourly: List<HourlyPercentiles>, x: (Double) -> Float, y: (Double) -> Float,
    value: (HourlyPercentiles) -> Double?, color: Color, width: Float
) {
    segments(hourly) { value(it) != null }.forEach { seg ->
        if (seg.size == 1) {
            drawCircle(color, width, Offset(x(seg[0].hour + 0.5), y(value(seg[0])!!)))
            return@forEach
        }
        val path = Path()
        seg.forEachIndexed { i, p ->
            val px = x(p.hour + 0.5); val py = y(value(p)!!)
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        drawPath(path, color, style = Stroke(width))
    }
}

private fun segments(hourly: List<HourlyPercentiles>, has: (HourlyPercentiles) -> Boolean): List<List<HourlyPercentiles>> {
    val out = mutableListOf<List<HourlyPercentiles>>()
    var cur = mutableListOf<HourlyPercentiles>()
    hourly.sortedBy { it.hour }.forEach { p ->
        if (has(p)) cur.add(p) else { if (cur.isNotEmpty()) out += cur; cur = mutableListOf() }
    }
    if (cur.isNotEmpty()) out += cur
    return out
}

private fun Color.luminanceIsDark(): Boolean = (0.299 * red + 0.587 * green + 0.114 * blue) < 0.5
