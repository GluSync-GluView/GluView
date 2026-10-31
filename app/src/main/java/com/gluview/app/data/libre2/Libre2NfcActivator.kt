package com.gluview.app.data.libre2

import android.nfc.Tag
import android.nfc.tech.NfcV
import com.gluview.app.data.datastore.AppPreferences
import com.gluview.app.util.AppLogger
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/** Resultado da ativação por NFC — tudo que o streaming BLE headless precisa. */
data class Libre2Session(
    val serial: String,
    val uidHex: String,
    val patchInfoHex: String,
    /** streamingAuthenticationData (12 bytes) computado no enable-streaming; persistido em hex. */
    val streamingAuthHex: String,
    /** MAC do sensor derivado pelo enable-streaming (bleAddress). Vazio se indisponível. */
    val bleMac: String,
    val calibration: Libre2Calibration,
    val framHex: String,
    val sensorStartMs: Long,
    val streamingEnabled: Boolean,
    val scanReading: Libre2Reading? = null
)

/**
 * Ativação por NFC do FreeStyle Libre 2 Plus — **port FIEL do Juggluco `Gen2.java`** (caminho US/Gen2).
 *
 * O sensor é uma tag NFC Tipo V (ISO 15693). Encostando o celular:
 *   1. lê o `patchInfo` (`nfcinfo` = comando `{02 A1 07}`) e confirma que é Gen2 (`getversion==2`);
 *   2. **ativa** o sensor (`Gen2.activate`/`inactivate`, comando de scanner `0x1b`) — inicia o warm-up;
 *   3. **habilita o streaming** (`Gen2.gen2enablestreaming`, comando `0x1e`) → produz o
 *      `streamingAuthenticationData` (12B) e o `bleAddress` (MAC, 6B), guardados no estado nativo via
 *      [Libre2Native.usEnabledStreaming] (equivalente ao `Natives.USenabledStreaming`);
 *   4. (best-effort) lê a FRAM (`Gen2.getuspatch`/`readscanend`, comando `0x1f`) p/ diagnóstico.
 *
 * A cripto de ativação NÃO é reimplementada: cada `V1`/`V2` chama `P1`/`P2` da lib da Abbott via
 * [Libre2Native] (a `libDataProcessing.so` do LibreLink 2.5.2). Sem a ponte nativa ([Libre2Native.available]
 * == false) a ativação falha de forma limpa e logada — não há placeholder de cripto.
 *
 * O reader-mode do NFC é escopo de Activity → a [Tag] chega da MainActivity
 * (`NfcAdapter.enableReaderMode`) e é passada para [activate].
 *
 * Referência: `tk.glucodata.Gen2` + `tk.glucodata.AlgNfcV` (issuenfc/goodnfc/activate) do Juggluco (GPLv3).
 * Opcodes/estrutura idênticos ao original; nomes traduzidos para o domínio do GluView.
 */
@Singleton
class Libre2NfcActivator @Inject constructor(
    private val preferences: AppPreferences,
    private val appLogger: AppLogger
) {
    /**
     * Mirrors ScanNfcV.scan in Juggluco: read FRAM, call processScan, then perform the
     * activation/streaming branch selected by the scan result and stored sensor state.
     */
    suspend fun activate(tag: Tag): Result<Libre2Session> {
        return try {
            if (!Libre2Native.init(appLogger)) {
                error("Abbott native bridge unavailable; libDataProcessing.so was not loaded")
            }

            val nfcv = NfcV.get(tag) ?: error("Tag is not NfcV (ISO 15693)")
            runCatching { nfcv.connect() }.getOrElse {
                error("Sensor moved during NFC read (${it::class.simpleName}); keep the phone against it")
            }
            try {
                val uid = tag.id ?: error("NFC UID unavailable")
                if (uid.size < 8) error("Unexpected NFC UID size (${uid.size} bytes)")

                val patchInfo = readPatchInfo(nfcv)
                runCatching { nfcv.close() }
                val gen = getVersion(patchInfo)
                val fam = Libre2Native.productFamily(patchInfo).takeIf { it > 0 } ?: 3
                val serial = getSerial(fam, uid).substring(5)
                val uidHex = uid.toHex()

                appLogger.usage(LOG, "NFC tag connected",
                    mapOf("uid" to uidHex, "patchInfo" to patchInfo.toHex(),
                          "gen" to gen, "fam" to fam, "serial" to serial))
                // Gen1 = Libre 2/2 Plus europeu: protocolo ainda não implementado (mensagem própria).
                if (gen == 1) throw LibreGen1NotSupportedException()
                if (gen != 2) error("Unsupported Libre sensor generation: $gen")
                // Região de comercialização pelo patchInfo: ajusta a variante mostrada na lista
                // (ex.: "FreeStyle Libre 2 Plus NA" → "SA"), sem mudar o código de leitura.
                LibrePatchInfo.detectedVariantId(patchInfo, preferences.sensorVariantId.first())?.let {
                    appLogger.usage(LOG, "Variante detectada", mapOf("variante" to it, "regiao" to LibrePatchInfo.regionByte(patchInfo)))
                    preferences.saveSensorVariantId(it)
                }

                // Juggluco: AlgNfcV.readNfcTag -> Natives.nfcdata. Activation comes later.
                val fram = withNfcV(tag, "read sensor memory") {
                    getUsPatch(it, uid, FRAM_START, FRAM_LEN)
                } ?: error("Could not read sensor memory; keep the phone against the sensor and try again")
                val scanResult = Libre2Native.processScan(uid, patchInfo, fram)
                    ?: error("Abbott processScan could not process the NFC reading")
                if (scanResult.status == Libre2ScanResult.STATUS_FAILED) {
                    error("Abbott processScan rejected the NFC reading")
                }
                val calibration = Libre2Calibration.fromFram(fram)
                appLogger.usage(LOG, "FRAM/processScan completed before activation",
                    mapOf("bytes" to fram.size,
                          "factoryParams" to calibration.hasFactoryParams,
                          "processScanStatus" to scanResult.status,
                          "processScanMgdl" to (scanResult.reading?.mgdl ?: 0)))

                val previousUidHex = preferences.libre2Uid.first()
                val previousAuthHex = preferences.libre2SessionKey.first()
                val previousMac = preferences.libre2Mac.first()
                val previousStreamingValid = preferences.libre2StreamingValid.first()
                val previousStartMs = preferences.libre2SensorStartMs.first()
                val sameSensor = previousUidHex.equals(uidHex, ignoreCase = true)
                val existingStreaming = sameSensor && previousStreamingValid &&
                    previousAuthHex.isNotBlank() && previousMac.isNotBlank()
                val sensorStartMs = if (sameSensor && previousStartMs > 0L) {
                    previousStartMs
                } else {
                    scanResult.sensorStartMs.takeIf { it > 0L } ?: System.currentTimeMillis()
                }

                val streamingAuthHex: String
                val bleMac: String
                val streamingEnabled: Boolean
                if (existingStreaming) {
                    // Equivalente ao status 9: só mantém uma sessão que já chegou a BLE ready.
                    streamingAuthHex = previousAuthHex
                    bleMac = previousMac
                    streamingEnabled = true
                    appLogger.usage(LOG, "Existing streaming session kept", mapOf("mac" to bleMac))
                } else {
                    if (sameSensor && previousAuthHex.isNotBlank() && previousMac.isNotBlank()) {
                        appLogger.usage(LOG, "Sessão BLE persistida inválida; renovando streaming",
                            mapOf("previousStreamingValid" to previousStreamingValid))
                    }
                    // Juggluco status 3: nfctry, Gen2.activate (0x1b), then enableStreaming (0x1e).
                    val responsive = withNfcV(tag, "test NFC communication") {
                        goodnfc(issuenfc(it, byteArrayOf(0x26, 0x01, 0x00)))
                    }
                    if (!responsive) error("Sensor did not answer the NFC test")

                    val activated = withNfcV(tag, "activate the sensor") { activateSensor(it, uid) }
                    appLogger.usage(LOG, "Sensor activation (0x1b)", mapOf("ok" to activated))
                    if (!activated) error("Sensor did not confirm activation")

                    val enable = withNfcV(tag, "enable Bluetooth streaming") {
                        enableStreaming(it, uid)
                    } ?: error("Sensor did not enable Bluetooth streaming")
                    val auth = enable.first
                    val address = enable.second
                    streamingAuthHex = auth.toHex()
                    bleMac = bytesToMac(address)
                    streamingEnabled = true
                    Libre2Native.usEnabledStreaming(uid, patchInfo, auth, address)
                    Libre2Native.setSensorStart(sensorStartMs / 1000L)
                    appLogger.usage(LOG, "Streaming enabled and stored natively",
                        mapOf("auth" to streamingAuthHex, "mac" to bleMac))
                }

                Result.success(Libre2Session(
                    serial = serial,
                    uidHex = uidHex,
                    patchInfoHex = patchInfo.toHex(),
                    streamingAuthHex = streamingAuthHex,
                    bleMac = bleMac,
                    calibration = calibration,
                    framHex = fram.toHex(),
                    sensorStartMs = sensorStartMs,
                    streamingEnabled = streamingEnabled,
                    scanReading = scanResult.reading
                ))
            } finally {
                runCatching { nfcv.close() }
            }
        } catch (e: Throwable) {
            appLogger.error(LOG, "NFC activation failed",
                mapOf("error" to (e.message ?: e::class.simpleName.orEmpty())))
            Result.failure(e)
        }
    }

    private companion object {
        const val LOG = "LIBRE2_NFC"

        // Timeout de leitura NFC (Juggluco AlgNfcV.nfcReadTimeout).
        const val NFC_TIMEOUT_MS = 5_000L
        const val NFC_RETRIES = 5

        // Comandos de scanner (o byte `value` de Gen2.scanner → montado no header do comando NFC).
        const val CMD_ACTIVATE: Byte = 0x1b        // Gen2.inactivate — ativa/inicia o sensor
        const val CMD_ENABLE_STREAM: Byte = 0x1e   // Gen2.gen2enablestreaming — habilita BLE
        const val CMD_READ_PATCH: Byte = 0x1f      // Gen2.getuspatch — lê a FRAM

        // Opcodes V1/V2 (Gen2.java).
        const val OP_V1_SCAN = 28960               // V1: P1 do scanner (v1v2addcommand)
        const val OP_V2_SCAN = 6440                // V2: P2 do scanner (v1v2addcommand)
        const val OP_V1_CHECK = 29465              // V1: v1check
        const val OP_V1_ERROR = 37400              // V1: errorP1 / contactReset
        const val OP_V2_STREAM = 22321             // V2: v2v1streaming
        const val OP_V2_LAST = 6145                // V2: lastenaable
        const val OP_V2_SCANEND = 6520             // V2: lastv2 (readscanend)

        // FRAM do Libre: 344 bytes a partir do offset 0.
        const val FRAM_START = 0
        const val FRAM_LEN = 344

        // Byte de manufatura Abbott nos comandos NfcV customizados (Java -95).
        val ABB: Byte = 0xA1.toByte()

        // Alfabeto base-32 do número de série (Juggluco share/serial.cpp selnum).
        const val SELNUM = "0123456789ACDEFGHJKLMNPQRTUVWXYZ"
        val HEX = "0123456789ABCDEF".toCharArray()
    }

    /**
     * Executa a ativação a partir de uma [Tag] NFC. Nunca lança — devolve [Result] para a UI tratar.
     * Roda numa thread de IO (chamada pela Activity fora da main thread).
     */
    private fun activateLegacy(tag: Tag): Result<Libre2Session> = runCatching {
        // A ativação inteira depende da lib da Abbott (V1/V2). Sem ela, nada de cripto.
        if (!Libre2Native.init(appLogger)) {
            error("Ponte nativa da Abbott indisponível (libDataProcessing.so não carregou) — " +
                  "sem ela não é possível ativar o sensor")
        }

        val nfcv = NfcV.get(tag) ?: error("Tag não é NfcV (ISO 15693) — não é um sensor Libre 2")
        runCatching { nfcv.connect() }.getOrElse {
            error("Sensor se afastou durante a leitura (${it::class.simpleName}) — mantenha o celular " +
                  "bem encostado no sensor por 2–3 segundos")
        }
        try {
            val uid = tag.id ?: error("UID indisponível na tag")
            if (uid.size < 8) error("UID de tamanho inesperado (${uid.size} bytes) — não é um sensor Libre")

            val patchInfo = readPatchInfo(nfcv)
            // Juggluco closes the nfcinfo connection before the following Gen2 operations.
            runCatching { nfcv.close() }
            val gen = getVersion(patchInfo)
            val fam = Libre2Native.productFamily(patchInfo).takeIf { it > 0 } ?: 3
            val serial = getSerial(fam, uid).substring(5)   // Juggluco getserial(...).data()+5

            appLogger.usage(LOG, "Tag NFC conectada",
                mapOf("uid" to uid.toHex(), "patchInfo" to patchInfo.toHex(),
                      "gen" to gen, "fam" to fam, "serial" to serial))

            if (gen != 2) {
                error("Este sensor não é Gen2 (getversion=$gen). O caminho nativo cobre Libre 2 Plus/US " +
                      "(Gen2); Libre 2 EU clássico exigiria o caminho não-US.")
            }

            // (2) Ativa/inicia o sensor (comando 0x1b). Loga o resultado; a ordem (ativar → habilitar)
            // é a do Juggluco. Não abortamos aqui: seguimos p/ o enable, que é o que produz o auth.
            val responsive = withNfcV(tag, "testar a comunicacao NFC") {
                goodnfc(issuenfc(it, byteArrayOf(0x26, 0x01, 0x00)))
            }
            if (!responsive) error("O sensor nao respondeu ao teste NFC. Mantenha o celular encostado no sensor.")

            // AlgNfcV.activate(tag) -> Gen2.activate(tag): one dedicated NfcV connection.
            val activated = withNfcV(tag, "ativar o sensor") { activateSensor(it, uid) }
            appLogger.usage(LOG, "Ativação do sensor (0x1b)", mapOf("ok" to activated))

            // (3) Habilita o streaming BLE (comando 0x1e) → streamingAuthenticationData + bleAddress.
            if (!activated) error("O sensor nao confirmou a ativacao. Mantenha o celular encostado e tente novamente.")

            // AlgNfcV.enableStreaming(tag) -> Gen2.gen2enablestreaming(tag): a new connection.
            val enable = withNfcV(tag, "habilitar o streaming Bluetooth") { enableStreaming(it, uid) }
            val streamingEnabled = enable != null
            val auth = enable?.first ?: ByteArray(0)
            val address = enable?.second ?: ByteArray(0)

            if (!streamingEnabled) {
                error("O sensor nao habilitou o streaming Bluetooth. Mantenha o celular encostado e tente novamente.")
            }

            if (streamingEnabled) {
                Libre2Native.usEnabledStreaming(uid, patchInfo, auth, address)
                Libre2Native.setSensorStart(System.currentTimeMillis() / 1000)
                appLogger.usage(LOG, "Streaming habilitado (sessão gravada no nativo)",
                    mapOf("auth" to auth.toHex(), "mac" to bytesToMac(address)))
            } else {
                appLogger.error(LOG, "Enable-streaming falhou — sensor não habilitou o BLE",
                    mapOf("serial" to serial))
            }

            // (4) Best-effort: lê a FRAM (para diagnóstico/calibração). Não bloqueia o fluxo.
            val fram = runCatching {
                // AlgNfcV.readNfcTag(): FRAM uses a new connection too. It never undoes a
                // confirmed activation/streaming; it only postpones processScan.
                withNfcV(tag, "ler a memoria do sensor") { getUsPatch(it, uid, FRAM_START, FRAM_LEN) }
            }.onFailure { e ->
                appLogger.error(LOG, "Leitura opcional da FRAM falhou",
                    mapOf("erro" to (e.message ?: e::class.simpleName.orEmpty())))
            }.getOrNull()
            val scanResult = fram?.let { Libre2Native.processScan(uid, patchInfo, it) }
            val calibration = fram?.let { Libre2Calibration.fromFram(it) } ?: Libre2Calibration()
            appLogger.usage(LOG, "FRAM (getuspatch)",
                mapOf(
                    "bytes" to (fram?.size ?: 0),
                    "temParamsFabrica" to calibration.hasFactoryParams,
                    "processScanStatus" to (scanResult?.status ?: "null"),
                    "processScanMgdl" to (scanResult?.reading?.mgdl ?: 0)
                ))

            Libre2Session(
                serial = serial,
                uidHex = uid.toHex(),
                patchInfoHex = patchInfo.toHex(),
                streamingAuthHex = auth.toHex(),
                bleMac = bytesToMac(address),
                calibration = calibration,
                framHex = fram?.toHex() ?: "",
                sensorStartMs = scanResult?.sensorStartMs?.takeIf { it > 0L } ?: System.currentTimeMillis(),
                streamingEnabled = streamingEnabled
            )
        } finally {
            runCatching { nfcv.close() }
        }
    }.onFailure { e ->
        appLogger.error(LOG, "Falha na ativação NFC",
            mapOf("erro" to (e.message ?: e::class.simpleName.orEmpty())))
    }

    // ── Transporte NfcV (Juggluco AlgNfcV.issuenfc / goodnfc) ───────────────────────

    /** goodnfc: resposta válida = não-nula, não-vazia e com bit 0 do status zerado. */
    private fun goodnfc(data: ByteArray?): Boolean =
        data != null && data.isNotEmpty() && (data[0].toInt() and 1) == 0

    /**
     * Juggluco opens/closes NfcV around each Tag-level operation (nfctry, Gen2, and FRAM).
     * Command retries retain the same five-second AlgNfcV.nfcReadTimeout limit in issuenfc.
     */
    private inline fun <T> withNfcV(tag: Tag, step: String, block: (NfcV) -> T): T {
        val nfc = NfcV.get(tag) ?: error("Tag nao e NfcV (ISO 15693), portanto nao e um sensor Libre 2")
        try {
            runCatching { nfc.connect() }.getOrElse { e ->
                error("Nao foi possivel $step (${e::class.simpleName}). Mantenha o celular encostado no sensor.")
            }
            return block(nfc)
        } finally {
            runCatching { nfc.close() }
        }
    }

    /** issuenfc: transceive com até 5 tentativas dentro do timeout; devolve null se falhar. */
    private fun issuenfc(nfc: NfcV, cmd: ByteArray): ByteArray? {
        val start = System.currentTimeMillis()
        for (i in 0 until NFC_RETRIES) {
            try {
                val data = nfc.transceive(cmd)
                if (goodnfc(data)) return data
            } catch (e: Throwable) {
                if (System.currentTimeMillis() > start + NFC_TIMEOUT_MS) {
                    appLogger.usage(LOG, "issuenfc timeout", mapOf("cmd" to cmd.toHex()))
                    return null
                }
            }
        }
        return null
    }

    /** patchInfo via nfcinfo (`{02 A1 07}`), descartando o byte de status. */
    private fun readPatchInfo(nfcv: NfcV): ByteArray {
        val resp = issuenfc(nfcv, byteArrayOf(0x02, ABB, 0x07))
        return if (resp != null && resp.size > 1) resp.copyOfRange(1, resp.size) else ByteArray(6)
    }

    // ── Primitivas de cripto (Gen2 V1/V2 → Libre2Native → P1/P2 da Abbott) ──────────

    private fun v1(op: Int, i2: Int, a: ByteArray?, b: ByteArray?): Int = Libre2Native.v1(op, i2, a, b)
    private fun v2(op: Int, i2: Int, a: ByteArray?, b: ByteArray?): ByteArray? = Libre2Native.v2(op, i2, a, b)

    private fun errorP1(i: Int): Int = v1(OP_V1_ERROR, i, null, null)
    private fun v1check(i: Int, i2: Int, bArr: ByteArray): Int = v1(OP_V1_CHECK, i, byteArrayOf(i2.toByte()), bArr)
    private fun lastv2(i: Int, i2: Int, i3: Int, bArr: ByteArray): ByteArray? =
        v2(OP_V2_SCANEND, i, byteArrayOf(i2.toByte(), i3.toByte()), bArr)

    private fun fsllFailure() { appLogger.error(LOG, "FSLLAuthFailure") }

    // ── Scanner (Gen2.scanner + v1v2addcommand) ─────────────────────────────────────

    /**
     * Gen2.scanner: lê `numb` (`{02 A1 uid[6] 22}`) + o bloco de auth (`{02 A1 uid[6] 20}`), roda o
     * par P1/P2 e escreve o comando NFC resultante em [uitar]. Retorna o handle P1 (>=0) ou -1.
     */
    private fun scanner(nfc: NfcV, uid: ByteArray, value: Byte, uitar: ByteArray): Int {
        return try {
            val always7 = uid[6]
            val extra = issuenfc(nfc, byteArrayOf(0x02, ABB, always7, 34))
            if (!goodnfc(extra)) {
                appLogger.error(LOG, "scanner: comando {02 A1 .. 22} falhou")
                return -1
            }
            // numb = (extra[3] | (extra[4] << 8)) & 0xFFFF — semântica de int idêntica ao Java.
            val numb = ((extra!![3].toInt()) or (extra[4].toInt() shl 8)) and 0xFFFF
            val a = issuenfc(nfc, byteArrayOf(0x02, ABB, always7, 32))
            if (goodnfc(a)) {
                val result = a!!.copyOfRange(1, a.size)
                v1v2addcommand(value.toInt(), numb, uid, result, uitar)
            } else {
                appLogger.error(LOG, "scanner: comando {02 A1 .. 20} falhou")
                -1
            }
        } catch (e: Throwable) {
            appLogger.error(LOG, "scanner: exceção", mapOf("erro" to (e.message ?: e::class.simpleName.orEmpty())))
            -1
        }
    }

    /** Gen2.v1v2addcommand: P1(28960,numb,uid) → P2(6440,resP1,{0,value},result); monta o header. */
    private fun v1v2addcommand(value: Int, numb: Int, uid: ByteArray, result: ByteArray, bArrout: ByteArray): Int {
        val resP1 = v1(OP_V1_SCAN, numb, uid, null)
        if (resP1 < 0) return resP1
        val b = value.toByte()
        val p2 = v2(OP_V2_SCAN, resP1, byteArrayOf(0, b), result) ?: run {
            errorP1(resP1); return -99
        }
        System.arraycopy(p2, 0, bArrout, 0, p2.size)
        bArrout[0] = 0x02
        bArrout[1] = ABB
        bArrout[2] = 0x07
        bArrout[3] = b
        return resP1
    }

    // ── Ativar / habilitar streaming (Gen2.inactivate / gen2enablestreaming) ────────

    /** Gen2.inactivate (comando 0x1b): ativa/inicia o sensor. */
    private fun activateSensor(nfc: NfcV, uid: ByteArray): Boolean {
        val outar = ByteArray(0x13)
        val resv1 = scanner(nfc, uid, CMD_ACTIVATE, outar)
        if (resv1 < 0) return false
        val resp1 = issuenfc(nfc, outar)
        val res = resp1 != null &&
            v2v1streaming(resv1, 0, resp1.copyOfRange(1, resp1.size), ByteArray(5)) > 0
        errorP1(resv1)
        return res
    }

    /**
     * Gen2.gen2enablestreaming (comando 0x1e): habilita o streaming BLE.
     * @return `(streamingAuthenticationData[12], bleAddress[6])` ou null se falhar.
     */
    private fun enableStreaming(nfc: NfcV, uid: ByteArray): Pair<ByteArray, ByteArray>? {
        val outar = ByteArray(0x13)
        val resv1 = scanner(nfc, uid, CMD_ENABLE_STREAM, outar)
        appLogger.usage(LOG, "enableStreaming: scanner", mapOf("resv1" to resv1))
        if (resv1 < 0) return null
        val resp1 = issuenfc(nfc, outar)
        if (!goodnfc(resp1)) {
            fsllFailure(); if (resv1 > 0) errorP1(resv1); return null
        }
        val tail = resp1!!.copyOfRange(1, resp1.size)
        val streamingAuth = ByteArray(12)
        val bleAddress = ByteArray(6)
        return if (lastenaable(resv1, tail, streamingAuth, bleAddress)) {
            if (resv1 > 0) errorP1(resv1)
            streamingAuth to bleAddress
        } else {
            fsllFailure(); null
        }
    }

    /** Gen2.v2v1streaming: P2(22321,i,{i2, len}, bArr) → copia para bArr2. Retorna len ou <0. */
    private fun v2v1streaming(i: Int, i2: Int, bArr: ByteArray, bArr2: ByteArray): Int {
        val p2 = v2(OP_V2_STREAM, i, byteArrayOf(i2.toByte(), bArr2.size.toByte()), bArr) ?: run {
            v1(OP_V1_ERROR, i, null, null); return -99
        }
        System.arraycopy(p2, 0, bArr2, 0, p2.size)
        return bArr2.size
    }

    /**
     * Gen2.lastenaable (newVersion): monta o `streamingAuthenticationData` de 12 bytes e o `bleAddress`
     * de 6 bytes a partir de `v2v1streaming` (9B) + `P2(6145)` (9B). Fiel ao layout do Juggluco:
     * `bleAddress = bArr4[0..6)`, `auth = P2[0..7) + bArr4[6..9) + P2[7..9)`.
     */
    private fun lastenaable(i: Int, bArr: ByteArray, streamingAuth: ByteArray, bleAddress: ByteArray): Boolean {
        val bArr4 = ByteArray(9)
        if (v2v1streaming(i, 0, bArr, bArr4) < 0) return false
        val p2 = v2(OP_V2_LAST, i, byteArrayOf(9), null) ?: return false
        val begin = p2.size - 2
        if (begin < 0 || begin + 5 > streamingAuth.size) {
            appLogger.error(LOG, "lastenaable: P2res de tamanho inesperado", mapOf("len" to p2.size))
            return false
        }
        System.arraycopy(bArr4, 0, bleAddress, 0, 6)
        System.arraycopy(p2, 0, streamingAuth, 0, begin)
        System.arraycopy(bArr4, 6, streamingAuth, begin, 3)
        System.arraycopy(p2, begin, streamingAuth, begin + 3, 2)
        return true
    }

    // ── Leitura da FRAM (Gen2.getuspatch / readscanend) ─────────────────────────────

    /** Gen2.getuspatch (comando 0x1f): faz o handshake de scan e devolve a FRAM decriptada, ou null. */
    private fun getUsPatch(nfc: NfcV, uid: ByteArray, start: Int, len: Int): ByteArray? {
        val outar = ByteArray(0x13)
        val res = scanner(nfc, uid, CMD_READ_PATCH, outar)
        if (res < 0) {
            appLogger.error(LOG, "getuspatch: scanner failed", mapOf("res" to res))
            return null
        }
        val transres = issuenfc(nfc, outar)
        if (!goodnfc(transres)) {
            appLogger.error(LOG, "getuspatch: encrypted response failed",
                mapOf("res" to res, "bytes" to (transres?.size ?: 0)))
            errorP1(res); return null
        }
        val notfirst = transres!!.copyOfRange(1, transres.size)
        val checked = v1check(res, 0, notfirst)
        appLogger.usage(LOG, "getuspatch: v1check", mapOf("res" to res, "checked" to checked))
        when {
            checked == -0xf -> {
                appLogger.error(LOG, "getuspatch: v1check == -0xf"); fsllFailure()
                if (res > 0) errorP1(res); return null
            }
            checked == -0x9 -> {
                appLogger.error(LOG, "getuspatch: v1check == -0x9"); fsllFailure()
                if (res > 0) errorP1(res); return null
            }
            checked >= 0 -> {
                val fram = readscanend(nfc, start, len, res)
                if (fram == null) {
                    appLogger.error(LOG, "getuspatch: readscanend failed", mapOf("res" to res))
                }
                if (res != 0) errorP1(res)
                return fram
            }
            else -> {
                appLogger.error(LOG, "getuspatch: v1check < 0", mapOf("checked" to checked))
                if (res > 0) errorP1(res); return null
            }
        }
    }

    /** Gen2.readscanend: lê os blocos cifrados (`{02 A1 07 21 ..}`) e decripta via lastv2 (V2 6520). */
    private fun readscanend(nfc: NfcV, start: Int, len: Int, extra: Int): ByteArray? {
        val startinblocks = start / 8
        val startblock = start % 8
        val totlen = len + startblock
        val blocks = (totlen + 7) / 8
        val buf = ByteArray(blocks * 8)
        var iter = 0
        var uitit = 0
        while (iter < blocks) {
            val curblock = startinblocks + iter
            val min = minOf(3, blocks - iter)
            val received = issuenfc(nfc, byteArrayOf(0x02, ABB, 0x07, 33, curblock.toByte(), (min - 1).toByte()))
            val minimum = ((min + 1) * 8) + 1
            if (!goodnfc(received) || received!!.size < minimum) {
                appLogger.error(LOG, "readscanend: invalid block response",
                    mapOf("block" to curblock, "requested" to min,
                          "bytes" to (received?.size ?: 0), "minimum" to minimum))
                return null
            }
            val takelen = received.size - 9
            System.arraycopy(received, 9, buf, uitit, takelen)
            uitit += takelen
            iter += min
        }
        val doorp2 = lastv2(extra, startinblocks, blocks, buf) ?: run {
            appLogger.error(LOG, "readscanend: lastv2 failed",
                mapOf("startBlock" to startinblocks, "blocks" to blocks, "bufferBytes" to buf.size))
            return null
        }
        return doorp2.copyOfRange(startblock, totlen)
    }

    // ── getversion + serial (Gen2.getversionjava / share/serial.cpp getserial) ──────

    /** getversion (Gen2.getversionjava): 2 = Gen2 (US/Libre 2 Plus), 1 = Gen1, 0 = desconhecido. */
    private fun getVersion(info: ByteArray): Int = LibrePatchInfo.generation(info)

    /** getserial(fam, uid): número de série completo ("XXXX-<fam><10 chars>"). Ver share/serial.cpp. */
    private fun getSerial(fam: Int, b: ByteArray): String {
        fun u(idx: Int) = b[idx].toInt() and 0xFF
        fun sel(idx: Int) = SELNUM[idx and 31]
        val sb = StringBuilder(16)
        sb.append(HEX[u(7) ushr 4]); sb.append(HEX[u(7) and 0xF])
        sb.append(HEX[u(6) ushr 4]); sb.append(HEX[u(6) and 0xF])
        sb.append('-')
        sb.append(SELNUM[fam and 31])
        sb.append(sel(u(5) ushr 3))
        sb.append(sel((u(4) ushr 6) or ((u(5) and 7) shl 2)))
        sb.append(sel((u(4) ushr 1) and 31))
        sb.append(sel(((u(4) and 1) shl 4) or (u(3) ushr 4)))
        sb.append(sel(((u(3) and 15) shl 1) or (u(2) ushr 7)))
        sb.append(sel((u(2) ushr 2) and 31))
        sb.append(sel(((u(2) and 3) shl 3) or (u(1) ushr 5)))
        sb.append(sel(u(1) and 31))
        sb.append(sel(u(0) ushr 3))
        sb.append(sel((u(0) shl 2) and 31))
        return sb.toString()
    }

    /** bleAddress (6B) → MAC "XX:XX:XX:XX:XX:XX" com bytes invertidos (Juggluco setDeviceAddressB). */
    private fun bytesToMac(addr: ByteArray): String {
        if (addr.size != 6) return ""
        val sb = StringBuilder(17)
        for (i in 5 downTo 0) {
            val v = addr[i].toInt() and 0xFF
            sb.append(HEX[v ushr 4]); sb.append(HEX[v and 0xF])
            if (i != 0) sb.append(':')
        }
        return sb.toString()
    }
}
