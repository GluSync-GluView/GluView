package com.gluview.app.data.integration

import com.gluview.app.domain.model.GlucoseReading
import com.gluview.app.domain.model.GlucoseZone
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Formato do PostReceiverEgvRecords igual ao xDrip (ShareUploadPayload/Egv/ShareUploadableBg). */
@RunWith(RobolectricTestRunner::class)
class DexcomShareUploaderTest {

    private fun reading(sgv: Double, direction: String, arrow: String, ms: Long) =
        GlucoseReading(sgv, direction, arrow, "", GlucoseZone.NORMAL, 0, false, 0, ms)

    @Test fun payloadMatchesXdripShape() {
        val json = JSONObject(DexcomShareUploader.payload("SM00000000",
            listOf(reading(123.6, "FortyFiveUp", "↗", 1_700_000_123_456L))))
        assertEquals("SM00000000", json.getString("SN"))
        assertEquals(-5, json.getInt("TA"))
        val egv = json.getJSONArray("Egvs").getJSONObject(0)
        assertEquals(124, egv.getInt("Value"))
        assertEquals(3, egv.getInt("Trend"))
        assertEquals("/Date(1700000123000)/", egv.getString("ST"))
        assertEquals(egv.getString("ST"), egv.getString("DT"))
    }

    @Test fun trendFallsBackToArrowWhenDirectionBlank() {
        val egv = JSONObject(DexcomShareUploader.payload("X", listOf(reading(90.0, "", "↓↓", 1000L))))
            .getJSONArray("Egvs").getJSONObject(0)
        assertEquals(7, egv.getInt("Trend"))
    }

    @Test fun trendOrdinalsFollowShare() {
        val expected = mapOf("DoubleUp" to 1, "SingleUp" to 2, "FortyFiveUp" to 3, "Flat" to 4,
            "FortyFiveDown" to 5, "SingleDown" to 6, "DoubleDown" to 7, "NOT COMPUTABLE" to 0, "" to 0)
        expected.forEach { (k, v) -> assertEquals(k, v, DexcomShareUploader.trendOrdinal(k)) }
    }
}
