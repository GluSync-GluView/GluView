package com.gluview.app.data.accuchek.cgm

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * Protocolo CGM (0x181F) do Accu-Chek SmartGuide — testes JVM puros.
 *
 * Vetores REAIS: os bytes citados no código do Juggluco 11.5.7 (GPLv3), capturados de sensores
 * SmartGuide — `cpp/accu/java.cpp` (registros "LO" e de erro, CGM Status) e
 * `AccuGattCallback.java` (comandos e respostas do SOCP). Os demais vetores são montados à mão
 * pela spec, com o CRC calculado pelo próprio [E2eCrc] (que os vetores reais validam).
 */
class SmartGuideProtocolTest {

    private fun hex(s: String): ByteArray =
        s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun <T> ok(r: CgmParse<T>): T {
        assertTrue("esperava Ok, veio $r", r is CgmParse.Ok)
        return (r as CgmParse.Ok).value
    }

    // ── CRC-16/MCRF4XX ──────────────────────────────────────────────────────────

    @Test
    fun `CRC confere com os comandos e respostas reais do SOCP`() {
        listOf("02 95 2C", "05 FF FF 36 F0", "05 00 00 8E 00", "03 05 7D 8D").forEach {
            assertTrue(it, E2eCrc.hasValidTrailing(hex(it)))
        }
    }

    @Test
    fun `CRC confere com os registros reais do Juggluco`() {
        listOf(
            "0F E3 02 08 E5 15 08 02 40 F0 FF 38 00 64 27",
            "0F E3 02 08 EA 15 08 02 40 BF EF 0E 00 E5 72",
            "0E C3 FF 07 AF 46 08 02 FF 07 FF 07 EA 5C",
            "0E C3 FF 07 FE 01 08 03 FF 07 FF 07 86 E8"
        ).forEach { assertTrue(it, E2eCrc.hasValidTrailing(hex(it))) }
    }

    @Test
    fun `CRC detecta um byte trocado`() {
        val bad = hex("03 05 7D 8D").also { it[1] = 0x06 }
        assertFalse(E2eCrc.hasValidTrailing(bad))
    }

    // ── SFLOAT ──────────────────────────────────────────────────────────────────

    @Test
    fun `SFLOAT decodifica casa decimal e valores especiais`() {
        assertEquals(95.5f, SFloat.decode(0xF3BB), 0.0001f)   // mantissa 955, expoente −1
        assertEquals(120f, SFloat.decode(0x0078), 0f)
        assertEquals(-1.5f, SFloat.decode(0xFFF1), 0.0001f)   // mantissa −15, expoente −1
        assertTrue(SFloat.decode(0x07FF).isNaN())
        assertTrue(SFloat.decode(0x0800).isNaN())
        assertEquals(Float.POSITIVE_INFINITY, SFloat.decode(0x07FE), 0f)
        assertEquals(Float.NEGATIVE_INFINITY, SFloat.decode(0x0802), 0f)
    }

    // ── CGM Measurement ─────────────────────────────────────────────────────────

    /** Registro normal: flags 0x43 (tendência + qualidade + octeto Cal/Temp), como o SmartGuide manda. */
    private fun normalRecord(glucose: Int, minute: Int, trendRaw: Int, quality: Int): ByteArray =
        E2eCrc.append(
            byteArrayOf(
                0x0D, 0x43,
                (glucose and 0xFF).toByte(), (glucose ushr 8).toByte(),
                (minute and 0xFF).toByte(), (minute ushr 8).toByte(),
                0x02,
                (trendRaw and 0xFF).toByte(), (trendRaw ushr 8).toByte(),
                (quality and 0xFF).toByte(), (quality ushr 8).toByte()
            )
        )

    @Test
    fun `leitura normal com tendencia e qualidade`() {
        val m = ok(CgmMeasurementParser.parse(normalRecord(0x0078, 100, 0xFFF1, 95))).single()
        assertEquals(120f, m.glucoseMgDl, 0f)
        assertEquals(100, m.timeOffsetMinutes)
        assertEquals(-1.5f, m.trendMgDlPerMin!!, 0.0001f)
        assertEquals(95f, m.qualityPercent!!, 0f)
        assertEquals(CrcState.VALID, m.crc)
        assertTrue(m.hasValue)
        assertTrue(m.annunciation.calibrationNotAllowed) // Cal/Temp = 02
        assertFalse(m.annunciation.sensorMalfunction)
    }

    @Test
    fun `varios registros na mesma notificacao (historico)`() {
        val batch = normalRecord(0x0078, 100, 0x0000, 95) + normalRecord(0x0082, 105, 0x0002, 94)
        val list = ok(CgmMeasurementParser.parse(batch))
        assertEquals(listOf(100, 105), list.map { it.timeOffsetMinutes })
        assertEquals(listOf(120f, 130f), list.map { it.glucoseMgDl })
        assertTrue(list.all { it.crc == CrcState.VALID })
    }

    @Test
    fun `registro real LO - abaixo da faixa do aparelho`() {
        val m = ok(CgmMeasurementParser.parse(hex("0F E3 02 08 E5 15 08 02 40 F0 FF 38 00 64 27"))).single()
        assertTrue(m.isLow)
        assertFalse(m.hasValue)
        assertEquals(0x15E5, m.timeOffsetMinutes)
        assertTrue(m.annunciation.belowDeviceRange)     // Warning 0x40
        assertTrue(m.annunciation.calibrationNotAllowed) // Cal/Temp 0x02
        assertTrue(m.annunciation.sensorMalfunction)     // Status 0x08
        assertEquals(CrcState.VALID, m.crc)
    }

    @Test
    fun `registro real de erro - glicose NaN`() {
        val m = ok(CgmMeasurementParser.parse(hex("0E C3 FF 07 AF 46 08 02 FF 07 FF 07 EA 5C"))).single()
        assertTrue(m.glucoseMgDl.isNaN())
        assertFalse(m.hasValue)
        assertTrue(m.annunciation.sensorMalfunction)
        assertTrue(m.annunciation.calibrationNotAllowed)
        assertTrue(m.trendMgDlPerMin!!.isNaN())
        assertEquals(CrcState.VALID, m.crc)
    }

    @Test
    fun `HI vira infinito positivo`() {
        val m = ok(CgmMeasurementParser.parse(normalRecord(0x07FE, 200, 0x0000, 90))).single()
        assertTrue(m.isHigh)
    }

    @Test
    fun `CRC errado e marcado sem descartar o registro`() {
        val rec = normalRecord(0x0078, 100, 0x0000, 95).also { it[2] = 0x79 }
        assertEquals(CrcState.INVALID, ok(CgmMeasurementParser.parse(rec)).single().crc)
    }

    @Test
    fun `registro sem CRC e aceito como ABSENT`() {
        val rec = byteArrayOf(0x06, 0x00, 0x78, 0x00, 0x64, 0x00)
        val m = ok(CgmMeasurementParser.parse(rec)).single()
        assertEquals(CrcState.ABSENT, m.crc)
        assertNull(m.trendMgDlPerMin)
    }

    @Test
    fun `tamanhos invalidos sao recusados sem excecao`() {
        assertTrue(CgmMeasurementParser.parse(ByteArray(0)) is CgmParse.Invalid)
        assertTrue(CgmMeasurementParser.parse(hex("05 00 78 00 64")) is CgmParse.Invalid)
        assertTrue(CgmMeasurementParser.parse(hex("0D 43 78 00")) is CgmParse.Invalid)          // tamanho > dados
        assertTrue(CgmMeasurementParser.parse(hex("07 43 78 00 64 00 02")) is CgmParse.Invalid) // flags pedem mais campos
        assertTrue(CgmMeasurementParser.parse(hex("07 00 78 00 64 00 02")) is CgmParse.Invalid) // 1 byte sobrando
    }

    // ── CGM Status / Feature / Start Time / Run Time ────────────────────────────

    @Test
    fun `CGM Status real do Juggluco`() {
        // init.txt do Juggluco (2025-09-03): onCharacteristicRead 2aa9 = 9A 12 00 02 00 52 8B
        val s = ok(CgmStatusParser.parse(hex("9A 12 00 02 00 52 8B")))
        assertEquals(0x129A, s.timeOffsetMinutes)
        assertTrue(s.annunciation.calibrationNotAllowed)
        assertFalse(s.annunciation.sessionStopped)
        assertEquals(CrcState.VALID, s.crc)
    }

    @Test
    fun `CGM Feature com E2E-CRC, tendencia e qualidade`() {
        val bits = (1 shl 0) or (1 shl 12) or (1 shl 15) or (1 shl 16)
        val body = byteArrayOf(bits.toByte(), (bits ushr 8).toByte(), (bits ushr 16).toByte(), 0x59)
        val f = ok(CgmFeatureParser.parse(E2eCrc.append(body)))
        assertTrue(f.e2eCrcSupported && f.trendSupported && f.qualitySupported && f.calibrationSupported)
        assertFalse(f.multipleBondSupported)
        assertEquals(9, f.type)            // fluido intersticial
        assertEquals(5, f.sampleLocation)  // subcutâneo
        assertEquals(CrcState.VALID, f.crc)
    }

    @Test
    fun `CGM Feature sem E2E-CRC ignora o campo de CRC FFFF`() {
        val f = ok(CgmFeatureParser.parse(hex("01 80 00 59 FF FF")))
        assertFalse(f.e2eCrcSupported)
        assertEquals(CrcState.ABSENT, f.crc)
    }

    private fun startTime(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int, tz: Int, dst: Int): ByteArray =
        E2eCrc.append(byteArrayOf((y and 0xFF).toByte(), (y ushr 8).toByte(),
            mo.toByte(), d.toByte(), h.toByte(), mi.toByte(), s.toByte(), tz.toByte(), dst.toByte()))

    @Test
    fun `Session Start Time em UTC (como o SmartGuide manda)`() {
        val st = ok(SessionStartTimeParser.parse(startTime(2026, 11, 7, 12, 30, 5, 0, 0xFF)))
        val expected = LocalDateTime.of(2026, 11, 7, 12, 30, 5).toInstant(ZoneOffset.UTC).toEpochMilli()
        assertEquals(expected, st.utcMillis)
        assertNull(st.dstQuarterHours)
        assertEquals(CrcState.VALID, st.crc)
    }

    @Test
    fun `Session Start Time com fuso de Brasilia`() {
        // 09:30 em UTC−3 (−12 quartos de hora), sem horário de verão = 12:30 UTC
        val st = ok(SessionStartTimeParser.parse(startTime(2026, 11, 7, 9, 30, 0, -12, 0)))
        val expected = LocalDateTime.of(2026, 11, 7, 12, 30, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        assertEquals(expected, st.utcMillis)
    }

    @Test
    fun `Session Start Time invalido`() {
        assertTrue(SessionStartTimeParser.parse(startTime(2026, 13, 7, 9, 30, 0, 0, 0)) is CgmParse.Invalid)
        assertTrue(SessionStartTimeParser.parse(hex("EA 07 0B")) is CgmParse.Invalid)
    }

    @Test
    fun `Session Run Time de 14 dias`() {
        val rt = ok(SessionRunTimeParser.parse(E2eCrc.append(byteArrayOf(0x50, 0x01))))
        assertEquals(336, rt.hours)
        assertEquals(CrcState.VALID, rt.crc)
    }

    // ── RACP ────────────────────────────────────────────────────────────────────

    @Test
    fun `RACP monta os pedidos de historico`() {
        assertArrayEquals(hex("01 01"), Racp.reportAll())
        // Juggluco sem nada salvo pede a partir do minuto 60 + 5 = 65
        assertArrayEquals(hex("01 03 01 41 00"), Racp.reportFromMinute(65))
        assertArrayEquals(hex("01 03 01 B2 48"), Racp.reportFromMinute(18610))
        assertArrayEquals(hex("01 04 01 0A 00 64 00"), Racp.reportRange(10, 100))
        assertArrayEquals(hex("01 06"), Racp.reportLast())
        assertArrayEquals(hex("04 01"), Racp.countAll())
    }

    @Test
    fun `RACP le as respostas`() {
        val done = ok(Racp.parseResponse(hex("06 00 01 01"))) as Racp.Response.Code
        assertTrue(done.isSuccess)
        assertEquals(1, done.requestOpCode)
        assertTrue((ok(Racp.parseResponse(hex("06 00 01 06"))) as Racp.Response.Code).isNoRecords)
        assertEquals(96, (ok(Racp.parseResponse(hex("05 00 60 00"))) as Racp.Response.NumberOfRecords).count)
        assertTrue(Racp.parseResponse(hex("06 00")) is CgmParse.Invalid)
        assertTrue(Racp.parseResponse(hex("09 00 01 01")) is CgmParse.Invalid)
    }

    // ── SOCP ────────────────────────────────────────────────────────────────────

    @Test
    fun `SOCP monta os mesmos comandos do Juggluco`() {
        assertArrayEquals(hex("02 95 2C"), Socp.getCommunicationInterval())
        assertArrayEquals(hex("05 FF FF 36 F0"), Socp.getCalibrationValue())
        assertArrayEquals(hex("05 00 00 8E 00"), Socp.getCalibrationValue(0))
        assertArrayEquals(hex("02"), Socp.getCommunicationInterval(e2eCrc = false))
    }

    @Test
    fun `SOCP le o intervalo de 5 minutos (resposta real)`() {
        val r = ok(Socp.parseResponse(hex("03 05 7D 8D"))) as Socp.Response.CommunicationInterval
        assertEquals(5, r.minutes)
        assertEquals(CrcState.VALID, r.crc)
    }

    @Test
    fun `SOCP le valor de calibracao e codigo de resposta`() {
        // 100 mg/dL, minuto 300, capilar(1)/dedo(1), próxima em 1740, registro 2, status 0
        val cal = E2eCrc.append(hex("06 64 00 2C 01 11 CC 06 02 00 00"))
        val c = ok(Socp.parseResponse(cal)) as Socp.Response.CalibrationValue
        assertEquals(100f, c.glucoseMgDl, 0f)
        assertEquals(300, c.calibrationTimeMinutes)
        assertEquals(1740, c.nextCalibrationMinutes)
        assertEquals(2, c.recordNumber)
        assertEquals(CrcState.VALID, c.crc)

        val code = ok(Socp.parseResponse(E2eCrc.append(hex("1C 05 03")))) as Socp.Response.Code
        assertEquals(Socp.OP_GET_CALIBRATION, code.requestOpCode)
        assertFalse(code.isSuccess)
    }

    @Test
    fun `SOCP resposta curta e recusada`() {
        assertTrue(Socp.parseResponse(hex("03 05")) is CgmParse.Invalid)
        assertTrue(Socp.parseResponse(ByteArray(0)) is CgmParse.Invalid)
    }
}
