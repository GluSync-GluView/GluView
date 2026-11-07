package com.gluview.app.data.accuchek.cgm

private fun u16(b: ByteArray, i: Int): Int = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
private fun u8(b: ByteArray, i: Int): Int = b[i].toInt() and 0xFF
private fun le16(v: Int): ByteArray = byteArrayOf((v and 0xFF).toByte(), ((v ushr 8) and 0xFF).toByte())

/**
 * Record Access Control Point (0x2A52) — histórico guardado no sensor.
 *
 * No serviço CGM o filtro 0x01 é o **Time Offset** (minutos desde o início), não um número de
 * sequência. O SmartGuide guarda só as últimas 8 horas (96 leituras). O RACP é genérico: não leva
 * E2E-CRC. Os registros chegam por notificação no CGM Measurement e o fim vem como Response Code
 * aqui (`06 00 01 01` = sucesso).
 *
 * Juggluco (`accuAskValues`) pede `01 03 01 <minuto>` a partir do último minuto salvo + 5; o xDrip
 * usa também o intervalo (`01 04 01 <de> <até>`).
 */
object Racp {
    private const val OP_REPORT_STORED = 0x01
    private const val OP_ABORT = 0x03
    private const val OP_REPORT_NUMBER = 0x04
    const val OP_NUMBER_RESPONSE = 0x05
    const val OP_RESPONSE_CODE = 0x06

    private const val OPERATOR_NULL = 0x00
    private const val OPERATOR_ALL = 0x01
    private const val OPERATOR_GREATER_OR_EQUAL = 0x03
    private const val OPERATOR_WITHIN_RANGE = 0x04
    private const val OPERATOR_LAST = 0x06

    private const val FILTER_TIME_OFFSET = 0x01

    const val CODE_SUCCESS = 0x01
    const val CODE_OP_NOT_SUPPORTED = 0x02
    const val CODE_INVALID_OPERATOR = 0x03
    const val CODE_OPERATOR_NOT_SUPPORTED = 0x04
    const val CODE_INVALID_OPERAND = 0x05
    const val CODE_NO_RECORDS = 0x06
    const val CODE_ABORT_FAILED = 0x07
    const val CODE_NOT_COMPLETED = 0x08
    const val CODE_OPERAND_NOT_SUPPORTED = 0x09

    fun reportAll(): ByteArray = byteArrayOf(OP_REPORT_STORED.toByte(), OPERATOR_ALL.toByte())

    /** Todos os registros com minuto ≥ [minute]. */
    fun reportFromMinute(minute: Int): ByteArray =
        byteArrayOf(OP_REPORT_STORED.toByte(), OPERATOR_GREATER_OR_EQUAL.toByte(), FILTER_TIME_OFFSET.toByte()) +
            le16(minute.coerceIn(0, 0xFFFF))

    /** Registros com minuto entre [fromMinute] e [toMinute] (inclusive). */
    fun reportRange(fromMinute: Int, toMinute: Int): ByteArray {
        val from = fromMinute.coerceIn(0, 0xFFFF)
        val to = toMinute.coerceIn(from, 0xFFFF)
        return byteArrayOf(OP_REPORT_STORED.toByte(), OPERATOR_WITHIN_RANGE.toByte(), FILTER_TIME_OFFSET.toByte()) +
            le16(from) + le16(to)
    }

    fun reportLast(): ByteArray = byteArrayOf(OP_REPORT_STORED.toByte(), OPERATOR_LAST.toByte())

    fun countAll(): ByteArray = byteArrayOf(OP_REPORT_NUMBER.toByte(), OPERATOR_ALL.toByte())

    fun abort(): ByteArray = byteArrayOf(OP_ABORT.toByte(), OPERATOR_NULL.toByte())

    sealed class Response {
        /** Fim de um pedido: [requestOpCode] é o pedido respondido, [code] o resultado. */
        data class Code(val requestOpCode: Int, val code: Int) : Response() {
            val isSuccess get() = code == CODE_SUCCESS
            val isNoRecords get() = code == CODE_NO_RECORDS
        }
        data class NumberOfRecords(val count: Int) : Response()
    }

    fun parseResponse(b: ByteArray): CgmParse<Response> {
        if (b.size < 2) return CgmParse.Invalid("resposta do RACP com ${b.size} bytes")
        return when (u8(b, 0)) {
            OP_RESPONSE_CODE ->
                if (b.size >= 4) CgmParse.Ok(Response.Code(u8(b, 2), u8(b, 3)))
                else CgmParse.Invalid("Response Code curto (${b.size} bytes)")
            OP_NUMBER_RESPONSE ->
                if (b.size >= 4) CgmParse.Ok(Response.NumberOfRecords(u16(b, 2)))
                else CgmParse.Invalid("Number of Records curto (${b.size} bytes)")
            else -> CgmParse.Invalid("opcode de resposta desconhecido 0x%02X".format(u8(b, 0)))
        }
    }
}

/**
 * CGM Specific Ops Control Point (0x2AAC) — comandos ao sensor. Com E2E-CRC ligado (o caso do
 * SmartGuide), todo comando leva o CRC no fim e as respostas também.
 *
 * Usados pelo Juggluco: `02` (ler intervalo; resposta `03 05` = 5 min) e `05 <registro>` (ler a
 * calibração; `FFFF` = a última). Calibrar (`04`) e mudar intervalo (`01`) ficam fora da F1.
 */
object Socp {
    const val OP_SET_INTERVAL = 0x01
    const val OP_GET_INTERVAL = 0x02
    const val OP_INTERVAL_RESPONSE = 0x03
    const val OP_GET_CALIBRATION = 0x05
    const val OP_CALIBRATION_RESPONSE = 0x06
    const val OP_RESPONSE_CODE = 0x1C

    const val CODE_SUCCESS = 0x01
    const val CODE_OP_NOT_SUPPORTED = 0x02
    const val CODE_INVALID_OPERAND = 0x03
    const val CODE_NOT_COMPLETED = 0x04
    const val CODE_OUT_OF_RANGE = 0x05

    /** Número de registro que pede a calibração mais recente. */
    const val LAST_CALIBRATION = 0xFFFF

    private fun withCrc(payload: ByteArray, e2eCrc: Boolean) = if (e2eCrc) E2eCrc.append(payload) else payload

    fun getCommunicationInterval(e2eCrc: Boolean = true): ByteArray =
        withCrc(byteArrayOf(OP_GET_INTERVAL.toByte()), e2eCrc)

    fun setCommunicationInterval(minutes: Int, e2eCrc: Boolean = true): ByteArray =
        withCrc(byteArrayOf(OP_SET_INTERVAL.toByte(), minutes.coerceIn(0, 0xFF).toByte()), e2eCrc)

    fun getCalibrationValue(record: Int = LAST_CALIBRATION, e2eCrc: Boolean = true): ByteArray =
        withCrc(byteArrayOf(OP_GET_CALIBRATION.toByte()) + le16(record.coerceIn(0, 0xFFFF)), e2eCrc)

    sealed class Response {
        abstract val crc: CrcState

        data class CommunicationInterval(val minutes: Int, override val crc: CrcState) : Response()

        data class CalibrationValue(
            val glucoseMgDl: Float,
            val calibrationTimeMinutes: Int,
            val type: Int,
            val sampleLocation: Int,
            val nextCalibrationMinutes: Int,
            val recordNumber: Int,
            val status: Int,
            override val crc: CrcState
        ) : Response()

        data class Code(val requestOpCode: Int, val code: Int, override val crc: CrcState) : Response() {
            val isSuccess get() = code == CODE_SUCCESS
        }
    }

    /**
     * @param e2eCrc o sensor anunciou E2E-CRC (CGM Feature) — então os 2 últimos bytes são CRC.
     */
    fun parseResponse(b: ByteArray, e2eCrc: Boolean = true): CgmParse<Response> {
        val bodySize = if (e2eCrc) b.size - 2 else b.size
        if (bodySize < 1) return CgmParse.Invalid("resposta do SOCP com ${b.size} bytes")
        val crc = if (!e2eCrc) CrcState.ABSENT else if (E2eCrc.hasValidTrailing(b)) CrcState.VALID else CrcState.INVALID
        return when (u8(b, 0)) {
            OP_INTERVAL_RESPONSE ->
                if (bodySize >= 2) CgmParse.Ok(Response.CommunicationInterval(u8(b, 1), crc))
                else CgmParse.Invalid("resposta de intervalo curta")
            OP_CALIBRATION_RESPONSE ->
                if (bodySize >= 11) {
                    val typeLocation = u8(b, 5)
                    CgmParse.Ok(
                        Response.CalibrationValue(
                            glucoseMgDl = SFloat.read(b, 1),
                            calibrationTimeMinutes = u16(b, 3),
                            type = typeLocation and 0x0F,
                            sampleLocation = typeLocation ushr 4,
                            nextCalibrationMinutes = u16(b, 6),
                            recordNumber = u16(b, 8),
                            status = u8(b, 10),
                            crc = crc
                        )
                    )
                } else CgmParse.Invalid("resposta de calibração curta ($bodySize bytes sem CRC)")
            OP_RESPONSE_CODE ->
                if (bodySize >= 3) CgmParse.Ok(Response.Code(u8(b, 1), u8(b, 2), crc))
                else CgmParse.Invalid("Response Code do SOCP curto")
            else -> CgmParse.Invalid("opcode de resposta do SOCP desconhecido 0x%02X".format(u8(b, 0)))
        }
    }
}
