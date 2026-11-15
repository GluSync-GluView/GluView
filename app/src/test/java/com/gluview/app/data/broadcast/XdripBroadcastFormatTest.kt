package com.gluview.app.data.broadcast

import com.gluview.app.domain.model.GlucoseReading
import com.gluview.app.domain.model.GlucoseZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** PT-7 Fase A — formato do broadcast do xDrip+ (nomes de tendência, inclinação, filtros). */
class XdripBroadcastFormatTest {

    private fun reading(mgdl: Double, at: Long, stale: Boolean = false) =
        GlucoseReading(mgdl, "", "", "", GlucoseZone.NORMAL, 0, stale, 0, at)

    @Test fun `nomes de tendencia nos cortes do xDrip`() {
        val cases = mapOf(
            null to "NONE", 50.0 to "NONE", 4.0 to "DoubleUp", 3.0 to "SingleUp", 1.5 to "FortyFiveUp",
            0.0 to "Flat", -1.0 to "FortyFiveDown", -1.5 to "FortyFiveDown", -2.5 to "SingleDown", -3.5 to "DoubleDown", -9.0 to "DoubleDown"
        )
        cases.forEach { (rate, name) -> assertEquals("$rate", name, XdripBroadcastFormat.slopeName(rate)) }
    }

    @Test fun `taxa por minuto e limites`() {
        assertEquals(2.0, XdripBroadcastFormat.rate(100.0, 0L, 110.0, 300_000L)!!, 1e-9)
        assertNull(XdripBroadcastFormat.rate(null, null, 110.0, 300_000L))
        assertNull(XdripBroadcastFormat.rate(100.0, 0L, 110.0, 16 * 60_000L))
        assertNull(XdripBroadcastFormat.rate(100.0, 500L, 110.0, 400L))
    }

    @Test fun `nao manda leitura velha nem repetida`() {
        assertTrue(XdripBroadcastFormat.shouldSend(reading(120.0, 1_000_000L), 0L))
        assertFalse(XdripBroadcastFormat.shouldSend(reading(120.0, 1_000_000L, stale = true), 0L))
        assertFalse(XdripBroadcastFormat.shouldSend(reading(120.0, 1_000_000L), 1_000_000L - 30_000L))
        assertFalse(XdripBroadcastFormat.shouldSend(reading(0.0, 1_000_000L), 0L))
    }

    @Test fun `extras no formato do xDrip`() {
        val e = XdripBroadcastFormat.extras(reading(123.0, 1_700_000_000_000L), 1.2, 1_699_000_000_000L, "Sibionics China")
        assertEquals(123.0, e[XdripBroadcastFormat.EXTRA_BG_ESTIMATE])
        assertEquals("FortyFiveUp", e[XdripBroadcastFormat.EXTRA_BG_SLOPE_NAME])
        assertEquals(1.2 / 60_000.0, e[XdripBroadcastFormat.EXTRA_BG_SLOPE] as Double, 1e-15)
        assertEquals(1_700_000_000_000L, e[XdripBroadcastFormat.EXTRA_TIMESTAMP])
        assertEquals(1_699_000_000_000L, e[XdripBroadcastFormat.EXTRA_SENSOR_STARTED_AT])
        assertEquals("com.eveningoutpost.dexdrip.BgEstimate", XdripBroadcastFormat.ACTION)
        assertFalse(XdripBroadcastFormat.extras(reading(123.0, 1L), null, 0L, "x").containsKey(XdripBroadcastFormat.EXTRA_SENSOR_STARTED_AT))
    }
}
