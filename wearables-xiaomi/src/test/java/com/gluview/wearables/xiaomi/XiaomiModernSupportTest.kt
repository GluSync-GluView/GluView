package com.gluview.wearables.xiaomi

import com.gluview.wearables.core.WearableCapabilities
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XiaomiModernSupportTest {

    @Test
    fun testAllModelsHaveDeviceInfo() {
        runTest {
            for (model in XiaomiModernModel.entries) {
                val info = model.deviceInfo()
                assertNotNull(info)
                assertEquals(model.id, info.id)
                assertEquals(model.displayName, info.displayName)
                assertEquals("xiaomi_modern", info.family)
                assertEquals("Sem teste com hardware real", info.maturityLabel)
                assertCapabilities(info.capabilities)
            }
        }
    }

    @Test
    fun testFromId() {
        assertEquals(XiaomiModernModel.MI_BAND_8, XiaomiModernModel.fromId("mi_band_8")!!)
        assertEquals(XiaomiModernModel.MI_BAND_9_PRO, XiaomiModernModel.fromId("mi_band_9_pro")!!)
        assertEquals(null, XiaomiModernModel.fromId("unknown"))
    }

    @Test
    fun testCreateTransportAndCoordinator() {
        runTest {
            val model = XiaomiModernModel.MI_BAND_8
            val transport = model.createTransport(this)
            val coordinator = model.createCoordinator(this)

            assertNotNull(transport)
            assertNotNull(coordinator)
            assertEquals(model.deviceInfo(), transport.info)
        }
    }

    @Test
    fun testGlucoseUpdateDataClass() {
        val update = XiaomiGlucoseUpdate(
            mgdl = 120,
            deltaMgdl = 5,
            direction = "UP",
            timestampMs = System.currentTimeMillis(),
            stale = false
        )
        assertEquals(120, update.mgdl)
        assertEquals(5, update.deltaMgdl)
        assertEquals("UP", update.direction)
        assertEquals(false, update.stale)
    }

    private fun assertCapabilities(caps: WearableCapabilities) {
        assertTrue(caps.canShowGlucose)
        assertTrue(caps.canShowGraph)
        assertTrue(caps.canVibrate)
        assertTrue(caps.canReceiveSnooze)
        assertTrue(caps.canReportBattery)
        assertTrue(caps.canSendWatchface)
    }
}