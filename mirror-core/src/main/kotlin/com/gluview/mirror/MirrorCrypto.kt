package com.gluview.mirror

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Cifra do espelho: cada par (paciente ↔ um seguidor) tem a própria chave de 32 bytes, gerada no
 * celular do paciente e passada ao seguidor pelo QR code (troca física, por isso não precisa de
 * troca de chaves pela rede). AES-256-GCM (existe em toda JVM e em todo Android desde o 1º
 * suportado pelo GluView): cifra e autentica; o cabeçalho do quadro entra como dado associado,
 * então trocar id do par, versão ou contador invalida o quadro.
 */
object MirrorCrypto {
    const val KEY_BYTES = 32
    const val NONCE_BYTES = 12
    const val PAIR_ID_BYTES = 16
    private const val TAG_BITS = 128
    private val random = SecureRandom()

    fun newKey(): ByteArray = ByteArray(KEY_BYTES).also { random.nextBytes(it) }
    fun newPairId(): ByteArray = ByteArray(PAIR_ID_BYTES).also { random.nextBytes(it) }

    fun seal(key: ByteArray, aad: ByteArray, plain: ByteArray): ByteArray {
        require(key.size == KEY_BYTES) { "chave com tamanho errado" }
        val nonce = ByteArray(NONCE_BYTES).also { random.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        c.updateAAD(aad)
        return nonce + c.doFinal(plain)
    }

    /** Lança exceção se o quadro foi alterado ou a chave é outra. */
    fun open(key: ByteArray, aad: ByteArray, sealed: ByteArray): ByteArray {
        require(sealed.size > NONCE_BYTES) { "quadro curto" }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"),
            GCMParameterSpec(TAG_BITS, sealed, 0, NONCE_BYTES))
        c.updateAAD(aad)
        return c.doFinal(sealed, NONCE_BYTES, sealed.size - NONCE_BYTES)
    }

    /**
     * Código de conferência de 6 dígitos tirado da chave: aparece nos dois celulares no pareamento
     * para a pessoa conferir que o QR lido é o certo (como no pareamento Bluetooth).
     */
    fun confirmationCode(key: ByteArray): String {
        val h = MessageDigest.getInstance("SHA-256").digest("gluview-mirror-pin".toByteArray() + key)
        val n = ((h[0].toInt() and 0x7f) shl 24) or ((h[1].toInt() and 0xff) shl 16) or
            ((h[2].toInt() and 0xff) shl 8) or (h[3].toInt() and 0xff)
        return String.format("%06d", n % 1_000_000)
    }

    fun hex(b: ByteArray): String = b.joinToString("") { String.format("%02x", it) }
    fun unhex(s: String): ByteArray? {
        if (s.length % 2 != 0) return null
        return runCatching { ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() } }.getOrNull()
    }
    fun b64(b: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    fun unb64(s: String): ByteArray? = runCatching { Base64.getUrlDecoder().decode(s) }.getOrNull()
}

/**
 * O que vai no QR code (e no código para copiar e colar, se a câmera não ajudar):
 * `gluview-mirror:1:<idPar>:<chave>:<host>:<porta>:<nome>` (host e nome em base64url, para IPv6 e
 * acentos não quebrarem o separador). O host pode ficar vazio: o seguidor acha o paciente na rede
 * pelo anúncio (mDNS) ou a pessoa digita depois.
 */
data class PairingCode(
    val pairId: ByteArray,
    val key: ByteArray,
    val host: String,
    val port: Int,
    val patientName: String
) {
    fun encode(): String = listOf(PREFIX, VERSION.toString(), MirrorCrypto.hex(pairId), MirrorCrypto.b64(key),
        MirrorCrypto.b64(host.toByteArray(Charsets.UTF_8)), port.toString(), MirrorCrypto.b64(patientName.toByteArray(Charsets.UTF_8))).joinToString(":")

    val confirmation: String get() = MirrorCrypto.confirmationCode(key)

    override fun equals(other: Any?): Boolean = other is PairingCode && pairId.contentEquals(other.pairId) &&
        key.contentEquals(other.key) && host == other.host && port == other.port && patientName == other.patientName
    override fun hashCode(): Int = pairId.contentHashCode()

    companion object {
        const val PREFIX = "gluview-mirror"
        const val VERSION = 1

        /** null quando o texto não é um código do espelho válido desta versão. */
        fun decode(text: String): PairingCode? {
            val p = text.trim().split(':')
            if (p.size != 7 || p[0] != PREFIX || p[1].toIntOrNull() != VERSION) return null
            val id = MirrorCrypto.unhex(p[2])?.takeIf { it.size == MirrorCrypto.PAIR_ID_BYTES } ?: return null
            val key = MirrorCrypto.unb64(p[3])?.takeIf { it.size == MirrorCrypto.KEY_BYTES } ?: return null
            val port = p[5].toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            val name = MirrorCrypto.unb64(p[6])?.toString(Charsets.UTF_8) ?: return null
            val host = MirrorCrypto.unb64(p[4])?.toString(Charsets.UTF_8)?.takeIf { h -> h.none { it.isWhitespace() } } ?: return null
            return PairingCode(id, key, host, port, name)
        }
    }
}
