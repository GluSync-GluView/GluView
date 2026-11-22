package com.gluview.app.wearables

import com.gluview.app.wearables.hub.WatchAlertBoard
import com.gluview.app.wearables.hub.WearableFamily
import com.gluview.app.wearables.hub.WearablesHub
import com.gluview.wearables.core.AlertRule
import com.gluview.wearables.core.VibrationStyle
import com.gluview.wearables.core.WearableAlertKind
import com.gluview.wearables.core.WearableAlertRouter
import com.gluview.wearables.core.WearableAlertSettings
import com.gluview.app.alert.wearableKind
import com.gluview.app.domain.model.GlucoseZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plano 21, F7.1 — partes puras do hub: ajustes por tipo de aparelho, quadro dos apps de relógio, zonas. */
class WearablesHubTest {
    @Test fun xiaomiSemAjusteNovoHerdaOLigaDesligaAntigo() {
        assertTrue(WearablesHub.decode(WearableFamily.XIAOMI, null, legacyXiaomiEnabled = true).enabled)
        assertTrue(!WearablesHub.decode(WearableFamily.XIAOMI, null, legacyXiaomiEnabled = false).enabled)
        // com ajuste salvo, vale o ajuste
        val saved = WearableAlertSettings.defaults(false, enabled = false).encode()
        assertTrue(!WearablesHub.decode(WearableFamily.XIAOMI, saved, legacyXiaomiEnabled = true).enabled)
        // os outros começam desligados
        assertTrue(!WearablesHub.decode(WearableFamily.HUAMI, null, legacyXiaomiEnabled = true).enabled)
    }

    @Test fun chamadaSoNaHuami() {
        val huami = WearablesHub.decode(WearableFamily.HUAMI, null, false).copy(enabled = true)
        assertEquals(VibrationStyle.CALL, WearableAlertRouter.decide(huami, WearableAlertKind.URGENT_LOW, WearableFamily.HUAMI.supported, 0, 1))
        // o mesmo ajuste numa Xiaomi/Wear/Garmin/Zepp vira insistente
        for (f in listOf(WearableFamily.XIAOMI, WearableFamily.WEAR_OS, WearableFamily.GARMIN, WearableFamily.ZEPP)) {
            assertEquals(f.name, VibrationStyle.INSISTENT, WearableAlertRouter.decide(huami, WearableAlertKind.URGENT_LOW, f.supported, 0, 1))
        }
        assertTrue(WearableFamily.HUAMI.supportsCall)
        assertTrue(WearableFamily.entries.filter { it.supportsCall } == listOf(WearableFamily.HUAMI))
    }

    @Test fun quadroDosRelogiosPorAppEExpira() {
        val b = WatchAlertBoard()
        b.post("garmin", WatchAlertBoard.Entry(7L, "LOW", "Glicose baixa", "62", listOf(500L, 200L, 500L), atMs = 1_000_000L))
        val m = b.forApp("garmin", nowMs = 1_000_000L + 3 * 60_000L)!!
        assertEquals(7L, m["id"]); assertEquals("Glicose baixa", m["t"]); assertEquals(3, m["age"])
        assertEquals(listOf(500L, 200L, 500L), m["v"])
        assertNull(b.forApp("zepp", 1_000_000L))
        assertNull(b.forApp("garmin", 1_000_000L + WatchAlertBoard.TTL_MS + 1))   // velho demais: some
        assertNull(b.forApp("garmin", 1_000_000L))
        b.post("zepp", WatchAlertBoard.Entry(8L, "HIGH", "Alta", "", emptyList(), 0L))
        b.clear()
        assertNull(b.forApp("zepp", 0L))
    }

    @Test fun zonasViramTiposDeAlerta() {
        assertEquals(WearableAlertKind.URGENT_LOW, GlucoseZone.URGENT_LOW.wearableKind())
        assertEquals(WearableAlertKind.QUICK_DROP, GlucoseZone.QUICK_DROP.wearableKind())
        assertEquals(WearableAlertKind.STALE, GlucoseZone.STALE.wearableKind())
        assertEquals(GlucoseZone.entries.map { it.name }, GlucoseZone.entries.map { it.wearableKind().name })
    }

    @Test fun regraDesligadaNaoVaiESemVibrarLevaOTexto() {
        val s = WearableAlertSettings.defaults(false, enabled = true)
            .with(WearableAlertKind.HIGH, AlertRule(false, VibrationStyle.DOUBLE))
            .with(WearableAlertKind.STALE, AlertRule(true, VibrationStyle.OFF))
        assertNull(WearableAlertRouter.decide(s, WearableAlertKind.HIGH, WearableFamily.GARMIN.supported, 0, 1))
        assertEquals(VibrationStyle.OFF, WearableAlertRouter.decide(s, WearableAlertKind.STALE, WearableFamily.GARMIN.supported, 0, 1))
    }
}
