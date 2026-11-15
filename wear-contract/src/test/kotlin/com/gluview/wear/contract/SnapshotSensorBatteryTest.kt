package com.gluview.wear.contract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Campos opcionais de sensor e bateria do celular (componentes do visor no Wear OS, 14/11/2026). */
class SnapshotSensorBatteryTest {
    private val now = 1_794_150_000_000L
    private val base = WatchFaceSnapshot(
        sgvMgdl = 112, deltaMgdl = -3, direction = "Flat", measuredAtMs = now - 60_000,
        zone = SnapshotZone.NORMAL, urgentLowMgdl = 55, lowMgdl = 70, highMgdl = 180, urgentHighMgdl = 250,
        unit = SnapshotUnit.MG_DL, use24h = true, source = SnapshotSource.PHONE,
    )

    @Test fun idaEVolta_comSensorEBateria() {
        val s = base.copy(sensorStartMs = now - 5 * 86_400_000L - 3_600_000L, sensorConnected = false, phoneBatteryPct = 82)
        val back = WatchFaceSnapshot.fromMap(s.toMap())!!
        assertEquals(s, back)
        assertEquals(5, back.sensorAgeDays(now))
    }

    @Test fun celularAntigo_semOsCampos_continuaValendo() {
        val back = WatchFaceSnapshot.fromMap(base.toMap())
        assertNotNull(back)
        assertNull(back!!.sensorStartMs); assertNull(back.sensorConnected); assertNull(back.phoneBatteryPct)
        assertNull(back.sensorAgeDays(now))
    }

    @Test fun valorInvalido_viraNull_semDerrubarALeitura() {
        val map = base.toMap() + mapOf(WatchFaceSnapshot.K_PHONE_BATTERY to 140, WatchFaceSnapshot.K_SENSOR_START to -5L,
            WatchFaceSnapshot.K_SENSOR_ON to "sim")
        val back = WatchFaceSnapshot.fromMap(map)!!
        assertEquals(112, back.sgvMgdl)
        assertNull(back.phoneBatteryPct); assertNull(back.sensorStartMs); assertNull(back.sensorConnected)
    }

    @Test fun inicioNoFuturo_naoDaIdade() {
        assertNull(base.copy(sensorStartMs = now + 60_000).sensorAgeDays(now))
    }
}
