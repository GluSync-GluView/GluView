package com.gluview.app.ui.screen.dailypattern

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import com.gluview.app.R
import com.gluview.app.data.repository.DailyPatternPoint
import com.gluview.app.ui.components.chart.BubbleText
import com.gluview.app.ui.components.chart.ChartAppearance
import com.gluview.app.ui.components.chart.GlucoseChart
import com.gluview.app.ui.components.chart.GlucoseChartPanel
import com.gluview.app.ui.components.chart.rememberChartAppearance
import com.gluview.app.ui.components.kit.LoadState
import com.gluview.app.ui.theme.Spacing
import com.gluview.app.util.GraphReading
import com.gluview.app.util.UiText
import com.gluview.app.util.epochMsToTimeOfDay
import com.gluview.app.util.graph.GraphWindow
import java.time.LocalDate
import java.time.ZoneId

// Sem Scaffold próprio — vira o conteúdo de uma aba de HistoryScreen (Scaffold/TopAppBar
// compartilhada entre Gráfico/TIR/Padrão).
@Composable
fun DailyPatternScreen(
    vm: DailyPatternViewModel = hiltViewModel()
) {
    val period    by vm.period.collectAsState()
    val points    by vm.points.collectAsState()
    val isLoading by vm.isLoading.collectAsState()
    val error     by vm.error.collectAsState()
    DailyPatternContent(
        period = period, points = points, isLoading = isLoading, error = error,
        appearance       = rememberChartAppearance(),
        onPeriodSelected = vm::onPeriodSelected,
        onRetry          = vm::retry
    )
}

/** Dia de referência do Padrão: só dá posição às faixas (data fixa, sem horário de verão). */
private val REFERENCE_DAY: LocalDate = LocalDate.of(2026, 1, 15)
private const val SLOT_MS = 3 * 3_600_000L

/** Início (epoch ms) do dia de referência no fuso do aparelho. */
private fun referenceStartMs(zone: ZoneId = ZoneId.systemDefault()): Long =
    REFERENCE_DAY.atStartOfDay(zone).toInstant().toEpochMilli()

/** As médias por faixa de 3 h como pontos do dia de referência — faixas sem dado ficam FORA
 *  (antes a curva do Vico caía a 0 nelas). */
internal fun patternReadings(points: List<DailyPatternPoint>, startMs: Long = referenceStartMs()): List<GraphReading> =
    points.filter { it.avgSgv > 0 }.map { GraphReading(startMs + it.slotHour * 3_600_000L, it.avgSgv.toFloat()) }

/**
 * Aba Padrão do Histórico (redesign visual, Fase 4): o mesmo modelo da Home ([GlucoseChart]) num
 * dia de referência 00h–24h, ticks de 3 h (o de 24h suprimido), escala 40–300 como no resto do
 * app, legenda ACIMA do gráfico e balão "Média 15:00–18:00". Os 8 valores são os mesmos da busca.
 */
@Composable
internal fun DailyPatternContent(
    period: PatternPeriod,
    points: List<DailyPatternPoint>,
    isLoading: Boolean,
    error: String?,
    appearance: ChartAppearance,
    onPeriodSelected: (PatternPeriod) -> Unit,
    onRetry: (() -> Unit)? = null
) {
    val startMs = remember { referenceStartMs() }
    val readings = remember(points, startMs) { patternReadings(points, startMs) }
    val state: LoadState<List<GraphReading>> = when {
        isLoading -> LoadState.Loading
        error != null -> LoadState.Error(UiText.Res(R.string.history_load_error_body), detail = error)
        readings.isEmpty() -> LoadState.Empty
        else -> LoadState.Ready(readings)
    }
    val caption = stringResource(R.string.dailypattern_avg_every_3h, period.days)
    val mean = stringResource(R.string.pattern_bubble_mean)
    val slotFormat = stringResource(R.string.pattern_slot_range)

    GlucoseChartPanel(
        title = null,
        modifier = Modifier.fillMaxSize().padding(horizontal = Spacing.md).padding(top = Spacing.xxs, bottom = Spacing.xs),
        periods = PatternPeriod.entries.map { it.days },
        selectedPeriod = period.days,
        onSelectPeriod = { d -> PatternPeriod.entries.firstOrNull { it.days == d }?.let(onPeriodSelected) },
        periodLabel = { stringResource(R.string.pattern_days_count, it) },
        // Legenda acima da caixa do gráfico (antes ficava embaixo).
        status = {
            Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    ) {
        GlucoseChart(
            state = state,
            // Até 23:59:59.999: o tick das 24h (= 00:00 do dia seguinte) não aparece.
            window = GraphWindow.Fixed(startMs, startMs + 24 * 3_600_000L - 1),
            appearance = appearance,
            onRetry = onRetry,
            modifier = Modifier.fillMaxWidth().weight(1f),
            xTickStepHours = 3,
            // Faixas vizinhas (3 h) ligadas; uma faixa sem dado no meio quebra a linha.
            gapBreakMs = SLOT_MS + 30 * 60_000L,
            bubbleText = { r ->
                BubbleText(
                    title = mean,
                    time = String.format(
                        slotFormat,
                        epochMsToTimeOfDay(r.timestampMs, appearance.timeFormat),
                        epochMsToTimeOfDay(r.timestampMs + SLOT_MS, appearance.timeFormat)
                    )
                )
            },
            description = caption
        )
    }
}
