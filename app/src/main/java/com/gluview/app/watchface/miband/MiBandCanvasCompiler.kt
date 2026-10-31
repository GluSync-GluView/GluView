package com.gluview.app.watchface.miband

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.gluview.app.ui.screen.watchface.WatchFaceRuntimeData
import com.gluview.app.util.GlucoseGraphRenderer
import com.gluview.app.watchface.ChartComponentKind
import com.gluview.app.watchface.ComponentRegistry
import com.gluview.app.watchface.StatusComponentKind
import com.gluview.app.watchface.TextComponentKind
import com.gluview.app.watchface.WatchFaceComponent
import com.gluview.app.watchface.WatchFaceDesign
import com.gluview.app.watchface.WatchFaceTextAlign
import com.gluview.app.watchface.compiler.WatchfaceCompiler
import kotlin.math.min
import kotlin.math.roundToInt


/** Janela do gráfico na Mi Band: 3 h fixas no editor E no envio automático (L14). */
const val MIBAND_GRAPH_HOURS = 3

/**
 * [WatchfaceCompiler] da Mi Band 6 (plano 06_watchfaces_v2_arquitetura.md, Fase 3) — extraído de
 * [WatchFaceDesignGenerator] SEM alterar nenhuma linha de lógica (mesmo desenho, mesma ordem de
 * operações; só mudou onde o código mora). [WatchFaceDesignGenerator] continua existindo como
 * orquestrador fino, delegando pra esta classe + [MiBandPackageBuilder].
 */
class MiBandCanvasCompiler(private val context: Context) : WatchfaceCompiler {

    override fun draw(design: WatchFaceDesign, runtime: WatchFaceRuntimeData, canvasWidth: Int, canvasHeight: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(canvasWidth, canvasHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.BLACK)

        // Componentes guardam posição/tamanho em px do wearable do design — remapeia
        // proporcionalmente pro canvas real, funciona mesmo que o design tenha sido feito
        // num wearable com dimensões diferentes das do recurso Huami.
        val scaleX = canvasWidth / design.wearable.widthPx.toFloat()
        val scaleY = canvasHeight / design.wearable.heightPx.toFloat()

        design.components.sortedBy { it.zIndex }.forEach { component ->
            drawComponent(canvas, component, runtime, scaleX, scaleY)
        }
        return bitmap
    }

    private fun drawComponent(
        canvas: Canvas,
        c: WatchFaceComponent,
        runtime: WatchFaceRuntimeData,
        scaleX: Float,
        scaleY: Float
    ) {
        val box = RectF(c.x * scaleX, c.y * scaleY, (c.x + c.width) * scaleX, (c.y + c.height) * scaleY)
        if (box.width() < 1f || box.height() < 1f) return

        canvas.save()
        canvas.rotate(c.rotation, box.centerX(), box.centerY())
        canvas.scale(c.scale, c.scale, box.centerX(), box.centerY())

        // ComponentRegistry (ver ComponentKind.kt) — sensor conectado NÃO força styleTag "pill"
        // aqui (diferente da prévia do editor) — comportamento preexistente preservado, ver
        // comentário em WatchFaceRenderer.SensorConnected().
        when (val kind = ComponentRegistry.byId(c.typeId)) {
            is ChartComponentKind -> drawChart(canvas, c, runtime, box)
            is StatusComponentKind -> drawText(canvas, c, kind.statusText(runtime), box, kind.statusColor(c, runtime))
            is TextComponentKind -> drawText(canvas, c, kind.textFor(c, runtime), box, c.color)
        }
        canvas.restore()
    }

    private fun drawChart(canvas: Canvas, c: WatchFaceComponent, runtime: WatchFaceRuntimeData, box: RectF) {
        val bmp = GlucoseGraphRenderer.renderWatchface(
            runtime.graphReadings, box.width().roundToInt(), box.height().roundToInt(), hoursBack = MIBAND_GRAPH_HOURS,
            hipo = runtime.hipo, hiper = runtime.hiper, urgentLow = runtime.urgentLow, urgentHigh = runtime.urgentHigh
        )
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { alpha = (c.alpha.coerceIn(0f, 1f) * 255).roundToInt() }
        canvas.drawBitmap(bmp, box.left, box.top, paint)
        bmp.recycle()
    }

    // Espelha TextComponent do preview (WatchFaceRenderer.kt): tamanho de fonte por altura/largura
    // da caixa, alinhamento, fundo por styleTag (pill/accent/outline) e decoratedText.
    private fun drawText(canvas: Canvas, c: WatchFaceComponent, text: String, box: RectF, colorLong: Long) {
        if (text.isEmpty()) return
        val displayText = decoratedText(c, text)
        val heightFactor = when (c.styleTag) {
            "compact", "stacked" -> 0.40f
            "bold" -> 0.62f
            "accent" -> 0.58f
            else -> 0.50f
        }
        val longestLine = displayText.lines().maxOf { it.length }.coerceAtLeast(1)
        val fontByHeight = box.height() * heightFactor
        val fontByWidth = box.width() / (longestLine * 0.60f)
        val fontSize = min(fontByHeight, fontByWidth).coerceIn(7f, 200f)

        drawBackground(canvas, c, box, colorLong)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colorLong.toInt()
            alpha = (c.alpha.coerceIn(0f, 1f) * 255).roundToInt()
            textSize = fontSize
            typeface = typefaceFor(c.font, bold = c.styleTag in listOf("bold", "accent"))
            textAlign = when (WatchFaceTextAlign.fromValue(c.alignment)) {
                WatchFaceTextAlign.START -> Paint.Align.LEFT
                WatchFaceTextAlign.CENTER -> Paint.Align.CENTER
                WatchFaceTextAlign.END -> Paint.Align.RIGHT
            }
        }

        val fm = paint.fontMetrics
        val lineHeight = fm.descent - fm.ascent
        val lines = displayText.lines().take(if (c.styleTag == "stacked") 2 else 1)
        var baseline = box.centerY() - (lineHeight * lines.size) / 2f - fm.ascent
        val x = when (paint.textAlign) {
            Paint.Align.LEFT -> box.left
            Paint.Align.CENTER -> box.centerX()
            Paint.Align.RIGHT -> box.right
        }
        lines.forEach { line ->
            canvas.drawText(line, x, baseline, paint)
            baseline += lineHeight
        }
    }

    private fun drawBackground(canvas: Canvas, c: WatchFaceComponent, box: RectF, colorLong: Long) {
        val radius = box.height() / 2f
        when (c.styleTag) {
            "pill" -> canvas.drawRoundRect(box, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colorLong.toInt(); alpha = 41 })
            "accent" -> canvas.drawRoundRect(box, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; alpha = 20 })
            "outline" -> canvas.drawRoundRect(box, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = colorLong.toInt(); alpha = 178; style = Paint.Style.STROKE; strokeWidth = 2f
            })
        }
    }

    private fun decoratedText(c: WatchFaceComponent, text: String): String = when (c.styleTag) {
        "labelled" -> "${c.label.substringBefore(" ")}\n$text"
        "split" -> text.replace(":", " : ").replace("/", " / ")
        else -> text
    }

    // "Default"/"Sans" usam os MESMOS arquivos Roboto do template WatchDrip (assets/fonts/), pra
    // bater com o visual original — Serif/Mono/Condensed não têm .ttf próprio, ficam no sistema.
    private fun typefaceFor(font: String, bold: Boolean): Typeface = when (font) {
        "Serif" -> Typeface.create(Typeface.SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
        "Mono" -> Typeface.create(Typeface.MONOSPACE, if (bold) Typeface.BOLD else Typeface.NORMAL)
        "Condensed" -> Typeface.create(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
        else -> {
            val assetName = if (bold) "Roboto-Bold" else "Roboto-Regular"
            runCatching { Typeface.createFromAsset(context.assets, "fonts/$assetName.ttf") }
                .getOrElse { Typeface.create(Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL) }
        }
    }
}
