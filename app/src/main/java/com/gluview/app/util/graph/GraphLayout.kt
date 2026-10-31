package com.gluview.app.util.graph

import com.gluview.app.util.GlucoseUnit
import com.gluview.app.util.GraphReading
import com.gluview.app.util.TimeFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Uma operação de desenho. A lista do [GraphLayout] é executada em ordem pelo único pintor. */
sealed interface DrawOp {
    data class Fill(val color: Int) : DrawOp
    data class Rect(val left: Float, val top: Float, val right: Float, val bottom: Float, val color: Int) : DrawOp
    /** [stroke]/[antiAlias] reproduzem exatamente as Paints de antes (grade e ticks sem AA). */
    data class Line(
        val x0: Float, val y0: Float, val x1: Float, val y1: Float,
        val color: Int, val width: Float, val antiAlias: Boolean, val stroke: Boolean,
        val dash: FloatArray? = null
    ) : DrawOp
    data class Text(
        val text: String, val x: Float, val y: Float, val color: Int, val sizePx: Float,
        val align: Align, val bold: Boolean = false
    ) : DrawOp
    /** Linha de glicemia: cada sublista é um trecho contínuo (x0,y0,x1,y1,...). */
    data class Polyline(val segments: List<FloatArray>, val color: Int, val width: Float) : DrawOp
    data class Dot(val x: Float, val y: Float, val radius: Float, val color: Int) : DrawOp

    enum class Align { LEFT, CENTER, RIGHT }
}

/**
 * Layout puro (Kotlin/JVM, sem android.graphics) de um gráfico: escala, padding, ticks, rótulos já
 * formatados, pontos visíveis e a lista ordenada de [DrawOp]. É o mesmo objeto para desenhar, tocar
 * e posicionar o balão (redesign visual, Fase 2).
 */
class GraphLayout(
    val spec: GraphSpec,
    val geometry: GraphGeometry,
    val showAxes: Boolean,
    val labelSizePx: Float,
    /** Leituras dentro da janela, em ordem de tempo. */
    val visible: List<GraphReading>,
    val ops: List<DrawOp>
) {
    companion object {

        /** Quanto uma leitura pode estar à frente de "agora" e ainda entrar (âncora Sibionics). */
        const val FUTURE_TOLERANCE_MS = 6 * 60_000L

        /** Borda direita: agora, ou a leitura mais nova se estiver (um pouco) à frente. */
        fun rightEdgeFor(readings: List<GraphReading>, nowMs: Long): Long {
            val dataMax = readings.maxOfOrNull { it.timestampMs } ?: nowMs
            return maxOf(nowMs, minOf(dataMax, nowMs + FUTURE_TOLERANCE_MS))
        }

        /** Largura aproximada de um texto (sem Paint): o pintor real passa a medida exata. */
        val ESTIMATED_TEXT_WIDTH: (String, Float) -> Float = { text, sizePx -> text.length * sizePx * 0.55f }

        fun compute(
            readings: List<GraphReading>,
            spec: GraphSpec,
            measureText: (String, Float) -> Float = ESTIMATED_TEXT_WIDTH
        ): GraphLayout = when (spec.variant) {
            GraphVariant.APP -> app(readings, spec, measureText)
            GraphVariant.WATCH -> watch(readings, spec)
        }

        private fun window(readings: List<GraphReading>, spec: GraphSpec): Pair<Long, Long> = when (val w = spec.window) {
            is GraphWindow.Trailing -> {
                val right = rightEdgeFor(readings, spec.nowMs)
                (right - w.hours * 3_600_000L) to right
            }
            is GraphWindow.Fixed -> w.fromMs to w.toMs
        }

        // ------------------------------------------------------------------ APP (modelo da Home)

        private fun app(readings: List<GraphReading>, spec: GraphSpec, measureText: (String, Float) -> Float): GraphLayout {
            val dark = spec.dark
            val hc = spec.highContrastLight && !dark
            val gridInk = if (dark) GraphPalette.GRID_DARK else GraphPalette.GRID_LIGHT
            val labelInk = if (dark) GraphPalette.LABEL_DARK else GraphPalette.LABEL_LIGHT
            val tickInk = if (dark) GraphPalette.TICK_DARK else GraphPalette.TICK_LIGHT
            val lineInk = if (dark) GraphPalette.LINE_DARK else GraphPalette.LINE_LIGHT
            val secondaryInk = if (dark) GraphPalette.SECONDARY_DARK else GraphPalette.SECONDARY_LIGHT
            val hipoInk = if (hc) GraphPalette.HIPO_LINE_LIGHT_HC else GraphPalette.HIPO_LINE
            val hiperInk = if (hc) GraphPalette.HIPER_LINE_LIGHT_HC else GraphPalette.HIPER_LINE
            val bandInk = if (hc) GraphPalette.URGENT_BAND_LIGHT_HC else GraphPalette.URGENT_BAND

            // Mesmas expressões do renderer de antes (o oráculo compara bit a bit): só o bitmap é
            // limitado a >= 1px; as contas usam o tamanho pedido.
            val w = spec.widthPx.toFloat()
            val h = spec.heightPx.toFloat()
            val showAxes = spec.forceAxes ?: (spec.heightPx >= 200)
            val labelSz = if (showAxes) (spec.labelSizePx ?: (spec.heightPx * 0.080f).coerceIn(20f, 32f)) else 0f
            val padL = if (showAxes) labelSz * 3.2f else 4f
            val padR = 8f
            val padT = 10f
            val padB = if (showAxes) labelSz * 2.2f else 6f
            val chartW = w - padL - padR
            val chartH = h - padT - padB
            val (fromMs, rightMs) = window(readings, spec)
            val geo = GraphGeometry(fromMs, rightMs, padL, padT, chartW, chartH)
            val hipo = spec.hipo
            val hiper = spec.hiper
            val ops = ArrayList<DrawOp>()

            if (!spec.transparentBackground) {
                ops += DrawOp.Fill(if (dark) GraphPalette.BG_DARK else GraphPalette.BG_LIGHT)
            }
            // Faixa sutil só na zona de urgência (abaixo de 55) — igual ao xDrip.
            ops += DrawOp.Rect(padL, geo.yOf(spec.urgentLow), padL + chartW, padT + chartH, bandInk)
            // Limiares do usuário (vermelho = hipo, laranja = hiper) e faixa-alvo fixa 100/150.
            fun refLine(y: Float, color: Int) = DrawOp.Line(padL, y, padL + chartW, y, color, 1.2f, antiAlias = true, stroke = true)
            ops += refLine(geo.yOf(hipo), hipoInk)
            ops += refLine(geo.yOf(hiper), hiperInk)
            ops += refLine(geo.yOf(100f), secondaryInk)
            ops += refLine(geo.yOf(150f), secondaryInk)

            if (showAxes) {
                // Eixo Y — rótulos em hipo, 100, 150, hiper e 250; grade sutil só no 250.
                val urgentHighInt = spec.urgentHigh.toInt()
                class YLabel(val sgv: Int, val color: Int, val priority: Int)
                var yLabels = listOf(
                    YLabel(hipo.toInt(), hipoInk, 3),
                    YLabel(100, secondaryInk, 1),
                    YLabel(150, secondaryInk, 1),
                    YLabel(hiper.toInt(), hiperInk, 3),
                    YLabel(urgentHighInt, labelInk, 2)
                )
                if (spec.avoidYLabelCollision) {
                    val minGap = labelSz * 1.1f
                    val kept = ArrayList<YLabel>()
                    for (l in yLabels.sortedByDescending { it.priority }) {
                        val y = geo.yOf(l.sgv.toFloat())
                        if (kept.none { kotlin.math.abs(geo.yOf(it.sgv.toFloat()) - y) < minGap }) kept += l
                    }
                    yLabels = yLabels.filter { it in kept }
                }
                for (l in yLabels) {
                    val y = geo.yOf(l.sgv.toFloat())
                    if (l.sgv == urgentHighInt) {
                        ops += DrawOp.Line(padL, y, padL + chartW, y, gridInk, 0.8f, antiAlias = false, stroke = false)
                    }
                    if (spec.drawText) {
                        ops += DrawOp.Text(yLabelText(l.sgv, spec.unit), padL - 6f, y + labelSz * 0.35f,
                            l.color, labelSz, DrawOp.Align.RIGHT)
                    }
                }

                // Eixo X — horários.
                val spanH = ((rightMs - fromMs) / 3_600_000L).toInt()
                val xIntervalH = spec.xTickStepHours ?: when {
                    spanH <= 6 -> 1
                    spanH <= 12 -> 2
                    else -> 4
                }
                val tz = spec.zone?.let { TimeZone.getTimeZone(it) } ?: TimeZone.getDefault()
                val sdf = when (spec.timeFormat) {
                    TimeFormat.H12 -> SimpleDateFormat("h a", Locale.US)
                    else -> SimpleDateFormat("HH:mm", Locale.getDefault())
                }.apply { timeZone = tz }
                // Primeiro tick: próxima hora "limpa" depois de fromMs.
                val cal = Calendar.getInstance(tz)
                cal.timeInMillis = fromMs
                cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
                val hod = cal.get(Calendar.HOUR_OF_DAY)
                val nextH = ((hod / xIntervalH) + 1) * xIntervalH
                if (nextH >= 24) {
                    cal.set(Calendar.HOUR_OF_DAY, nextH - 24)
                    cal.add(Calendar.DAY_OF_MONTH, 1)
                } else {
                    cal.set(Calendar.HOUR_OF_DAY, nextH)
                }
                val xBaseline = h - labelSz * 0.4f
                var tickMs = cal.timeInMillis
                while (tickMs <= rightMs) {
                    val x = geo.xOf(tickMs)
                    ops += DrawOp.Line(x, padT, x, padT + chartH, tickInk, 0.8f, antiAlias = false, stroke = false)
                    if (spec.drawText) {
                        val label = sdf.format(Date(tickMs))
                        val lx = if (spec.clampXLabels) {
                            val half = measureText(label, labelSz) / 2f
                            x.coerceIn(half + 1f, (w - half - 1f).coerceAtLeast(half + 1f))
                        } else x
                        ops += DrawOp.Text(label, lx, xBaseline, labelInk, labelSz, DrawOp.Align.CENTER)
                    }
                    tickMs += xIntervalH * 3_600_000L
                }
            }

            val visible = readings.filter { it.timestampMs in fromMs..rightMs }.sortedBy { it.timestampMs }
            if (visible.isEmpty()) return GraphLayout(spec, geo, showAxes, labelSz, visible, ops)

            // Linha de glicemia (cinza sutil, como o xDrip); quebra em buraco só se pedido.
            ops += DrawOp.Polyline(segments(visible, geo, spec.gapBreakMs), lineInk, if (showAxes) 2f else 1.5f)

            // Pontos por zona; provisórios (confirmed == false) menores e translúcidos.
            val dotR = if (showAxes) 8f else 5.5f
            for (r in visible) {
                val provisional = r.confirmed == false
                val color = GraphPalette.zoneColor(r.sgvMgDl, hipo, hiper, hc, spec.urgentLow, spec.urgentHigh)
                val alpha = if (provisional) 130 else 255
                ops += DrawOp.Dot(geo.xOf(r.timestampMs), geo.yOf(r.sgvMgDl),
                    if (provisional) dotR * 0.55f else dotR, (color and 0x00FFFFFF) or (alpha shl 24))
            }

            if (spec.outOfRangeMarkers && spec.drawText) {
                val markSz = if (showAxes) labelSz * 0.7f else 18f
                for (r in visible) {
                    val low = r.sgvMgDl < GraphScale.MIN_SGV
                    val high = r.sgvMgDl > GraphScale.MAX_SGV
                    if (!low && !high) continue
                    val x = geo.xOf(r.timestampMs)
                    val y = geo.yOf(r.sgvMgDl)
                    val color = GraphPalette.zoneColor(r.sgvMgDl, hipo, hiper, hc, spec.urgentLow, spec.urgentHigh)
                    // "LO" acima do ponto colado no fundo; "HI" abaixo do ponto colado no topo.
                    val ty = if (low) y - dotR - 4f else y + dotR + markSz
                    ops += DrawOp.Text(if (low) "LO" else "HI", x, ty, color, markSz, DrawOp.Align.CENTER, bold = true)
                }
            }
            return GraphLayout(spec, geo, showAxes, labelSz, visible, ops)
        }

        private fun yLabelText(sgv: Int, unit: GlucoseUnit): String =
            if (unit == GlucoseUnit.MG_DL) sgv.toString() else with(unit) { sgv.toDouble().toDisplay() }

        private fun segments(visible: List<GraphReading>, geo: GraphGeometry, gapBreakMs: Long?): List<FloatArray> {
            val out = ArrayList<FloatArray>()
            var cur = ArrayList<Float>()
            var prevTs: Long? = null
            for (r in visible) {
                if (gapBreakMs != null && prevTs != null && r.timestampMs - prevTs > gapBreakMs && cur.isNotEmpty()) {
                    out += cur.toFloatArray(); cur = ArrayList()
                }
                cur += geo.xOf(r.timestampMs); cur += geo.yOf(r.sgvMgDl)
                prevTs = r.timestampMs
            }
            if (cur.isNotEmpty()) out += cur.toFloatArray()
            return out
        }

        // ------------------------------------------------------------------ WATCH (Mi Band)

        private val WATCH_DASH = floatArrayOf(6f, 5f)

        private fun watch(readings: List<GraphReading>, spec: GraphSpec): GraphLayout {
            val w = spec.widthPx.toFloat()
            val h = spec.heightPx.toFloat()
            val labelSz = 15f
            val padL = 2f; val padR = 2f; val padT = 4f; val padB = 4f
            val chartW = w - padL - padR
            val chartH = h - padT - padB
            val (fromMs, rightMs) = window(readings, spec)
            val geo = GraphGeometry(fromMs, rightMs, padL, padT, chartW, chartH)
            val ops = ArrayList<DrawOp>()

            ops += DrawOp.Fill(GraphPalette.WATCH_BG)
            ops += DrawOp.Rect(padL, geo.yOf(spec.urgentLow), padL + chartW, padT + chartH, GraphPalette.WATCH_URGENT_BAND)
            // Limiares tracejados com o valor rotulado à esquerda.
            for ((sgv, color) in listOf(spec.hipo to GraphPalette.WATCH_HIPO, spec.hiper to GraphPalette.WATCH_HIPER)) {
                val y = geo.yOf(sgv)
                ops += DrawOp.Line(padL, y, padL + chartW, y, color, 1.5f, antiAlias = true, stroke = true, dash = WATCH_DASH)
                if (spec.drawText) {
                    // Rótulo na unidade do usuário (Fase 5; antes sempre mg/dL).
                    ops += DrawOp.Text(yLabelText(sgv.toInt(), spec.unit), padL + 2f, y - 3f, color, labelSz, DrawOp.Align.LEFT, bold = true)
                }
            }
            // Faixa-alvo 100/150: linha neutra sem rótulo.
            for (sgv in listOf(100f, 150f)) {
                val y = geo.yOf(sgv)
                ops += DrawOp.Line(padL, y, padL + chartW, y, GraphPalette.WATCH_SECONDARY, 1f, antiAlias = true, stroke = true, dash = WATCH_DASH)
            }

            val visible = readings.filter { it.timestampMs in fromMs..rightMs }.sortedBy { it.timestampMs }
            if (visible.isEmpty()) return GraphLayout(spec, geo, false, labelSz, visible, ops)

            ops += DrawOp.Polyline(segments(visible, geo, spec.gapBreakMs), GraphPalette.WATCH_LINE, 1.2f)
            visible.forEachIndexed { index, r ->
                ops += DrawOp.Dot(geo.xOf(r.timestampMs), geo.yOf(r.sgvMgDl),
                    if (index == visible.lastIndex) 3.5f else 2.2f,
                    GraphPalette.zoneColor(r.sgvMgDl, spec.hipo, spec.hiper, urgentLow = spec.urgentLow, urgentHigh = spec.urgentHigh))
            }
            return GraphLayout(spec, geo, false, labelSz, visible, ops)
        }
    }
}
