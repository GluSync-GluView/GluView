package com.gluview.app.watchface.miband

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.gson.Gson
import com.gluview.app.watchface.compiler.WatchfacePackageBuilder
import com.gluview.app.watchface.miband.firmware.BinUtils
import com.gluview.app.watchface.miband.firmware.WatchFaceParts.ConfigPOJO.WatchfaceConfig
import com.gluview.app.watchface.miband.firmware.WatchFaceParts.Header.HeaderMiBand5
import com.gluview.app.watchface.miband.firmware.WatchFaceParts.Image.ImageMiBandPalette
import com.gluview.app.watchface.miband.firmware.WatchFaceParts.Parameter
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * [WatchfacePackageBuilder] da Mi Band 6 (plano 06_watchfaces_v2_arquitetura.md, Fase 3) —
 * extraído de [WatchFaceDesignGenerator] SEM alterar nenhuma linha de lógica: parse do template
 * `.bin` (header UIHH + parâmetros TLV + tabela de offsets), codificação do bitmap desenhado na
 * paleta indexada Huami ([ImageMiBandPalette]), recorte do recurso e reescrita da tabela de
 * offsets. Mesmo mecanismo já testado byte a byte contra a banda real — só mudou de arquivo.
 */
class MiBandPackageBuilder(context: Context, assetDir: String) : WatchfacePackageBuilder {

    private val tag = "MiBandPackageBuilder"

    private val template: ByteArray =
        context.assets.open(assetDir + "watchface.bin").use { it.readBytes() }
    private val resourceToReplace: Int =
        context.assets.open(assetDir + "config.json").use {
            Gson().fromJson(it.reader(), WatchfaceConfig::class.java)
        }.resourceToReplace

    override fun buildPackage(screen: Bitmap): ByteArray {
        val parsed = parseTemplate()

        val baos = ByteArrayOutputStream()
        val enc = ImageMiBandPalette(baos)
        enc.setImageMask(null)
        val prepared = enc.prepareImage(screen)
        enc.write(prepared)
        prepared.recycle()

        if (resourceToReplace < 0 || resourceToReplace >= parsed.resources.size) {
            throw IllegalStateException("resourceToReplace=$resourceToReplace fora do range (${parsed.resources.size} imagens)")
        }
        parsed.resources[resourceToReplace] = baos.toByteArray()
        Log.d(tag, "Imagem $resourceToReplace substituída (${parsed.resources[resourceToReplace].size} bytes)")
        return writeBin(parsed)
    }

    // ---- parse/escrita do .bin: mesmo mecanismo do MiBandWatchFaceGenerator (duplicado de
    // propósito — ver plano do construtor de visor: o Pipeline B não deve ser tocado) ----
    private class Parsed(val resourcesTableOffset: Int, val resources: MutableList<ByteArray>)

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
        return Parsed(resourcesTableOffset, resources)
    }

    private fun writeBin(p: Parsed): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(template, 0, p.resourcesTableOffset)
        val numPadding = 4 - p.resourcesTableOffset % 4
        var offset = numPadding
        for (res in p.resources) { out.write(BinUtils.fromUint32(offset)); offset += res.size }
        repeat(numPadding) { out.write(0xff) }
        for (res in p.resources) out.write(res)
        val data = out.toByteArray()
        Log.d(tag, "Watchface (design livre) gerado: ${data.size} bytes")
        return data
    }
}
