package com.gluview.app.visualaudit.oracle

import android.graphics.*
import com.gluview.app.util.GraphReading
import java.text.SimpleDateFormat
import java.util.*

// =============================================================================================
// ORÁCULO CONGELADO — NÃO EDITAR.
// Cópia literal de app/src/main/java/com/gluview/app/util/GlucoseGraphRenderer.kt no commit
// 6446f3d (= b3ac4c4 + só o relógio injetável nowMs; o diff daquele commit é mecânico).
// Serve de referência pixel a pixel para a Fase 2 do redesign visual: o motor novo
// (GraphLayout + draw) tem que dar Bitmap.sameAs == true contra ISTO em todas as chamadas reais
// (RendererOracleTest). Só muda por decisão explícita do mantenedor, num commit próprio.
// =============================================================================================
@Suppress("unused", "DEPRECATION")
object LegacyGlucoseGraphRenderer {
    private const val MIN_SGV     = 40f
    private const val MAX_SGV     = 300f
    private const val URGENT_LOW  = 55f
    private const val LOW         = 70f
    private const val HIGH        = 180f
    private const val URGENT_HIGH = 250f

    /** Geometria de um render — reaproveitada para converter toque (px) <-> dado (timestamp/sgv),
     *  sem duplicar os cálculos de padding/escala do [render]. */
    class GraphGeometry(
        val fromMs: Long,
        val nowMs: Long,
        private val padL: Float,
        private val padT: Float,
        private val chartW: Float,
        private val chartH: Float
    ) {
        fun yOf(sgv: Float): Float {
            val ratio = (sgv.coerceIn(MIN_SGV, MAX_SGV) - MIN_SGV) / (MAX_SGV - MIN_SGV)
            return padT + chartH * (1f - ratio)
        }
        fun xOf(ts: Long): Float {
            val ratio = ((ts - fromMs).toFloat() / (nowMs - fromMs).coerceAtLeast(1L)).coerceIn(0f, 1f)
            return padL + ratio * chartW
        }
        fun timestampAt(xPx: Float): Long {
            val ratio = ((xPx - padL) / chartW).coerceIn(0f, 1f)
            return fromMs + (ratio * (nowMs - fromMs)).toLong()
        }
    }

    /** Quanto uma leitura pode estar datada à frente de "agora" e ainda entrar no gráfico:
     *  a âncora do sensor Sibionics pode adiantar alguns segundos (ver
     *  SibionicsBleSource.persist). Sem esta folga, o ponto mais novo — o que mais importa —
     *  desaparecia do gráfico embora o card o mostrasse. */
    const val FUTURE_TOLERANCE_MS = 6 * 60_000L

    fun geometry(
        widthPx: Int,
        heightPx: Int,
        hoursBack: Int,
        forceAxes: Boolean? = null,
        // Borda direita do eixo X. Default = agora; o [render] passa
        // max(agora, leitura mais nova) para o último ponto nunca cair fora da área.
        rightEdgeMs: Long = System.currentTimeMillis()
    ): GraphGeometry {
        val showAxes = forceAxes ?: (heightPx >= 200)
        val labelSz  = if (showAxes) (heightPx * 0.080f).coerceIn(20f, 32f) else 0f
        val padL     = if (showAxes) labelSz * 3.2f else 4f
        val padR     = 8f
        val padT     = 10f
        val padB     = if (showAxes) labelSz * 2.2f else 6f
        val nowMs    = rightEdgeMs
        val fromMs   = nowMs - hoursBack * 3_600_000L
        return GraphGeometry(fromMs, nowMs, padL, padT, widthPx - padL - padR, heightPx - padT - padB)
    }

    /** Borda direita do gráfico: agora, ou a leitura mais nova se ela estiver (um pouco) à
     *  frente — limitada por [FUTURE_TOLERANCE_MS] para uma leitura absurda não esticar o eixo.
     *  [nowMs] só é passado por teste/captura (relógio fixo); em produção vale o relógio real. */
    fun rightEdgeFor(readings: List<GraphReading>, nowMs: Long = System.currentTimeMillis()): Long {
        val now = nowMs
        val dataMax = readings.maxOfOrNull { it.timestampMs } ?: now
        return maxOf(now, minOf(dataMax, now + FUTURE_TOLERANCE_MS))
    }

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

    /**
     * @param showAxes null = automático (≥200px); true/false = forçado.
     *   Widgets passam false: sem labels mas mantêm linhas de referência.
     *   Home e notificação usam null (automático).
     * @param dark true (padrão) = fundo escuro #1C1C1E, tinta clara — usado por Home do
     *   paciente, notificação e widget, que já compõem sobre superfícies escuras. false =
     *   fundo/tinta claros, para o gráfico do seguidor conviver com o tema claro do app
     *   (não fica "sujo" quando destoando do resto da tela).
     * @param hipo/hiper limiares configurados pelo usuário em Ajustes > Alertas ("Baixo"/"Alto").
     *   Default 70/180 preserva o comportamento antigo pra quem não repassa o valor real.
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
    ): Bitmap {
        val bmp = Bitmap.createBitmap(widthPx.coerceAtLeast(1), heightPx.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.parseColor(if (dark) "#1C1C1E" else "#FAFAFA"))

        // Tinta base (grid/labels/linha) — clara sobre fundo escuro, escura sobre fundo claro.
        // Cores por zona (vermelho/laranja/verde) e a faixa de urgência ficam iguais nos dois
        // temas: já têm contraste suficiente tanto no branco quanto no #1C1C1E.
        val gridInk      = if (dark) "#25FFFFFF" else "#1F000000"
        val labelInk     = if (dark) "#AAFFFFFF" else "#99000000"
        val tickInk      = if (dark) "#30FFFFFF" else "#26000000"
        val lineInk      = if (dark) "#70FFFFFF" else "#80616161"
        // Faixa-alvo fixa (100/150) — cinza claro/neutro, mesma linguagem visual do gráfico Vico
        // da aba Histórico, distinta das cores vermelho/laranja de hipo/hiper.
        val secondaryInk = if (dark) "#80FFFFFF" else "#80000000"

        val w = widthPx.toFloat()
        val h = heightPx.toFloat()

        val showAxes = forceAxes ?: (heightPx >= 200)

        val labelSz = if (showAxes) (heightPx * 0.080f).coerceIn(20f, 32f) else 0f
        val padL    = if (showAxes) labelSz * 3.2f else 4f
        val padR    = 8f
        val padT    = 10f
        val padB    = if (showAxes) labelSz * 2.2f else 6f
        val chartW  = w - padL - padR
        val chartH  = h - padT - padB

        val geo    = geometry(widthPx, heightPx, hoursBack, forceAxes, rightEdgeFor(readings, nowMs))
        val rightMs = geo.nowMs
        val fromMs = geo.fromMs

        val yOf = { sgv: Float -> geo.yOf(sgv) }
        val xOf = { ts: Long -> geo.xOf(ts) }

        // Faixa sutil apenas na zona de urgência (abaixo de 55) — igual ao xDrip
        canvas.drawRect(padL, yOf(URGENT_LOW), padL + chartW, padT + chartH,
            Paint().apply { color = Color.parseColor("#2AEF5350") })

        // Linhas de referência sólidas nos limiares configurados: vermelho=hipo, laranja=hiper
        val refLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.2f }
        refLine.color = Color.parseColor("#CCEF5350")
        canvas.drawLine(padL, yOf(hipo),  padL + chartW, yOf(hipo),  refLine)
        refLine.color = Color.parseColor("#CCFFA726")
        canvas.drawLine(padL, yOf(hiper), padL + chartW, yOf(hiper), refLine)

        // Faixa-alvo fixa 100/150, entre hipo e hiper — mesmas linhas cinza claro da aba Histórico
        refLine.color = Color.parseColor(secondaryInk)
        canvas.drawLine(padL, yOf(100f), padL + chartW, yOf(100f), refLine)
        canvas.drawLine(padL, yOf(150f), padL + chartW, yOf(150f), refLine)

        // Eixos: Y (glicemia) + X (horário)
        if (showAxes) {
            val gridPaint = Paint().apply {
                color = Color.parseColor(gridInk); strokeWidth = 0.8f
            }
            val yLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor(labelInk); textSize = labelSz
                textAlign = Paint.Align.RIGHT
            }
            val xLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor(labelInk); textSize = labelSz
                textAlign = Paint.Align.CENTER
            }
            val xTickPaint = Paint().apply {
                color = Color.parseColor(tickInk); strokeWidth = 0.8f
            }

            // Eixo Y — labels em hipo, 100, 150, hiper, 250(URGENT_HIGH); grid sutil só no topo
            // (as demais já têm linha de referência colorida/neutra desenhada acima)
            val yLabelPaintRed     = Paint(yLabelPaint).apply { color = Color.parseColor("#CCEF5350") }
            val yLabelPaintOrange  = Paint(yLabelPaint).apply { color = Color.parseColor("#CCFFA726") }
            val yLabelPaintNeutral = Paint(yLabelPaint).apply { color = Color.parseColor(secondaryInk) }
            val urgentHighInt = URGENT_HIGH.toInt()
            for ((sgv, label, paint) in listOf(
                Triple(hipo.toInt(),  hipo.toInt().toString(),  yLabelPaintRed),
                Triple(100, "100", yLabelPaintNeutral),
                Triple(150, "150", yLabelPaintNeutral),
                Triple(hiper.toInt(), hiper.toInt().toString(), yLabelPaintOrange),
                Triple(urgentHighInt, urgentHighInt.toString(), yLabelPaint)
            )) {
                val y = yOf(sgv.toFloat())
                if (sgv == urgentHighInt) canvas.drawLine(padL, y, padL + chartW, y, gridPaint)
                canvas.drawText(label, padL - 6f, y + labelSz * 0.35f, paint)
            }

            // Eixo X — horários
            val xIntervalH = when {
                hoursBack <= 6  -> 1
                hoursBack <= 12 -> 2
                else            -> 4
            }
            val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())

            // Primeiro tick: próxima hora "limpa" após fromMs
            val cal = Calendar.getInstance()
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
                val x = xOf(tickMs)
                // Linha vertical sutil
                canvas.drawLine(x, padT, x, padT + chartH, xTickPaint)
                // Label do horário
                canvas.drawText(sdf.format(Date(tickMs)), x, xBaseline, xLabelPaint)
                tickMs += xIntervalH * 3_600_000L
            }
        }

        val filtered = readings.filter { it.timestampMs in fromMs..rightMs }.sortedBy { it.timestampMs }
        if (filtered.isEmpty()) return bmp

        // Linha de glicemia — cinza sutil como xDrip (sem gradiente de preenchimento)
        val line = Path(); var first = true
        for (r in filtered) {
            val x = xOf(r.timestampMs); val y = yOf(r.sgvMgDl)
            if (first) { line.moveTo(x, y); first = false } else line.lineTo(x, y)
        }
        canvas.drawPath(line, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = if (showAxes) 2f else 1.5f
            color = Color.parseColor(lineInk)
            strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
        })

        // Pontos coloridos por zona — provisórios (confirmed=false, modo "Todas + distinção no
        // gráfico") saem menores/translúcidos; confirmados ou sem o conceito (null) ficam como
        // sempre foram (tamanho cheio, opacos).
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val dotR = if (showAxes) 8f else 5.5f
        for (r in filtered) {
            dot.color = zoneColor(r.sgvMgDl, hipo, hiper)
            val provisional = r.confirmed == false
            dot.alpha = if (provisional) 130 else 255
            canvas.drawCircle(xOf(r.timestampMs), yOf(r.sgvMgDl), if (provisional) dotR * 0.55f else dotR, dot)
        }

        return bmp
    }

    /**
     * Gráfico para o visor da Mi Band: fundo preto (casa com o canvas do template), linhas de
     * referência tracejadas em 70 (vermelho) e 180 (laranja) COM os valores rotulados, faixa
     * sutil abaixo de 55, e a curva de glicemia (linha fina + pontos coloridos por zona).
     * Canvas pequeno (152×136 no Mi Band 6) → labels compactos desenhados dentro da área.
     */
    fun renderWatchface(
        readings: List<GraphReading>,
        widthPx: Int,
        heightPx: Int,
        hoursBack: Int,
        hipo: Float = LOW,
        hiper: Float = HIGH,
        nowMs: Long = System.currentTimeMillis()
    ): Bitmap {
        val bmp = Bitmap.createBitmap(widthPx.coerceAtLeast(1), heightPx.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.BLACK)

        val w = widthPx.toFloat()
        val h = heightPx.toFloat()
        val labelSz = 15f
        val padL = 2f; val padR = 2f; val padT = 4f; val padB = 4f
        val chartW = w - padL - padR
        val chartH = h - padT - padB

        val rightMs = rightEdgeFor(readings, nowMs)
        val fromMs  = rightMs - hoursBack * 3_600_000L

        val yOf = { sgv: Float ->
            val ratio = (sgv.coerceIn(MIN_SGV, MAX_SGV) - MIN_SGV) / (MAX_SGV - MIN_SGV)
            padT + chartH * (1f - ratio)
        }
        val xOf = { ts: Long ->
            val ratio = ((ts - fromMs).toFloat() / (rightMs - fromMs).coerceAtLeast(1L)).coerceIn(0f, 1f)
            padL + ratio * chartW
        }

        // Faixa sutil na zona de urgência (abaixo de 55)
        canvas.drawRect(padL, yOf(URGENT_LOW), padL + chartW, padT + chartH,
            Paint().apply { color = Color.parseColor("#33EF5350") })

        // Linhas tracejadas nos limiares 70 e 180, com o valor rotulado à esquerda
        val dash = android.graphics.DashPathEffect(floatArrayOf(6f, 5f), 0f)
        val refLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 1.5f; pathEffect = dash
        }
        val refLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = labelSz; typeface = Typeface.DEFAULT_BOLD
        }
        for ((sgv, color) in listOf(hipo to "#EF5350", hiper to "#FFA726")) {
            val y = yOf(sgv)
            refLine.color = Color.parseColor(color)
            canvas.drawLine(padL, y, padL + chartW, y, refLine)
            refLabel.color = Color.parseColor(color)
            canvas.drawText(sgv.toInt().toString(), padL + 2f, y - 3f, refLabel)
        }

        // Faixa-alvo fixa 100/150 — linha neutra sem rótulo (tela minúscula, hipo/hiper já
        // rotulados acima; aqui só a referência visual da faixa mais estreita).
        val secondaryLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 1f; pathEffect = dash
            color = Color.parseColor("#66FFFFFF")
        }
        canvas.drawLine(padL, yOf(100f), padL + chartW, yOf(100f), secondaryLine)
        canvas.drawLine(padL, yOf(150f), padL + chartW, yOf(150f), secondaryLine)

        val filtered = readings.filter { it.timestampMs in fromMs..rightMs }.sortedBy { it.timestampMs }
        if (filtered.isEmpty()) return bmp

        // Linha fina de continuidade + pontos coloridos por zona
        val line = Path(); var first = true
        for (r in filtered) {
            val x = xOf(r.timestampMs); val y = yOf(r.sgvMgDl)
            if (first) { line.moveTo(x, y); first = false } else line.lineTo(x, y)
        }
        canvas.drawPath(line, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 1.2f
            color = Color.parseColor("#50FFFFFF")
            strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
        })
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        filtered.forEachIndexed { index, r ->
            dot.color = zoneColor(r.sgvMgDl, hipo, hiper)
            val radius = if (index == filtered.lastIndex) 3.5f else 2.2f
            canvas.drawCircle(xOf(r.timestampMs), yOf(r.sgvMgDl), radius, dot)
        }
        return bmp
    }

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
        nowMs: Long = System.currentTimeMillis()
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
            val ratio = (sgv.coerceIn(MIN_SGV, MAX_SGV) - MIN_SGV) / (MAX_SGV - MIN_SGV)
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
            dotPaint.color = zoneColor(r.sgvMgDl, hipo, hiper)
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
    // Única mudança no oráculo congelado: a regra de zona (L2, decisão do mantenedor em 18/10/2026 —
    // o valor do limite conta como fora, como o card e o GluSync). O resto do desenho segue intacto.
    fun zoneColor(sgv: Float, hipo: Float, hiper: Float) = when {
        sgv <= URGENT_LOW  -> Color.parseColor("#EF5350")
        sgv <= hipo        -> Color.parseColor("#FF7043")
        sgv >= URGENT_HIGH -> Color.parseColor("#EF5350")
        sgv >= hiper       -> Color.parseColor("#FF7043")
        else              -> Color.parseColor("#66BB6A")
    }
}
