package com.gluview.app.data.libre3

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Cifra dos dados do Libre 3 depois da autorização — porte do Juggluco (GPLv3)
 * `Common/src/main/cpp/bcrypt/bcrypt.cpp` (tinycrypt AES-128-CCM).
 *
 * - Chave kEnc (16 bytes) e ivEnc (8 bytes) saem da resposta do desafio (bytes 32..48 e 48..56).
 * - Nonce de 13 bytes: [seq lo, seq hi] + descritor do tipo de pacote (3 bytes) + ivEnc (8 bytes).
 * - Tag de 4 bytes, sem dados associados.
 * - Pacote recebido = cifrado (n+4) + 2 bytes da sequência; pacote enviado igual, com a sequência
 *   de saída começando em 1 (`outCryptoSequence`).
 *
 * Tipos (`kind`, índice em [PACKET_DESCRIPTOR]): 0 controle (envio), 1 resposta do controle,
 * 2 status do sensor, 3 glicemia do minuto, 4 histórico, 5 dados clínicos, 6 registro de eventos,
 * 7 dados de fábrica.
 */
class Libre3Crypto(key: ByteArray, ivEnc: ByteArray) {
    private val key = key.copyOf()
    private val iv = ivEnc.copyOf()
    private var outSequence = 1

    init {
        require(key.size == 16) { "kEnc precisa de 16 bytes" }
        require(ivEnc.size == 8) { "ivEnc precisa de 8 bytes" }
    }

    /** Cifra um comando para o sensor (Juggluco `intEncrypt`). */
    @Synchronized
    fun encrypt(kind: Int, plain: ByteArray): ByteArray? {
        if (plain.isEmpty() || kind !in PACKET_DESCRIPTOR.indices) return null
        val seq = outSequence
        val nonce = nonce(seq, kind)
        val enc = ccmEncrypt(key, nonce, plain, TAG_LEN)
        outSequence++
        return enc + byteArrayOf(nonce[0], nonce[1])
    }

    /** Decifra um pacote do sensor (Juggluco `intDecrypt`). null = tag não confere ou tamanho errado. */
    @Synchronized
    fun decrypt(kind: Int, packet: ByteArray): ByteArray? {
        if (packet.size < 7 || kind !in PACKET_DESCRIPTOR.indices) return null
        val encLen = packet.size - 2
        val nonce = nonce(0, kind)
        nonce[0] = packet[encLen]
        nonce[1] = packet[encLen + 1]
        return ccmDecrypt(key, nonce, packet.copyOfRange(0, encLen), TAG_LEN)
    }

    private fun nonce(seq: Int, kind: Int): ByteArray {
        val n = ByteArray(NONCE_LEN)
        n[0] = (seq and 0xFF).toByte()
        n[1] = ((seq shr 8) and 0xFF).toByte()
        System.arraycopy(PACKET_DESCRIPTOR[kind], 0, n, 2, 3)
        System.arraycopy(iv, 0, n, 5, 8)
        return n
    }

    companion object {
        const val NONCE_LEN = 13
        const val TAG_LEN = 4

        val PACKET_DESCRIPTOR: Array<ByteArray> = arrayOf(
            byteArrayOf(0x00, 0x00, 0x00),
            byteArrayOf(0x00, 0x00, 0x0F),
            byteArrayOf(0x00, 0x00, 0xF0.toByte()),
            byteArrayOf(0x00, 0x0F, 0x00),
            byteArrayOf(0x00, 0xF0.toByte(), 0x00),
            byteArrayOf(0x0F, 0x00, 0x00),
            byteArrayOf(0xF0.toByte(), 0x00, 0x00),
            byteArrayOf(0x44, 0x00, 0x00)
        )

        private fun aes(key: ByteArray): Cipher =
            Cipher.getInstance("AES/ECB/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES")) }

        /** AES-CCM (NIST SP 800-38C) sem dados associados: devolve cifrado || tag. */
        fun ccmEncrypt(key: ByteArray, nonce: ByteArray, plain: ByteArray, tagLen: Int): ByteArray {
            val c = aes(key)
            val mac = cbcMac(c, nonce, plain, tagLen)
            val out = ByteArray(plain.size + tagLen)
            ctr(c, nonce, plain, out)
            val s0 = c.doFinal(counterBlock(nonce, 0))
            for (i in 0 until tagLen) out[plain.size + i] = (mac[i].toInt() xor s0[i].toInt()).toByte()
            return out
        }

        /** AES-CCM: devolve o texto claro, ou null se a tag não conferir. */
        fun ccmDecrypt(key: ByteArray, nonce: ByteArray, cipherAndTag: ByteArray, tagLen: Int): ByteArray? {
            if (cipherAndTag.size < tagLen) return null
            val c = aes(key)
            val n = cipherAndTag.size - tagLen
            val plain = ByteArray(n)
            ctr(c, nonce, cipherAndTag.copyOfRange(0, n), plain)
            val mac = cbcMac(c, nonce, plain, tagLen)
            val s0 = c.doFinal(counterBlock(nonce, 0))
            var diff = 0
            for (i in 0 until tagLen) diff = diff or ((mac[i].toInt() xor s0[i].toInt()) xor cipherAndTag[n + i].toInt())
            return if (diff and 0xFF == 0) plain else null
        }

        private fun lenField(nonce: ByteArray): Int = 15 - nonce.size

        private fun counterBlock(nonce: ByteArray, counter: Int): ByteArray {
            val l = lenField(nonce)
            val a = ByteArray(16)
            a[0] = (l - 1).toByte()
            System.arraycopy(nonce, 0, a, 1, nonce.size)
            var v = counter
            for (i in 15 downTo 16 - l) { a[i] = (v and 0xFF).toByte(); v = v ushr 8 }
            return a
        }

        private fun ctr(c: Cipher, nonce: ByteArray, input: ByteArray, output: ByteArray) {
            var off = 0
            var counter = 1
            while (off < input.size) {
                val s = c.doFinal(counterBlock(nonce, counter++))
                val n = minOf(16, input.size - off)
                for (i in 0 until n) output[off + i] = (input[off + i].toInt() xor s[i].toInt()).toByte()
                off += n
            }
        }

        private fun cbcMac(c: Cipher, nonce: ByteArray, plain: ByteArray, tagLen: Int): ByteArray {
            val l = lenField(nonce)
            val b0 = ByteArray(16)
            b0[0] = ((((tagLen - 2) / 2) shl 3) or (l - 1)).toByte()
            System.arraycopy(nonce, 0, b0, 1, nonce.size)
            var v = plain.size
            for (i in 15 downTo 16 - l) { b0[i] = (v and 0xFF).toByte(); v = v ushr 8 }
            var x = c.doFinal(b0)
            var off = 0
            while (off < plain.size) {
                val block = ByteArray(16)
                val n = minOf(16, plain.size - off)
                System.arraycopy(plain, off, block, 0, n)
                for (i in 0 until 16) block[i] = (block[i].toInt() xor x[i].toInt()).toByte()
                x = c.doFinal(block)
                off += n
            }
            return x.copyOf(tagLen)
        }
    }
}
