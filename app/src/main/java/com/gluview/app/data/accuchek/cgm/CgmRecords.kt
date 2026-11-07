package com.gluview.app.data.accuchek.cgm

import java.time.LocalDateTime
import java.time.ZoneOffset

/** Resultado de um leitor de característica: valor ou motivo da recusa (nunca lança exceção). */
sealed class CgmParse<out T> {
    data class Ok<T>(val value: T) : CgmParse<T>()
    data class Invalid(val reason: String) : CgmParse<Nothing>()

    fun getOrNull(): T? = (this as? Ok<T>)?.value
}

/** Situação do CRC de um registro. */
enum class CrcState { ABSENT, VALID, INVALID }

private fun crcState(record: ByteArray, present: Boolean): CrcState = when {
    !present -> CrcState.ABSENT
    E2eCrc.hasValidTrailing(record) -> CrcState.VALID
    else -> CrcState.INVALID
}

private fun u16(b: ByteArray, i: Int): Int = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
private fun u8(b: ByteArray, i: Int): Int = b[i].toInt() and 0xFF

/**
 * Sensor Status Annunciation (24 bits): bits 0–7 = octeto Status, 8–15 = Cal/Temp, 16–23 = Warning.
 * Mesmo layout no CGM Measurement e no CGM Status.
 */
@JvmInline
value class SensorAnnunciation(val bits: Int) {
    private fun bit(n: Int) = bits and (1 shl n) != 0

    val sessionStopped get() = bit(0)
    val batteryLow get() = bit(1)
    val sensorTypeIncorrect get() = bit(2)
    val sensorMalfunction get() = bit(3)
    val deviceSpecificAlert get() = bit(4)
    val generalDeviceFault get() = bit(5)

    val timeSyncRequired get() = bit(8)
    val calibrationNotAllowed get() = bit(9)
    val calibrationRecommended get() = bit(10)
    val calibrationRequired get() = bit(11)
    val temperatureTooHigh get() = bit(12)
    val temperatureTooLow get() = bit(13)
    val calibrationPending get() = bit(14)

    val belowPatientLow get() = bit(16)
    val abovePatientHigh get() = bit(17)
    val belowHypo get() = bit(18)
    val aboveHyper get() = bit(19)
    val rateOfDecreaseExceeded get() = bit(20)
    val rateOfIncreaseExceeded get() = bit(21)
    val belowDeviceRange get() = bit(22)
    val aboveDeviceRange get() = bit(23)

    /** Nomes dos bits ligados, para log (em inglês, como na spec). */
    fun labels(): List<String> = (0 until 24).filter { bit(it) }.map { LABELS[it] ?: "reserved bit $it" }

    override fun toString(): String = labels().ifEmpty { listOf("ok") }.joinToString(", ")

    companion object {
        val NONE = SensorAnnunciation(0)

        private val LABELS = mapOf(
            0 to "session stopped", 1 to "battery low", 2 to "sensor type incorrect",
            3 to "sensor malfunction", 4 to "device specific alert", 5 to "general device fault",
            8 to "time sync required", 9 to "calibration not allowed", 10 to "calibration recommended",
            11 to "calibration required", 12 to "temperature too high", 13 to "temperature too low",
            14 to "calibration pending", 16 to "below patient low", 17 to "above patient high",
            18 to "below hypo", 19 to "above hyper", 20 to "rate of decrease exceeded",
            21 to "rate of increase exceeded", 22 to "below device range", 23 to "above device range"
        )
    }
}

/**
 * Um registro do CGM Measurement (0x2AA7).
 *
 * @param glucoseMgDl mg/dL; −∞ = abaixo da faixa ("LO"), +∞ = acima ("HI"), NaN = sem valor (erro).
 * @param timeOffsetMinutes minutos desde o início da sessão (a hora vem de início + este valor).
 * @param trendMgDlPerMin tendência calculada pelo próprio sensor, quando presente.
 * @param qualityPercent qualidade da leitura (%), quando presente.
 */
data class CgmMeasurement(
    val glucoseMgDl: Float,
    val timeOffsetMinutes: Int,
    val annunciation: SensorAnnunciation,
    val trendMgDlPerMin: Float?,
    val qualityPercent: Float?,
    val crc: CrcState,
    val raw: ByteArray
) {
    val isLow: Boolean get() = glucoseMgDl == Float.NEGATIVE_INFINITY
    val isHigh: Boolean get() = glucoseMgDl == Float.POSITIVE_INFINITY
    val hasValue: Boolean get() = !glucoseMgDl.isNaN() && !glucoseMgDl.isInfinite()

    override fun equals(other: Any?): Boolean =
        other is CgmMeasurement && glucoseMgDl.equals(other.glucoseMgDl) &&
            timeOffsetMinutes == other.timeOffsetMinutes && annunciation == other.annunciation &&
            trendMgDlPerMin == other.trendMgDlPerMin && qualityPercent == other.qualityPercent &&
            crc == other.crc && raw.contentEquals(other.raw)

    override fun hashCode(): Int = raw.contentHashCode()
}

/**
 * Leitor do CGM Measurement (0x2AA7), layout da Bluetooth SIG:
 *
 * `Size · Flags · Glicose(SFLOAT) · TimeOffset(u16) · [Status] · [Cal/Temp] · [Warning] ·
 *  [Tendência(SFLOAT)] · [Qualidade(SFLOAT)] · [E2E-CRC]`
 *
 * Flags: bit0 tendência, bit1 qualidade, bit5 octeto Warning, bit6 Cal/Temp, bit7 Status.
 *
 * Ordem dos octetos de aviso = Status, Cal/Temp, Warning — tirada dos bytes reais: o octeto
 * Cal/Temp do SmartGuide vale `02` em toda leitura normal (flags 0x43, 1 octeto), e nos registros
 * com mais octetos (Juggluco `cpp/accu/java.cpp`) o `02` fica no meio: erro `0E C3 FF07 … 08 02`
 * (Status 08 = defeito) e "LO" `0F E3 0208 … 08 02 40` (Warning 40 = abaixo da faixa do aparelho).
 *
 * Uma notificação pode carregar vários registros seguidos, cada um começando pelo seu tamanho.
 */
object CgmMeasurementParser {
    private const val FLAG_TREND = 0x01
    private const val FLAG_QUALITY = 0x02
    private const val FLAG_WARNING_OCTET = 0x20
    private const val FLAG_CAL_TEMP_OCTET = 0x40
    private const val FLAG_STATUS_OCTET = 0x80
    private const val MIN_RECORD = 6

    fun parse(value: ByteArray): CgmParse<List<CgmMeasurement>> {
        if (value.isEmpty()) return CgmParse.Invalid("notificação vazia")
        val records = mutableListOf<CgmMeasurement>()
        var i = 0
        while (i < value.size) {
            val size = value[i].toInt() and 0xFF
            if (size < MIN_RECORD || i + size > value.size) {
                return CgmParse.Invalid("tamanho de registro $size inválido na posição $i (total ${value.size})")
            }
            when (val r = parseRecord(value.copyOfRange(i, i + size))) {
                is CgmParse.Ok -> records += r.value
                is CgmParse.Invalid -> return CgmParse.Invalid("registro na posição $i: ${r.reason}")
            }
            i += size
        }
        return CgmParse.Ok(records)
    }

    private fun parseRecord(r: ByteArray): CgmParse<CgmMeasurement> {
        val flags = u8(r, 1)
        var p = MIN_RECORD
        var status = 0
        var calTemp = 0
        var warning = 0
        if (flags and FLAG_STATUS_OCTET != 0) { if (p >= r.size) return short(); status = u8(r, p++) }
        if (flags and FLAG_CAL_TEMP_OCTET != 0) { if (p >= r.size) return short(); calTemp = u8(r, p++) }
        if (flags and FLAG_WARNING_OCTET != 0) { if (p >= r.size) return short(); warning = u8(r, p++) }
        var trend: Float? = null
        var quality: Float? = null
        if (flags and FLAG_TREND != 0) { if (p + 2 > r.size) return short(); trend = SFloat.read(r, p); p += 2 }
        if (flags and FLAG_QUALITY != 0) { if (p + 2 > r.size) return short(); quality = SFloat.read(r, p); p += 2 }
        val remaining = r.size - p
        if (remaining != 0 && remaining != 2) return CgmParse.Invalid("$remaining bytes sobrando no fim do registro")
        return CgmParse.Ok(
            CgmMeasurement(
                glucoseMgDl = SFloat.read(r, 2),
                timeOffsetMinutes = u16(r, 4),
                annunciation = SensorAnnunciation(status or (calTemp shl 8) or (warning shl 16)),
                trendMgDlPerMin = trend,
                qualityPercent = quality,
                crc = crcState(r, remaining == 2),
                raw = r
            )
        )
    }

    private fun short() = CgmParse.Invalid("registro termina antes dos campos que as flags anunciam")
}

/** CGM Status (0x2AA9): minutos desde o início + avisos. É daqui que sai a hora de início. */
data class CgmStatus(val timeOffsetMinutes: Int, val annunciation: SensorAnnunciation, val crc: CrcState)

object CgmStatusParser {
    fun parse(b: ByteArray): CgmParse<CgmStatus> {
        if (b.size != 5 && b.size != 7) return CgmParse.Invalid("CGM Status com ${b.size} bytes (esperado 5 ou 7)")
        return CgmParse.Ok(
            CgmStatus(
                timeOffsetMinutes = u16(b, 0),
                annunciation = SensorAnnunciation(u8(b, 2) or (u8(b, 3) shl 8) or (u8(b, 4) shl 16)),
                crc = crcState(b, b.size == 7)
            )
        )
    }
}

/** CGM Feature (0x2AA8): capacidades do sensor. O CRC vale `FFFF` quando o E2E-CRC não é suportado. */
data class CgmFeature(val bits: Int, val type: Int, val sampleLocation: Int, val crc: CrcState) {
    private fun bit(n: Int) = bits and (1 shl n) != 0
    val calibrationSupported get() = bit(0)
    val e2eCrcSupported get() = bit(12)
    val multipleBondSupported get() = bit(13)
    val multipleSessionsSupported get() = bit(14)
    val trendSupported get() = bit(15)
    val qualitySupported get() = bit(16)
}

object CgmFeatureParser {
    fun parse(b: ByteArray): CgmParse<CgmFeature> {
        if (b.size != 6) return CgmParse.Invalid("CGM Feature com ${b.size} bytes (esperado 6)")
        val bits = u8(b, 0) or (u8(b, 1) shl 8) or (u8(b, 2) shl 16)
        val typeLocation = u8(b, 3)
        val e2e = bits and (1 shl 12) != 0
        return CgmParse.Ok(CgmFeature(bits, typeLocation and 0x0F, typeLocation ushr 4, crcState(b, e2e)))
    }
}

/**
 * CGM Session Start Time (0x2AAA): data/hora local do fuso informado + fuso (quartos de hora, com
 * sinal) + horário de verão (quartos de hora; `FF` = desconhecido). O SmartGuide manda fuso 0 (UTC).
 */
data class SessionStartTime(
    val utcMillis: Long,
    val timeZoneQuarterHours: Int,
    val dstQuarterHours: Int?,
    val crc: CrcState
)

object SessionStartTimeParser {
    fun parse(b: ByteArray): CgmParse<SessionStartTime> {
        if (b.size != 9 && b.size != 11) return CgmParse.Invalid("Session Start Time com ${b.size} bytes (esperado 9 ou 11)")
        val wall = runCatching {
            LocalDateTime.of(u16(b, 0), u8(b, 2), u8(b, 3), u8(b, 4), u8(b, 5), u8(b, 6))
        }.getOrNull() ?: return CgmParse.Invalid("data/hora inválida")
        val tz = b[7].toInt() // com sinal
        if (tz == -128) return CgmParse.Invalid("fuso desconhecido")
        val dst = u8(b, 8).takeUnless { it == 0xFF }
        val offsetMinutes = 15 * (tz + (dst ?: 0))
        val utc = wall.toInstant(ZoneOffset.UTC).toEpochMilli() - offsetMinutes * 60_000L
        return CgmParse.Ok(SessionStartTime(utc, tz, dst, crcState(b, b.size == 11)))
    }
}

/** CGM Session Run Time (0x2AAB): vida útil prevista da sessão, em horas. */
data class SessionRunTime(val hours: Int, val crc: CrcState)

object SessionRunTimeParser {
    fun parse(b: ByteArray): CgmParse<SessionRunTime> {
        if (b.size != 2 && b.size != 4) return CgmParse.Invalid("Session Run Time com ${b.size} bytes (esperado 2 ou 4)")
        return CgmParse.Ok(SessionRunTime(u16(b, 0), crcState(b, b.size == 4)))
    }
}
