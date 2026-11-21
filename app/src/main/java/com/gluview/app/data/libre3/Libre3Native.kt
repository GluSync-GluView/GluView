package com.gluview.app.data.libre3

import com.gluview.app.util.AppLogger

/**
 * Libre 3 / Lingo native bridge - port of Juggluco (GPLv3).
 * Implements whitebox crypto, authorization (fresh/saved auth), kAuth 149B,
 * NFC activation, BLE streaming and Lingo realtime parsing.
 */
object Libre3Native {
    private const val LOG = "LIBRE3_NATIVE"
    private const val NATIVE_LIB = "gluviewlibre3"

    @Volatile
    var available = false
        private set

    @Volatile private var warned = false
    private var logger: AppLogger? = null

    /**
     * Carrega `libgluviewlibre3.so`. Idempotente.
     * Retorna `true` só quando o nativo está de fato disponível.
     */
    @Synchronized
    fun init(appLogger: AppLogger): Boolean {
        logger = appLogger
        if (available) return true
        val loaded = runCatching { System.loadLibrary(NATIVE_LIB) }
            .onFailure {
                appLogger.error(LOG, "libgluviewlibre3.so ausente — ponte nativa Libre 3 pendente",
                    mapOf("erro" to (it.message ?: "?")))
            }
            .isSuccess
        if (!loaded) return false
        // Auditoria 21/11: carregar a .so não basta — confere se as funções JNI existem com os nomes
        // que o Kotlin chama. Hoje não existem (a .so exporta os nomes do Juggluco), e sem esta
        // conferência a 1ª chamada derrubava o app com UnsatisfiedLinkError.
        val linked = try { nativeFreeSecurityContext(0L); true } catch (e: UnsatisfiedLinkError) {
            appLogger.error(LOG, "Ponte nativa do Libre 3 incompleta (funções JNI não ligadas)", mapOf("erro" to (e.message ?: "?")))
            false
        }
        if (!linked) return false
        available = true
        appLogger.usage(LOG, "init", mapOf("available" to available))
        return available
    }

    /** Cria/inicializa contexto de segurança para handshake Libre 3. Retorna handle (0 = falha). */
    fun beginSecurityHandshake(context: Long = 0L): Long =
        if (available) nativeBeginSecurityHandshake(context) else { pending("beginSecurityHandshake"); 0L }

    /** Libera contexto de segurança (no-op no build estável — ver loadlibs.cpp). */
    fun freeSecurityContext(context: Long) {
        if (available) nativeFreeSecurityContext(context) else pending("freeSecurityContext")
    }

    /**
     * Seleciona app key e importa saved authorization (149 bytes).
     * securityVersion: 1 = Libre 3, 3 = Lingo v3.
     * Retorna código de erro L3_SECURITY_* (1 = sucesso).
     */
    fun selectAppKeyAndSavedAuthorization(
        context: Long,
        securityVersion: Int,
        savedAuthorization: ByteArray?
    ): Int =
        if (available) nativeSelectAppKeyAndSavedAuthorization(context, securityVersion, savedAuthorization)
        else { pending("selectAppKeyAndSavedAuthorization"); -1 }

    /**
     * Aceita certificado do patch (140 bytes Libre 3, 65 bytes Lingo).
     * Retorna código de erro L3_SECURITY_* (1 = sucesso).
     */
    fun acceptPatchCertificate(context: Long, patchCertificate: ByteArray): Int =
        if (available) nativeAcceptPatchCertificate(context, patchCertificate)
        else { pending("acceptPatchCertificate"); -1 }

    /** Cria chave pública efêmera (64 bytes). Retorna byte[] ou null se falha. */
    fun createEphemeralPublicKey(context: Long): ByteArray? =
        if (available) nativeCreateEphemeralPublicKey(context) else { pending("createEphemeralPublicKey"); null }

    /**
     * Deriva authorization root a partir da chave efêmera do patch (65 bytes).
     * Retorna código de erro L3_SECURITY_* (1 = sucesso).
     */
    fun deriveAuthorizationRoot(context: Long, patchEphemeralPublicKey: ByteArray): Int =
        if (available) nativeDeriveAuthorizationRoot(context, patchEphemeralPublicKey)
        else { pending("deriveAuthorizationRoot"); -1 }

    /**
     * Criptografa challenge reply (nonce 7 bytes + plaintext 56 bytes → ciphertext 40 bytes).
     * Retorna byte[] ou null se falha.
     */
    fun encryptChallengeReply(context: Long, nonce: ByteArray, plaintext: ByteArray): ByteArray? =
        if (available) nativeEncryptChallengeReply(context, nonce, plaintext)
        else { pending("encryptChallengeReply"); null }

    /**
     * Descriptografa challenge response (nonce 7 bytes + ciphertext 60 bytes → plaintext 56 bytes).
     * Retorna byte[] ou null se falha.
     */
    fun decryptChallengeResponse(context: Long, nonce: ByteArray, ciphertext: ByteArray): ByteArray? =
        if (available) nativeDecryptChallengeResponse(context, nonce, ciphertext)
        else { pending("decryptChallengeResponse"); null }

    /** Exporta challenge context (176 bytes) para provisionamento Garmin. */
    fun exportChallengeContext(context: Long): ByteArray? =
        if (available) nativeExportChallengeContext(context) else { pending("exportChallengeContext"); null }

    /** Exporta saved authorization (149 bytes = kAuth) para persistência. */
    fun exportSavedAuthorization(context: Long): ByteArray? =
        if (available) nativeExportSavedAuthorization(context) else { pending("exportSavedAuthorization"); null }

    // ── NFC / Ativação ──

    /** Retorna security version do patch Lingo (0 = não-Lingo, 3 = Lingo v3, -1 = erro). */
    fun lingoPatchSecurityVersion(patchInfo: ByteArray): Int =
        if (available) nativeLingoPatchSecurityVersion(patchInfo) else { pending("lingoPatchSecurityVersion"); 0 }

    /** Calcula timestamp de ativação a partir do NFC1. Retorna epoch seconds. */
    fun getLibre3secs(nfc1: ByteArray): Long =
        if (available) nativeGetLibre3secs(nfc1) else { pending("getLibre3secs"); 0L }

    /**
     * Interpreta NFC2 (resposta de ativação).
     * Retorna sensor pointer (jlong) ou códigos de erro: 0=fail, 1=account ID rejection, 5/6=fim sensor, 3=outro erro.
     * `jlongreturn[0]` recebe o stream pointer ou código.
     */
    fun interpret3NFC2(nfc1: ByteArray, nfc2: ByteArray, nowSec: Long, jlongreturn: LongArray): String? =
        if (available) nativeInterpret3NFC2(nfc1, nfc2, nowSec, jlongreturn)
        else { pending("interpret3NFC2"); null }

    /** Prepara comando de ativação DP (10 bytes). */
    fun startTimeIDsum(buffer: ByteArray, timeSec: Long, accountId: Long): Int =
        if (available) nativeStartTimeIDsum(buffer, timeSec, accountId) else { pending("startTimeIDsum"); -1 }

    // ── BLE / Streaming ──

    /** Retorna Lingo security version do sensor (0 = Libre 3, 3 = Lingo v3, -1 = precisa rescan). */
    fun getLingoSecurityVersion(sensorPtr: Long): Int =
        if (available) nativeGetLingoSecurityVersion(sensorPtr) else { pending("getLingoSecurityVersion"); 0 }

    /** Define kAuth (149 bytes) no sensor. `null` limpa. */
    fun setLibre3kAuth(sensorPtr: Long, kauth: ByteArray?) {
        if (available) nativeSetLibre3kAuth(sensorPtr, kauth) else pending("setLibre3kAuth")
    }

    /** Obtém kAuth (149 bytes) do sensor. Retorna byte[] ou null. */
    fun getLibre3kAuth(sensorPtr: Long): ByteArray? =
        if (available) nativeGetLibre3kAuth(sensorPtr) else { pending("getLibre3kAuth"); null }

    /** Cria/recupera sensor pointer a partir de serial, startTime, PIN, address. */
    fun getLibre3Sensorptr(serial: String, startTimeMs: Long, pin: ByteArray?, address: String?): Long =
        if (available) nativeGetLibre3Sensorptr(serial, startTimeMs, pin, address)
        else { pending("getLibre3Sensorptr"); 0L }

    /** Processa pacote de 1 minuto Libre 3 (35 bytes criptografados → oneminute struct). */
    fun saveLibre3MinuteL(sensorPtr: Long, minuteData: ByteArray, msec: Long): Long =
        if (available) nativeSaveLibre3MinuteL(sensorPtr, minuteData, msec) else { pending("saveLibre3MinuteL"); 0L }

    /** Processa pacote Lingo realtime (51 bytes plaintext → oneminute struct). */
    fun saveLingoMinuteL(sensorPtr: Long, minuteData: ByteArray, msec: Long): Long =
        if (available) nativeSaveLingoMinuteL(sensorPtr, minuteData, msec) else { pending("saveLingoMinuteL"); 0L }

    /** Processa fastData Libre 3 (14 bytes). Retorna Boolean. */
    fun saveLibre3fastData(sensorPtr: Long, fastData: ByteArray): Boolean =
        if (available) nativeSaveLibre3fastData(sensorPtr, fastData) else { pending("saveLibre3fastData"); false }

    /** Processa historical data Libre 3 (variable length). Retorna Boolean. */
    fun saveLibre3History(sensorPtr: Long, history: ByteArray): Boolean =
        if (available) nativeSaveLibre3History(sensorPtr, history) else { pending("saveLibre3History"); false }

    /** Processa historical data Lingo (14 bytes). Retorna Boolean. */
    fun saveLingoHistory(sensorPtr: Long, history: ByteArray): Boolean =
        if (available) nativeSaveLingoHistory(sensorPtr, history) else { pending("saveLingoHistory"); false }

    /** Processa fastData Lingo (14 bytes). Retorna Boolean. */
    fun saveLingoFastData(sensorPtr: Long, fastData: ByteArray): Boolean =
        if (available) nativeSaveLingoFastData(sensorPtr, fastData) else { pending("saveLingoFastData"); false }

    /** Processa patch status (20 bytes Libre 3, 22 bytes Lingo). Retorna currentLifeCount | index<<16. */
    fun libre3processpatchstatus(sensorPtr: Long, status: ByteArray): Int =
        if (available) nativeLibre3processpatchstatus(sensorPtr, status) else { pending("libre3processpatchstatus"); -1 }

    /** Processa event log. Retorna último index ou -1. */
    fun libre3EventLog(sensorPtr: Long, logData: ByteArray): Int =
        if (available) nativeLibre3EventLog(sensorPtr, logData) else { pending("libre3EventLog"); -1 }

    /** Cria comando Control History. Retorna byte[]. */
    fun libre3ControlHistory(arg: Int, from: Int): ByteArray? =
        if (available) nativeLibre3ControlHistory(arg, from) else { pending("libre3ControlHistory"); null }

    /** Cria comando Clinical Control. Retorna byte[]. */
    fun libre3ClinicalControl(arg: Int, from: Int): ByteArray? =
        if (available) nativeLibre3ClinicalControl(arg, from) else { pending("libre3ClinicalControl"); null }

    /** Cria comando Lingo Control History. Retorna byte[]. */
    fun lingoControlHistory(arg: Int, from: Int): ByteArray? =
        if (available) nativeLingoControlHistory(arg, from) else { pending("lingoControlHistory"); null }

    /** Cria comando Lingo Clinical Control. Retorna byte[]. */
    fun lingoClinicalControl(arg: Int, from: Int): ByteArray? =
        if (available) nativeLingoClinicalControl(arg, from) else { pending("lingoClinicalControl"); null }

    /** Cria comando Event Log Control. Retorna byte[]. */
    fun libre3EventLogControl(arg: Int): ByteArray? =
        if (available) nativeLibre3EventLogControl(arg) else { pending("libre3EventLogControl"); null }

    // ── Emulador (testes) ──

    /** Descriptografa reply do emulador (nonce 7 + ciphertext 40 → 36 bytes). */
    fun libre3EmulatorDecryptReply(context: Long, nonce: ByteArray, ciphertext: ByteArray): ByteArray? =
        if (available) nativeLibre3EmulatorDecryptReply(context, nonce, ciphertext) else { pending("libre3EmulatorDecryptReply"); null }

/** Criptografa response do emulador (nonce 7 + plaintext 56 → 60 bytes). */
    fun libre3EmulatorEncryptResponse(context: Long, nonce: ByteArray, plaintext: ByteArray): ByteArray? =
        if (available) nativeLibre3EmulatorEncryptResponse(context, nonce, plaintext) else { pending("libre3EmulatorEncryptResponse"); null }

    /** Obtém o PIN do sensor (4 bytes). */
    fun getpin(sensorPtr: Long): ByteArray? =
        if (available) nativeGetpin(sensorPtr) else { pending("getpin"); null }

    private fun pending(op: String) {
        if (!warned) {
            warned = true
            logger?.error(LOG, "Ponte nativa Libre 3 pendente — chamada ignorada: $op")
        }
    }

    // ── JNI — implementadas por libgluviewlibre3.so (port Juggluco GPLv3) ──

    private external fun nativeBeginSecurityHandshake(context: Long): Long
    private external fun nativeFreeSecurityContext(context: Long)
    private external fun nativeSelectAppKeyAndSavedAuthorization(context: Long, securityVersion: Int, savedAuthorization: ByteArray?): Int
    private external fun nativeAcceptPatchCertificate(context: Long, patchCertificate: ByteArray): Int
    private external fun nativeCreateEphemeralPublicKey(context: Long): ByteArray?
    private external fun nativeDeriveAuthorizationRoot(context: Long, patchEphemeralPublicKey: ByteArray): Int
    private external fun nativeEncryptChallengeReply(context: Long, nonce: ByteArray, plaintext: ByteArray): ByteArray?
    private external fun nativeDecryptChallengeResponse(context: Long, nonce: ByteArray, ciphertext: ByteArray): ByteArray?
    private external fun nativeExportChallengeContext(context: Long): ByteArray?
    private external fun nativeExportSavedAuthorization(context: Long): ByteArray?
    private external fun nativeLingoPatchSecurityVersion(patchInfo: ByteArray): Int
    private external fun nativeGetLibre3secs(nfc1: ByteArray): Long
    private external fun nativeInterpret3NFC2(nfc1: ByteArray, nfc2: ByteArray, nowSec: Long, jlongreturn: LongArray): String?
    private external fun nativeStartTimeIDsum(buffer: ByteArray, timeSec: Long, accountId: Long): Int
    private external fun nativeGetLingoSecurityVersion(sensorPtr: Long): Int
    private external fun nativeSetLibre3kAuth(sensorPtr: Long, kauth: ByteArray?)
    private external fun nativeGetLibre3kAuth(sensorPtr: Long): ByteArray?
    private external fun nativeGetLibre3Sensorptr(serial: String, startTimeMs: Long, pin: ByteArray?, address: String?): Long
    private external fun nativeSaveLibre3MinuteL(sensorPtr: Long, minuteData: ByteArray, msec: Long): Long
    private external fun nativeSaveLingoMinuteL(sensorPtr: Long, minuteData: ByteArray, msec: Long): Long
    private external fun nativeSaveLibre3fastData(sensorPtr: Long, fastData: ByteArray): Boolean
    private external fun nativeSaveLibre3History(sensorPtr: Long, history: ByteArray): Boolean
    private external fun nativeSaveLingoHistory(sensorPtr: Long, history: ByteArray): Boolean
    private external fun nativeSaveLingoFastData(sensorPtr: Long, fastData: ByteArray): Boolean
    private external fun nativeLibre3processpatchstatus(sensorPtr: Long, status: ByteArray): Int
    private external fun nativeLibre3EventLog(sensorPtr: Long, logData: ByteArray): Int
    private external fun nativeLibre3ControlHistory(arg: Int, from: Int): ByteArray?
    private external fun nativeLibre3ClinicalControl(arg: Int, from: Int): ByteArray?
    private external fun nativeLingoControlHistory(arg: Int, from: Int): ByteArray?
    private external fun nativeLingoClinicalControl(arg: Int, from: Int): ByteArray?
private external fun nativeLibre3EventLogControl(arg: Int): ByteArray?
    private external fun nativeLibre3EmulatorDecryptReply(context: Long, nonce: ByteArray, ciphertext: ByteArray): ByteArray?
    private external fun nativeLibre3EmulatorEncryptResponse(context: Long, nonce: ByteArray, plaintext: ByteArray): ByteArray?
    private external fun nativeGetpin(sensorPtr: Long): ByteArray?
}


