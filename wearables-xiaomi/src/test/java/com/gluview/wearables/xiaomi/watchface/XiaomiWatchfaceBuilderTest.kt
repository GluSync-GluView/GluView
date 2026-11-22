package com.gluview.wearables.xiaomi.watchface

import com.gluview.wearables.xiaomi.watchface.XiaomiWatchfaceBuilder.Argb
import com.gluview.wearables.xiaomi.watchface.XiaomiWatchfaceBuilder.Family
import com.gluview.wearables.xiaomi.watchface.XiaomiWatchfaceBuilder.TimeLayout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Os arquivos esperados (ref_mi8.bin, ref_n66.bin) foram gerados pelo empacotador de referência
 * (Mi8WfBinTool, rodado no PC, NÃO distribuído) a partir das MESMAS imagens sintéticas deste diretório (PNG convertido para .argb)
 * e de um wfDef.json equivalente (fundo + 4 dígitos de hora). O gerador tem que dar os mesmos bytes.
 */
class XiaomiWatchfaceBuilderTest {
    /** Imagens do teste em formato cru (u32 largura, u32 altura, pixels ARGB u32), convertidas dos PNG. */
    private fun png(name: String): Argb {
        val bb = ByteBuffer.wrap(bytes("$name.argb")).order(ByteOrder.LITTLE_ENDIAN)
        val w = bb.int; val h = bb.int
        return Argb(w, h, IntArray(w * h) { bb.int })
    }
    private fun bytes(name: String) = javaClass.getResourceAsStream("/watchface/$name")!!.readBytes()
    private val digits get() = (0..9).map { png("d_$it") }
    private val time = TimeLayout(20, 58, 104, 142, 40)

    @Test fun miBand8IgualAoEmpacotadorDeReferencia() {
        val out = XiaomiWatchfaceBuilder.build(Family.MI_BAND_8, "167210065", "GluView", png("bg"), png("preview"), digits, time,
            matchReferenceHeader = true)
        assertArrayEquals(bytes("ref_mi8.bin"), out)
    }

    @Test fun miBand9IgualAoEmpacotadorDeReferencia() {
        val out = XiaomiWatchfaceBuilder.build(Family.MI_BAND_9, "167210065", "GluView", png("bg"), png("preview"), digits, time)
        assertArrayEquals(bytes("ref_n66.bin"), out)
    }

    @Test fun miBand8ComOsBytesDeUmVisorAceitoPelaPulseira() {
        // Notas do pip-boy: um visor que a Mi Band 8 aceitou tem 0x1E = 4 e o byte 3 do cabeçalho do estilo = 2.
        val out = XiaomiWatchfaceBuilder.build(Family.MI_BAND_8, "167210065", "GluView", png("bg"), png("preview"), digits, time)
        assertEquals(4, out[0x1E].toInt())
        assertEquals(2, out[0xA8 + 3].toInt())
        val ref = bytes("ref_mi8.bin").copyOf().also { it[0x1E] = 4; it[0xA8 + 3] = 2 }
        assertArrayEquals(ref, out)
        // cabeçalho que o Gadgetbridge reconhece: 5A A5 e o id numérico em 0x28
        assertEquals(0x5A, out[0].toInt() and 0xff); assertEquals(0xA5, out[1].toInt() and 0xff)
        assertEquals("167210065", String(out, 0x28, 9, Charsets.US_ASCII))
    }

    @Test fun rleV10RepeteEV20MisturaLiteralERepeticao() {
        val v10 = XiaomiWatchfaceBuilder.Rle.v10(byteArrayOf(1, 2, 1, 2, 1, 2, 3, 4), 2)
        assertArrayEquals(byteArrayOf(3, 1, 2, 1, 3, 4), v10)
        val v20 = XiaomiWatchfaceBuilder.Rle.v20(byteArrayOf(5, 5, 5, 5, 1, 2))
        assertArrayEquals(byteArrayOf(4, 5, 0x82.toByte(), 1, 2), v20)
    }
}
