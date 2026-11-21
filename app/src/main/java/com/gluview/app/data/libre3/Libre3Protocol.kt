package com.gluview.app.data.libre3

import java.util.UUID
import kotlin.math.roundToInt

/**
 * Protocolo do FreeStyle Libre 3 em Kotlin puro — porte do Juggluco 11.5.x (GPLv3):
 * - UUIDs: `SuperGattCallback.java:74-95`;
 * - certificados do app: `KEYSCrypto.java:29-40`;
 * - pacotes e comandos: `cpp/libre3/bluetooth.cpp` (structs `oneminute`, `fastData`, `HistoryData`,
 *   `Patchstatus`, `EventLog`, `RequestData`);
 * - ativação NFC: `cpp/libre3/nfc.cpp` (`firstnfc`, `nfc2`, `nfc2error`), `libre3/NFC.java`,
 *   `Libre3.java` e `cpp/libre3/dp_activation.hpp` (CRC do comando).
 *
 * Tudo little-endian (structs `packed` do C em ARM). Sem dependência de Android: testado na JVM.
 */
object Libre3Protocol {

    // ---------------------------------------------------------------- UUIDs
    val DATA_SERVICE: UUID = UUID.fromString("089810cc-ef89-11e9-81b4-2a2ae2dbcce4")
    val SECURITY_SERVICE: UUID = UUID.fromString("0898203a-ef89-11e9-81b4-2a2ae2dbcce4")
    val CHAR_PATCH_CONTROL: UUID = UUID.fromString("08981338-ef89-11e9-81b4-2a2ae2dbcce4")
    val CHAR_PATCH_STATUS: UUID = UUID.fromString("08981482-ef89-11e9-81b4-2a2ae2dbcce4")
    val CHAR_EVENT_LOG: UUID = UUID.fromString("08981bee-ef89-11e9-81b4-2a2ae2dbcce4")
    val CHAR_GLUCOSE_DATA: UUID = UUID.fromString("0898177a-ef89-11e9-81b4-2a2ae2dbcce4")
    val CHAR_HISTORIC_DATA: UUID = UUID.fromString("0898195a-ef89-11e9-81b4-2a2ae2dbcce4")
    val CHAR_CLINICAL_DATA: UUID = UUID.fromString("08981ab8-ef89-11e9-81b4-2a2ae2dbcce4")
    val CHAR_FACTORY_DATA: UUID = UUID.fromString("08981d24-ef89-11e9-81b4-2a2ae2dbcce4")
    val SEC_CHAR_COMMAND_RESPONSE: UUID = UUID.fromString("08982198-ef89-11e9-81b4-2a2ae2dbcce4")
    val SEC_CHAR_CHALLENGE_DATA: UUID = UUID.fromString("089822ce-ef89-11e9-81b4-2a2ae2dbcce4")
    val SEC_CHAR_CERT_DATA: UUID = UUID.fromString("089823fa-ef89-11e9-81b4-2a2ae2dbcce4")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** Tipos de pacote da cifra ([Libre3Crypto.PACKET_DESCRIPTOR]). */
    object Kind {
        const val CONTROL = 0
        const val CONTROL_RESPONSE = 1
        const val PATCH_STATUS = 2
        const val GLUCOSE = 3
        const val HISTORY = 4
        const val CLINICAL = 5
        const val EVENT_LOG = 6
        const val FACTORY = 7
    }

    /** Leitura do minuto: 35 bytes cifrados (29 claros + 4 tag + 2 sequência). */
    const val MINUTE_PACKET_LEN = 35
    const val MINUTE_PLAIN_LEN = 29
    const val FAST_DATA_LEN = 14
    const val PATCH_STATUS_LEN = 12
    const val EVENT_LOG_ENTRY_LEN = 7
    /** Histórico do Libre 3 a cada 5 min (`getmininterval()` do sensor Libre 3). */
    const val HISTORY_INTERVAL_MIN = 5
    const val MIN_VALID_MGDL = 39
    const val MAX_VALID_MGDL = 501

    // ---------------------------------------------------------------- certificados do app
    /** `KEYSCrypto.LIBRE3_APP_CERTIFICATES_B`; o Juggluco usa sempre o índice 1 (nível 1). */
    // Bytes extraídos por script de KEYSCrypto.java (162 bytes cada; teste confere tamanho e SHA-256).
    val APP_CERTIFICATES: Array<ByteArray> = arrayOf(
        hex(
            "03000102030405060708090a0b0c0d0e0f1000015f149fe1010000000000000000042751fd1ef42b14" +
            "5a52c593ae6b5a75588a9f7eaf1c0f9985f993d58f147bb84168422449963792dc43f38447efebbbeb" +
            "4a53b3255c0be0fe1f235844a3d3299eba97b8e6c3170939f2778f64866f066deb915dd6629eee4730" +
            "a1e14cab75c18c4fec53f8854c87643a764f4087aec0394c210c18865a8ff45adc3727f48b53a7"
        ),
        hex(
            "03030102030405060708090a0b0c0d0e0f10000161897655010000000000000000048242be33f1a330" +
            "880112fa62cc4842a43d1204922ad201d8775bb226f611f75b0ef3d5bc6cc4317caa457584ab003f17" +
            "12336089d3a4f29838ed0dc666deaea2d65a00dfff5d7bcae21655e302e3458e774daaaaca87af75f1" +
            "b87884b18d4ce875d0d108c903a834471a4ff674b2d30bcba062373014b7786e4437b177aec3c8"
        )
    )
    const val APP_SECURITY_LEVEL = 1

    // ---------------------------------------------------------------- pacotes recebidos

    /** `struct oneminute` (29 bytes). */
    data class OneMinute(
        val lifeCount: Int,
        val readingMgDl: Int,
        val rateOfChange: Int,
        val esaDuration: Int,
        val projectedGlucose: Int,
        val historicalLifeCount: Int,
        val historicalReading: Int,
        val trend: Int,
        val uncappedCurrentMgDl: Int,
        val uncappedHistoricMgDl: Int,
        val temperature: Int
    ) {
        /** Valor exibido: `UNCAPPED` do Juggluco (save3current) — 1..38 vira 39 (LO), 502..999 vira 501 (HI). */
        val currentMgDl: Int? get() = clampValid(uncappedCurrentMgDl)
        val historicMgDl: Int? get() = clampValid(uncappedHistoricMgDl)

        /** mg/dL por minuto: `rateOfChange/100`; sem medida (-32768) usa a seta (`trend2rate`). */
        val rateMgDlPerMin: Double?
            get() = if (rateOfChange == -32768) {
                if (trend == 0) null else (trend - 3.0) * 1.3
            } else rateOfChange / 100.0
    }

    fun parseOneMinute(b: ByteArray): OneMinute? {
        if (b.size != MINUTE_PLAIN_LEN) return null
        return OneMinute(
            lifeCount = u16(b, 0),
            readingMgDl = u16(b, 2),
            rateOfChange = s16(b, 4),
            esaDuration = u16(b, 6),
            projectedGlucose = u16(b, 8),
            historicalLifeCount = u16(b, 10),
            historicalReading = u16(b, 12),
            trend = b[14].toInt() and 0x07,
            uncappedCurrentMgDl = u16(b, 15),
            uncappedHistoricMgDl = u16(b, 17),
            temperature = u16(b, 19)
        )
    }

    /** `struct fastData` (14 bytes, dados clínicos de backfill). */
    data class FastData(val lifeCount: Int, val readingMgDl: Int, val historicMgDl: Int) {
        val historicLifeCount: Int get() = (((lifeCount - 19.0) / 5.0).roundToInt()) * 5
        val current: Int? get() = readingMgDl.takeIf { it in MIN_VALID_MGDL..MAX_VALID_MGDL }
        val historic: Int? get() = historicMgDl.takeIf { it in MIN_VALID_MGDL..MAX_VALID_MGDL }
    }

    fun parseFastData(b: ByteArray): FastData? =
        if (b.size != FAST_DATA_LEN) null else FastData(u16(b, 0), u16(b, 10), u16(b, 12))

    /** `struct HistoryData`: lifeCount inicial + valores a cada [HISTORY_INTERVAL_MIN]. Só os válidos. */
    fun parseHistory(b: ByteArray): List<Pair<Int, Int>> {
        if (b.size < 4) return emptyList()
        val start = u16(b, 0)
        val out = ArrayList<Pair<Int, Int>>()
        var i = 2
        var life = start
        while (i + 1 < b.size) {
            val v = u16(b, i)
            if (v in MIN_VALID_MGDL..MAX_VALID_MGDL) out += life to v
            i += 2
            life += HISTORY_INTERVAL_MIN
        }
        return out
    }

    /** `struct Patchstatus` (12 bytes). */
    data class PatchStatus(
        val lifeCount: Int,
        val errorData: Int,
        val eventData: Int,
        val index: Int,
        val patchState: Int,
        val currentLifeCount: Int,
        val stackDisconnectReason: Int,
        val appDisconnectReason: Int
    )

    fun parsePatchStatus(b: ByteArray): PatchStatus? {
        if (b.size != PATCH_STATUS_LEN) return null
        return PatchStatus(
            lifeCount = s16(b, 0), errorData = s16(b, 2), eventData = s16(b, 4),
            index = b[6].toInt(), patchState = b[7].toInt(), currentLifeCount = s16(b, 8),
            stackDisconnectReason = b[10].toInt(), appDisconnectReason = b[11].toInt()
        )
    }

    /** Último índice de evento válido (≠ 0xFF), de trás para frente; -1 se nenhum (`libre3EventLog`). */
    fun lastEventIndex(b: ByteArray): Int {
        val units = b.size / EVENT_LOG_ENTRY_LEN
        for (i in units - 1 downTo 0) {
            val idx = b[i * EVENT_LOG_ENTRY_LEN + 6].toInt() and 0xFF
            if (idx != 0xFF) return idx
        }
        return -1
    }

    /** Histórico pedido a partir de `((lifeCount-16)/5)*5` (`receivedpatchstatus`). */
    fun backfillStartHistoric(currentLifeCount: Int): Int = ((currentLifeCount - 16) / 5) * 5

    // ---------------------------------------------------------------- comandos (antes de cifrar)
    fun controlHistory(arg: Int, from: Int): ByteArray = request(1, 0, arg, from)
    fun clinicalControl(arg: Int, from: Int): ByteArray = request(1, 1, arg, from)
    fun eventLogControl(from: Int): ByteArray =
        byteArrayOf(4, from.toByte(), (from shr 8).toByte(), (from shr 16).toByte(), (from shr 24).toByte(), 0, 0)

    private fun request(k0: Int, k1: Int, arg: Int, from: Int) = byteArrayOf(
        k0.toByte(), k1.toByte(), arg.toByte(),
        from.toByte(), (from shr 8).toByte(), (from shr 16).toByte(), (from shr 24).toByte()
    )

    // ---------------------------------------------------------------- NFC
    val NFC_FIRST_COMMAND = byteArrayOf(0x02, 0xA1.toByte(), 0x7A)

    /** `struct firstnfc` (26 bytes depois do cabeçalho `00 A5 00`). */
    data class NfcInfo(
        val securityVersion: Int,
        val localization: Int,
        val puckGeneration: Int,
        val wearDurationMin: Int,
        val firmwareVersion: Long,
        val productType: Int,
        val warmupMin: Int,
        val state: Int,
        val serial: String
    ) {
        /** Produto 9 = Lingo (outro tipo de credencial; fora do escopo do GluView). */
        val isLingo: Boolean get() = productType == 9
        /** Estado 1 = sensor novo, ainda não ativado (`NFC.java`: `nfc1[17]==1`). */
        val needsActivation: Boolean get() = state == 1
    }

    fun parseNfcInfo(r: ByteArray): NfcInfo? {
        if (r.size < 26 + 3) return null
        // Igual ao `nfc1` do C: pula o 1º byte, os A5 seguidos e mais um (o byte que encerrou o laço).
        var i = 1
        while (i < r.size) { val b = r[i++].toInt() and 0xFF; if (b != 0xA5) break }
        if (r.size - i < 26) return null
        return NfcInfo(
            securityVersion = u16(r, i), localization = u16(r, i + 2), puckGeneration = u16(r, i + 4),
            wearDurationMin = u16(r, i + 6), firmwareVersion = u32(r, i + 8),
            productType = r[i + 12].toInt() and 0xFF, warmupMin = (r[i + 13].toInt() and 0xFF) * 5,
            state = r[i + 14].toInt() and 0xFF,
            serial = String(r, i + 15, 9, Charsets.US_ASCII)
        )
    }

    /** 2º comando: `02 A0 7A` (ativar sensor novo) ou `02 A8 7A` (assumir sensor já ativo) + 10 bytes. */
    fun activationCommand(info: NfcInfo, nowSec: Long, accountId: Long): ByteArray =
        byteArrayOf(0x02, if (info.needsActivation) 0xA0.toByte() else 0xA8.toByte(), 0x7A) +
            activationData(nowSec, accountId)

    /** `DPGetActivationCommandData`: LE32(hora-1) + LE32(conta) + CRC16 LE. */
    fun activationData(nowSec: Long, accountId: Long): ByteArray {
        val a = (nowSec - 1).toInt()
        val c = accountId.toInt()
        val out = byteArrayOf(
            a.toByte(), (a shr 8).toByte(), (a shr 16).toByte(), (a shr 24).toByte(),
            c.toByte(), (c shr 8).toByte(), (c shr 16).toByte(), (c shr 24).toByte(), 0, 0
        )
        val crc = crc16Activation(out, 8)
        out[8] = (crc and 0xFF).toByte(); out[9] = (crc shr 8).toByte()
        return out
    }

    /** CRC-16 poly 0x1021, init 0xFFFF, entrada refletida, saída não refletida (dp_activation.hpp). */
    fun crc16Activation(data: ByteArray, len: Int = data.size): Int {
        var crc = 0xFFFF
        for (k in 0 until len) {
            crc = crc xor (Integer.reverse(data[k].toInt() and 0xFF).ushr(24) shl 8)
            repeat(8) { crc = if (crc and 0x8000 != 0) ((crc shl 1) xor 0x1021) and 0xFFFF else (crc shl 1) and 0xFFFF }
        }
        return crc
    }

    sealed interface ActivationResult {
        /** `nfc2`: endereço BLE, PIN (4 bytes brutos) e hora de ativação (s). */
        data class Ok(val bleAddress: String, val pin: ByteArray, val activationSec: Long) : ActivationResult
        /** Erro 0xB1: o sensor foi ativado com outro ID de conta (outro app/conta). */
        data object WrongAccount : ActivationResult
        data object SensorEnded : ActivationResult
        data class Failed(val reason: String) : ActivationResult
    }

    fun parseActivationResponse(r: ByteArray?, info: NfcInfo, nowSec: Long): ActivationResult {
        if (r == null) return ActivationResult.Failed("sem resposta do sensor")
        if (r.size == 19) {
            val mac = (5 downTo 0).joinToString(":") { "%02X".format(r[3 + it].toInt() and 0xFF) }
            val pin = r.copyOfRange(9, 13)
            val act = u32(r, 13).takeIf { it > 0 } ?: nowSec
            return ActivationResult.Ok(mac, pin, act)
        }
        if (r.size == 4 && u16(r, 1) == 0x01A5) {
            val err = r[3].toInt() and 0xFF
            return when {
                err == 0xB1 -> ActivationResult.WrongAccount
                info.state == 8 || info.state == 6 -> ActivationResult.SensorEnded
                else -> ActivationResult.Failed("erro do sensor 0x%02X (estado %d)".format(err, info.state))
            }
        }
        return ActivationResult.Failed("resposta com ${r.size} bytes")
    }

    // ---------------------------------------------------------------- utilidades
    private fun clampValid(v: Int): Int? {
        var x = v
        if (x in 1..38) x = MIN_VALID_MGDL
        if (x in 502..999) x = MAX_VALID_MGDL
        return x.takeIf { it in MIN_VALID_MGDL..MAX_VALID_MGDL }
    }

    fun u16(b: ByteArray, o: Int): Int = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    fun s16(b: ByteArray, o: Int): Int = u16(b, o).toShort().toInt()
    fun u32(b: ByteArray, o: Int): Long =
        (u16(b, o).toLong()) or (u16(b, o + 2).toLong() shl 16)

    fun hex(s: String): ByteArray {
        val c = s.filter { !it.isWhitespace() }
        return ByteArray(c.length / 2) { ((Character.digit(c[2 * it], 16) shl 4) or Character.digit(c[2 * it + 1], 16)).toByte() }
    }
}
