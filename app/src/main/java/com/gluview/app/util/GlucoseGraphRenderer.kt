package com.gluview.app.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import com.gluview.app.util.graph.DrawOp
import com.gluview.app.util.graph.GraphGeometry
import com.gluview.app.util.graph.GraphLayout
import com.gluview.app.util.graph.GraphPalette
import com.gluview.app.util.graph.GraphScale
import com.gluview.app.util.graph.GraphSpec
import com.gluview.app.util.graph.GraphVariant
import com.gluview.app.util.graph.GraphWindow

data class GraphReading(
    val timestampMs: Long,
    val sgvMgDl: Float,
    // Modos de leitura Sibionics (Ajustes > Sensor): true = a .so mudou de valor nesta leitura
    // ("confirmada"); false = repetiu o valor anterior ("provisória", desenhada menor/translúcida
    // no modo "Todas + distinção no gráfico"); null = fonte sem o conceito ou modo não distingue —
    // desenhada igual a `true` (aparência de sempre). Ver GraphReadingMapper.applyDisplayMode.
    val confirmed: Boolean? = null
)

/**
 * O pintor do gráfico de glicemia (redesign visual, Fase 2). A geometria, os rótulos e a ordem de
 * desenho saem do [GraphLayout] (Kotlin puro, testável na JVM); aqui só se executa a lista de
 * [DrawOp] num Canvas — o mesmo [draw] serve ao bitmap (notificação, widgets, Auto, Dream, Mi
 * Band) e, a partir da Fase 3, ao Canvas do Compose no app. O RendererOracleTest prova que os
 * bitmaps são idênticos aos do renderer de antes da refatoração.
 */
object GlucoseGraphRenderer {
    private const val LOW  = GraphScale.DEFAULT_HIPO
    private const val HIGH = GraphScale.DEFAULT_HIPER

    /** Ver [GraphLayout.FUTURE_TOLERANCE_MS]. */
    const val FUTURE_TOLERANCE_MS = GraphLayout.FUTURE_TOLERANCE_MS

    /** Geometria da variante do app para um tamanho e janela (toque/balão da Home). */
    fun geometry(
        widthPx: Int,
        heightPx: Int,
        hoursBack: Int,
        forceAxes: Boolean? = null,
        // Borda direita do eixo X. Default = agora; o [render] passa
        // max(agora, leitura mais nova) para o último ponto nunca cair fora da área.
        rightEdgeMs: Long = System.currentTimeMillis()
    ): GraphGeometry = GraphLayout.compute(
        emptyList(),
        GraphSpec(widthPx, heightPx, GraphWindow.Trailing(hoursBack), nowMs = rightEdgeMs, forceAxes = forceAxes)
    ).geometry

    /** Borda direita do gráfico — ver [GraphLayout.rightEdgeFor]. [nowMs] só muda em teste/captura. */
    fun rightEdgeFor(readings: List<GraphReading>, nowMs: Long = System.currentTimeMillis()): Long =
        GraphLayout.rightEdgeFor(readings, nowMs)

    /** Leitura mais próxima (no tempo) de um toque em [tapXPx], ou null se não houver leituras
     *  visíveis na janela ou o toque cair longe demais de qualquer ponto (fora de [maxDistancePx]). */
    fun nearestReading(
        readings: List<GraphReading>,
        tapXPx: Float,
        geo: GraphGeometry,
        maxDistancePx: Float = 80f
    ): GraphReading? {
        val filtered = readings.filter { it.timestampMs in geo.fromMs..geo.nowMs }
        if (filtered.isEmpty()) return null
        val nearest = filtered.minByOrNull { kotlin.math.abs(geo.xOf(it.timestampMs) - tapXPx) } ?: return null
        return nearest.takeIf { kotlin.math.abs(geo.xOf(it.timestampMs) - tapXPx) <= maxDistancePx }
    }

    /** O único pintor: executa as operações do layout, em ordem, no [canvas]. */
    fun draw(canvas: Canvas, layout: GraphLayout) {
        for (op in layout.ops) when (op) {
            is DrawOp.Fill -> canvas.drawColor(op.color)
            is DrawOp.Rect -> canvas.drawRect(op.left, op.top, op.right, op.bottom, Paint().apply { color = op.color })
            is DrawOp.Line -> canvas.drawLine(op.x0, op.y0, op.x1, op.y1,
                // Paint() e não Paint(0): o construtor sem argumento tem flags padrão próprias
                // (no Android 15 a grade/tick sai diferente) — é o que o desenho de antes usava.
                (if (op.antiAlias) Paint(Paint.ANTI_ALIAS_FLAG) else Paint()).apply {
                    if (op.stroke) style = Paint.Style.STROKE
                    strokeWidth = op.width
                    op.dash?.let { pathEffect = DashPathEffect(it, 0f) }
                    color = op.color
                })
            is DrawOp.Text -> canvas.drawText(op.text, op.x, op.y, textPaint(op))
            is DrawOp.Polyline -> {
                val path = Path()
                for (seg in op.segments) {
                    path.moveTo(seg[0], seg[1])
                    var i = 2
                    while (i < seg.size) { path.lineTo(seg[i], seg[i + 1]); i += 2 }
                }
                canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = op.width
                    color = op.color
                    strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
                })
            }
            is DrawOp.Dot -> canvas.drawCircle(op.x, op.y, op.radius,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = op.color })
        }
    }

    private fun textPaint(op: DrawOp.Text) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = op.color
        textSize = op.sizePx
        if (op.bold) typeface = Typeface.DEFAULT_BOLD
        textAlign = when (op.align) {
            DrawOp.Align.LEFT -> Paint.Align.LEFT
            DrawOp.Align.CENTER -> Paint.Align.CENTER
            DrawOp.Align.RIGHT -> Paint.Align.RIGHT
        }
    }

    /** Medida exata de texto para o layout (ex.: rótulo do eixo X sem cortar na borda). */
    val measureText: (String, Float) -> Float = { text, size ->
        Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size }.measureText(text)
    }

    /** Bitmap do gráfico para uma [spec] (superfícies fora do app). */
    fun render(readings: List<GraphReading>, spec: GraphSpec): Bitmap {
        val bmp = Bitmap.createBitmap(spec.widthPx.coerceAtLeast(1), spec.heightPx.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        draw(Canvas(bmp), GraphLayout.compute(readings, spec, measureText))
        return bmp
    }

    /**
     * O modelo da Home em bitmap (assinatura de antes da Fase 2; delega para [render] com spec).
     * @param forceAxes null = automático (>= 200px); true/false = forçado (widgets passam false).
     * @param dark true = fundo #1C1C1E (Home, notificação, widget); false = claro (seguidor).
     * @param hipo/hiper limiares de Ajustes > Alertas; default 70/180.
     */
    fun render(
        readings: List<GraphReading>,
        widthPx: Int,
        heightPx: Int,
        hoursBack: Int,
        forceAxes: Boolean? = null,
        dark: Boolean = true,
        hipo: Float = LOW,
        hiper: Float = HIGH,
        nowMs: Long = System.currentTimeMillis()
    ): Bitmap = render(readings, GraphSpec(
        widthPx, heightPx, GraphWindow.Trailing(hoursBack), nowMs = nowMs,
        dark = dark, hipo = hipo, hiper = hiper, forceAxes = forceAxes
    ))

    /**
     * Gráfico do visor da Mi Band (variante [GraphVariant.WATCH]): fundo preto, limiares tracejados
     * e rotulados, faixa abaixo de 55, linha fina e pontos por zona (o último maior).
     */
    fun renderWatchface(
        readings: List<GraphReading>,
        widthPx: Int,
        heightPx: Int,
        hoursBack: Int,
        hipo: Float = LOW,
        hiper: Float = HIGH,
        nowMs: Long = System.currentTimeMillis(),
        /** Unidade dos rótulos de hipo/hiper (Fase 5); mg/dL = o desenho de antes. */
        unit: GlucoseUnit = GlucoseUnit.MG_DL,
        urgentLow: Float = GraphScale.URGENT_LOW,
        urgentHigh: Float = GraphScale.URGENT_HIGH
    ): Bitmap = render(readings, GraphSpec(
        widthPx, heightPx, GraphWindow.Trailing(hoursBack), nowMs = nowMs,
        variant = GraphVariant.WATCH, hipo = hipo, hiper = hiper, unit = unit,
        urgentLow = urgentLow, urgentHigh = urgentHigh
    ))

    /**
     * Renderiza apenas pontos coloridos por zona sobre fundo transparente.
     * Usado no mini-gráfico do widget estilo xDrip.
     */
    fun renderDots(
        readings: List<GraphReading>,
        widthPx: Int,
        heightPx: Int,
        hoursBack: Int,
        hipo: Float = LOW,
        hiper: Float = HIGH,
        nowMs: Long = System.currentTimeMillis(),
        urgentLow: Float = GraphScale.URGENT_LOW,
        urgentHigh: Float = GraphScale.URGENT_HIGH
    ): Bitmap {
        val bmp = Bitmap.createBitmap(widthPx.coerceAtLeast(1), heightPx.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        // Sem fundo — bitmap totalmente transparente

        val w = widthPx.toFloat(); val h = heightPx.toFloat()
        val padL = 4f; val padR = 4f; val padT = 4f; val padB = 4f
        val chartW = w - padL - padR; val chartH = h - padT - padB

        val rightMs = rightEdgeFor(readings, nowMs)
        val fromMs  = rightMs - hoursBack * 3_600_000L

        val yOf = { sgv: Float ->
            val ratio = (sgv.coerceIn(GraphScale.MIN_SGV, GraphScale.MAX_SGV) - GraphScale.MIN_SGV) / (GraphScale.MAX_SGV - GraphScale.MIN_SGV)
            padT + chartH * (1f - ratio)
        }
        val xOf = { ts: Long ->
            val ratio = ((ts - fromMs).toFloat() / (rightMs - fromMs).coerceAtLeast(1L)).coerceIn(0f, 1f)
            padL + ratio * chartW
        }

        val filtered = readings.filter { it.timestampMs in fromMs..rightMs }.sortedBy { it.timestampMs }
        if (filtered.isEmpty()) return bmp

        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        filtered.forEachIndexed { index, r ->
            dotPaint.color = zoneColor(r.sgvMgDl, hipo, hiper, urgentLow, urgentHigh)
            // Último ponto ligeiramente maior para destacar leitura atual
            val baseRadius = if (index == filtered.lastIndex) 9f else 6f
            // Provisórios (confirmed=false, modo "Todas + distinção no gráfico") saem menores/
            // translúcidos; confirmados ou sem o conceito (null) ficam como sempre foram.
            val provisional = r.confirmed == false
            dotPaint.alpha = if (provisional) 130 else 255
            canvas.drawCircle(xOf(r.timestampMs), yOf(r.sgvMgDl), if (provisional) baseRadius * 0.55f else baseRadius, dotPaint)
        }
        return bmp
    }

    /** Cor por zona (vermelho/laranja/verde) — pública para o balão de toque reusar o mesmo
     *  código de cor do ponto tocado (Home e Histórico). */
    fun zoneColor(sgv: Float, hipo: Float, hiper: Float, urgentLow: Float = GraphScale.URGENT_LOW,
                  urgentHigh: Float = GraphScale.URGENT_HIGH): Int =
        GraphPalette.zoneColor(sgv, hipo, hiper, urgentLow = urgentLow, urgentHigh = urgentHigh)
}
