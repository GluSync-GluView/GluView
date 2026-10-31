package com.gluview.app.data.medlevensohn

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Testes DETERMINÍSTICOS e puros (JVM) do protocolo Smart 2.0 (Microtech MD / AiDEX X / LinX).
 *
 * O vetor de advertisement abaixo é REAL — não sintético: é o array de bytes exato usado no
 * autoteste do próprio Juggluco (`aidexx/crc.cpp`, bloco `TEST2`), capturado de um sensor
 * "AiDEX X-22222A2WC8" de verdade. Como o CRC32 embutido no pacote É um valor observado (não
 * inventado por mim), bater com ele é uma prova muito mais forte que um vetor sintético
 * derivado só da spec — equivalente ao que a suíte do Sibionics chama de "oráculo".
 *
 * O vetor AES-128-CFB128 é o vetor público NIST SP 800-38A (Apêndice F, CFB128-AES128, bloco 1) —
 * independente do Juggluco, usado só para provar que `javax.crypto` com "AES/CFB128/NoPadding"
 * implementa o mesmo modo que o `EVP_aes_128_cfb128` do OpenSSL usado pelo sensor.
 *
 * O restante (derivação de IV/chave a partir do serial, CRC8/CRC16) ainda não tem vetor
 * independente disponível — testado por autoconsistência (determinismo/sensibilidade a bytes),
 * mesmo padrão já usado em `Libre2CryptoTest`. Validação byte-a-byte fica para quando o sensor
 * chegar (ver TODO de oráculo em [MedLevensohnBleManager]).
 */
class MedLevensohnProtocolTest {

    // ── Advertisement real (aidexx/crc.cpp TEST2) ──────────────────────────────

    private val realAdvertisement = intArrayOf(
        0x02, 0x01, 0x06, 0x03, 0x02, 0x1F, 0x18, 0x17, 0xFF, 0x59, 0x00,
        0x60, 0x54, 0x01, 0x00, 0x1F, 0x5C, 0x80, 0x3E, 0x54, 0x80, 0x3A, 0x4E, 0x80, 0x36, 0x00, 0x00,
        0x7E, 0xAE, 0xDD, 0x01,
        0x13, 0x09, 0x41, 0x69, 0x44, 0x45, 0x58, 0x20, 0x58, 0x2D, 0x32, 0x32, 0x32, 0x32, 0x32, 0x41, 0x32, 0x57, 0x43, 0x38,
        0x08, 0xFF, 0x59, 0x00, 0x00, 0xF4, 0x05, 0x8A, 0x36, 0x00, 0x00
    ).map { it.toByte() }.toByteArray()

    @Test
    fun `advertisement real tem exatamente 62 bytes`() {
        assertEquals(62, realAdvertisement.size)
        assertEquals(MedLevensohnAdvertisement.EXPECTED_SIZE, realAdvertisement.size)
    }

    @Test
    fun `advertisement real bate com o CRC32 embutido no proprio pacote`() {
        val adv = MedLevensohnAdvertisement.parse(realAdvertisement)
        assertTrue("parse() não deveria retornar null", adv != null)
        assertTrue("CRC32 calculado deve bater com o embutido no pacote real", adv!!.crcOk)
    }

    @Test
    fun `advertisement real decodifica nome, glicemia atual e historico curto`() {
        val adv = MedLevensohnAdvertisement.parse(realAdvertisement)!!
        assertEquals("AiDEX X-22222A2WC8", adv.deviceName)
        assertEquals(21600, adv.last.minFromStart) // 15 dias em minutos — sensor no fim da vida
        assertEquals(92, adv.last.glucoseMgDl)
        assertTrue(adv.last.valid)
        assertEquals(2, adv.previous.size)
        assertEquals(84, adv.previous[0].glucoseMgDl)
        assertEquals(78, adv.previous[1].glucoseMgDl)
    }

    @Test
    fun `advertisement corrompido reprova o CRC32`() {
        val corrupted = realAdvertisement.copyOf()
        corrupted[12] = (corrupted[12] + 1).toByte() // mexe num byte do payload protegido
        val adv = MedLevensohnAdvertisement.parse(corrupted)!!
        assertFalse(adv.crcOk)
    }

    @Test
    fun `advertisement curto demais retorna null`() {
        assertEquals(null, MedLevensohnAdvertisement.parse(ByteArray(30)))
    }

    // ── AES-128-CFB128 (vetor público NIST SP 800-38A, independente do Juggluco) ─

    @Test
    fun `AES-CFB128 bate com o vetor publico NIST SP800-38A`() {
        val key = hex("2b7e151628aed2a6abf7158809cf4f3c")
        val iv = hex("000102030405060708090a0b0c0d0e0f")
        val plaintext = hex("6bc1bee22e409f96e93d7e117393172a")
        val expectedCiphertext = hex("3b3fd92eb72dad20333449f8e83cfb4a")

        val ciphertext = MedLevensohnCrypto.aesCfb128Encrypt(plaintext, key, iv)
        assertArrayEquals(expectedCiphertext, ciphertext)

        val decrypted = MedLevensohnCrypto.aesCfb128Decrypt(expectedCiphertext, key, iv)
        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun `AES-CFB128 curto (menos de 1 bloco) faz round-trip`() {
        val key = hex("2b7e151628aed2a6abf7158809cf4f3c")
        val iv = hex("000102030405060708090a0b0c0d0e0f")
        val plaintext = byteArrayOf(0x10, 0xC1.toByte(), 0xF3.toByte()) // formato real de comando (3 bytes)
        val ciphertext = MedLevensohnCrypto.aesCfb128Encrypt(plaintext, key, iv)
        assertEquals(plaintext.size, ciphertext.size)
        assertArrayEquals(plaintext, MedLevensohnCrypto.aesCfb128Decrypt(ciphertext, key, iv))
    }

    // ── Derivação de IV/chave a partir do serial (autoconsistência — sem vetor independente ainda) ─

    @Test
    fun `deriveIv e deriveAskKeyPayload sao deterministicos e de 16 bytes`() {
        val serial = "22222A2WC8" // mesmo serial do advertisement real acima
        val iv1 = MedLevensohnCrypto.deriveIv(serial)
        val iv2 = MedLevensohnCrypto.deriveIv(serial)
        assertArrayEquals(iv1, iv2)
        assertEquals(16, iv1.size)

        val key1 = MedLevensohnCrypto.deriveAskKeyPayload(serial)
        assertEquals(16, key1.size)
        assertNotEquals(iv1.toList(), key1.toList()) // multiplicadores diferentes (17/0x13 vs 13/61) devem divergir
    }

    @Test
    fun `deriveIv muda se o serial mudar`() {
        val a = MedLevensohnCrypto.deriveIv("22222A2WC8")
        val b = MedLevensohnCrypto.deriveIv("99999B9XY1")
        assertNotEquals(a.toList(), b.toList())
    }

    // ── CRCs (autoconsistência — CRC32 já validado acima com dado real) ────────

    @Test
    fun `crc16CcittFalse e deterministico e sensivel aos dados`() {
        val a = byteArrayOf(0x23, 0x01, 0x00)
        val b = byteArrayOf(0x23, 0x01, 0x01)
        assertEquals(MedLevensohnCrc.crc16CcittFalse(a), MedLevensohnCrc.crc16CcittFalse(a))
        assertNotEquals(MedLevensohnCrc.crc16CcittFalse(a), MedLevensohnCrc.crc16CcittFalse(b))
    }

    @Test
    fun `crc8Maxim e deterministico e sensivel aos dados`() {
        val a = byteArrayOf(0x11, 0x22, 0x33, 0x44)
        val b = byteArrayOf(0x11, 0x22, 0x33, 0x45)
        assertEquals(MedLevensohnCrc.crc8Maxim(a), MedLevensohnCrc.crc8Maxim(a))
        assertNotEquals(MedLevensohnCrc.crc8Maxim(a), MedLevensohnCrc.crc8Maxim(b))
        assertTrue(MedLevensohnCrc.crc8Maxim(a) in 0..255)
    }

    // ── Structs de glicemia ─────────────────────────────────────────────────────

    @Test
    fun `CurrentGlucose parse extrai glicemia e trend`() {
        // 15 bytes sintéticos: type=1, trend=-5, minFromStart=100, glucose=120 (valid, sem warmup)
        val glucoseWord = 120 or (0 shl 10) or (1 shl 15)
        val bytes = ByteArray(15)
        bytes[0] = 1
        bytes[3] = (-5).toByte()
        bytes[4] = (100 and 0xFF).toByte(); bytes[5] = ((100 shr 8) and 0xFF).toByte()
        bytes[6] = (glucoseWord and 0xFF).toByte(); bytes[7] = ((glucoseWord shr 8) and 0xFF).toByte()
        val cur = CurrentGlucose.parse(bytes)
        assertTrue(cur != null)
        assertEquals(1, cur!!.type)
        assertEquals(-5, cur.trend)
        assertEquals(100, cur.minFromStart)
        assertEquals(120, cur.glucoseMgDl)
        assertTrue(cur.valid)
        assertFalse(cur.warmup)
    }

    @Test
    fun `CurrentGlucose parse retorna null se curto demais`() {
        assertEquals(null, CurrentGlucose.parse(ByteArray(10)))
    }

    @Test
    fun `extractSerial pega o que vem depois do ultimo hifen`() {
        assertEquals("22222A2WC8", MedLevensohnGatt.extractSerial("AiDEX X-22222A2WC8"))
        assertEquals("ABC123", MedLevensohnGatt.extractSerial("Smart-ABC123"))
        assertEquals("XYZ", MedLevensohnGatt.extractSerial("Lumi-XYZ"))
    }

    @Test
    fun `isMicrotechName reconhece os 4 prefixos conhecidos`() {
        assertTrue(MedLevensohnGatt.isMicrotechName("Smart-ABC123"))
        assertTrue(MedLevensohnGatt.isMicrotechName("AiDEX X-22222A2WC8"))
        assertTrue(MedLevensohnGatt.isMicrotechName("LinX-XYZ"))
        assertTrue(MedLevensohnGatt.isMicrotechName("Lumi-XYZ"))
        assertFalse(MedLevensohnGatt.isMicrotechName("ACCU-CHEK SmartGuide"))
        assertFalse(MedLevensohnGatt.isMicrotechName(null))
    }

    private fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}
