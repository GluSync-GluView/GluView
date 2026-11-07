package com.gluview.app.data.accuchek.cgm

import com.gluview.app.data.sensor.SensorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Código GS1 da embalagem do SmartGuide e reconhecimento do sensor pelo nome Bluetooth.
 * GTIN real do produto (público, impresso na embalagem); serial e lote são FICTÍCIOS.
 */
class SmartGuidePackageCodeTest {

    private val gs = '\u001D'
    private val gtin = "04015630089215"
    private val serial = "1R009999990"

    private fun raw(prefix: String = "") =
        "$prefix${gs}01$gtin" + "11260330" + "17261211" + "10400009999Z$gs" + "21$serial"

    @Test
    fun `le o DataMatrix com FNC1 e lote`() {
        val code = SmartGuidePackageParser.parse(raw())!!
        assertEquals(gtin, code.gtin)
        assertEquals(serial, code.serial)
        assertEquals("400009999Z", code.lot)
        assertEquals(LocalDate.of(2026, 3, 30), code.productionDate)
        assertEquals(LocalDate.of(2026, 12, 11), code.expiryDate)
        assertEquals("AC-$serial", code.advertisedName)
    }

    @Test
    fun `aceita identificador de simbologia e campos em outra ordem`() {
        assertEquals(serial, SmartGuidePackageParser.parse(raw("]d2"))!!.serial)
        val reordered = "21$serial$gs" + "17261211" + "01$gtin" + "10ABC"
        val code = SmartGuidePackageParser.parse(reordered)!!
        assertEquals(serial, code.serial)
        assertEquals("ABC", code.lot)
    }

    @Test
    fun `aceita o texto legivel com parenteses`() {
        val code = SmartGuidePackageParser.parse("(01)$gtin(17)261211(10)400009999Z(21)$serial")!!
        assertEquals(serial, code.serial)
        assertEquals(LocalDate.of(2026, 12, 11), code.expiryDate)
    }

    @Test
    fun `formato do Juggluco sem lote (46 caracteres)`() {
        val juggluco = "${gs}01$gtin" + "11260330" + "17261211" + "21$serial"
        assertEquals(46, juggluco.length)
        assertEquals(serial, SmartGuidePackageParser.parse(juggluco)!!.serial)
    }

    @Test
    fun `recusa embalagem de outro fabricante, digito verificador errado ou sem serial`() {
        assertNull(SmartGuidePackageParser.parse("${gs}0106972831640110" + "21$serial")) // GTIN Sibionics
        assertNull(SmartGuidePackageParser.parse("${gs}0104015630089216" + "21$serial")) // dígito errado
        assertNull(SmartGuidePackageParser.parse("${gs}01$gtin" + "17261211"))
        assertNull(SmartGuidePackageParser.parse(""))
        assertNull(SmartGuidePackageParser.parse(null))
    }

    @Test
    fun `data GS1 com dia 00 e o fim do mes`() {
        assertEquals(LocalDate.of(2026, 2, 28), SmartGuidePackageParser.gs1Date("260200"))
        assertNull(SmartGuidePackageParser.gs1Date("261301"))
    }

    @Test
    fun `serial digitado a mao`() {
        assertEquals(serial, SmartGuidePackageParser.normalizeSerial(" ${serial.lowercase()} "))
        assertEquals(serial, SmartGuidePackageParser.normalizeSerial("ac-$serial"))
        assertNull(SmartGuidePackageParser.normalizeSerial("1R0"))
        assertNull(SmartGuidePackageParser.normalizeSerial("1R00-999999"))
    }

    @Test
    fun `reconhece o sensor pelo nome AC- mais o serial inteiro`() {
        val matcher = SmartGuideIdentityMatcher()
        val code = SmartGuidePackageParser.parse(raw())!!.toSensorCode()
        assertTrue(matcher.matches("AC-$serial", code))
        assertTrue(matcher.matches("ac-${serial.lowercase()}", code))
        assertFalse(matcher.matches("AC-1R009999991", code))  // outro sensor
        assertFalse(matcher.matches("LT0123$serial", code))   // outra família
        assertFalse(matcher.matches(null, code))
        assertFalse(matcher.matches("AC-0", SensorCode("0", "0"))) // serial curto demais
    }
}
