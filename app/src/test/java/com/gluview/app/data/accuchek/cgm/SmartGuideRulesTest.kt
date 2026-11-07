package com.gluview.app.data.accuchek.cgm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartGuideRulesTest {

    private fun m(glucose: Float, minute: Int, annunciation: Int = 0x0200) =
        CgmMeasurement(glucose, minute, SensorAnnunciation(annunciation), null, null, CrcState.VALID, ByteArray(0))

    private val malfunctionLow = 0x08 or (0x02 shl 8) or (0x40 shl 16) // registro "LO" real do Juggluco

    @Test
    fun `valor numerico e arredondado`() {
        assertEquals(96, SmartGuideRules.glucoseToStore(m(95.5f, 100), null))
        assertEquals(120, SmartGuideRules.glucoseToStore(m(120f, 100), null))
    }

    @Test
    fun `LO vira 39 so quando coerente com a leitura anterior (regra do Juggluco)`() {
        val lo = m(Float.NEGATIVE_INFINITY, 105, malfunctionLow)
        assertEquals(39, SmartGuideRules.glucoseToStore(lo, SmartGuideRules.Previous(55, 100)))
        assertNull(SmartGuideRules.glucoseToStore(lo, SmartGuideRules.Previous(150, 100)))  // vinha alto
        assertNull(SmartGuideRules.glucoseToStore(lo, SmartGuideRules.Previous(55, 102)))   // só 3 min
        assertNull(SmartGuideRules.glucoseToStore(lo, SmartGuideRules.Previous(55, 70)))    // 35 min
        assertNull(SmartGuideRules.glucoseToStore(lo, null))
    }

    @Test
    fun `HI vira 401 so quando coerente`() {
        val hi = m(Float.POSITIVE_INFINITY, 105)
        assertEquals(401, SmartGuideRules.glucoseToStore(hi, SmartGuideRules.Previous(380, 100)))
        assertNull(SmartGuideRules.glucoseToStore(hi, SmartGuideRules.Previous(180, 100)))
    }

    @Test
    fun `erro do sensor nao grava`() {
        assertNull(SmartGuideRules.glucoseToStore(m(Float.NaN, 105, 0x08), SmartGuideRules.Previous(100, 100)))
    }

    @Test
    fun `avisos da anunciacao, sem o defeito`() {
        assertEquals(emptySet<SmartGuideAlert>(), SmartGuideRules.alertsFor(SensorAnnunciation(0x0200)))
        assertEquals(setOf(SmartGuideAlert.SESSION_ENDED), SmartGuideRules.alertsFor(SensorAnnunciation(0x01)))
        assertEquals(setOf(SmartGuideAlert.BATTERY_LOW), SmartGuideRules.alertsFor(SensorAnnunciation(0x02)))
        assertEquals(setOf(SmartGuideAlert.TEMPERATURE), SmartGuideRules.alertsFor(SensorAnnunciation(1 shl 12)))
        assertEquals(setOf(SmartGuideAlert.TEMPERATURE), SmartGuideRules.alertsFor(SensorAnnunciation(1 shl 13)))
        assertEquals(setOf(SmartGuideAlert.CALIBRATION_REQUIRED), SmartGuideRules.alertsFor(SensorAnnunciation(1 shl 11)))
        assertEquals(emptySet<SmartGuideAlert>(), SmartGuideRules.alertsFor(SensorAnnunciation(malfunctionLow)))
        assertTrue(SmartGuideRules.isMalfunction(SensorAnnunciation(malfunctionLow)))
        assertFalse(SmartGuideRules.isMalfunction(SensorAnnunciation(0x0200)))
    }

    @Test
    fun `seta pela tendencia do sensor com os cortes do Juggluco`() {
        assertEquals("→", SmartGuideRules.arrowFor(0.25f))
        assertEquals("→", SmartGuideRules.arrowFor(1f))
        assertEquals("↗", SmartGuideRules.arrowFor(1.2f))
        assertEquals("↗", SmartGuideRules.arrowFor(2f))
        assertEquals("↑", SmartGuideRules.arrowFor(3.5f))
        assertEquals("↘", SmartGuideRules.arrowFor(-1f))
        assertEquals("↘", SmartGuideRules.arrowFor(-1.9f))
        assertEquals("↓", SmartGuideRules.arrowFor(-2f))
        assertEquals("↓", SmartGuideRules.arrowFor(-4f))
        assertNull(SmartGuideRules.arrowFor(Float.NaN))
        assertNull(SmartGuideRules.arrowFor(null))
    }

    @Test
    fun `defeito so avisa depois de 3 leituras seguidas`() {
        val s = MalfunctionStreak()
        assertFalse(s.onLiveRecord(stored = false, malfunction = true))
        assertFalse(s.onLiveRecord(stored = false, malfunction = true))
        assertTrue(s.onLiveRecord(stored = false, malfunction = true))
        assertTrue(s.isActive)
        assertFalse(s.onLiveRecord(stored = false, malfunction = true)) // não repete o aviso
        assertFalse(s.onLiveRecord(stored = true, malfunction = false))
        assertFalse(s.isActive)
        assertFalse(s.onLiveRecord(stored = true, malfunction = true))   // LO gravado não conta
        assertEquals(0, s.count)
    }
}
