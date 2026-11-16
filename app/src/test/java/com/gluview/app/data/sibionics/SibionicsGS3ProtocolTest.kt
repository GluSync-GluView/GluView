package com.gluview.app.data.sibionics

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Testes de vetor para o protocolo Sibionics GS3 (V2 RC4 e V3 AES-OFB) e reset GS2 (F6.3).
 *
 * Referências no Juggluco 11.5.7:
 * - v3protocol.hpp:18-78 (constantes, IV, checksum, OFB)
 * - v3session.hpp:18-186 (sessão V3, DeviceTime, âncora, histórico)
 * - interpretgs3.cpp:764-797 (decisão V2/V3)
 * - handleData.cpp:49-56 (bytes de reset GS2 24 E7 6F 34)
 */
class SibionicsGS3ProtocolTest {

    private val testMac = "F6:09:FF:E2:20:B2"
    private val expectedAddress = byteArrayOf(
        0xF6.toByte(), 0x09.toByte(), 0xFF.toByte(), 0xE2.toByte(), 0x20.toByte(), 0xB2.toByte()
    )

    @Test
    fun testMacParsingAndIvDerivation() {
        val address = SibionicsGS3Protocol.parseMacToAddress(testMac)
        assertArrayEquals(expectedAddress, address)

        // v3protocol.hpp:28-32: make_iv inverte a ordem dos 6 bytes
        val iv = SibionicsGS3Protocol.makeIv(address)
        assertEquals(16, iv.size)
        assertEquals(0xB2.toByte(), iv[0])
        assertEquals(0x20.toByte(), iv[1])
        assertEquals(0xE2.toByte(), iv[2])
        assertEquals(0xFF.toByte(), iv[3])
        assertEquals(0x09.toByte(), iv[4])
        assertEquals(0xF6.toByte(), iv[5])
        for (i in 6 until 16) {
            assertEquals(0.toByte(), iv[i])
        }
    }

    @Test
    fun testChecksumCalculationAndValidation() {
        // v3protocol.hpp:40-50
        val frame = byteArrayOf(0x03, 0xF0.toByte(), 0x03, 0x00)
        SibionicsGS3Protocol.computeChecksum(frame)
        assertTrue(SibionicsGS3Protocol.isValidFrame(frame))

        // Checksum inválido
        frame[frame.size - 1] = (frame[frame.size - 1] + 1).toByte()
        assertFalse(SibionicsGS3Protocol.isValidFrame(frame))
    }

    @Test
    fun testOfbEncryptionRoundTrip() {
        val key = SibionicsGS3Protocol.TRANSPORT_KEY
        val iv = SibionicsGS3Protocol.makeIv(expectedAddress)
        val plain = "MensagemDeTesteComMaisDeDezesseisBytesParaGarantirBlocos".toByteArray(Charsets.UTF_8)

        val encrypted = SibionicsGS3Protocol.ofbCrypt(key, iv, plain)
        assertFalse(plain.contentEquals(encrypted))

        val decrypted = SibionicsGS3Protocol.ofbCrypt(key, iv, encrypted)
        assertArrayEquals(plain, decrypted)
    }

    @Test
    fun testRc4EncryptionRoundTrip() {
        val key = SibionicsGS3Protocol.RC4_KEY
        val plain = byteArrayOf(0x04, 0x00, 0x00, 0x00, 0xFC.toByte())

        val encrypted = SibionicsGS3Protocol.rc4Crypt(key, plain)
        val decrypted = SibionicsGS3Protocol.rc4Crypt(key, encrypted)
        assertArrayEquals(plain, decrypted)
    }

    @Test
    fun testAuthPlainStructure() {
        val account = SibionicsGS3Protocol.accountIdFromLong(987654321L)
        val authPlain = SibionicsGS3Protocol.buildAuthPlain(expectedAddress, account)

        assertEquals(38, authPlain.size)
        assertEquals(0x25.toByte(), authPlain[0])
        assertEquals(0xE2.toByte(), authPlain[1])
        assertEquals(0x00.toByte(), authPlain[2])
        // Endereço invertido em 3..8
        assertEquals(0xB2.toByte(), authPlain[3])
        assertEquals(0xF6.toByte(), authPlain[8])
        // Registro global em 9..24
        val regStr = String(authPlain.copyOfRange(9, 25), Charsets.US_ASCII)
        assertEquals("THE544U0TYITE461", regStr)
        assertTrue(SibionicsGS3Protocol.isValidFrame(authPlain))
    }

    @Test
    fun testQueryAndHistoryPlainStructure() {
        val q = SibionicsGS3Protocol.buildQueryPlain(0x0F)
        assertEquals(4, q.size)
        assertEquals(0x03.toByte(), q[0])
        assertEquals(0xF0.toByte(), q[1])
        assertEquals(0x0F.toByte(), q[2])
        assertTrue(SibionicsGS3Protocol.isValidFrame(q))

        val h = SibionicsGS3Protocol.buildHistoryPlain(10, 50)
        assertEquals(7, h.size)
        assertEquals(0x06.toByte(), h[0])
        assertEquals(0x39.toByte(), h[1])
        val buf = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(10, buf.getShort(2).toInt())
        assertEquals(50, buf.getShort(4).toInt())
        assertTrue(SibionicsGS3Protocol.isValidFrame(h))
    }

    @Test
    fun testGs2ResetBytesConstant() {
        // Juggluco handleData.cpp:49-56 / SiGattCallback.java:266-307: 24 E7 6F 34
        val expectedReset = byteArrayOf(0x24.toByte(), 0xE7.toByte(), 0x6F.toByte(), 0x34.toByte())
        assertArrayEquals(expectedReset, SibionicsV112Protocol.GS2_RESET_BYTES)
    }

    @Test
    fun testFullV3SessionClockAndHistoryGate() {
        val protocol = SibionicsGS3Protocol(
            mac = testMac,
            accountIdBytes = SibionicsGS3Protocol.DEFAULT_ACCOUNT_ID
        )

        var sentWire: ByteArray? = null
        protocol.onReady { sentWire = it }
        assertNotNull(sentWire)

        // Simula resposta de sucesso de autenticação: 04 e2 01 00 + checksum
        val iv = SibionicsGS3Protocol.makeIv(expectedAddress)
        val authReplyPlain = byteArrayOf(0x04, 0xE2.toByte(), 0x01, 0x00, 0x00)
        SibionicsGS3Protocol.computeChecksum(authReplyPlain)
        val authReplyWire = SibionicsGS3Protocol.ofbCrypt(SibionicsGS3Protocol.TRANSPORT_KEY, iv, authReplyPlain)

        var sentQuery: ByteArray? = null
        val readings0 = protocol.onPacket(authReplyWire) { sentQuery = it }
        assertTrue(readings0.isEmpty())
        assertNotNull(sentQuery) // Enviou query 0 (sub 0x0f)

        // Responde query 0x0f
        val q0ReplyPlain = byteArrayOf(0x04, 0xF0.toByte(), 0x0F, 0x00, 0x00)
        SibionicsGS3Protocol.computeChecksum(q0ReplyPlain)
        protocol.onPacket(SibionicsGS3Protocol.ofbCrypt(SibionicsGS3Protocol.TRANSPORT_KEY, iv, q0ReplyPlain)) {}

        // Responde query 0x0e (29 bytes)
        val q1ReplyPlain = ByteArray(29)
        q1ReplyPlain[0] = 0x1C
        q1ReplyPlain[1] = 0xF0.toByte()
        q1ReplyPlain[2] = 0x0E
        SibionicsGS3Protocol.computeChecksum(q1ReplyPlain)
        protocol.onPacket(SibionicsGS3Protocol.ofbCrypt(SibionicsGS3Protocol.TRANSPORT_KEY, iv, q1ReplyPlain)) {}

        // Responde query 0x0f de novo
        protocol.onPacket(SibionicsGS3Protocol.ofbCrypt(SibionicsGS3Protocol.TRANSPORT_KEY, iv, q0ReplyPlain)) {}

        // Responde query 0x03 (DeviceTime, 20 bytes)
        val dtPlain = ByteArray(20)
        dtPlain[0] = 0x13
        dtPlain[1] = 0xF0.toByte()
        dtPlain[2] = 0x03
        val dtBuf = ByteBuffer.wrap(dtPlain).order(ByteOrder.LITTLE_ENDIAN)
        dtBuf.putShort(3, 0)
        dtBuf.putInt(5, 1700000000) // activation_time
        dtBuf.putInt(9, 1700060000) // current_time
        dtBuf.putInt(13, 1700060000) // last_time
        dtBuf.putShort(17, 100) // last_index
        SibionicsGS3Protocol.computeChecksum(dtPlain)
        protocol.onPacket(SibionicsGS3Protocol.ofbCrypt(SibionicsGS3Protocol.TRANSPORT_KEY, iv, dtPlain)) {}

        // Responde query 0x11 (5 bytes)
        val q4ReplyPlain = byteArrayOf(0x04, 0xF0.toByte(), 0x11, 0x00, 0x00)
        SibionicsGS3Protocol.computeChecksum(q4ReplyPlain)
        protocol.onPacket(SibionicsGS3Protocol.ofbCrypt(SibionicsGS3Protocol.TRANSPORT_KEY, iv, q4ReplyPlain)) {}

        // Responde query 0x01 (8 bytes)
        val q5ReplyPlain = byteArrayOf(0x07, 0xF0.toByte(), 0x01, 0x00, 0x00, 0x00, 0x00, 0x00)
        SibionicsGS3Protocol.computeChecksum(q5ReplyPlain)
        protocol.onPacket(SibionicsGS3Protocol.ofbCrypt(SibionicsGS3Protocol.TRANSPORT_KEY, iv, q5ReplyPlain)) {}

        // Agora envia um registro ao vivo (cmd 0x32, 1 registro, remaining = 0)
        // Isso ancora o relógio!
        val liveRecordPlain = ByteArray(24) // 8 + 16 * 1
        liveRecordPlain[0] = 23
        liveRecordPlain[1] = 0x32
        liveRecordPlain[2] = 1 // count
        val recBuf = ByteBuffer.wrap(liveRecordPlain).order(ByteOrder.LITTLE_ENDIAN)
        recBuf.putShort(3, 101) // start index
        recBuf.putShort(5, 360) // temp
        recBuf.putShort(7, 0) // dump
        recBuf.putShort(9, 1500) // current
        recBuf.putShort(11, 125) // display_glucose = 125 mg/dL
        recBuf.putShort(13, 0) // algo wire
        liveRecordPlain[15] = 0 // trend
        liveRecordPlain[16] = 0
        recBuf.putShort(17, 3000) // ce_voltage
        recBuf.putShort(19, 1500) // re_voltage
        recBuf.putShort(21, 0) // tail remaining = 0
        SibionicsGS3Protocol.computeChecksum(liveRecordPlain)

        val readingsBeforeHistDone = protocol.onPacket(SibionicsGS3Protocol.ofbCrypt(SibionicsGS3Protocol.TRANSPORT_KEY, iv, liveRecordPlain)) {}
        // Regra de segurança A.7: ainda não emite porque historyDone é falso!
        assertTrue(readingsBeforeHistDone.isEmpty())

        // Envia pacote de encerramento de histórico (cmd 0x39 com count = 0)
        val histEndPlain = byteArrayOf(0x07, 0x39, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
        SibionicsGS3Protocol.computeChecksum(histEndPlain)
        val readingsAfterHistDone = protocol.onPacket(SibionicsGS3Protocol.ofbCrypt(SibionicsGS3Protocol.TRANSPORT_KEY, iv, histEndPlain)) {}

        // Agora sim a leitura deve ser emitida!
        assertEquals(1, readingsAfterHistDone.size)
        assertEquals(101, readingsAfterHistDone[0].index)
        assertEquals(125, readingsAfterHistDone[0].mgdl)
        assertEquals(3000, readingsAfterHistDone[0].batteryMv)
        assertEquals(1500, readingsAfterHistDone[0].rawCurrent)
    }
}
