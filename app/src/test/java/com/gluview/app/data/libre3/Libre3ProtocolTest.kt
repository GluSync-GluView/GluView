package com.gluview.app.data.libre3

import com.gluview.app.data.libre3.Libre3Protocol.hex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/**
 * Libre 3 em Kotlin contra vetores independentes:
 * - CCM: gerados com a biblioteca `cryptography` do Python (AESCCM, tag 4, nonce 13 = formato do
 *   bcrypt.cpp do Juggluco);
 * - NFC: os exemplos REAIS que estão nos comentários do Juggluco (nfc.cpp e NFC.java);
 * - CRC da ativação: exemplo do NFC.java e os static_assert do dp_activation.hpp.
 */
class Libre3ProtocolTest {

    private val key = ByteArray(16) { (0x10 + it).toByte() }
    private val iv = hex("A1B2C3D4E5F60718")

    @Test fun `ccm encrypt reproduces python vectors with sequence counting from 1`() {
        val c = Libre3Crypto(key, iv)
        // 1º envio usa sequência 1 (outCryptoSequence começa em 1).
        assertArrayEquals(hex("50b154f0fed094a1e69a4c0100"), c.encrypt(0, hex("01000105000000")))
    }

    @Test fun `ccm decrypt of sensor packets matches python vectors`() {
        val c = Libre3Crypto(key, iv)
        val minute = c.decrypt(3, hex("0f50bf500995a5fdda13b497c0e66251b20b6d3f4b88a71f0e7d34113d1c80a5b20302"))
        assertArrayEquals(ByteArray(29) { it.toByte() }, minute)
        val status = c.decrypt(2, hex("21094c88f90d6d3c00895e9d9fc9fec30700"))
        assertArrayEquals(ByteArray(12) { it.toByte() }, status)
    }

    @Test fun `ccm rejects tampered packet and wrong kind`() {
        val c = Libre3Crypto(key, iv)
        val pkt = hex("21094c88f90d6d3c00895e9d9fc9fec30700")
        assertNull(c.decrypt(3, pkt)) // tipo errado → nonce errado → tag não confere
        val bad = pkt.copyOf().also { it[3] = (it[3].toInt() xor 1).toByte() }
        assertNull(c.decrypt(2, bad))
    }

    @Test fun `ccm round trip for every packet kind`() {
        val tx = Libre3Crypto(key, iv)
        for (kind in 0..7) {
            val plain = ByteArray(7 + kind) { (it * 3 + kind).toByte() }
            val pkt = tx.encrypt(kind, plain)!!
            assertEquals(plain.size + 6, pkt.size)
            assertArrayEquals(plain, Libre3Crypto(key, iv).decrypt(kind, pkt))
        }
    }

    @Test fun `app certificates are byte exact copies of KEYSCrypto`() {
        fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
        assertEquals(162, Libre3Protocol.APP_CERTIFICATES[0].size)
        assertEquals(162, Libre3Protocol.APP_CERTIFICATES[1].size)
        assertEquals("729fc87e2ef159ade7c06e872b90d11980d711cfeb8ced84cb647b13c7a98143", sha(Libre3Protocol.APP_CERTIFICATES[0]))
        assertEquals("3dbacabed0c7d0de1a946042e66299af8a3a842b1ae4def2f1bd38a967a68bca", sha(Libre3Protocol.APP_CERTIFICATES[1]))
    }

    @Test fun `first nfc answer from Juggluco comment is parsed`() {
        // nfc.cpp:36 — 00A500010001000000C04E1E0D0101040C043036385A4D524631382F97
        val info = Libre3Protocol.parseNfcInfo(hex("00A500010001000000C04E1E0D0101040C043036385A4D524631382F97"))!!
        assertEquals("068ZMRF18", info.serial)
        assertEquals(14 * 24 * 60, info.wearDurationMin)
        assertEquals(60, info.warmupMin)
        assertEquals(4, info.productType)
        assertEquals(4, info.state)
        assertEquals(1, info.securityVersion)
        assertTrue(!info.isLingo && !info.needsActivation)
    }

    @Test fun `activation data crc matches Juggluco example and static asserts`() {
        // NFC.java:72 — "8C 42 86 62 8D 6D 41 1F BC 93" = LE(hora-1), LE(conta), CRC.
        val data = Libre3Protocol.activationData(nowSec = 1652966029L, accountId = 524381581L)
        assertArrayEquals(hex("8C4286628D6D411FBC93"), data)
        assertEquals(0x313E, Libre3Protocol.crc16Activation(ByteArray(8)))
        assertEquals(0xCCBF, Libre3Protocol.crc16Activation(byteArrayOf(1, 0, 0, 0, 0, 0, 0, 0)))
        assertEquals(0x2063, Libre3Protocol.crc16Activation(byteArrayOf(8, 0, 0, 0, 12, 0, 0, 0)))
    }

    @Test fun `activation command chooses activate or takeover by state`() {
        val base = Libre3Protocol.parseNfcInfo(hex("00A500010001000000C04E1E0D0101040C043036385A4D524631382F97"))!!
        val cmdTakeover = Libre3Protocol.activationCommand(base, 1652966029L, 524381581L)
        assertArrayEquals(hex("02A87A8C4286628D6D411FBC93"), cmdTakeover)
        val fresh = base.copy(state = 1)
        assertEquals(0xA0.toByte(), Libre3Protocol.activationCommand(fresh, 1L, 0L)[1])
    }

    @Test fun `activation response from Juggluco comment gives mac pin and time`() {
        // nfc.cpp:157-172 — BD_Addr 11 1D B7 19 32 18 → 18:32:19:B7:1D:11, pin 9A948F6E, A_UTC 1651837599
        val info = Libre3Protocol.parseNfcInfo(hex("00A500010001000000C04E1E0D0101040C043036385A4D524631382F97"))!!
        val r = Libre3Protocol.parseActivationResponse(hex("00A500111DB71932189A948F6E9F0A7562A2FA"), info, 0L)
        r as Libre3Protocol.ActivationResult.Ok
        assertEquals("18:32:19:B7:1D:11", r.bleAddress)
        assertArrayEquals(hex("9A948F6E"), r.pin)
        assertEquals(1651837599L, r.activationSec)
    }

    @Test fun `activation error b1 means other account`() {
        val info = Libre3Protocol.parseNfcInfo(hex("00A500010001000000C04E1E0D0101040C043036385A4D524631382F97"))!!
        assertEquals(Libre3Protocol.ActivationResult.WrongAccount, Libre3Protocol.parseActivationResponse(hex("00A501B1"), info, 0L))
        assertEquals(Libre3Protocol.ActivationResult.SensorEnded,
            Libre3Protocol.parseActivationResponse(hex("00A50122"), info.copy(state = 8), 0L))
    }

    @Test fun `one minute struct follows bluetooth cpp layout and uncapped rules`() {
        val b = ByteArray(29)
        fun put16(o: Int, v: Int) { b[o] = v.toByte(); b[o + 1] = (v shr 8).toByte() }
        put16(0, 1234)        // lifeCount
        put16(2, 118)         // readingMgDl (capped)
        put16(4, -150)        // rateOfChange = -1.50 mg/dL/min
        put16(10, 1225)       // historicalLifeCount
        put16(12, 120)        // historicalReading
        b[14] = 0b1111_1010.toByte() // trend = 2 (3 bits baixos)
        put16(15, 30)         // uncappedCurrent → LO (39)
        put16(17, 700)        // uncappedHistoric → HI (501)
        val m = Libre3Protocol.parseOneMinute(b)!!
        assertEquals(1234, m.lifeCount)
        assertEquals(2, m.trend)
        assertEquals(-1.5, m.rateMgDlPerMin!!, 1e-9)
        assertEquals(39, m.currentMgDl)
        assertEquals(501, m.historicMgDl)
        put16(4, -32768)
        assertEquals((2 - 3.0) * 1.3, Libre3Protocol.parseOneMinute(b)!!.rateMgDlPerMin!!, 1e-9)
        put16(15, 0)
        assertNull(Libre3Protocol.parseOneMinute(b)!!.currentMgDl) // 0 = sem valor
        assertNull(Libre3Protocol.parseOneMinute(ByteArray(28)))
    }

    @Test fun `history fast data status and event log`() {
        val hist = byteArrayOf(0xE8.toByte(), 0x03, 100, 0, 20, 0, 0x2C, 0x01)
        assertEquals(listOf(1000 to 100, 1010 to 300), Libre3Protocol.parseHistory(hist))
        val fast = ByteArray(14).also { it[0] = 0xF4.toByte(); it[1] = 1; it[10] = 110; it[12] = 105 }
        val f = Libre3Protocol.parseFastData(fast)!!
        assertEquals(500, f.lifeCount); assertEquals(110, f.current); assertEquals(480, f.historicLifeCount)
        val st = byteArrayOf(1, 0, 2, 0, 3, 0, 9, 4, 0x10, 0x27, 0, 0)
        val p = Libre3Protocol.parsePatchStatus(st)!!
        assertEquals(10000, p.currentLifeCount); assertEquals(9, p.index); assertEquals(4, p.patchState)
        val log = ByteArray(14).also { it[6] = 5; it[13] = 0xFF.toByte() }
        assertEquals(5, Libre3Protocol.lastEventIndex(log))
        assertEquals(9980, Libre3Protocol.backfillStartHistoric(10000))
    }

    @Test fun `control commands match RequestData structs`() {
        assertArrayEquals(byteArrayOf(1, 0, 1, 5, 0, 0, 0), Libre3Protocol.controlHistory(1, 5))
        assertArrayEquals(byteArrayOf(1, 1, 1, 0x10, 0x27, 0, 0), Libre3Protocol.clinicalControl(1, 10000))
        assertArrayEquals(byteArrayOf(4, 7, 0, 0, 0, 0, 0), Libre3Protocol.eventLogControl(7))
        assertNotNull(Libre3Protocol.NFC_FIRST_COMMAND)
    }
}
