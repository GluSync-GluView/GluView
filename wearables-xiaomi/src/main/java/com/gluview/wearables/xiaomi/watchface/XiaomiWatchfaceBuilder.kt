package com.gluview.wearables.xiaomi.watchface

import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/**
 * Plano 21, F7.4 (22/11/2026, pedido do Felipe: glicemia no visor com gráfico na Mi Band 8/9) — monta um
 * visor binário "v2" das Xiaomi novas (cabeçalho 5A A5 34 12) com:
 *  - um fundo de tela inteira (desenhado no celular: glicemia, seta, gráfico) — refeito a cada leitura;
 *  - 4 dígitos de hora ao vivo (dezena/unidade da hora e do minuto), que a pulseira atualiza sozinha.
 *
 * Formato pela documentação do Mi8WfBinTool (README, github.com/zhy8388608/Mi8WfBinTool) e pelas notas do
 * pip-boy (docs/FORMAT-NOTES.md). Código próprio; a saída é conferida BYTE A BYTE contra o empacotador de
 * referência rodado no PC (teste XiaomiWatchfaceBuilderTest, arquivos esperados gerados por ele).
 * Sem pulseira de verdade para testar: o que a pulseira aceita é só o que o formato documenta.
 */
object XiaomiWatchfaceBuilder {

    /** Imagem ARGB (0xAARRGGBB), linha a linha. */
    class Argb(val width: Int, val height: Int, val pixels: IntArray) {
        init { require(pixels.size == width * height) { "pixels ≠ largura × altura" } }
        fun hasAlpha() = pixels.any { (it ushr 24) != 0xff }
        fun colorCount(limit: Int = 257): Int {
            val s = HashSet<Int>()
            for (p in pixels) { s.add(p); if (s.size >= limit) break }
            return s.size
        }
    }

    /**
     * Família de codificação. MB8 = "mi8" do empacotador; MB9 = "mi8pro" com o detalhe N66 (Mi Band 9:
     * imagens sempre com alfa, 256 cores com paleta ou RGB565+alfa de 3 bytes). A Mi Band 9 Active (172x320)
     * usa a regra da 9 sem nenhuma referência: experimental.
     */
    enum class Family(val width: Int, val height: Int, val pro: Boolean) {
        MI_BAND_8(192, 490, false),
        MI_BAND_9(192, 490, true),
        MI_BAND_9_ACTIVE(172, 320, true)
    }

    /** Onde ficam os 4 dígitos da hora (canto superior esquerdo de cada um). */
    data class TimeLayout(val hourTensX: Int, val hourUnitsX: Int, val minTensX: Int, val minUnitsX: Int, val y: Int)

    // Fontes de dados (Mi-Create, sources.json): dezena/unidade da hora e do minuto.
    private val TIME_SOURCES = listOf("0A11", "0911", "1211", "1111")

    /**
     * [id] = 9 dígitos (identifica o visor na pulseira; mantê-lo igual faz a pulseira trocar o visor
     * em vez de acumular). [digits] = imagens 0..9, todas do mesmo tamanho.
     */
    fun build(family: Family, id: String, name: String, background: Argb, preview: Argb, digits: List<Argb>, time: TimeLayout,
              matchReferenceHeader: Boolean = false): ByteArray {
        require(id.length == 9 && id.all { it.isDigit() }) { "id do visor tem 9 dígitos" }
        require(digits.size == 10 && digits.all { it.width == digits[0].width && it.height == digits[0].height })
        val codec = ImageCodec(family.pro)

        // Recursos: 1 imagem (fundo) e 4 listas (a mesma lista de dígitos, uma definição por widget,
        // como o empacotador faz: chaves diferentes, bytes iguais → um só bloco no arquivo).
        val bgBytes = codec.single(background)
        val listBytes = codec.imageList(digits)
        val previewBytes = codec.single(preview)
        val uniques = linkedMapOf(md5(bgBytes) to bgBytes)
        uniques.putIfAbsent(md5(listBytes), listBytes)

        val elementCount = 1 + TIME_SOURCES.size
        val singles = 1
        val lists = TIME_SOURCES.size
        val widgets = TIME_SOURCES.size
        val widgetLen = 0x10 + 10 * 4
        val start = 0xA8 + 0x58 + 0x48
        val defSize = (elementCount + singles + lists + widgets) * 16
        val propSize = elementCount * 16 + widgets * widgetLen
        val propStart = start + defSize
        val previewOffset = propStart + propSize
        var cursor = previewOffset + previewBytes.size
        val imageOffsets = HashMap<String, Int>()
        for ((k, v) in uniques) { imageOffsets[k] = cursor; cursor += v.size }

        val out = ByteArrayOutputStream()
        // ---- cabeçalho principal (0xA8)
        val h = ByteArray(0xA8)
        h[0] = 0x5A; h[1] = 0xA5.toByte(); h[2] = 0x34; h[3] = 0x12
        w32(h, 0x10, 0x800)
        if (family.pro) w16(h, 0x1E, 1) else h[0x1E] = if (matchReferenceHeader) 6 else 4
        h[0x1C] = 1
        System.arraycopy(id.toByteArray(Charsets.US_ASCII), 0, h, 0x28, 9)
        val nb = name.toByteArray(Charsets.UTF_8)
        System.arraycopy(nb, 0, h, 0x68, minOf(nb.size, 0x3C))
        w32(h, 0x20, previewOffset)
        out.write(h)

        // ---- cabeçalho do estilo (0x58) + bloco de nome (0x48)
        val fh = ByteArray(0x58)
        if (!family.pro && !matchReferenceHeader) fh[3] = 2   // valor de um visor aceito pela Mi Band 8 (notas do pip-boy)
        w32(fh, 0x04, previewOffset)
        var off = start
        sec(fh, 0x08, elementCount, off); off += elementCount * 16
        sec(fh, 0x10, 0, off)
        sec(fh, 0x18, singles, off); off += singles * 16
        sec(fh, 0x20, lists, off); off += lists * 16
        sec(fh, 0x28, 0, off)
        sec(fh, 0x30, 0, off)
        sec(fh, 0x38, 0, off)
        sec(fh, 0x40, widgets, off); off += widgets * 16
        sec(fh, 0x48, 0, off)
        sec(fh, 0x50, 0, off)
        out.write(fh)
        out.write(ByteArray(0x48))

        // ---- definições (16 bytes cada)
        val defs = ByteArray(defSize)
        var p = 0
        var po = propStart
        for (i in 0 until elementCount) { def(defs, p, i, 0, po, 16); p += 16; po += 16 }
        def(defs, p, 0, 2, imageOffsets.getValue(md5(bgBytes)), bgBytes.size); p += 16
        for (i in 0 until lists) { def(defs, p, i, 3, imageOffsets.getValue(md5(listBytes)), listBytes.size); p += 16 }
        for (i in 0 until widgets) { def(defs, p, i, 7, po, widgetLen); p += 16; po += widgetLen }
        out.write(defs)

        // ---- propriedades: elementos (16) e widgets de lista de imagens (16 + 4 por imagem)
        val props = ByteArray(propSize)
        var q = 0
        w24(props, q, 0); props[q + 3] = 2; w16(props, q + 4, 0); w16(props, q + 6, 0); q += 16   // fundo
        val xs = listOf(time.hourTensX, time.hourUnitsX, time.minTensX, time.minUnitsX)
        for (i in 0 until widgets) { w24(props, q, i); props[q + 3] = 7; w16(props, q + 4, xs[i]); w16(props, q + 6, time.y); q += 16 }
        for (i in 0 until widgets) {
            val src = TIME_SOURCES[i]
            props[q] = src.substring(0, 2).toInt(16).toByte()
            props[q + 1] = src.substring(2, 4).toInt(16).toByte()
            props[q + 3] = 0x20
            w24(props, q + 8, i); props[q + 11] = 3
            w24(props, q + 12, 0x200)
            for (v in 0..9) w16(props, q + 16 + v * 4, v)
            q += widgetLen
        }
        out.write(props)

        // ---- prévia e recursos
        out.write(previewBytes)
        for (b in uniques.values) out.write(b)
        return out.toByteArray()
    }

    /** Codificação das imagens (ImageCodec do empacotador, regras das famílias mi8 e N66). */
    internal class ImageCodec(private val pro: Boolean) {
        fun single(img: Argb): ByteArray {
            val alpha = pro || img.hasAlpha()
            val sign = sign(listOf(img))
            val (pix, bpp) = pixels(img, sign, alpha)
            val payload = compressed(pix, bpp, sign)
            val h = ByteArray(12)
            h[0] = (if (sign != 0) sign else if (!alpha) 3 else 0).toByte()
            h[1] = 4
            w16(h, 4, img.width); w16(h, 6, img.height); w32(h, 8, payload.size)
            return h + payload
        }

        fun imageList(imgs: List<Argb>): ByteArray {
            val sign = sign(imgs)
            val payloads = imgs.map { val (pix, bpp) = pixels(it, sign, true); compressed(pix, bpp, sign) }
            val h = ByteArray(12 + imgs.size * 4)
            h[0] = sign.toByte(); h[1] = imgs.size.toByte(); h[2] = 4
            w16(h, 4, imgs[0].width); w16(h, 6, imgs[0].height)
            w32(h, 8, payloads.sumOf { it.size } + imgs.size * 4)
            payloads.forEachIndexed { i, b -> w32(h, 12 + i * 4, b.size) }
            val out = ByteArrayOutputStream(); out.write(h); payloads.forEach { out.write(it) }
            return out.toByteArray()
        }

        private fun sign(imgs: List<Argb>): Int {
            if (!pro) return 0
            return if (imgs.all { it.colorCount() <= 256 }) 0x10 else 6
        }

        private fun pixels(img: Argb, sign: Int, alpha: Boolean): Pair<ByteArray, Int> = when {
            sign == 0x10 -> index256(img) to 1
            sign == 6 -> rgb565a(img) to 3
            alpha -> bgra(img) to 4
            else -> rgb565(img) to 2
        }

        private fun compressed(pix: ByteArray, bpp: Int, sign: Int): ByteArray {
            val enc = if (sign == 0x10) Rle.v20(pix) else Rle.v10(pix, bpp)
            val r = ByteArray(8)
            r[0] = 0xE0.toByte(); r[1] = 0x21; r[2] = 0xA5.toByte(); r[3] = 0x5A
            w32(r, 4, (pix.size shl 4) or bpp)
            return r + enc
        }

        private fun rgb565(img: Argb): ByteArray {
            val o = ByteArray(img.pixels.size * 2); var p = 0
            for (c in img.pixels) {
                val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
                o[p++] = (((g and 0x1C) shl 3) or ((b and 0xF8) shr 3)).toByte()
                o[p++] = ((r and 0xF8) or ((g and 0xE0) shr 5)).toByte()
            }
            return o
        }

        private fun rgb565a(img: Argb): ByteArray {
            val o = ByteArray(img.pixels.size * 3); var p = 0
            for (c in img.pixels) {
                val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
                o[p++] = (((g and 0x1C) shl 3) or ((b and 0xF8) shr 3)).toByte()
                o[p++] = ((r and 0xF8) or ((g and 0xE0) shr 5)).toByte()
                o[p++] = (c ushr 24).toByte()
            }
            return o
        }

        private fun bgra(img: Argb): ByteArray {
            val o = ByteArray(img.pixels.size * 4); var p = 0
            for (c in img.pixels) {
                o[p++] = (c and 255).toByte(); o[p++] = ((c shr 8) and 255).toByte()
                o[p++] = ((c shr 16) and 255).toByte(); o[p++] = (c ushr 24).toByte()
            }
            return o
        }

        /** Paleta de 256 cores (BGRA, 1024 bytes) + 1 índice por pixel. */
        private fun index256(img: Argb): ByteArray {
            val o = ByteArray(img.pixels.size + 1024)
            val map = LinkedHashMap<Int, Int>(); var pp = 0
            for (c in img.pixels) {
                var idx = map[c]
                if (idx == null) {
                    idx = map.size
                    if (idx < 256) {
                        map[c] = idx
                        val p = idx * 4
                        o[p] = (c and 255).toByte(); o[p + 1] = ((c shr 8) and 255).toByte()
                        o[p + 2] = ((c shr 16) and 255).toByte(); o[p + 3] = (c ushr 24).toByte()
                    } else idx = 255
                }
                o[1024 + pp++] = idx.toByte()
            }
            return o
        }
    }

    /** RLE das imagens: v1.0 (repetição de pixels inteiros) e v2.0 (bytes de índice, com trechos literais). */
    internal object Rle {
        fun v10(data: ByteArray, bpp: Int): ByteArray {
            val out = ByteArrayOutputStream(); var i = 0
            while (i < data.size) {
                var count = 1
                while (count < 0x7F && i + (count + 1) * bpp <= data.size && (0 until bpp).all { data[i + it] == data[i + count * bpp + it] }) count++
                out.write(count)
                out.write(data, i, minOf(bpp, data.size - i))
                for (k in data.size - i until bpp) out.write(0)
                i += count * bpp
            }
            return out.toByteArray()
        }

        fun v20(data: ByteArray): ByteArray {
            val out = ByteArrayOutputStream(); var i = 0
            while (i < data.size) {
                if (i == data.size - 1) { out.write(1); out.write(data[i].toInt() and 0xFF); break }
                if (data[i] == data[i + 1] && same(data, i, 3)) {
                    var c = 1
                    while (c < 0x7F && i + c < data.size && data[i + c] == data[i]) c++
                    out.write(c and 0x7F); out.write(data[i].toInt() and 0xFF); i += c
                } else {
                    var c = 1
                    while (c < 0x7F && i + c < data.size && !same(data, i + c, 3)) c++
                    out.write((c or 0x80) and 0xFF); out.write(data, i, c); i += c
                }
            }
            return out.toByteArray()
        }

        private fun same(d: ByteArray, p: Int, n: Int): Boolean {
            if (p + n > d.size) return false
            for (k in 1 until n) if (d[p + k] != d[p]) return false
            return true
        }
    }

    private fun md5(b: ByteArray): String = MessageDigest.getInstance("MD5").digest(b).joinToString("") { "%02x".format(it) }
    private fun def(b: ByteArray, p: Int, i: Int, t: Int, off: Int, len: Int) { w16(b, p, i); b[p + 3] = t.toByte(); w32(b, p + 8, off); w32(b, p + 12, len) }
    private fun sec(h: ByteArray, o: Int, count: Int, off: Int) { w32(h, o, count); w32(h, o + 4, off) }
    internal fun w16(b: ByteArray, p: Int, v: Int) { b[p] = v.toByte(); b[p + 1] = (v shr 8).toByte() }
    internal fun w24(b: ByteArray, p: Int, v: Int) { b[p] = v.toByte(); b[p + 1] = (v shr 8).toByte(); b[p + 2] = (v shr 16).toByte() }
    internal fun w32(b: ByteArray, p: Int, v: Int) { b[p] = v.toByte(); b[p + 1] = (v shr 8).toByte(); b[p + 2] = (v shr 16).toByte(); b[p + 3] = (v shr 24).toByte() }
}
