package com.gluview.app.util.graph

import android.content.res.Configuration
import android.content.res.Resources
import com.gluview.app.util.GlucoseUnit
import com.gluview.app.util.TimeFormat
import kotlin.math.min
import kotlin.math.roundToInt

/** Quebra a linha entre leituras mais distantes que isto (decisão 5.2 #5). */
const val DEFAULT_GAP_BREAK_MS = 15 * 60_000L

/** Teto da escala de fonte nos rótulos dos gráficos (decisão 5.2 #3). */
const val SURFACE_LABEL_MAX_FONT_SCALE = 1.5f

/**
 * Spec das superfícies em bitmap (redesign visual, Fase 5): notificação, widgets, Dream e Auto
 * desenham o MESMO modelo do app, com as extensões da 5.2 ligadas — unidade e 12/24h nos eixos,
 * rótulos sem corte nem colisão, linha quebrada em buraco, LO/HI e o claro com contraste. Fundo
 * transparente por padrão: quem hospeda (cartão da notificação, widget, Dream) pinta o fundo.
 */
fun surfaceSpec(
    widthPx: Int,
    heightPx: Int,
    hours: Int,
    nowMs: Long,
    dark: Boolean,
    hipo: Float,
    hiper: Float,
    unit: GlucoseUnit,
    timeFormat: TimeFormat,
    labelSizePx: Float?,
    forceAxes: Boolean? = null,
    transparentBackground: Boolean = true,
    /** Limites urgentes de Ajustes > Alertas (L2); default 55/250. */
    urgentLow: Float = GraphScale.URGENT_LOW,
    urgentHigh: Float = GraphScale.URGENT_HIGH
): GraphSpec = GraphSpec(
    widthPx = widthPx,
    heightPx = heightPx,
    window = GraphWindow.Trailing(hours),
    nowMs = nowMs,
    dark = dark,
    hipo = hipo,
    hiper = hiper,
    urgentLow = urgentLow,
    urgentHigh = urgentHigh,
    forceAxes = forceAxes,
    unit = unit,
    timeFormat = timeFormat,
    clampXLabels = true,
    avoidYLabelCollision = true,
    labelSizePx = labelSizePx,
    transparentBackground = transparentBackground,
    gapBreakMs = DEFAULT_GAP_BREAK_MS,
    outOfRangeMarkers = true,
    highContrastLight = true
)

/** Rótulo do gráfico em px: [sp] com a escala de fonte limitada a [maxScale]. */
fun surfaceLabelPx(res: Resources, sp: Float = 12f, maxScale: Float = SURFACE_LABEL_MAX_FONT_SCALE): Float {
    val dm = res.displayMetrics
    return sp * dm.density * min(res.configuration.fontScale, maxScale)
}

/** Tema do SISTEMA (decisão 5.4 #1: widget e notificação seguem o tema de onde aparecem). */
fun systemIsDark(res: Resources): Boolean =
    (res.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

/** Tamanho real (px) do gráfico da notificação expandida e da miniatura da recolhida. */
data class NotificationChartSizes(val expandedW: Int, val expandedH: Int, val collapsedW: Int, val collapsedH: Int)

/**
 * Estimativa do espaço do gráfico dentro do cartão da notificação (antes: bitmap fixo de 800×289
 * reescalado, com rótulos de ~9,7dp). Largura do cartão = tela (até 600dp) menos margens e o
 * recuo do conteúdo (~32dp de cada lado); altura na proporção 900:400 da Home, com teto para a
 * visão expandida caber no limite do sistema. A recolhida usa metade da largura (o texto fica com
 * o resto) e ~40dp de altura.
 */
fun notificationChartSizes(widthPx: Int, density: Float): NotificationChartSizes {
    val cardW = min(widthPx.toFloat(), 600f * density) - 64f * density
    val expandedW = cardW.roundToInt().coerceAtLeast(1)
    val expandedH = min(cardW * 4f / 9f, 150f * density).roundToInt().coerceAtLeast(1)
    val collapsedW = (cardW / 2f).roundToInt().coerceAtLeast(1)
    val collapsedH = (40f * density).roundToInt()
    return NotificationChartSizes(expandedW, expandedH, collapsedW, collapsedH)
}
