package com.gluview.app.data.accuchek

import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

/**
 * Perfil BLE do Accu-Chek SmartGuide — é o perfil PADRÃO "Glucose Service" da Bluetooth SIG
 * (0x1808, o mesmo de medidores de ponta de dedo tipo Contour/Accu-Chek Mobile), e NÃO o
 * "Continuous Glucose Monitoring Service" (0x181F) que o nome do sensor sugeriria.
 *
 * Confirmado lendo o código-fonte real do Juggluco (j-kaltes/Juggluco, GPLv3 — o mesmo app usado
 * como referência absoluta para o port do Libre 2 Plus, ver as notas internas do projeto):
 * `GlucoseMeterGatt.java`, `MeterScanner.java`, `glucose.cpp`, `MeterData.hpp`. O CareSens Air
 * (fabricante i-SENS) usa o MESMO transporte/perfil, só com quirks de manufacturer diferentes.
 *
 * Diferença central em relação a Sibionics/Libre2 Plus: aqui NÃO há cripto proprietária nem .so —
 * é um perfil Bluetooth SIG documentado publicamente (Glucose Service v1.0.1 + GATT Specification
 * Supplement). O ganho de reimplementar clean-room é real (zero binário de terceiros), mas os
 * detalhes byte-exatos abaixo ainda não foram validados contra um sensor real (o mantenedor não tem o
 * hardware ainda) — só contra a spec oficial + o comportamento do Juggluco. Ver testes em
 * `AccuChekProtocolTest` (vetores sintéticos) e o TODO de captura em [AccuChekBleManager].
 */
object AccuChekGatt {
    private fun sig(shortUuid: String): UUID =
        UUID.fromString("0000$shortUuid-0000-1000-8000-00805f9b34fb")

    val GLUCOSE_SERVICE: UUID             = sig("1808")
    val DEVICE_INFO_SERVICE: UUID         = sig("180a")

    val GLUCOSE_MEASUREMENT: UUID         = sig("2a18") // notify — leituras
    val GLUCOSE_MEASUREMENT_CONTEXT: UUID = sig("2a34") // notify — opcional, log-only (não usado p/ mg/dL)
    val RECORD_ACCESS_CONTROL_POINT: UUID = sig("2a52") // write + indicate — RACP (busca histórico)
    val DATE_TIME: UUID                   = sig("2a08") // read — hora do sensor (p/ corrigir timestamp)
    val SERIAL_NUMBER: UUID               = sig("2a25") // read
    val MANUFACTURER_NAME: UUID           = sig("2a29") // read — Device Information Service
    val CCCD: UUID                        = sig("2902")

    /** Nome de anúncio BLE do sensor: sempre começa com "ACCU-CHEK" (confirmado no Juggluco/MeterScanner). */
    fun isAccuChekName(name: String?): Boolean =
        name?.uppercase()?.startsWith("ACCU-CHEK") == true

    /** Nome do fabricante lido da característica 0x2A29 — Roche identifica o SmartGuide (e Accu-Chek Mobile). */
    fun isRocheManufacturer(manufacturerName: String?): Boolean =
        manufacturerName?.startsWith("Roche", ignoreCase = true) == true
}

/**
 * IEEE 11073-20601 16-bit SFLOAT — 4 bits de expoente (signed, complemento de 2) +
 * 12 bits de mantissa (signed, complemento de 2); valor = mantissa × 10^expoente.
 * Usado pela característica Glucose Measurement (0x2A18) para a concentração de glicose.
 */
object SFloat {
    private const val MANTISSA_NAN            = 0x07FF
    private const val MANTISSA_RESERVED       = 0x0801
    private const val MANTISSA_NRES           = 0x0800
    private const val MANTISSA_POS_INFINITY   = 0x07FE
    private const val MANTISSA_NEG_INFINITY   = 0x0802

    /** Decodifica 2 bytes little-endian (LSB primeiro, como chegam pelo BLE) para Float. */
    fun decode(lsb: Byte, msb: Byte): Float {
        val raw = ((msb.toInt() and 0xFF) shl 8) or (lsb.toInt() and 0xFF)
        val mantissaRaw = raw and 0x0FFF
        return when (mantissaRaw) {
            MANTISSA_NAN, MANTISSA_RESERVED, MANTISSA_NRES -> Float.NaN
            MANTISSA_POS_INFINITY -> Float.POSITIVE_INFINITY
            MANTISSA_NEG_INFINITY -> Float.NEGATIVE_INFINITY
            else -> {
                val exponentRaw = (raw shr 12) and 0x0F
                val mantissa = signExtend(mantissaRaw, 12)
                val exponent = signExtend(exponentRaw, 4)
                (mantissa * Math.pow(10.0, exponent.toDouble())).toFloat()
            }
        }
    }

    private fun signExtend(value: Int, bits: Int): Int {
        val shift = 32 - bits
        return (value shl shift) shr shift
    }
}

/** Um registro decodificado da característica Glucose Measurement (0x2A18). */
data class AccuChekReading(
    val sequenceNumber: Int,
    val baseTime: LocalDateTime,
    val timeOffsetMinutes: Int,
    val mgdl: Float,
    val sampleType: Int,
    val sampleLocation: Int
) {
    /** Timestamp absoluto do registro = baseTime + timeOffsetMinutes, no fuso informado (padrão: local do aparelho). */
    fun epochMillis(zone: ZoneId = ZoneId.systemDefault()): Long =
        baseTime.plusMinutes(timeOffsetMinutes.toLong())
            .atZone(zone)
            .toInstant()
            .toEpochMilli()
}

sealed class AccuChekParseResult {
    data class Reading(val value: AccuChekReading) : AccuChekParseResult()
    /** Registro sem glicemia (ex.: marcador de controle/erro) — Juggluco também aceita isso sem quebrar. */
    data class NoGlucose(val sequenceNumber: Int) : AccuChekParseResult()
    data class Invalid(val reason: String) : AccuChekParseResult()
}

/**
 * Parser da característica Glucose Measurement (0x2A18), fiel ao layout oficial da Bluetooth SIG
 * (org.bluetooth.characteristic.glucose_measurement) — o MESMO layout que o `MeterData.hpp` do
 * Juggluco implementa em C++ (flags na mesma ordem de bits, campos opcionais nas mesmas posições).
 *
 * Layout: Flags(1B) · SequenceNumber(2B LE) · BaseTime(7B: year LE16,mon,day,hour,min,sec) ·
 * [TimeOffset(2B LE, se flag bit0)] · [GlucoseConcentration(SFLOAT 2B)+Type/Location(1B), se bit1] ·
 * [SensorStatusAnnunciation(2B LE), se bit3].
 */
object AccuChekMeasurementParser {
    private const val FLAG_TIME_OFFSET_PRESENT   = 0x01
    private const val FLAG_GLUCOSE_PRESENT       = 0x02
    private const val FLAG_UNIT_MMOL_PER_L       = 0x04
    private const val FLAG_SENSOR_STATUS_PRESENT = 0x08
    // bit4 = "Context Information Follows" — vem por notificação separada na 0x2A34, log-only aqui.

    private const val MMOLL_TO_MGDL = 18.0f // mesma constante do Juggluco (glucose.cpp / MeterData.hpp)
    private const val MIN_LENGTH = 10       // flags(1) + seq(2) + baseTime(7)

    fun parse(bytes: ByteArray): AccuChekParseResult {
        if (bytes.size < MIN_LENGTH) return AccuChekParseResult.Invalid("tamanho ${bytes.size} < $MIN_LENGTH")

        val flags = bytes[0].toInt() and 0xFF
        val timeOffsetPresent  = flags and FLAG_TIME_OFFSET_PRESENT != 0
        val glucosePresent     = flags and FLAG_GLUCOSE_PRESENT != 0
        val unitIsMmol         = flags and FLAG_UNIT_MMOL_PER_L != 0
        val sensorStatusFlag   = flags and FLAG_SENSOR_STATUS_PRESENT != 0

        val sequenceNumber = readU16LE(bytes, 1)

        val baseTime = parseBaseTime(bytes, 3)
            ?: return AccuChekParseResult.Invalid("base time inválido")
        var offset = 10

        var timeOffsetMinutes = 0
        if (timeOffsetPresent) {
            if (bytes.size < offset + 2) return AccuChekParseResult.Invalid("faltam bytes do time offset")
            timeOffsetMinutes = readS16LE(bytes, offset)
            offset += 2
        }

        if (!glucosePresent) {
            return AccuChekParseResult.NoGlucose(sequenceNumber)
        }
        if (bytes.size < offset + 3) return AccuChekParseResult.Invalid("faltam bytes da glicemia")
        val raw = SFloat.decode(bytes[offset], bytes[offset + 1])
        val typeLocationByte = bytes[offset + 2].toInt() and 0xFF
        val sampleType = typeLocationByte and 0x0F
        val sampleLocation = (typeLocationByte shr 4) and 0x0F
        offset += 3

        if (sensorStatusFlag && bytes.size >= offset + 2) {
            offset += 2 // Sensor Status Annunciation — lido só p/ avançar o offset; não usado no valor de mg/dL.
        }

        if (raw.isNaN()) return AccuChekParseResult.NoGlucose(sequenceNumber)

        val mgdl = if (unitIsMmol) raw * 1000f * MMOLL_TO_MGDL else raw * 100_000f
        return AccuChekParseResult.Reading(
            AccuChekReading(sequenceNumber, baseTime, timeOffsetMinutes, mgdl, sampleType, sampleLocation)
        )
    }

    private fun parseBaseTime(bytes: ByteArray, offset: Int): LocalDateTime? {
        if (bytes.size < offset + 7) return null
        val year   = readU16LE(bytes, offset)
        val month  = bytes[offset + 2].toInt() and 0xFF
        val day    = bytes[offset + 3].toInt() and 0xFF
        val hour   = bytes[offset + 4].toInt() and 0xFF
        val minute = bytes[offset + 5].toInt() and 0xFF
        val second = bytes[offset + 6].toInt() and 0xFF
        if (year == 0 || month !in 1..12 || day !in 1..31) return null
        return runCatching { LocalDateTime.of(year, month, day, hour, minute, second) }.getOrNull()
    }

    private fun readU16LE(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset + 1].toInt() and 0xFF) shl 8) or (bytes[offset].toInt() and 0xFF)

    private fun readS16LE(bytes: ByteArray, offset: Int): Int =
        readU16LE(bytes, offset).toShort().toInt()
}

/**
 * Record Access Control Point (0x2A52) — só a operação que o Juggluco usa para AccuChek/CareSens:
 * "Report Stored Records ≥ Sequence Number" (busca tudo que for novo desde o último índice salvo).
 * Comando confirmado byte-a-byte em `glucose.cpp::getfromcmd` (`{1,3,1,indexLo,indexHi}`).
 */
object AccuChekRacp {
    private const val OPCODE_REPORT_STORED_RECORDS      = 0x01
    private const val OPERATOR_GREATER_THAN_OR_EQUAL     = 0x03
    private const val FILTER_TYPE_SEQUENCE_NUMBER        = 0x01

    private const val OPCODE_RESPONSE_CODE = 0x06
    private const val OPERATOR_NULL        = 0x00
    const val RESPONSE_SUCCESS          = 0x01
    const val RESPONSE_NO_RECORDS_FOUND = 0x06

    /** Pede todos os registros com número de sequência >= [fromSequence] (novos desde a última busca). */
    fun buildFetchFromSequence(fromSequence: Int): ByteArray {
        val seq = fromSequence.coerceIn(0, 0xFFFF)
        return byteArrayOf(
            OPCODE_REPORT_STORED_RECORDS.toByte(),
            OPERATOR_GREATER_THAN_OR_EQUAL.toByte(),
            FILTER_TYPE_SEQUENCE_NUMBER.toByte(),
            (seq and 0xFF).toByte(),
            ((seq shr 8) and 0xFF).toByte()
        )
    }

    /** true se [bytes] é uma Response Code (0x06) de sucesso — fim do fetch, todos os registros já chegaram por notify. */
    fun isSuccessResponse(bytes: ByteArray): Boolean = responseCodeValue(bytes) == RESPONSE_SUCCESS

    /** true se [bytes] indica "nenhum registro encontrado" (nada novo desde [fromSequence]). */
    fun isNoRecordsFound(bytes: ByteArray): Boolean = responseCodeValue(bytes) == RESPONSE_NO_RECORDS_FOUND

    private fun responseCodeValue(bytes: ByteArray): Int? {
        if (bytes.size < 4) return null
        if ((bytes[0].toInt() and 0xFF) != OPCODE_RESPONSE_CODE) return null
        if ((bytes[1].toInt() and 0xFF) != OPERATOR_NULL) return null
        return bytes[3].toInt() and 0xFF
    }
}
