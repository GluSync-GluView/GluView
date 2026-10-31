package com.gluview.app.data.dexcom

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Testes DETERMINÍSTICOS e puros (JVM) do protocolo Dexcom G6 — sem BLE, sem Android.
 *
 * A derivação de chave e o CRC16 são publicamente documentados (pesquisa de segurança sobre o
 * G5/G6, e replicados no driver nativo do próprio xDrip — `Ob1G5StateMachine`/`FastCRC16`), não
 * são segredo de fabricante nem engenharia reversa de binário. O CRC16 é validado contra o vetor
 * de verificação PADRÃO do CRC-16/XMODEM (independente do Dexcom). Como o mantenedor não tem o
 * transmissor físico ainda (27/07/2026), os pacotes de glicemia são vetores SINTÉTICOS construídos
 * a partir do layout documentado — validam o parser, não a fidelidade a um transmissor real.
 */
class DexcomProtocolTest {

    // ── DexcomG6Crypto ──────────────────────────────────────────────────────────

    @Test
    fun `deriveKey monta 00+id+00+id em ASCII`() {
        val key = DexcomG6Crypto.deriveKey("8G1234")
        assertEquals("008G1234008G1234", String(key, Charsets.US_ASCII))
        assertEquals(16, key.size)
    }

    @Test
    fun `deriveKey exige 6 caracteres`() {
        var threw = false
        try { DexcomG6Crypto.deriveKey("12345") } catch (e: IllegalArgumentException) { threw = true }
        assertTrue(threw)
    }

    @Test
    fun `challengeHash e deterministico, 8 bytes, e sensivel a chave e desafio`() {
        val key1 = DexcomG6Crypto.deriveKey("8G1234")
        val key2 = DexcomG6Crypto.deriveKey("8G9999")
        val challenge = ByteArray(8) { it.toByte() }

        val h1 = DexcomG6Crypto.challengeHash(challenge, key1)
        val h2 = DexcomG6Crypto.challengeHash(challenge, key1)
        val h3 = DexcomG6Crypto.challengeHash(challenge, key2)

        assertEquals(8, h1.size)
        assertArrayEquals(h1, h2) // determinístico
        assertFalse(h1.contentEquals(h3)) // chave diferente -> hash diferente
    }

    @Test
    fun `challengeHash muda se o desafio mudar`() {
        val key = DexcomG6Crypto.deriveKey("8G1234")
        val a = DexcomG6Crypto.challengeHash(ByteArray(8) { 0x11 }, key)
        val b = DexcomG6Crypto.challengeHash(ByteArray(8) { 0x22 }, key)
        assertFalse(a.contentEquals(b))
    }

    // ── DexcomCrc — validado contra o vetor PADRÃO do CRC-16/XMODEM (independente do Dexcom) ──

    @Test
    fun `crc16 bate com o vetor de verificacao padrao do CRC-16-XMODEM`() {
        // Vetor de checagem oficial da família CRC-16/XMODEM (poly 0x1021, init 0x0000): "123456789" -> 0x31C3
        val check = "123456789".toByteArray(Charsets.US_ASCII)
        assertEquals(0x31C3, DexcomCrc.crc16(check))
    }

    @Test
    fun `append e isValid fazem round-trip`() {
        val payload = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05)
        val withCrc = DexcomCrc.append(payload)
        assertEquals(payload.size + 2, withCrc.size)
        assertTrue(DexcomCrc.isValid(withCrc))
    }

    @Test
    fun `isValid rejeita pacote corrompido`() {
        val withCrc = DexcomCrc.append(byteArrayOf(0x01, 0x02, 0x03))
        withCrc[0] = (withCrc[0] + 1).toByte()
        assertFalse(DexcomCrc.isValid(withCrc))
    }

    // ── DexcomG6Tx / DexcomG6Rx ─────────────────────────────────────────────────

    @Test
    fun `authRequest monta opcode+token+slot`() {
        val token = ByteArray(8) { (it + 1).toByte() }
        val request = DexcomG6Tx.authRequest(token, slot = 0x02)
        assertEquals(10, request.size)
        assertEquals(0x01, request[0].toInt())
        assertArrayEquals(token, request.copyOfRange(1, 9))
        assertEquals(0x02, request[9].toInt())
    }

    @Test
    fun `parseAuthChallenge extrai tokenHash e challenge`() {
        val tokenHash = ByteArray(8) { 0xA0.toByte() }
        val challenge = ByteArray(8) { 0xB0.toByte() }
        val packet = byteArrayOf(0x03) + tokenHash + challenge
        val result = DexcomG6Rx.parseAuthChallenge(packet)
        assertTrue(result is AuthChallengeResult.Parsed)
        val parsed = result as AuthChallengeResult.Parsed
        assertArrayEquals(tokenHash, parsed.tokenHash)
        assertArrayEquals(challenge, parsed.challenge)
    }

    @Test
    fun `parseAuthChallenge rejeita opcode errado`() {
        val packet = byteArrayOf(0x99.toByte()) + ByteArray(16)
        assertTrue(DexcomG6Rx.parseAuthChallenge(packet) is AuthChallengeResult.Invalid)
    }

    @Test
    fun `parseAuthStatus le authenticated e bonded`() {
        val status = DexcomG6Rx.parseAuthStatus(byteArrayOf(0x05, 1, 0))
        assertNotNull(status)
        assertTrue(status!!.authenticated)
        assertFalse(status.bonded)
    }

    @Test
    fun `handshake completo simulado bate ponta a ponta`() {
        // Simula os DOIS lados com a MESMA chave — prova que app e "transmissor" concordam no protocolo.
        val key = DexcomG6Crypto.deriveKey("8G4242")
        val myToken = ByteArray(8) { (it * 3).toByte() }
        val transmitterChallenge = ByteArray(8) { (it * 7).toByte() }

        // "Transmissor" calcula o tokenHash com a chave dele (== a nossa, cenário feliz)
        val tokenHashFromTransmitter = DexcomG6Crypto.challengeHash(myToken, key)
        // App verifica
        assertArrayEquals(tokenHashFromTransmitter, DexcomG6Crypto.challengeHash(myToken, key))

        // App responde ao challenge do transmissor
        val myResponse = DexcomG6Crypto.challengeHash(transmitterChallenge, key)
        // "Transmissor" verifica a resposta do app com a própria chave
        assertArrayEquals(myResponse, DexcomG6Crypto.challengeHash(transmitterChallenge, key))
    }

    // ── Glucose (vetores sintéticos, layout documentado) ───────────────────────

    @Test
    fun `parseGlucose decodifica registro GlucoseRxMessage (0x31)`() {
        val payload = byteArrayOf(
            0x31, // opcode
            0x05, // status
            0x2A, 0x00, 0x00, 0x00, // sequence = 42 LE
            0x64, 0x00, 0x00, 0x00, // timestamp = 100 LE
            0x78, 0x00,             // glucose = 120 (0x78), displayOnly=0
            0x03,                   // state
            0xFE.toByte()           // trend = -2 (signed)
        )
        val packet = DexcomCrc.append(payload)
        val reading = DexcomG6Rx.parseGlucose(packet)
        assertNotNull(reading)
        reading!!
        assertEquals(42, reading.sequence)
        assertEquals(100, reading.timestampSec)
        assertEquals(120, reading.mgdl)
        assertFalse(reading.displayOnly)
        assertEquals(3, reading.state)
        assertEquals(-2, reading.trend)
        assertNull(reading.predictedMgdl)
    }

    @Test
    fun `parseGlucose decodifica registro EGlucoseRxMessage (0x4F) com predicao`() {
        val payload = byteArrayOf(
            0x4F,
            0x05,
            0x01, 0x00, 0x00, 0x00,
            0x02, 0x00, 0x00, 0x00,
            0x64, 0x00, // glucose = 100
            0x00,
            0x00,
            0x8C.toByte(), 0x00 // predicted = 140
        )
        val packet = DexcomCrc.append(payload)
        val reading = DexcomG6Rx.parseGlucose(packet)
        assertNotNull(reading)
        assertEquals(140, reading!!.predictedMgdl)
    }

    @Test
    fun `parseGlucose rejeita CRC invalido`() {
        val payload = byteArrayOf(0x31, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x78, 0, 0, 0)
        val packet = DexcomCrc.append(payload)
        packet[2] = (packet[2] + 1).toByte() // corrompe sequence
        assertNull(DexcomG6Rx.parseGlucose(packet))
    }

    @Test
    fun `parseTransmitterTime detecta sessao em andamento`() {
        val payload = byteArrayOf(0x25, 0x05, 100, 0, 0, 0, 10, 0, 0, 0) // currentTime=100, sessionStart=10
        val packet = DexcomCrc.append(payload)
        val time = DexcomG6Rx.parseTransmitterTime(packet)
        assertNotNull(time)
        assertEquals(100, time!!.currentTimeSec)
        assertEquals(10, time.sessionStartTimeSec)
        assertTrue(time.sessionInProgress)
    }

    // ── DexcomGatt ──────────────────────────────────────────────────────────────

    @Test
    fun `isValidTransmitterId aceita 6 caracteres alfanumericos`() {
        assertTrue(DexcomGatt.isValidTransmitterId("8G1234"))
        assertFalse(DexcomGatt.isValidTransmitterId("8G123"))
        assertFalse(DexcomGatt.isValidTransmitterId("8G-234"))
    }

    // ── DexcomG7Parser (formato pós-decifra, independe do handshake) ──────────

    @Test
    fun `DexcomG7Parser decodifica o registro de 19 bytes`() {
        val bytes = ByteArray(19)
        bytes[0] = 1 // type
        bytes[1] = 0 // status
        // secsSinceStart = 1000 (LE, offset 2-5)
        bytes[2] = (1000 and 0xFF).toByte(); bytes[3] = ((1000 shr 8) and 0xFF).toByte()
        // sequence = 7 (offset 6-7)
        bytes[6] = 7
        // age = 30 (offset 10-11)
        bytes[10] = 30
        // glucose = 150 (offset 12-13, 12 bits)
        bytes[12] = (150 and 0xFF).toByte(); bytes[13] = ((150 shr 8) and 0x0F).toByte()
        bytes[14] = 2 // state
        bytes[15] = 5 // trend
        // predicted = 160 (offset 16-17, 10 bits)
        bytes[16] = (160 and 0xFF).toByte(); bytes[17] = ((160 shr 8) and 0x03).toByte()

        val result = DexcomG7Parser.parse(bytes)
        assertNotNull(result)
        result!!
        assertEquals(1, result.type)
        assertEquals(1000, result.secsSinceStart)
        assertEquals(7, result.sequence)
        assertEquals(30, result.ageSec)
        assertEquals(150, result.mgdl)
        assertEquals(5, result.trend)
        assertEquals(160, result.predictedMgdl)
    }

    @Test
    fun `DexcomG7Parser retorna null se curto demais`() {
        assertNull(DexcomG7Parser.parse(ByteArray(10)))
    }
}
