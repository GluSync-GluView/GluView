package com.gluview.app.debug

import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * SÓ NO BUILD DEBUG / SIMULADOR. Fabrica uma autorização salva (kAuth, 149 bytes) a partir de um
 * "quadro" de autorização aleatório de 66 bytes — o mesmo formato que `libre3_app_core.c`
 * (`l3_app_core_export_saved_authorization_into` + `saved_authorization_record.c`) exporta.
 *
 * O import do núcleo é estrutural (assinatura "L3S9", campos fixos, HMAC-SHA1 do cabeçalho com o
 * nonce como chave, soma FNV-1a de 16 bits do quadro e raiz = 16 primeiros bytes do quadro); o
 * contexto do desafio sai do quadro pela caixa-branca. Logo, app e sensor virtual que importam a
 * MESMA kAuth fabricada falam o mesmo desafio — sem chave da Abbott e sem sensor real. Um sensor
 * real nunca aceitaria esta kAuth (ele guarda o quadro da autorização verdadeira).
 */
object Libre3TestAuthorization {
    fun fabricate(frame: ByteArray = ByteArray(66).also { SecureRandom().nextBytes(it) }): ByteArray {
        require(frame.size == 66)
        val out = ByteArray(149)
        "L3S9".toByteArray(Charsets.US_ASCII).copyInto(out, 0x00)
        out[0x04] = 0x02
        be32(out, 0x05, 0)
        be32(out, 0x09, 0x10)
        be32(out, 0x0d, 0)
        frame.copyInto(out, 0x11, 0, 16)    // manager id = quadro[0..16]
        frame.copyInto(out, 0x21, 16, 32)   // public     = quadro[16..32]
        frame.copyInto(out, 0x31, 32, 48)   // nonce      = quadro[32..48]
        be32(out, 0x41, 1)
        val nonce = frame.copyOfRange(32, 48)
        val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(nonce, "HmacSHA1")) }.doFinal(out.copyOfRange(0, 0x45))
        mac.copyInto(out, 0x45)
        frame.copyInto(out, 0x59, 48, 52)   // payload tag = quadro[48..52]
        be32(out, 0x5d, 0x10)
        frame.copyInto(out, 0x61, 0, 16)    // raiz = quadro[0..16]
        // 0x71..0x81 zeros; trailer = quadro[52..66] + soma16 LE + 4 zeros
        frame.copyInto(out, 0x81, 52, 66)
        val sum = checksum16(frame)
        out[0x81 + 14] = (sum and 0xFF).toByte()
        out[0x81 + 15] = ((sum shr 8) and 0xFF).toByte()
        return out
    }

    /** `frame_checksum16`: FNV-1a 32 bits sobre os 66 bytes, dobrado para 16. */
    fun checksum16(frame: ByteArray): Int {
        var h = 2166136261L
        for (b in frame) { h = ((h xor (b.toLong() and 0xFF)) * 16777619L) and 0xFFFFFFFFL }
        return ((h ushr 16) xor h).toInt() and 0xFFFF
    }

    private fun be32(b: ByteArray, o: Int, v: Int) {
        b[o] = (v ushr 24).toByte(); b[o + 1] = (v ushr 16).toByte(); b[o + 2] = (v ushr 8).toByte(); b[o + 3] = v.toByte()
    }
}
