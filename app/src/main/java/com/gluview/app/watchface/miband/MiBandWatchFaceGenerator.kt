package com.gluview.app.watchface.miband

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.util.Log
import com.google.gson.Gson
import com.gluview.app.watchface.miband.firmware.BinUtils
import com.gluview.app.watchface.miband.firmware.WatchFaceParts.ConfigPOJO.Position
import com.gluview.app.watchface.miband.firmware.WatchFaceParts.ConfigPOJO.SimpleText
import com.gluview.app.watchface.miband.firmware.WatchFaceParts.ConfigPOJO.TextSettings
import com.gluview.app.watchface.miband.firmware.WatchFaceParts.ConfigPOJO.WatchfaceConfig
import com.gluview.app.watchface.miband.firmware.WatchFaceParts.Header.HeaderMiBand5
import com.gluview.app.watchface.miband.firmware.WatchFaceParts.Image.ImageMiBandPalette
import com.gluview.app.watchface.miband.firmware.WatchFaceParts.Parameter
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Dados a estampar no visor da Mi Band. O gráfico já vem renderizado no tamanho do config.graph.
 */
data class MiBandWatchData(
    val bgValue: String,
    val delta: String,
    val timeText: String,
    val arrowName: String,   // nome do PNG em assets/.../arrows (ex.: "Flat", "SingleUp")
    val isHigh: Boolean,
    val isLow: Boolean,
    val isStale: Boolean,
    val graph: Bitmap?
)

/**
 * Gera o arquivo .bin de watchface para a Mi Band 6, no estilo do WatchDrip (porte GPLv3):
 * carrega o template dos assets, desenha a tela (gráfico + seta + glicemia + delta) sobre o
 * canvas conforme o config.json, codifica como imagem Huami e substitui o recurso correspondente,
 * reescrevendo a tabela de offsets. Mi Band 6 NÃO usa compressão.
 *
 * Camada 3 do envio de visor. A camada 2 (upload BLE) consome o byte[] retornado por [generate].
 */
class MiBandWatchFaceGenerator(private val context: Context) {

    private val tag = "MiBandWFGen"
    private val assetDir = "miband_watchface_parts/miband6/"
    private val arrowsDir = "miband_watchface_parts/arrows/"

    private val template: ByteArray =
        context.assets.open(assetDir + "watchface.bin").use { it.readBytes() }
    private val canvasBmp: Bitmap =
        context.assets.open(assetDir + "canvas.png").use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply {
                inScaled = false; inPreferredConfig = Bitmap.Config.ARGB_8888
            })!!
        }
    private val config: WatchfaceConfig =
        context.assets.open(assetDir + "config.json").use {
            Gson().fromJson(it.reader(), WatchfaceConfig::class.java)
        }

    /** Tamanho do bitmap do gráfico esperado por este visor (config.json), p/ quem for renderizá-lo. */
    val graphWidth: Int get() = config.graph?.width ?: 0
    val graphHeight: Int get() = config.graph?.height ?: 0

    /** Estrutura do .bin lida do template, para reescrever trocando uma imagem. */
    private class Parsed(val resourcesTableOffset: Int, val resources: MutableList<ByteArray>)

    fun generate(d: MiBandWatchData): ByteArray {
        val parsed = parseTemplate()
        val screen = drawScreen(d)

        runCatching {
            java.io.File(context.getExternalFilesDir(null), "debug_screen.png").outputStream().use {
                screen.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }

        // codifica a tela no formato de imagem Huami (paleta)
        val baos = ByteArrayOutputStream()
        val enc = ImageMiBandPalette(baos)
        enc.setImageMask(null)
        val prepared = enc.prepareImage(screen)
        enc.write(prepared)
        screen.recycle()
        prepared.recycle()

        val idx = config.resourceToReplace
        if (idx < 0 || idx >= parsed.resources.size) {
            throw IllegalStateException("resourceToReplace=$idx fora do range (${parsed.resources.size} imagens)")
        }
        parsed.resources[idx] = baos.toByteArray()
        Log.d(tag, "Imagem ${idx} substituída (${parsed.resources[idx].size} bytes)")

        return writeBin(parsed)
    }

    // ---- parse do template (header UIHH + parâmetros + tabela de offsets + recursos) ----
    private fun parseTemplate(): Parsed {
        val header = HeaderMiBand5().readFrom(ByteArrayInputStream(template))
        require(header.isValid) { "Formato de watchface inválido (assinatura)" }
        val headerSize = header.headerSize
        val parametersSize = header.parametersSize
        require(parametersSize in 1..10000) { "parametersSize inválido: $parametersSize" }

        val paramBytes = template.copyOfRange(headerSize, headerSize + parametersSize)
        val mainParam = Parameter.readFrom(ByteArrayInputStream(paramBytes), 0)
        val parametersTableLength = mainParam.children[0].value.toInt()
        val imagesCount = (mainParam.children[1].value - header.startImageIndex).toInt()

        val resourcesTableOffset = headerSize + parametersSize + parametersTableLength
        val offsets = IntArray(imagesCount)
        val bb = ByteBuffer.wrap(template, resourcesTableOffset, imagesCount * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until imagesCount) offsets[i] = bb.int
        val resourcesOffset = resourcesTableOffset + imagesCount * 4

        val resources = ArrayList<ByteArray>(imagesCount)
        for (i in 0 until imagesCount) {
            val off = offsets[i] + resourcesOffset
            val next = if (i + 1 < imagesCount) offsets[i + 1] + resourcesOffset else template.size
            resources.add(template.copyOfRange(off, next))
        }
        Log.d(tag, "Template: headerSize=$headerSize paramsSize=$parametersSize imagens=$imagesCount tableOffset=$resourcesTableOffset")
        return Parsed(resourcesTableOffset, resources)
    }

    // ---- reescreve o .bin: header/params originais + nova tabela de offsets + recursos ----
    private fun writeBin(p: Parsed): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(template, 0, p.resourcesTableOffset)
        val numPadding = 4 - p.resourcesTableOffset % 4
        var offset = numPadding
        for (res in p.resources) { out.write(BinUtils.fromUint32(offset)); offset += res.size }
        repeat(numPadding) { out.write(0xff) }
        for (res in p.resources) out.write(res)
        val data = out.toByteArray()
        Log.d(tag, "Watchface gerado: ${data.size} bytes")
        return data
    }

    // ---- desenho da tela ----
    private fun drawScreen(d: MiBandWatchData): Bitmap {
        val result = canvasBmp.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(result)

        config.graph?.let { g ->
            d.graph?.let { gb ->
                canvas.save()
                canvas.rotate(g.position.rotate.toFloat(), g.position.x.toFloat(), g.position.y.toFloat())
                canvas.drawBitmap(gb, g.position.x.toFloat(), g.position.y.toFloat(), null)
                canvas.restore()
            }
        }

        // seta de tendência
        loadArrow(d.arrowName)?.let { arrow ->
            val p = config.arrowPosition
            canvas.save()
            canvas.rotate(p.rotate.toFloat(), p.x.toFloat(), p.y.toFloat())
            canvas.drawBitmap(arrow, p.x.toFloat(), p.y.toFloat(), null)
            canvas.restore()
        }

        // valor da glicemia
        val bgPaint = textPaint(config.bgValue.textSettings)
        if (d.isHigh) bgPaint.color = config.bgValue.colorHigh
        if (d.isLow) bgPaint.color = config.bgValue.colorLow
        bgPaint.isStrikeThruText = d.isStale
        drawText(canvas, format(config.bgValue.textPattern, d.bgValue, d.timeText), config.bgValue.position, bgPaint)

        // delta + horário — a fonte diminui até caber na largura (antes "há 25min sem sinal · 14:05"
        // tinha ~250px num visor de 152 e o começo era cortado).
        val deltaText = format(config.deltaText.textPattern, d.delta, d.timeText)
        val deltaPaint = textPaint(config.deltaText.textSettings)
        val maxWidth = canvas.width - 2f * config.deltaText.position.x
        while (deltaPaint.measureText(deltaText) > maxWidth && deltaPaint.textSize > MIN_DELTA_TEXT_PX) {
            deltaPaint.textSize -= 1f
        }
        drawText(canvas, deltaText, config.deltaText.position, deltaPaint)

        return result
    }

    private fun format(pattern: String?, value: String, time: String): String =
        (pattern ?: "")
            .replace(Regex("\\\$value\\b"), value)
            .replace(Regex("\\\$time\\b"), time)

    private fun loadArrow(name: String): Bitmap? = try {
        context.assets.open("$arrowsDir$name.png").use { BitmapFactory.decodeStream(it) }
    } catch (e: Exception) { Log.w(tag, "Seta '$name' não encontrada"); null }

    private fun textPaint(ts: TextSettings): Paint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
        color = ts.getColor()
        ts.fontFamily?.let { fam ->
            typeface = runCatching { Typeface.createFromAsset(context.assets, "fonts/$fam.ttf") }
                .getOrElse { Typeface.create(fam, if (ts.textStyle.equals("bold", true)) Typeface.BOLD else Typeface.NORMAL) }
        }
        ts.textAlign?.let { textAlign = Paint.Align.valueOf(it.uppercase()) }
        ts.textScale?.let { textScaleX = it }
        ts.fontSize?.let { textSize = it }
        ts.strokeWidth?.let { strokeWidth = it }
    }

    // Replica drawTextOnCanvas do WatchDrip: align RIGHT/CENTER usam a largura do canvas; y é baseline.
    private fun drawText(canvas: Canvas, text: String, position: Position, paint: Paint) {
        if (text.isEmpty()) return
        val align = paint.textAlign
        paint.textAlign = Paint.Align.LEFT
        when (align) {
            Paint.Align.LEFT -> { canvas.drawText(text, position.x.toFloat(), position.y.toFloat(), paint); return }
            else -> {
                val bounds = Rect()
                paint.getTextBounds(text, 0, text.length, bounds)
                val xOffset = if (align == Paint.Align.CENTER)
                    (canvas.width / 2f - bounds.width() / 2f - bounds.left) + position.x
                else
                    (canvas.width - bounds.width() - position.x).toFloat()
                canvas.save()
                canvas.translate(xOffset, 0f)
                canvas.rotate(position.rotate.toFloat(), 0f, position.y.toFloat())
                canvas.drawText(text, 0f, position.y.toFloat(), paint)
                canvas.restore()
            }
        }
    }
}

/** Menor fonte do texto de delta/hora antes de desistir de encolher (px do visor). */
private const val MIN_DELTA_TEXT_PX = 12f
