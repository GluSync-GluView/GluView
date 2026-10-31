package com.gluview.app.ui.screen.tir

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
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
import com.gluview.app.data.remote.dto.TirDayDto
import com.gluview.app.ui.components.chart.CHART_LABEL_MAX_FONT_SCALE
import com.gluview.app.ui.components.chart.GlucoseChartFrame
import com.gluview.app.ui.components.chart.rememberChartAppearance
import com.gluview.app.ui.components.kit.AppDialog
import com.gluview.app.ui.components.kit.ChoiceChips
import com.gluview.app.ui.components.kit.EmptyState
import com.gluview.app.ui.components.kit.ErrorState
import com.gluview.app.ui.components.kit.ListSkeleton
import com.gluview.app.ui.components.kit.StatGrid
import com.gluview.app.ui.components.kit.StatTile
import com.gluview.app.ui.components.kit.Tone
import com.gluview.app.ui.theme.Spacing
import com.gluview.app.util.ServerDates
import com.gluview.app.util.graph.GraphPalette
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

// Cores próprias do TIR (decisão 5.3 #1 do redesign: ficam como estão).
private val ColorHipo  = Color(0xFFF44336)
private val ColorNorm  = Color(0xFF4CAF50)
private val ColorHiper = Color(0xFFFFC107)

// Sem Scaffold próprio — vira o conteúdo de uma aba de HistoryScreen (Scaffold/TopAppBar
// compartilhada entre Gráfico/TIR/Padrão).
@Composable
fun TirScreen(
    vm: TirViewModel = hiltViewModel()
) {
    val state by vm.uiState.collectAsState()
    TirContent(
        state = state,
        onPeriodSelected = vm::onPeriodSelected,
        chartDark = rememberChartAppearance().dark,
        onRetry = vm::retry
    )
}

/**
 * Aba TIR do Histórico (redesign visual, Fase 4): os chips de período e a ajuda (48dp) na mesma
 * linha, sem o título repetido da aba; carregando e erro não escondem o seletor; os resumos são
 * [StatTile]; as barras por dia ficam na moldura do gráfico, com eixo 0–100%, datas e descrição.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TirContent(
    state: TirUiState,
    onPeriodSelected: (TirPeriod) -> Unit,
    chartDark: Boolean = false,
    onRetry: (() -> Unit)? = null
) {
    var showExplanation by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.md)
            .padding(top = Spacing.xxs, bottom = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        // Topo: com os chips em 2 linhas, a ajuda fica ao lado da primeira.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            ChoiceChips(
                options = TirPeriod.entries,
                selected = state.period,
                onSelect = onPeriodSelected,
                label = { stringResource(R.string.pattern_days_count, it.days) },
                modifier = Modifier.weight(1f),
                fillWidth = false
            )
            IconButton(onClick = { showExplanation = true }) {
                Icon(
                    Icons.AutoMirrored.Outlined.HelpOutline,
                    contentDescription = stringResource(R.string.tir_explanation_title),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        val report = state.report
        when {
            state.isLoading -> ListSkeleton(rows = 2)
            state.error != null -> ErrorState(
                message = stringResource(R.string.history_load_error_body),
                onRetry = onRetry,
                detail = state.error
            )
            // L4: dia sem nenhuma leitura chega como 0/0/0 — não é "0% no alvo", é sem dado.
            report == null || report.dias.none { it.hasData() } -> EmptyState(
                title = stringResource(R.string.tir_empty_title),
                body = stringResource(R.string.tir_empty_body),
                icon = Icons.Outlined.BarChart
            )
            else -> {
                val sublabel = stringResource(R.string.tir_period_sublabel, state.period.days)
                StatGrid(listOf(
                    // L3: verde só com a meta de 70% atingida (consenso internacional), como no portal.
                    { m -> StatTile(stringResource(R.string.tir_avg_tir), formatPercent(report.mediaTir), m, unit = "%",
                        sublabel = sublabel, tone = tirTone(report.mediaTir)) },
                    // L4: HbA1c 0 = o servidor não teve dado para estimar: "—", não "0,0%".
                    { m -> StatTile(stringResource(R.string.tir_avg_hba1c), report.mediaHbA1c.takeIf { it > 0 }?.let(::formatPercent), m,
                        unit = "%", sublabel = sublabel) }
                ))

                Text(
                    stringResource(R.string.tir_per_day),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = Spacing.xs).semantics { heading() }
                )
                // Legenda ACIMA da caixa, como nos outros gráficos.
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                    LegendItem(ColorHipo,  stringResource(R.string.tir_legend_hypo))
                    LegendItem(ColorNorm,  stringResource(R.string.tir_legend_in_target))
                    LegendItem(ColorHiper, stringResource(R.string.tir_legend_hyper))
                }
                GlucoseChartFrame(dark = chartDark) {
                    TirStackedBars(report.dias, chartDark, tirChartDescription(report.dias))
                }
            }
        }
    }

    if (showExplanation) {
        AppDialog(
            title = stringResource(R.string.tir_explanation_title),
            body = stringResource(R.string.tir_explanation_body),
            onDismiss = { showExplanation = false },
            confirmLabel = stringResource(R.string.tir_explanation_close)
        )
    }
}

private fun formatPercent(v: Double): String = String.format(Locale.getDefault(), "%.1f", v)

/** Meta de tempo no alvo (Battelino et al., Diabetes Care 2019): mais de 70% do tempo na faixa. */
internal const val TIR_TARGET_PERCENT = 70.0

/** Tom do "Tempo no alvo" (L3): sucesso só com a meta atingida; abaixo, atenção (antes neutro sempre). */
internal fun tirTone(percent: Double): Tone = if (percent >= TIR_TARGET_PERCENT) Tone.Success else Tone.Warning

/** Dia com alguma leitura (sem leitura o servidor manda 0 nas três faixas). */
internal fun TirDayDto.hasData() = hipoglicemia + tir + hiperglicemia > 0.0

/** O servidor manda "dd/MM/yyyy" (TO_CHAR do GluSync); aceita também ISO. null se não reconhecer. */
internal fun parseTirDay(s: String): LocalDate? = ServerDates.parseDay(s)

/** Dia e mês no formato do idioma ("03/10" / "10/3"); o texto original se não for uma data. */
internal fun tirDayLabel(s: String, locale: Locale = Locale.getDefault()): String {
    val date = parseTirDay(s) ?: return s
    val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, "dM")
    return date.format(DateTimeFormatter.ofPattern(pattern, locale))
}

/** "Tempo no alvo por dia, de 19/09 a 18/10. Menor 45% em 03/10, maior 95% em 10/10." */
@Composable
private fun tirChartDescription(days: List<TirDayDto>): String {
    // Só dias com leitura: um dia vazio não é o "menor 0%".
    val withData = days.filter { it.hasData() }.ifEmpty { days }
    val worst = withData.minBy { it.tir }
    val best = withData.maxBy { it.tir }
    fun pct(v: Double) = String.format(Locale.getDefault(), "%.0f", v)
    return stringResource(
        R.string.tir_a11y_chart,
        tirDayLabel(days.first().dia), tirDayLabel(days.last().dia),
        pct(worst.tir), tirDayLabel(worst.dia), pct(best.tir), tirDayLabel(best.dia)
    )
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
        Canvas(Modifier.size(12.dp)) { drawRect(color) }
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * Barras empilhadas por dia (hipo na base, alvo no meio, hiper no topo) com eixo 0/50/100% à
 * esquerda, linhas de grade a cada 25% e as datas do primeiro, do meio e do último dia embaixo.
 * Cores de grade e rótulo vêm do [GraphPalette], as mesmas do gráfico de glicemia.
 */
@Composable
private fun TirStackedBars(days: List<TirDayDto>, dark: Boolean, description: String) {
    val measurer = rememberTextMeasurer()
    val labelColor = Color(if (dark) GraphPalette.LABEL_DARK else GraphPalette.LABEL_LIGHT)
    val gridColor = Color(if (dark) GraphPalette.GRID_DARK else GraphPalette.GRID_LIGHT)
    // Rótulos do eixo como no gráfico de glicemia: 12sp, sem crescer além de 1,5x.
    val fontScale = LocalDensity.current.fontScale
    val style = TextStyle(fontSize = 12.sp * (minOf(fontScale, CHART_LABEL_MAX_FONT_SCALE) / fontScale), color = labelColor)
    val labels = remember(days) { days.map { tirDayLabel(it.dia) } }

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(200.dp)
            .semantics { contentDescription = description }
    ) {
        val yLabels = listOf(100, 50, 0).associateWith { measurer.measure("$it%", style) }
        val gutter = yLabels.values.maxOf { it.size.width } + 6.dp.toPx()
        val dateH = measurer.measure("0", style).size.height
        val plotTop = dateH / 2f
        val plotBottom = size.height - dateH - 4.dp.toPx()
        val plotH = plotBottom - plotTop
        val plotW = size.width - gutter
        fun yOf(pct: Double) = plotBottom - (pct.toFloat() / 100f) * plotH

        for (p in listOf(0, 25, 50, 75, 100)) {
            val y = yOf(p.toDouble())
            drawLine(gridColor, Offset(gutter, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
        }
        yLabels.forEach { (p, text) ->
            drawText(text, topLeft = Offset(gutter - 6.dp.toPx() - text.size.width, yOf(p.toDouble()) - text.size.height / 2f))
        }

        val count = days.size
        // Barra encolhe quando o período tem muitos dias — tudo cabe na largura.
        val slot = plotW / count
        val barW = minOf(18.dp.toPx(), slot * 0.7f)
        fun barX(i: Int) = gutter + slot * i + (slot - barW) / 2f
        days.forEachIndexed { i, day ->
            val x = barX(i)
            var bottom = plotBottom
            for ((pct, color) in listOf(day.hipoglicemia to ColorHipo, day.tir to ColorNorm, day.hiperglicemia to ColorHiper)) {
                val h = (pct.toFloat().coerceAtLeast(0f) / 100f) * plotH
                if (h > 0f) drawRect(color, topLeft = Offset(x, bottom - h), size = Size(barW, h))
                bottom -= h
            }
        }

        // Datas: primeira alinhada à esquerda, última à direita e a do meio só se couber.
        val dateY = size.height - dateH
        val first = measurer.measure(labels.first(), style)
        drawText(first, topLeft = Offset(barX(0).coerceAtMost(size.width - first.size.width), dateY))
        if (count > 1) {
            val last = measurer.measure(labels.last(), style)
            val lastX = (barX(count - 1) + barW - last.size.width).coerceAtLeast(gutter)
            val gap = 8.dp.toPx()
            if (lastX > barX(0) + first.size.width + gap) drawText(last, topLeft = Offset(lastX, dateY))
            if (count > 2) {
                val mid = count / 2
                val middle = measurer.measure(labels[mid], style)
                val midX = barX(mid) + barW / 2f - middle.size.width / 2f
                if (midX > barX(0) + first.size.width + gap && midX + middle.size.width + gap < lastX) {
                    drawText(middle, topLeft = Offset(midX, dateY))
                }
            }
        }
    }
}
