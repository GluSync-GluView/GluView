package com.gluview.app.ui.components.chart

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gluview.app.R
import com.gluview.app.ui.components.CHART_TEST_TAG
import com.gluview.app.ui.components.LocalClock
import com.gluview.app.ui.components.kit.ChoiceChips
import com.gluview.app.ui.components.kit.ErrorState
import com.gluview.app.ui.components.kit.LoadState
import com.gluview.app.ui.components.kit.SkeletonBlock
import com.gluview.app.ui.theme.Spacing
import com.gluview.app.ui.theme.panelColorScheme
import com.gluview.app.util.GlucoseGraphRenderer
import com.gluview.app.util.GlucoseUnit
import com.gluview.app.util.GraphReading
import com.gluview.app.util.TimeFormat
import com.gluview.app.util.epochMsToTimeOfDay
import com.gluview.app.util.formatDateTime
import com.gluview.app.util.graph.DEFAULT_GAP_BREAK_MS
import com.gluview.app.util.graph.GraphLayout
import com.gluview.app.util.graph.GraphPalette
import com.gluview.app.util.graph.GraphSpec
import com.gluview.app.util.graph.GraphWindow
import com.gluview.app.util.graph.SURFACE_LABEL_MAX_FONT_SCALE
import kotlinx.coroutines.delay

/**
 * Aparência do gráfico vinda das preferências (lida por um ViewModel só de leitura nas telas):
 * tema do gráfico, limiares de Alertas, unidade e formato de hora.
 */
data class ChartAppearance(
    val dark: Boolean,
    val hipo: Float = 70f,
    val hiper: Float = 180f,
    val unit: GlucoseUnit = GlucoseUnit.MG_DL,
    val timeFormat: TimeFormat = TimeFormat.H24,
    /** Limites urgentes de Ajustes > Alertas (L2): faixa vermelha e ponto urgente. */
    val urgentLow: Float = 55f,
    val urgentHigh: Float = 250f
)

/** Texto do balão de um ponto: título opcional ("Média") e a hora/faixa ("15:00–18:00"). */
data class BubbleText(val title: String?, val time: String)

/** Teto de escala dos rótulos do gráfico (decisão 5.2 #3). */
const val CHART_LABEL_MAX_FONT_SCALE = SURFACE_LABEL_MAX_FONT_SCALE

/** Quebra a linha entre leituras mais distantes que isto (decisão 5.2 #5). */
const val CHART_GAP_BREAK_MS = DEFAULT_GAP_BREAK_MS

/**
 * A spec do modelo da Home como o app o mostra (Fase 3+): a mesma do bitmap das superfícies, com
 * as extensões aprovadas na 5.2 ligadas — unidade e 12/24h nos eixos, rótulos sem corte nem
 * colisão, em sp com teto, linha quebrada em buraco, LO/HI fora da escala e o claro com contraste.
 */
fun appChartSpec(
    widthPx: Int,
    heightPx: Int,
    window: GraphWindow,
    nowMs: Long,
    appearance: ChartAppearance,
    labelSizePx: Float?,
    xTickStepHours: Int? = null,
    gapBreakMs: Long? = CHART_GAP_BREAK_MS
): GraphSpec = GraphSpec(
    widthPx = widthPx,
    heightPx = heightPx,
    window = window,
    nowMs = nowMs,
    dark = appearance.dark,
    hipo = appearance.hipo,
    hiper = appearance.hiper,
    urgentLow = appearance.urgentLow,
    urgentHigh = appearance.urgentHigh,
    unit = appearance.unit,
    timeFormat = appearance.timeFormat,
    clampXLabels = true,
    avoidYLabelCollision = true,
    labelSizePx = labelSizePx,
    gapBreakMs = gapBreakMs,
    xTickStepHours = xTickStepHours,
    outOfRangeMarkers = true,
    highContrastLight = true
)

/** Tamanho do rótulo do gráfico em px: [size] em sp com a escala de fonte limitada a [maxScale]. */
fun chartLabelPx(density: Density, size: TextUnit = 12.sp, maxScale: Float = CHART_LABEL_MAX_FONT_SCALE): Float =
    size.value * density.density * density.fontScale.coerceAtMost(maxScale)

/** Cor do painel do gráfico ("tema do gráfico"), a mesma do bitmap das superfícies. */
fun chartPanelColor(dark: Boolean) = Color(if (dark) GraphPalette.BG_DARK else GraphPalette.BG_LIGHT)

/**
 * O gráfico de glicemia do app (redesign visual, Fase 2): o MESMO [GlucoseGraphRenderer.draw] das
 * superfícies, desenhado direto no Canvas do Compose (sem bitmap — validado no Moto Z2 no spike de
 * 03/10). Estados honestos (carregando no formato do gráfico, vazio que explica, erro com "Tentar
 * novamente"), descrição para o TalkBack e toque com o MESMO layout do desenho.
 */
@Composable
fun GlucoseChart(
    state: LoadState<List<GraphReading>>,
    window: GraphWindow,
    appearance: ChartAppearance,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
    minHeight: Dp = 160.dp,
    labelSize: TextUnit = 12.sp,
    interactive: Boolean = true,
    /** null = 1h/2h/4h conforme a janela (o de sempre); o Padrão usa 3h. */
    xTickStepHours: Int? = null,
    /** Distância a partir da qual a linha quebra; o Padrão (médias de 3 em 3 h) usa mais. */
    gapBreakMs: Long? = CHART_GAP_BREAK_MS,
    /** Texto do balão; null = a hora da leitura no formato do usuário. */
    bubbleText: ((GraphReading) -> BubbleText)? = null,
    /** Descrição falada; null = período, última, mínima e máxima. */
    description: String? = null,
    /** Registros do diário (plano 17) desenhados por cima da curva. */
    markers: List<ChartMarker> = emptyList()
) {
    val panel = chartPanelColor(appearance.dark)
    val ink = if (appearance.dark) Color.White else Color.Black
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .clip(RoundedCornerShape(12.dp))
            .background(panel)
    ) {
        // O painel segue o "tema do gráfico", não o do app: estados e balão usam as cores do
        // tema do painel (senão o erro sai cinza-escuro sobre o painel escuro num app claro).
        val scheme = panelColorScheme(appearance.dark)
        MaterialTheme(colorScheme = scheme, typography = MaterialTheme.typography, shapes = MaterialTheme.shapes) {
            CompositionLocalProvider(LocalContentColor provides scheme.onSurface) {
                when (state) {
                    LoadState.Loading -> SkeletonBlock(Modifier.fillMaxSize(), height = minHeight)
                    LoadState.Empty -> ChartMessage(stringResource(R.string.chart_empty), ink)
                    // Rola dentro do painel (com fonte grande e o painel baixo, "Tentar novamente" ficava
                    // cortado), mas só com altura limitada: numa tela que já rola (Home apertada) ela é
                    // infinita, e a rolagem aninhada derrubaria o app.
                    is LoadState.Error -> BoxWithConstraints {
                        val scroll = if (constraints.hasBoundedHeight) Modifier.verticalScroll(rememberScrollState()) else Modifier
                        ErrorState(state.message.asString(), onRetry, scroll.padding(Spacing.md), detail = state.detail)
                    }
                    is LoadState.Ready -> ChartCanvas(state.data, window, appearance, labelSize, interactive,
                        xTickStepHours, gapBreakMs, bubbleText, description, markers)
                }
            }
        }
    }
}

@Composable
private fun ChartMessage(text: String, ink: Color) {
    Box(Modifier.fillMaxSize().padding(Spacing.md), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = ink.copy(alpha = 0.7f), textAlign = TextAlign.Center)
    }
}

@Composable
private fun ChartCanvas(
    readings: List<GraphReading>,
    window: GraphWindow,
    appearance: ChartAppearance,
    labelSize: TextUnit,
    interactive: Boolean,
    xTickStepHours: Int?,
    gapBreakMs: Long?,
    bubbleText: ((GraphReading) -> BubbleText)?,
    descriptionOverride: String?,
    markers: List<ChartMarker> = emptyList()
) {
    val clock = LocalClock.current
    // Janela que anda: re-desenha a cada minuto mesmo sem leitura nova (o eixo X não para no
    // passado quando o sensor cai). Janela fixa (Histórico) não anda.
    var minuteTick by remember { mutableLongStateOf(clock() / 60_000L) }
    if (window is GraphWindow.Trailing) {
        LaunchedEffect(Unit) {
            while (true) {
                delay(60_000L - clock() % 60_000L)
                minuteTick = clock() / 60_000L
            }
        }
    }
    val density = LocalDensity.current
    val labelPx = chartLabelPx(density, labelSize)
    val description = descriptionOverride ?: chartDescription(readings, window, appearance)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val widthPx = constraints.maxWidth.coerceAtLeast(1)
        val heightPx = constraints.maxHeight.coerceAtLeast(1)
        val layout = remember(readings, window, appearance, widthPx, heightPx, labelPx, minuteTick, xTickStepHours, gapBreakMs) {
            GraphLayout.compute(
                readings,
                appChartSpec(widthPx, heightPx, window, clock(), appearance, labelPx, xTickStepHours, gapBreakMs),
                GlucoseGraphRenderer.measureText
            )
        }
        var selected by remember { mutableStateOf<GraphReading?>(null) }
        LaunchedEffect(window) { selected = null }

        Spacer(
            Modifier
                .fillMaxSize()
                .testTag(CHART_TEST_TAG)
                .semantics { contentDescription = description }
                .drawBehind { drawIntoCanvas { GlucoseGraphRenderer.draw(it.nativeCanvas, layout) } }
                .then(
                    if (interactive) Modifier.pointerInput(layout) {
                        detectTapGestures { offset ->
                            selected = GlucoseGraphRenderer.nearestReading(layout.visible, offset.x, layout.geometry)
                        }
                    } else Modifier
                )
        )

        if (markers.isNotEmpty()) {
            val ring = if (appearance.dark) Color.Black else Color.White
            Spacer(
                Modifier.fillMaxSize().drawBehind {
                    val geo = layout.geometry
                    val r = 4.5.dp.toPx()
                    markers.forEach { m ->
                        if (m.timestampMs < geo.fromMs || m.timestampMs > geo.nowMs) return@forEach
                        val x = geo.xOf(m.timestampMs)
                        val y = if (m.sgvMgDl > 0) geo.yOf(m.sgvMgDl.toFloat()) else geo.padT + geo.chartH - r * 1.5f
                        drawCircle(ring, radius = r + 1.5.dp.toPx(), center = Offset(x, y))
                        drawCircle(Color(m.argb), radius = r, center = Offset(x, y))
                    }
                }
            )
        }

        selected?.let { r ->
            val geo = layout.geometry
            val text = bubbleText?.invoke(r) ?: BubbleText(null, epochMsToTimeOfDay(r.timestampMs, appearance.timeFormat))
            ChartPointBubble(
                point = Offset(geo.xOf(r.timestampMs), geo.yOf(r.sgvMgDl)),
                title = text.title,
                timeText = text.time,
                valueText = with(appearance.unit) { r.sgvMgDl.toDouble().toDisplay() },
                unitLabel = appearance.unit.label,
                accentColor = Color(GlucoseGraphRenderer.zoneColor(r.sgvMgDl, appearance.hipo, appearance.hiper,
                    appearance.urgentLow, appearance.urgentHigh)),
                onDismiss = { selected = null },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

/** "Gráfico de glicemia, últimas 3 horas. Última leitura 135 mg/dL, mínima 98, máxima 182." */
@Composable
private fun chartDescription(readings: List<GraphReading>, window: GraphWindow, appearance: ChartAppearance): String {
    val period = when (window) {
        is GraphWindow.Trailing -> pluralStringResource(R.plurals.chart_period_hours, window.hours, window.hours)
        is GraphWindow.Fixed -> stringResource(
            R.string.chart_period_range,
            formatDateTime(window.fromMs, appearance.timeFormat),
            formatDateTime(window.toMs, appearance.timeFormat)
        )
    }
    if (readings.isEmpty()) return stringResource(R.string.chart_a11y_no_data, period)
    fun v(r: GraphReading) = with(appearance.unit) { r.sgvMgDl.toDouble().toDisplay() } + " " + appearance.unit.label
    val last = readings.maxBy { it.timestampMs }
    return stringResource(R.string.chart_a11y, period, v(last), v(readings.minBy { it.sgvMgDl }), v(readings.maxBy { it.sgvMgDl }))
}

/**
 * Painel do gráfico: título e chips de período na MESMA linha, que quebra (os chips descem só
 * quando não cabem — antes estouravam com fonte grande), controles extras (data e setas do
 * Histórico), linha de estado e o gráfico como ÚLTIMO filho — texto de estado e legenda vão acima
 * da caixa. [chart] roda numa coluna: use Modifier.weight(1f) para o gráfico ocupar o que sobra.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GlucoseChartPanel(
    title: String?,
    modifier: Modifier = Modifier,
    periods: List<Int> = emptyList(),
    selectedPeriod: Int? = null,
    onSelectPeriod: (Int) -> Unit = {},
    periodLabel: @Composable (Int) -> String = { "${it}h" },
    extraControls: (@Composable () -> Unit)? = null,
    status: (@Composable () -> Unit)? = null,
    chart: @Composable ColumnScope.() -> Unit
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.xxs)
        ) {
            if (title != null) {
                Text(title, style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.align(Alignment.CenterVertically).semantics { heading() })
            }
            if (periods.isNotEmpty() && selectedPeriod != null) {
                ChoiceChips(periods, selectedPeriod, onSelectPeriod, periodLabel,
                    modifier = Modifier.align(Alignment.CenterVertically), fillWidth = false)
            }
        }
        extraControls?.invoke()
        status?.invoke()
        chart()
    }
}

/** Moldura da família do gráfico (fundo do tema do gráfico e cantos de 12dp) — usada pelo TIR. */
@Composable
fun GlucoseChartFrame(dark: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(chartPanelColor(dark))
            .padding(Spacing.sm)
    ) { content() }
}
