package com.gluview.app.data.libre3

import com.gluview.app.util.AppLogger

/**
 * Ponte para a criptografia da AUTORIZAÇÃO do FreeStyle Libre 3 (porte do Juggluco, GPLv3:
 * `Common/src/main/cpp/libre3/loadlibs.cpp` + `libre3/process/`).
 *
 * Só a parte que precisa ficar em C (caixa-branca, P-256 próprio, HMAC): handshake, certificado do
 * sensor, chave efêmera, desafio cifrado e exportação da autorização salva (kAuth, 149 bytes).
 * A cifra dos dados (AES-CCM), os pacotes, os comandos e a ativação NFC estão em Kotlin
 * ([Libre3Crypto], [Libre3Protocol]) — no Juggluco eles dependiam do banco de sensores dele.
 *
 * Auditoria 21/11/2026: a versão anterior declarava 34 funções que não existiam na `.so` (os nomes
 * gerados eram os do Juggluco) e a 1ª chamada derrubava o app. Agora `loadlibs.cpp` registra as
 * funções desta classe em `JNI_OnLoad`; se o registro falhar, `loadLibrary` falha e [available]
 * fica falso.
 *
 * Retornos inteiros seguem o Juggluco: **1 = sucesso**, qualquer outro valor = falha.
 */
object Libre3Native {
    private const val LOG = "LIBRE3_NATIVE"
    private const val NATIVE_LIB = "gluviewlibre3"

    const val LEN_SAVED_AUTHORIZATION = 149
    const val LEN_PATCH_CERTIFICATE = 140
    const val LEN_PATCH_EPHEMERAL = 65
    const val LEN_APP_EPHEMERAL = 64
    const val LEN_NONCE = 7
    const val LEN_REPLY_PLAIN = 36
    const val LEN_REPLY_CRYPT = 40
    const val LEN_RESPONSE_CRYPT = 60
    const val LEN_RESPONSE_PLAIN = 56
    const val LEN_CHALLENGE_CONTEXT = 176

    @Volatile
    var available = false
        private set

    @Synchronized
    fun init(appLogger: AppLogger?): Boolean {
        if (available) return true
        val loaded = runCatching { System.loadLibrary(NATIVE_LIB) }
            .onFailure {
                appLogger?.error(LOG, "Biblioteca do Libre 3 indisponível neste aparelho",
                    mapOf("erro" to (it.message ?: it::class.simpleName.orEmpty())))
            }.isSuccess
        if (!loaded) return false
        // Conferência de ligação: chamada inofensiva (libera o contexto 0 = nada).
        val linked = try { nativeFreeSecurityContext(0L); true } catch (e: UnsatisfiedLinkError) {
            appLogger?.error(LOG, "Funções nativas do Libre 3 não ligadas", mapOf("erro" to (e.message ?: "?")))
            false
        }
        available = linked
        appLogger?.usage(LOG, "init", mapOf("disponivel" to linked))
        return linked
    }

    /** Cria (context = 0) ou reinicia o estado de segurança. 0 = falha. */
    fun beginSecurityHandshake(context: Long): Long =
        if (available) nativeBeginSecurityHandshake(context) else 0L

    /** No-op proposital no Juggluco (evita uso após liberar com callbacks BLE atrasados). */
    fun freeSecurityContext(context: Long) { if (available && context != 0L) nativeFreeSecurityContext(context) }

    /** Escolhe a chave do app (versão 1 = Libre 3) e importa a autorização salva, se houver. */
    fun selectAppKeyAndSavedAuthorization(context: Long, securityVersion: Int, saved: ByteArray?): Boolean =
        available && context != 0L && nativeSelectAppKeyAndSavedAuthorization(context, securityVersion, saved) == 1

    fun acceptPatchCertificate(context: Long, certificate: ByteArray): Boolean =
        available && context != 0L && certificate.size == LEN_PATCH_CERTIFICATE &&
            nativeAcceptPatchCertificate(context, certificate) == 1

    /** 64 bytes (x||y) da chave efêmera do app, ou null. */
    fun createEphemeralPublicKey(context: Long): ByteArray? =
        if (available && context != 0L) nativeCreateEphemeralPublicKey(context)?.takeIf { it.size == LEN_APP_EPHEMERAL } else null

    fun deriveAuthorizationRoot(context: Long, patchEphemeral: ByteArray): Boolean =
        available && context != 0L && patchEphemeral.size == LEN_PATCH_EPHEMERAL &&
            nativeDeriveAuthorizationRoot(context, patchEphemeral) == 1

    fun encryptChallengeReply(context: Long, nonce: ByteArray, plain: ByteArray): ByteArray? =
        if (available && context != 0L && nonce.size == LEN_NONCE && plain.size == LEN_REPLY_PLAIN)
            nativeEncryptChallengeReply(context, nonce, plain)?.takeIf { it.size == LEN_REPLY_CRYPT } else null

    fun decryptChallengeResponse(context: Long, nonce: ByteArray, crypt: ByteArray): ByteArray? =
        if (available && context != 0L && nonce.size == LEN_NONCE && crypt.size == LEN_RESPONSE_CRYPT)
            nativeDecryptChallengeResponse(context, nonce, crypt)?.takeIf { it.size == LEN_RESPONSE_PLAIN } else null

    fun exportChallengeContext(context: Long): ByteArray? =
        if (available && context != 0L) nativeExportChallengeContext(context)?.takeIf { it.size == LEN_CHALLENGE_CONTEXT } else null

    fun exportSavedAuthorization(context: Long): ByteArray? =
        if (available && context != 0L) nativeExportSavedAuthorization(context)?.takeIf { it.size == LEN_SAVED_AUTHORIZATION } else null

    /** Lado do SENSOR no desafio (só para o simulador/testes): decifra a resposta do app (40 → 36). */
    fun emulatorDecryptReply(context: Long, nonce: ByteArray, crypt: ByteArray): ByteArray? =
        if (available && context != 0L) nativeEmulatorDecryptReply(context, nonce, crypt) else null

    /** Lado do SENSOR no desafio (só para o simulador/testes): cifra a resposta do sensor (56 → 60). */
    fun emulatorEncryptResponse(context: Long, nonce: ByteArray, plain: ByteArray): ByteArray? =
        if (available && context != 0L) nativeEmulatorEncryptResponse(context, nonce, plain) else null

    @JvmStatic private external fun nativeBeginSecurityHandshake(context: Long): Long
    @JvmStatic private external fun nativeFreeSecurityContext(context: Long)
    @JvmStatic private external fun nativeSelectAppKeyAndSavedAuthorization(context: Long, securityVersion: Int, saved: ByteArray?): Int
    @JvmStatic private external fun nativeAcceptPatchCertificate(context: Long, certificate: ByteArray): Int
    @JvmStatic private external fun nativeCreateEphemeralPublicKey(context: Long): ByteArray?
    @JvmStatic private external fun nativeDeriveAuthorizationRoot(context: Long, patchEphemeral: ByteArray): Int
    @JvmStatic private external fun nativeEncryptChallengeReply(context: Long, nonce: ByteArray, plain: ByteArray): ByteArray?
    @JvmStatic private external fun nativeDecryptChallengeResponse(context: Long, nonce: ByteArray, crypt: ByteArray): ByteArray?
    @JvmStatic private external fun nativeExportChallengeContext(context: Long): ByteArray?
    @JvmStatic private external fun nativeExportSavedAuthorization(context: Long): ByteArray?
    @JvmStatic private external fun nativeEmulatorDecryptReply(context: Long, nonce: ByteArray, crypt: ByteArray): ByteArray?
    @JvmStatic private external fun nativeEmulatorEncryptResponse(context: Long, nonce: ByteArray, plain: ByteArray): ByteArray?
}
