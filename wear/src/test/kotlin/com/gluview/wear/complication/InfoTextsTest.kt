package com.gluview.wear.complication

import com.gluview.wear.contract.SnapshotSource
import com.gluview.wear.contract.SnapshotUnit
import com.gluview.wear.contract.SnapshotZone
import com.gluview.wear.contract.WatchFaceSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Textos das complications de informação: mesmas regras do visor da Mi Band (ComponentKind do :app). */
class InfoTextsTest {
    private val now = 1_794_150_000_000L
    private fun snap(sgv: Int, ageMin: Long = 2) = WatchFaceSnapshot(
        sgvMgdl = sgv, deltaMgdl = 0, direction = "Flat", measuredAtMs = now - ageMin * 60_000,
        zone = SnapshotZone.NORMAL, urgentLowMgdl = 55, lowMgdl = 70, highMgdl = 180, urgentHighMgdl = 250,
        unit = SnapshotUnit.MG_DL, use24h = true, source = SnapshotSource.PHONE,
    )

    @Test fun alertaDeBaixa_soAbaixoDoLimite() {
        assertEquals("⚠ Baixo", InfoTexts.alert(snap(69), now, low = true))
        assertNull(InfoTexts.alert(snap(70), now, low = true))
        assertNull(InfoTexts.alert(snap(69), now, low = false))
    }

    @Test fun alertaDeAlta_soAcimaDoLimite() {
        assertEquals("⚠ Alto", InfoTexts.alert(snap(181), now, low = false))
        assertNull(InfoTexts.alert(snap(180), now, low = false))
    }

    @Test fun leituraVelha_nuncaMostraAlerta() {
        assertNull(InfoTexts.alert(snap(50, ageMin = 30), now, low = true))
        assertNull(InfoTexts.alert(null, now, low = true))
    }

    @Test fun sensorEBateria() {
        val s = snap(112).copy(sensorStartMs = now - 3 * 86_400_000L, sensorConnected = true, phoneBatteryPct = 45)
        assertEquals("3d", InfoTexts.sensorDays(s, now))
        assertEquals("ON", InfoTexts.sensorOnOff(s))
        assertEquals("45%", InfoTexts.phoneBattery(s))
        assertEquals("--", InfoTexts.sensorDays(snap(112), now))
        assertNull(InfoTexts.sensorOnOff(snap(112)))
        assertNull(InfoTexts.phoneBattery(snap(112)))
    }
}
