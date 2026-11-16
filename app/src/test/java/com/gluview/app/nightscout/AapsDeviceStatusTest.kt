package com.gluview.app.nightscout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AapsDeviceStatusTest {
    private val aaps = """
        [{"_id":"1","device":"openaps://samsung","created_at":"2026-11-16T10:00:00Z","mills":1794823200000,
          "pump":{"reservoir":123.5,"battery":{"percent":75},"status":{"status":"normal","suspended":false}},
          "openaps":{"iob":{"iob":2.31,"basaliob":-0.2,"time":"2026-11-16T10:00:00Z"},
            "suggested":{"bg":140,"COB":22,"IOB":2.31,"eventualBG":118,"reason":"COB: 22, Dev: 5","timestamp":"2026-11-16T09:59:00Z"},
            "enacted":{"rate":0.8,"duration":30,"received":true,"reason":"COB: 22, Dev: 5","timestamp":"2026-11-16T09:59:30Z"}},
          "uploaderBattery":64},
         {"_id":"0","device":"openaps://samsung","created_at":"2026-11-16T09:55:00Z","pump":{"reservoir":124.0}}]
    """.trimIndent()

    @Test fun leOMaisNovoComTudo() {
        val s = AapsDeviceStatus.parseLatest(aaps)!!
        assertEquals(123.5, s.reservoirU!!, 0.0)
        assertEquals(75, s.pumpBatteryPct)
        assertEquals(2.31, s.iob!!, 1e-9)
        assertEquals(22.0, s.cob!!, 0.0)
        assertEquals(0.8, s.tempBasalRate!!, 0.0)
        assertEquals(30, s.tempBasalMin)
        assertEquals(118.0, s.eventualBg!!, 0.0)
        assertTrue(s.enacted)
        assertEquals(64, s.uploaderBatteryPct)
        assertEquals(1794823170000L, s.loopAtMs)
    }

    @Test fun toleraFaltasELixo() {
        assertNull(AapsDeviceStatus.parseLatest("[]"))
        assertNull(AapsDeviceStatus.parseLatest("lixo"))
        assertNull(AapsDeviceStatus.parseLatest("""[{"created_at":"2026-11-16T10:00:00Z","uploader":{"battery":50}}]"""))
        val s = AapsDeviceStatus.parseLatest("""[{"created_at":"2026-11-16T10:00:00Z","pump":{"status":{"suspended":true}}}]""")!!
        assertTrue(s.suspended); assertNull(s.iob); assertNull(s.loopAtMs)
    }
}
