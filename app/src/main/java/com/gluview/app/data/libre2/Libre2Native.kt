package com.gluview.app.data.libre2

import com.gluview.app.util.AppLogger

/**
 * Ponte nativa para a lib de processamento da Abbott (`libDataProcessing.so`), na forma FIEL ao
 * Juggluco (`tk.glucodata.Natives` + `g.cpp`/`libre2.cpp`). Expõe as primitivas de cripto V1/V2 —
 * que no nativo chamam `P1`/`P2` da Abbott com o token de 72 bytes — e as operações por-sessão que
 * o handshake BLE do Libre 2 Plus (Gen2) precisa.
 *
 * A implementação JNI (modo DYNLINK: `dlopen`+`dlsym` da lib + subenv fabricado + `debugclone`/ptrace)
 * é a **Fase 1** do plano de adaptação e depende da `libDataProcessing.so` CORRETA
 * (`com.freestylelibre2.app.us` 2.7.1 — a versão que o Juggluco mira, com `initialize` legado e
 * anti-tamper fraco). Enquanto `libgluviewlibre2.so` não estiver presente, [available] é `false` e
 * cada método responde de forma inerte (log único), sem quebrar o app — o caminho Libre 2 Plus
 * simplesmente não produz glicemia até o nativo existir.
 *
 * Mapeamento (Juggluco → aqui):
 * ```
 *   Natives.V1 / V2                          → v1 / v2                (op = seletor de operação)
 *   Natives.getsensorident(dataptr)          → sensorIdent()
 *   Natives.getstreamingAuthenticationData() → streamingAuthData()
 *   Natives.getDeviceAddress(dataptr)        → deviceAddress()
 *   Natives.processTooth(dataptr, packet)    → processTooth(packet)
 *   Natives.resetbluetooth(dataptr)          → resetBluetooth()
 * ```
 * As funções por-sessão operam sobre o sensor ATIVO, estabelecido no nativo durante a ativação NFC
 * (Fase 2). V1/V2 são globais (usam o token + lib carregada).
 */
object Libre2Native {
    private const val LOG = "LIBRE2_NATIVE"
    private const val NATIVE_LIB = "gluviewlibre2"

    @Volatile
    var available = false
        private set

    @Volatile private var warned = false
    private var logger: AppLogger? = null

    /**
     * Carrega `libgluviewlibre2.so` e inicializa a máquina da Abbott (`abbottinit`). Idempotente.
     * Retorna `true` só quando o nativo está de fato disponível e inicializou.
     */
    @Synchronized
    fun init(appLogger: AppLogger): Boolean {
        logger = appLogger
        if (available) return true
        val loaded = runCatching { System.loadLibrary(NATIVE_LIB) }
            .onFailure {
                appLogger.error(LOG, "libgluviewlibre2.so ausente — ponte nativa pendente (Fase 1)",
                    mapOf("erro" to (it.message ?: "?")))
            }
            .isSuccess
        if (!loaded) return false
        available = runCatching { nativeAbbottInit() }
            .onFailure { appLogger.error(LOG, "abbottinit lançou", mapOf("erro" to (it.message ?: "?"))) }
            .getOrDefault(false)
        appLogger.usage(LOG, "init", mapOf("available" to available))
        if (available) selfTest(appLogger)
        return available
    }

    /**
     * Teste de aceite da Fase 1: `getProductFamily` deve retornar != 0 para um patchInfo válido.
     * Roda com um exemplo conhecido (0x9D) e com o sensor de teste (0x2B / patchInfo 2b0a3a081fe1).
     */
    private fun selfTest(appLogger: AppLogger) {
        val exemplo = byteArrayOf(0x9D.toByte(), 0x08, 0x30, 0x01, 0x71, 0x36)
        val sensor  = byteArrayOf(0x2B, 0x0A, 0x3A, 0x08, 0x1F, 0xE1.toByte())
        // Família "7F" (serial 301+/302+, UK/EU desde 2025): patchInfo 7F 0E 30 (Libre 2) e
        // 7F 0E 31 (Libre 2 Plus). Se `getProductFamily` devolver 0 aqui, a libDataProcessing.so
        // empacotada (LibreLink 2.5.2) NÃO reconhece esses sensores e precisa ser trocada por uma
        // mais nova antes de tentar suportá-los. Ver 07_revisao_ecossistema_2026-09.md (item A3).
        val fam7fL2   = byteArrayOf(0x7F, 0x0E, 0x30, 0x01, 0x00, 0x00)
        val fam7fL2p  = byteArrayOf(0x7F, 0x0E, 0x31, 0x01, 0x00, 0x00)
        appLogger.usage(LOG, "SELFTEST getProductFamily",
            mapOf(
                "exemplo9D" to productFamily(exemplo),
                "sensor2B" to productFamily(sensor),
                "fam7F_L2_7f0e30" to productFamily(fam7fL2),
                "fam7F_L2plus_7f0e31" to productFamily(fam7fL2p),
            ))
    }

    /** getProductFamily(patchInfo): família do produto (>0 = reconhecido). Ver Juggluco g.cpp:1613. */
    fun productFamily(patchInfo: ByteArray): Int =
        if (available) nativeProductFamily(patchInfo) else { pending("productFamily"); 0 }

    // ── Primitivas de cripto (Natives.V1/V2 → P1/P2 da Abbott) ──────────────────────
    fun v1(op: Int, i2: Int, a: ByteArray?, b: ByteArray?): Int =
        if (available) nativeV1(op, i2, a, b) else { pending("v1($op)"); -1 }

    fun v2(op: Int, i2: Int, a: ByteArray?, b: ByteArray?): ByteArray? =
        if (available) nativeV2(op, i2, a, b) else { pending("v2($op)"); null }

    // ── Sessão ativa (estabelecida na ativação NFC, no nativo) ──────────────────────

    /**
     * Grava a sessão do sensor ativo no nativo (Juggluco `Natives.USenabledStreaming`): o UID
     * (`sensorIdent`), o `patchInfo` (usado pelo `processStream`), o `streamingAuthenticationData`
     * (`auth`, até 12 bytes) e o `bleAddress` (`address`, 6 bytes → MAC). Chamado pela ativação NFC
     * ([Libre2NfcActivator]) e restaurado das prefs pelo [com.gluview.app.data.libre2.Libre2Source];
     * consumido pelo handshake BLE (sensorIdent/streamingAuthData) e por [processTooth].
     */
    fun usEnabledStreaming(uid: ByteArray, patchInfo: ByteArray, auth: ByteArray, address: ByteArray): Boolean =
        if (available) { nativeUsEnabledStreaming(uid, patchInfo, auth, address); true }
        else { pending("usEnabledStreaming"); false }

    /** Início do sensor (epoch em segundos) → `startsincebase` do algoritmo. Ver Juggluco libre2stream. */
    fun setSensorStart(epochSec: Long) {
        if (available) nativeSetSensorStart(epochSec) else pending("setSensorStart")
    }

    fun sensorIdent(): ByteArray? =
        if (available) nativeSensorIdent() else { pending("sensorIdent"); null }

    fun streamingAuthData(): ByteArray? =
        if (available) nativeStreamingAuthData() else { pending("streamingAuthData"); null }

    fun deviceAddress(): String? =
        if (available) nativeDeviceAddress() else { pending("deviceAddress"); null }

    /** Processa um pacote BLE já descriptografado. Retorna a glicemia empacotada (ver [Libre2Reading]) ou 0. */
    fun processTooth(packet: ByteArray): Long =
        if (available) nativeProcessTooth(packet) else { pending("processTooth"); 0L }

    /**
     * Trend/histórico embutido no ÚLTIMO pacote processado por [processTooth] (21/07/2026) — o
     * pacote BLE do Libre 2 Plus já carrega pontos históricos (a própria `.so` da Abbott decodifica,
     * ver `AlgorithmResults::history()`), só nunca eram extraídos aqui. Cada elemento do array:
     * bits 32-63 = timestamp epoch (segundos), bits 0-31 = mg/dL×10. Chamar sempre logo depois de
     * `processTooth` (consome/limpa o buffer nativo ao ler — não é idempotente).
     */
    fun consumeHistory(): LongArray? =
        if (available) nativeConsumeHistory() else null

    /** Diagnóstico (21/07/2026): [bruto, válidos] do último `processTooth` — ver [nativeHistoryDebug]. */
    fun historyDebug(): IntArray? =
        if (available) nativeHistoryDebug() else null

    /** Diagnóstico (21/07/2026): id/qualidade/valor de cada ponto bruto — ver [nativeHistoryRawDetail]. */
    fun historyRawDetail(): String? =
        if (available) nativeHistoryRawDetail() else null

    fun processScan(
        uid: ByteArray,
        patchInfo: ByteArray,
        fram: ByteArray,
        scanEpochSec: Long = System.currentTimeMillis() / 1000
    ): Libre2ScanResult? =
        if (available) nativeProcessScan(uid, patchInfo, fram, scanEpochSec)?.let { Libre2ScanResult.fromNative(it) }
        else { pending("processScan"); null }

    fun resetBluetooth() {
        if (available) nativeResetBluetooth() else pending("resetBluetooth")
    }

    private fun pending(op: String) {
        if (!warned) {
            warned = true
            logger?.error(LOG,
                "Ponte nativa Libre2 pendente (Fase 1 / libDataProcessing.so ausente) — chamada ignorada: $op")
        }
    }

    // ── JNI — implementadas por libgluviewlibre2.so na Fase 1 (port de g.cpp/libre2.cpp) ──
    private external fun nativeAbbottInit(): Boolean
    private external fun nativeProductFamily(patchInfo: ByteArray): Int
    private external fun nativeV1(op: Int, i2: Int, a: ByteArray?, b: ByteArray?): Int
    private external fun nativeV2(op: Int, i2: Int, a: ByteArray?, b: ByteArray?): ByteArray?
    private external fun nativeUsEnabledStreaming(uid: ByteArray, patchInfo: ByteArray, auth: ByteArray, address: ByteArray)
    private external fun nativeSetSensorStart(epochSec: Long)
    private external fun nativeSensorIdent(): ByteArray?
    private external fun nativeStreamingAuthData(): ByteArray?
    private external fun nativeDeviceAddress(): String?
    private external fun nativeProcessScan(uid: ByteArray, patchInfo: ByteArray, fram: ByteArray, scanEpochSec: Long): LongArray?
    private external fun nativeProcessTooth(packet: ByteArray): Long
    private external fun nativeConsumeHistory(): LongArray?
    private external fun nativeHistoryDebug(): IntArray?
    private external fun nativeHistoryRawDetail(): String?
    private external fun nativeResetBluetooth()
}

data class Libre2ScanResult(
    val reading: Libre2Reading?,
    val sensorStartMs: Long,
    val status: Int
) {
    companion object {
        const val STATUS_PROCESSED = 0
        const val STATUS_INITIALIZED = 1
        const val STATUS_FAILED = -1

        fun fromNative(values: LongArray): Libre2ScanResult? {
            if (values.size < 3) return null
            return Libre2ScanResult(
                reading = Libre2Reading.decode(values[0]),
                sensorStartMs = if (values[1] > 0) values[1] * 1000L else 0L,
                status = values[2].toInt()
            )
        }
    }
}

/**
 * Glicemia decodificada do retorno (empacotado) de `processTooth`, com o encoding EXATO do Juggluco
 * (`SuperGattCallback.handleGlucoseResult`, g.cpp `glucoseback`):
 * ```
 *   res & 0xFFFFFFFF        = glicemia ×10 (mg/dL); 0 = falha
 *   (res >> 32) & 0xFFFF    = taxa ×1000 (short com sinal) → tendência em mg/dL por min
 *   (res >> 48) & 0xFF      = alarme (3=again,4=highest,5=lowest,6=high,7=low,16=veryHigh,17=veryLow,18=preHigh,19=preLow)
 * ```
 */
data class Libre2Reading(val mgdl: Int, val rate: Float, val alarm: Int) {
    companion object {
        fun decode(res: Long): Libre2Reading? {
            val glumgL = (res and 0xFFFFFFFFL).toInt()          // glicemia ×10
            if (glumgL == 0) return null                        // 0 = falha (Juggluco descarta)
            val rate = ((res shr 32) and 0xFFFFL).toShort() / 1000.0f
            val alarm = ((res shr 48) and 0xFFL).toInt()
            return Libre2Reading(mgdl = Math.round(glumgL / 10.0f), rate = rate, alarm = alarm)
        }
    }
}
