package com.gluview.app.data.accuchek

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * Testes DETERMINÍSTICOS e puros (JVM) do protocolo Accu-Chek SmartGuide — sem BLE, sem Android.
 * Como o mantenedor ainda não tem o sensor físico (27/07/2026), estes vetores são SINTÉTICOS: construídos
 * byte a byte a partir da spec oficial da Bluetooth SIG (Glucose Measurement 0x2A18 + RACP 0x2A52),
 * não capturados de hardware real. Validam que o PARSER está correto para a spec; a fidelidade ao
 * sensor real só pode ser confirmada com uma captura (ver TODO de oráculo em [AccuChekBleManager]).
 */
class AccuChekProtocolTest {

    // ── SFloat (IEEE 11073-20601) ──────────────────────────────────────────────

    @Test
    fun `SFloat decodifica mantissa e expoente positivos`() {
        // mantissa=120, expoente=0 -> 120.0 (raw=0x0078)
        val value = SFloat.decode(lsb = 0x78, msb = 0x00)
        assertEquals(120.0f, value, 0.001f)
    }

    @Test
    fun `SFloat decodifica expoente negativo`() {
        // mantissa=12, expoente=-4 -> 0.0012 (raw=0xC00C)
        val value = SFloat.decode(lsb = 0x0C, msb = 0xC0.toByte())
        assertEquals(0.0012f, value, 0.00001f)
    }

    @Test
    fun `SFloat reconhece valores especiais`() {
        assertTrue(SFloat.decode(0xFF.toByte(), 0x07).isNaN())          // 0x07FF = NaN
        assertTrue(SFloat.decode(0x00, 0x08).isNaN())                    // 0x0800 = NRes
        assertEquals(Float.POSITIVE_INFINITY, SFloat.decode(0xFE.toByte(), 0x07), 0.0f)
        assertEquals(Float.NEGATIVE_INFINITY, SFloat.decode(0x02, 0x08), 0.0f)
    }

    // ── AccuChekGatt ────────────────────────────────────────────────────────────

    @Test
    fun `isAccuChekName reconhece prefixo case-insensitive`() {
        assertTrue(AccuChekGatt.isAccuChekName("ACCU-CHEK SmartGuide"))
        assertTrue(AccuChekGatt.isAccuChekName("accu-chek XYZ123"))
        assertFalse(AccuChekGatt.isAccuChekName("Dexcom G7"))
        assertFalse(AccuChekGatt.isAccuChekName(null))
    }

    @Test
    fun `isRocheManufacturer reconhece prefixo Roche`() {
        assertTrue(AccuChekGatt.isRocheManufacturer("Roche Diabetes Care"))
        assertFalse(AccuChekGatt.isRocheManufacturer("i-SENS"))
        assertFalse(AccuChekGatt.isRocheManufacturer(null))
    }

    // ── AccuChekMeasurementParser ───────────────────────────────────────────────

    @Test
    fun `parse decodifica registro completo em mg-dL`() {
        val bytes = buildRecord(
            flags = FLAG_TIME_OFFSET or FLAG_GLUCOSE, // sem bit de unidade -> mg/dL (kg/L)
            sequenceNumber = 42,
            year = 2026, month = 7, day = 27, hour = 10, minute = 30, second = 0,
            timeOffsetMinutes = 5,
            glucoseLsb = 0x0C, glucoseMsb = 0xC0.toByte(), // 0.0012 kg/L -> 120 mg/dL
            typeLocation = 0x11
        )
        val result = AccuChekMeasurementParser.parse(bytes)
        assertTrue(result is AccuChekParseResult.Reading)
        val reading = (result as AccuChekParseResult.Reading).value
        assertEquals(42, reading.sequenceNumber)
        assertEquals(LocalDateTime.of(2026, 7, 27, 10, 30, 0), reading.baseTime)
        assertEquals(5, reading.timeOffsetMinutes)
        assertEquals(120.0f, reading.mgdl, 0.1f)
        assertEquals(1, reading.sampleType)
        assertEquals(1, reading.sampleLocation)
    }

    @Test
    fun `parse converte mmol-L para mg-dL quando a flag de unidade esta setada`() {
        val bytes = buildRecord(
            flags = FLAG_TIME_OFFSET or FLAG_GLUCOSE or FLAG_UNIT_MMOL,
            sequenceNumber = 7,
            year = 2026, month = 7, day = 27, hour = 8, minute = 0, second = 0,
            timeOffsetMinutes = 0,
            glucoseLsb = 0x43, glucoseMsb = 0xC0.toByte(), // 0.0067 mol/L -> ~120.6 mg/dL
            typeLocation = 0x00
        )
        val result = AccuChekMeasurementParser.parse(bytes) as AccuChekParseResult.Reading
        assertEquals(120.6f, result.value.mgdl, 0.5f)
    }

    @Test
    fun `parse retorna NoGlucose quando o bit de glicemia nao esta setado`() {
        // Só flags(0) + seq(2) + baseTime(7) = 10 bytes, sem nenhum campo opcional.
        val bytes = ByteArray(10)
        bytes[0] = 0x00
        bytes[1] = 9; bytes[2] = 0 // seq=9
        bytes[3] = 0xEA.toByte(); bytes[4] = 0x07 // year=2026
        bytes[5] = 7; bytes[6] = 27; bytes[7] = 10; bytes[8] = 30; bytes[9] = 0
        val result = AccuChekMeasurementParser.parse(bytes)
        assertTrue(result is AccuChekParseResult.NoGlucose)
        assertEquals(9, (result as AccuChekParseResult.NoGlucose).sequenceNumber)
    }

    @Test
    fun `parse retorna Invalid para pacote curto demais`() {
        val result = AccuChekMeasurementParser.parse(ByteArray(5))
        assertTrue(result is AccuChekParseResult.Invalid)
    }

    // ── AccuChekRacp ────────────────────────────────────────────────────────────

    @Test
    fun `buildFetchFromSequence monta o comando 1,3,1,seqLo,seqHi`() {
        val cmd = AccuChekRacp.buildFetchFromSequence(300) // 300 = 0x012C
        assertArrayEquals(byteArrayOf(1, 3, 1, 0x2C, 0x01), cmd)
    }

    @Test
    fun `buildFetchFromSequence com sequencia zero (primeira busca = backfill total)`() {
        val cmd = AccuChekRacp.buildFetchFromSequence(0)
        assertArrayEquals(byteArrayOf(1, 3, 1, 0, 0), cmd)
    }

    @Test
    fun `RACP reconhece Response Code de sucesso e de nenhum registro`() {
        val success = byteArrayOf(6, 0, 1, 1)
        val noRecords = byteArrayOf(6, 0, 1, 6)
        assertTrue(AccuChekRacp.isSuccessResponse(success))
        assertFalse(AccuChekRacp.isNoRecordsFound(success))
        assertTrue(AccuChekRacp.isNoRecordsFound(noRecords))
        assertFalse(AccuChekRacp.isSuccessResponse(noRecords))
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private companion object {
        const val FLAG_TIME_OFFSET = 0x01
        const val FLAG_GLUCOSE     = 0x02
        const val FLAG_UNIT_MMOL   = 0x04
    }

    private fun assertArrayEquals(expected: ByteArray, actual: ByteArray) =
        org.junit.Assert.assertArrayEquals(expected, actual)

    @Suppress("LongParameterList")
    private fun buildRecord(
        flags: Int,
        sequenceNumber: Int,
        year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int,
        timeOffsetMinutes: Int,
        glucoseLsb: Byte, glucoseMsb: Byte,
        typeLocation: Int
    ): ByteArray {
        val out = mutableListOf<Byte>()
        out += flags.toByte()
        out += (sequenceNumber and 0xFF).toByte()
        out += ((sequenceNumber shr 8) and 0xFF).toByte()
        out += (year and 0xFF).toByte()
        out += ((year shr 8) and 0xFF).toByte()
        out += month.toByte()
        out += day.toByte()
        out += hour.toByte()
        out += minute.toByte()
        out += second.toByte()
        if (flags and FLAG_TIME_OFFSET != 0) {
            out += (timeOffsetMinutes and 0xFF).toByte()
            out += ((timeOffsetMinutes shr 8) and 0xFF).toByte()
        }
        if (flags and FLAG_GLUCOSE != 0) {
            out += glucoseLsb
            out += glucoseMsb
            out += typeLocation.toByte()
        }
        return out.toByteArray()
    }
}
