package com.gluview.app.data.libre2

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Testa as partes DETERMINÍSTICAS e puras (JVM) da cripto do Libre 2 — sem Android, sem org.json.
 * As transformações marcadas como SEAM (a validar com hardware) são testadas pelas suas propriedades
 * estruturais (reversibilidade, estabilidade), não por valores de glicemia reais.
 */
class Libre2CryptoTest {

    private val uid = hexToBytes("e07c0000123456ab")   // 8 bytes de exemplo
    private val patchInfo = hexToBytes("9d0830010c1e")  // 6 bytes de exemplo (família 0x9d)

    @Test
    fun `hex round-trip`() {
        val bytes = byteArrayOf(0x00, 0x0f, 0x7f, 0xff.toByte(), 0xa1.toByte())
        assertArrayEquals(bytes, hexToBytes(bytes.toHex()))
    }

    @Test
    fun `hexToBytes tolera espacos e prefixo 0x`() {
        assertArrayEquals(byteArrayOf(0x0a, 0xbc.toByte()), hexToBytes("0x0A BC"))
    }

    @Test
    fun `crc16 e deterministico e sensivel aos dados`() {
        val a = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val b = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 9)
        assertEquals(Libre2Crypto.crc16(a, 0, a.size), Libre2Crypto.crc16(a, 0, a.size))
        assertNotEquals(Libre2Crypto.crc16(a, 0, a.size), Libre2Crypto.crc16(b, 0, b.size))
    }

    @Test
    fun `decodeSerial e estavel e nao-vazio`() {
        val s1 = Libre2Crypto.decodeSerial(uid, patchInfo)
        val s2 = Libre2Crypto.decodeSerial(uid, patchInfo)
        assertEquals(s1, s2)
        assertTrue("serial não deveria ser vazio", s1.isNotEmpty())
        // Família 0x9d → prefixo "3".
        assertTrue("serial deveria começar com o prefixo da família", s1.startsWith("3"))
    }

    @Test
    fun `keystream do decrypt e reversivel (involucao XOR)`() {
        val data = hexToBytes("00112233445566778899aabbccddeeff")
        val enc = Libre2Crypto.decryptBleValue(uid, patchInfo, 7, data)
        val dec = Libre2Crypto.decryptBleValue(uid, patchInfo, 7, enc)
        assertArrayEquals("aplicar o keystream 2× deve devolver o original", data, dec)
        assertNotEquals("o keystream deve de fato alterar os dados", data.toHex(), enc.toHex())
    }

    @Test
    fun `unlock payload muda com o contador`() {
        val u0 = Libre2Crypto.streamingUnlockPayload(uid, patchInfo, 42, 0)
        val u1 = Libre2Crypto.streamingUnlockPayload(uid, patchInfo, 42, 1)
        assertEquals("payload de unlock tem 4 bytes", 4, u0.size)
        assertNotEquals("contador diferente deve gerar payload diferente", u0.toHex(), u1.toHex())
    }
}
