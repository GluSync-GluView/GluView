/*  Copyright (C) 2023-2024 José Rebelo, Yoran Vulker e colaboradores do Gadgetbridge

    This file is part of Gadgetbridge (derived work, adapted for GluView).

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Gadgetbridge is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>.

    Adaptado de service/devices/xiaomi/XiaomiAuthService.java para o GluView — plano 21, F7.4 (22/11/2026). */
package com.gluview.wearables.xiaomi.protocol

import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * AES-CCM sem dados associados, como o Gadgetbridge usa com a Xiaomi (BouncyCastle CCMBlockCipher, MAC de
 * 32 bits, nonce de 12 bytes). Feito sobre AES/ECB porque o Android não expõe CCM no JCE.
 */
object XiaomiCcm {
    private fun aes(key: ByteArray): Cipher =
        Cipher.getInstance("AES/ECB/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES")) }

    private fun ctrBlock(nonce: ByteArray, l: Int, i: Int): ByteArray {
        val a = ByteArray(16)
        a[0] = (l - 1).toByte()
        System.arraycopy(nonce, 0, a, 1, nonce.size)
        var v = i
        for (k in 15 downTo 16 - l) { a[k] = (v and 0xff).toByte(); v = v ushr 8 }
        return a
    }

    private fun mac(c: Cipher, nonce: ByteArray, l: Int, tagLen: Int, plain: ByteArray): ByteArray {
        val b0 = ByteArray(16)
        b0[0] = ((((tagLen - 2) / 2) shl 3) or (l - 1)).toByte()
        System.arraycopy(nonce, 0, b0, 1, nonce.size)
        var len = plain.size
        for (k in 15 downTo 16 - l) { b0[k] = (len and 0xff).toByte(); len = len ushr 8 }
        var x = c.doFinal(b0)
        var off = 0
        while (off < plain.size) {
            val blk = ByteArray(16)
            System.arraycopy(plain, off, blk, 0, minOf(16, plain.size - off))
            for (k in 0 until 16) blk[k] = (blk[k].toInt() xor x[k].toInt()).toByte()
            x = c.doFinal(blk)
            off += 16
        }
        return x.copyOf(tagLen)
    }

    private fun ctr(c: Cipher, nonce: ByteArray, l: Int, data: ByteArray): ByteArray {
        val out = ByteArray(data.size)
        var off = 0
        var i = 1
        while (off < data.size) {
            val s = c.doFinal(ctrBlock(nonce, l, i++))
            for (k in 0 until minOf(16, data.size - off)) out[off + k] = (data[off + k].toInt() xor s[k].toInt()).toByte()
            off += 16
        }
        return out
    }

    fun encrypt(key: ByteArray, nonce: ByteArray, plain: ByteArray, tagLen: Int = 4): ByteArray {
        val l = 15 - nonce.size
        val c = aes(key)
        val t = mac(c, nonce, l, tagLen, plain)
        val s0 = c.doFinal(ctrBlock(nonce, l, 0))
        val tag = ByteArray(tagLen) { (t[it].toInt() xor s0[it].toInt()).toByte() }
        return ctr(c, nonce, l, plain) + tag
    }

    /** null se a etiqueta não confere. */
    fun decrypt(key: ByteArray, nonce: ByteArray, data: ByteArray, tagLen: Int = 4): ByteArray? {
        if (data.size < tagLen) return null
        val l = 15 - nonce.size
        val c = aes(key)
        val plain = ctr(c, nonce, l, data.copyOf(data.size - tagLen))
        val t = mac(c, nonce, l, tagLen, plain)
        val s0 = c.doFinal(ctrBlock(nonce, l, 0))
        val tag = ByteArray(tagLen) { (t[it].toInt() xor s0[it].toInt()).toByte() }
        var diff = 0
        for (k in 0 until tagLen) diff = diff or (tag[k].toInt() xor data[data.size - tagLen + k].toInt())
        return if (diff == 0) plain else null
    }
}

/**
 * Chaves da sessão depois do aperto de mão (XiaomiAuthService.handleWatchNonce): 64 bytes derivados por
 * HMAC-SHA256 com "miwear-auth" → chave/nonce de decifra (0..15, 32..35) e de cifra (16..31, 36..39).
 */
class XiaomiSession private constructor(
    val decryptionKey: ByteArray,
    val encryptionKey: ByteArray,
    val decryptionNonce: ByteArray,
    val encryptionNonce: ByteArray
) {
    /** Cifra um comando do app; [index] vai no nonce (0 = autenticação; os comandos começam em 1). */
    fun encrypt(payload: ByteArray, index: Int): ByteArray =
        XiaomiCcm.encrypt(encryptionKey, encryptionNonce + le32(0) + le32(index), payload)

    /** Decifra o que a pulseira mandou (o nonce dela tem contador 0). */
    fun decrypt(payload: ByteArray): ByteArray? =
        XiaomiCcm.decrypt(decryptionKey, decryptionNonce + le32(0) + le32(0), payload)

    companion object {
        private val MIWEAR_AUTH = "miwear-auth".toByteArray(Charsets.US_ASCII)

        fun hmacSha256(key: ByteArray, vararg data: ByteArray): ByteArray =
            Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); data.forEach { update(it) }; doFinal() }

        fun le32(v: Int) = byteArrayOf((v and 0xff).toByte(), ((v ushr 8) and 0xff).toByte(),
            ((v ushr 16) and 0xff).toByte(), ((v ushr 24) and 0xff).toByte())

        /** computeAuthStep3Hmac do Gadgetbridge. */
        fun derive(secretKey: ByteArray, phoneNonce: ByteArray, watchNonce: ByteArray): ByteArray {
            val k = hmacSha256(phoneNonce + watchNonce, secretKey)
            val out = ByteArray(64)
            var tmp = ByteArray(0)
            var b = 1
            var i = 0
            while (i < out.size) {
                tmp = hmacSha256(k, tmp, MIWEAR_AUTH, byteArrayOf(b.toByte()))
                for (j in tmp.indices) { if (i >= out.size) break; out[i++] = tmp[j] }
                b++
            }
            return out
        }

        /** Sessão a partir do nonce da pulseira, ou null se o HMAC dela não confere (chave errada). */
        fun fromWatch(secretKey: ByteArray, phoneNonce: ByteArray, watchNonce: ByteArray, watchHmac: ByteArray): XiaomiSession? {
            val d = derive(secretKey, phoneNonce, watchNonce)
            val s = XiaomiSession(d.copyOfRange(0, 16), d.copyOfRange(16, 32), d.copyOfRange(32, 36), d.copyOfRange(36, 40))
            val expected = hmacSha256(s.decryptionKey, watchNonce, phoneNonce)
            return if (java.security.MessageDigest.isEqual(expected, watchHmac)) s else null
        }
    }

    /** encryptedNonces do AppConfirm. */
    fun confirmNonces(phoneNonce: ByteArray, watchNonce: ByteArray): ByteArray = hmacSha256(encryptionKey, phoneNonce, watchNonce)
}
