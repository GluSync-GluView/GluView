package com.gluview.wearables.xiaomi.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F7.4 — vetores gerados de forma INDEPENDENTE em Python (hmac + cryptography AESCCM), não com este código:
 * chave 00112233…ff, nonce do celular 00..0f, nonce da pulseira 10..1f.
 */
class XiaomiProtocolTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    private val secret = hex("00112233445566778899aabbccddeeff")
    private val phone = ByteArray(16) { it.toByte() }
    private val watch = ByteArray(16) { (it + 16).toByte() }
    private val watchHmac = hex("1e256c28d3cf6309487c499ff2b7381f3f67298718a8336dbddaa486e4f9333e")

    @Test fun derivacaoIgualAoPython() {
        assertArrayEquals(hex("a2cf6c33c13b444edf33b4d1a3b3a4c1644de586a875a5817a40ba4a92361a464f584e38f35dd40a077d0e5ce19184b929e3e1c203c66d760e920b4daad6f9d2"),
            XiaomiSession.derive(secret, phone, watch))
    }

    @Test fun hmacDaPulseiraConfereEChaveErradaNao() {
        val s = XiaomiSession.fromWatch(secret, phone, watch, watchHmac)
        assertNotNull(s)
        assertArrayEquals(hex("a42538060b853135c3366dbc790a777cff061c5475f2c7cb08e42da2b6f95b4b"), s!!.confirmNonces(phone, watch))
        assertNull(XiaomiSession.fromWatch(ByteArray(16), phone, watch, watchHmac))
    }

    @Test fun ccmIgualAoPythonEDetectaAlteracao() {
        val s = XiaomiSession.fromWatch(secret, phone, watch, watchHmac)!!
        val ct = s.encrypt("GluView 123 mg/dL".toByteArray(), 5)
        assertArrayEquals(hex("8b18f44d32bc92cfe585316acf82e665e70f7aa8db"), ct)
        // Ida e volta com a mesma chave/nonce (a pulseira usaria a chave do outro lado).
        val nonce = s.encryptionNonce + XiaomiSession.le32(0) + XiaomiSession.le32(5)
        assertEquals("GluView 123 mg/dL", String(XiaomiCcm.decrypt(s.encryptionKey, nonce, ct)!!))
        assertNull(XiaomiCcm.decrypt(s.encryptionKey, nonce, ct.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }))
    }

    @Test fun comandoDoNonceNoFormatoProtobuf() {
        val b = XiaomiMessages.nonceCommand(phone)
        // 08 01 (type 1) 10 1a (subtype 26) 1a 15 (auth, 21 bytes) f2 01 12 (appVerify=30, 18) 0a 10 + nonce
        assertArrayEquals(hex("0801101a1a15f201120a10") + phone, b)
        val back = XiaomiMessages.parseCommand(b)
        assertEquals(1, back.type); assertEquals(26, back.subtype)
    }

    @Test fun respostaDaPulseiraEhLida() {
        val dv = XiaomiMessages.Writer().bytes(1, watch).bytes(2, watchHmac).build()
        val acc = XiaomiMessages.Writer().bytes(31, dv).build()
        val cmd = XiaomiMessages.Writer().uint(1, 1).uint(2, 26).bytes(3, acc).build()
        val c = XiaomiMessages.parseCommand(cmd)
        assertArrayEquals(watch, c.watchNonce); assertArrayEquals(watchHmac, c.watchHmac)
    }

    @Test fun notificacaoTemTituloECorpo() {
        val n = XiaomiMessages.notificationCommand(7, "Glicose baixa", "62 mg/dL ↓", 0L)
        val top = XiaomiMessages.parse(n)
        assertEquals(7L, top[1]!!.first()); assertEquals(0L, top[2]!!.first())
        val n3 = XiaomiMessages.parse(XiaomiMessages.parse(XiaomiMessages.parse(top[9]!!.first() as ByteArray)[3]!!.first() as ByteArray)[1]!!.first() as ByteArray)
        assertEquals("Glicose baixa", String(n3[3]!!.first() as ByteArray))
        assertEquals("62 mg/dL ↓", String(n3[5]!!.first() as ByteArray, Charsets.UTF_8))
        assertEquals("com.gluview.app", String(n3[1]!!.first() as ByteArray))
    }

    @Test fun enquadramentoV1() {
        assertArrayEquals(hex("00000202") + byteArrayOf(9), XiaomiV1Frames.single(byteArrayOf(9), encrypted = false))
        assertArrayEquals(hex("000002010300") + byteArrayOf(9), XiaomiV1Frames.single(byteArrayOf(9), encrypted = true, index = 3))
        val parts = XiaomiV1Frames.chunks(ByteArray(500) { it.toByte() }, 244)
        assertEquals(3, parts.size)
        assertArrayEquals(hex("0100"), parts[0].copyOf(2)); assertEquals(244, parts[0].size)
        assertArrayEquals(hex("0300"), parts[2].copyOf(2)); assertEquals(2 + 500 - 2 * 242, parts[2].size)
        assertArrayEquals(hex("000000010300"), XiaomiV1Frames.chunkStart(3, encrypted = true))
        assertEquals(XiaomiV1Frames.Incoming.Result(true), XiaomiV1Frames.parse(XiaomiV1Frames.ACK))
        assertEquals(XiaomiV1Frames.Incoming.ReadyForChunks, XiaomiV1Frames.parse(XiaomiV1Frames.CHUNK_START_ACK))
        val inc = XiaomiV1Frames.parse(hex("00000202aabb")) as XiaomiV1Frames.Incoming.Command
        assertTrue(!inc.encrypted); assertArrayEquals(hex("aabb"), inc.payload)
        assertTrue(XiaomiV1Frames.fitsSingle(238, 244)); assertTrue(!XiaomiV1Frames.fitsSingle(239, 244))
    }

    // ---- V2 (Mi Band 9 Active): vetores do simulador Python (crc16 refletido 0xA001, cryptography AES-CTR)
    @Test fun crcDoV2EhCrc16Arc() {
        assertEquals(0xbb3d, XiaomiV2Frames.checksum("123456789".toByteArray()))
    }

    @Test fun enquadramentoV2() {
        assertArrayEquals(hex("a5a5020016001d4d0101030001000002020000fc03020020000402001027"), XiaomiV2Frames.startSession(0))
        val f = XiaomiV2Frames.data(7, XiaomiV2Frames.OPCODE_ENCRYPTED, "abc".toByteArray())
        assertArrayEquals(hex("a5a50307050004ef0102616263"), f)
        // Lixo antes, pacote completo, pedaço incompleto depois.
        val buf = hex("0011") + f + f.copyOf(5)
        val (junk, skip) = XiaomiV2Frames.take(buf)
        assertNull(junk); assertEquals(2, skip)
        val (p, used) = XiaomiV2Frames.take(buf.copyOfRange(2, buf.size))
        assertEquals(f.size, used)
        assertEquals(XiaomiV2Frames.TYPE_DATA, p!!.type); assertEquals(7, p.seq)
        assertEquals(1, p.channel); assertEquals(2, p.opcode); assertArrayEquals("abc".toByteArray(), p.body)
        assertEquals(0, XiaomiV2Frames.take(f.copyOf(5)).second)
        // CRC errado: descarta o pacote inteiro.
        val bad = f.copyOf().also { it[it.size - 1] = 0 }
        assertEquals(null to bad.size, XiaomiV2Frames.take(bad).let { it.first to it.second })
    }

    @Test fun ctrDoV2IgualAoPython() {
        val s = XiaomiSession.fromWatch(secret, phone, watch, watchHmac)!!
        assertArrayEquals(hex("9fab7913b10bc365d02252c1a604341f57"), s.encryptV2("GluView 123 mg/dL".toByteArray()))
        assertEquals("GluView 123 mg/dL", String(s.decryptV2(hex("7f6768fafabf7deb1352a901d8342ffd79"))))
    }

    @Test fun vibracaoPersonalizada() {
        val c = XiaomiMessages.parse(XiaomiMessages.vibrationCommand(longArrayOf(400, 250, 400), 80))
        assertEquals(2L, c[1]!!.first()); assertEquals(59L, c[2]!!.first())
        val test = XiaomiMessages.parse(XiaomiMessages.parse(c[4]!!.first() as ByteArray)[41]!!.first() as ByteArray)
        val parts = test[1]!!.map { XiaomiMessages.parse(it as ByteArray) }
        assertEquals(3, parts.size)
        assertEquals(1L, parts[0][1]!!.first()); assertEquals(400L, parts[0][2]!!.first()); assertEquals(80L, parts[0][3]!!.first())
        assertEquals(0L, parts[1][1]!!.first()); assertEquals(250L, parts[1][2]!!.first()); assertNull(parts[1][3])
    }
}
