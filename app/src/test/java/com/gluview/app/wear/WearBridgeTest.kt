package com.gluview.app.wear

import com.gluview.app.domain.model.GlucoseReading
import com.gluview.app.domain.model.GlucoseThresholds
import com.gluview.app.domain.model.GlucoseZone
import com.gluview.app.util.GlucoseUnit
import com.gluview.wear.contract.SnapshotSource
import com.gluview.wear.contract.SnapshotUnit
import com.gluview.wear.contract.SnapshotZone
import com.gluview.wear.contract.WatchFaceSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

class WearBridgeTest {
    private val now = 1_794_150_000_000L
    private val limits = GlucoseThresholds(54f, 72f, 190f, 270f)

    private fun reading(zone: GlucoseZone = GlucoseZone.NORMAL, sgv: Double = 112.4, stale: Boolean = false) =
        GlucoseReading(sgvMgDl = sgv, direction = "FortyFiveDown", arrow = "↘", timestamp = "14:00",
            zone = zone, delta = -3, isStale = stale, epochMs = now - 120_000)

    @Test fun converteLeituraComLimitesDoUsuario() {
        val s = WearBridge.buildSnapshot(reading(), limits, GlucoseUnit.MMOL_L, use24h = false, staleAfterMin = 20)
        assertEquals(112, s.sgvMgdl)
        assertEquals(-3, s.deltaMgdl)
        assertEquals(now - 120_000, s.measuredAtMs)
        assertEquals(listOf(54, 72, 190, 270), listOf(s.urgentLowMgdl, s.lowMgdl, s.highMgdl, s.urgentHighMgdl))
        assertEquals(SnapshotUnit.MMOL_L, s.unit)
        assertEquals(false, s.use24h)
        assertEquals(SnapshotSource.PHONE, s.source)
        assertEquals(20, s.staleAfterMin)
        // O que sai do celular sempre volta íntegro no relógio.
        assertEquals(s, WatchFaceSnapshot.fromMap(s.toMap()))
    }

    @Test fun zonaStaleDoCelular_eReclassificadaPeloValor_relogioDecideAIdade() {
        val s = WearBridge.buildSnapshot(reading(GlucoseZone.STALE, sgv = 60.0, stale = true), limits,
            GlucoseUnit.MG_DL, use24h = true, staleAfterMin = 20)
        assertEquals(SnapshotZone.LOW, s.zone)
    }

    @Test fun limiteDeDadoVelhoForaDaFaixa_eLimitado() {
        assertEquals(120, WearBridge.buildSnapshot(reading(), limits, GlucoseUnit.MG_DL, true, 500).staleAfterMin)
        assertEquals(1, WearBridge.buildSnapshot(reading(), limits, GlucoseUnit.MG_DL, true, 0).staleAfterMin)
    }

    @Test fun direcaoComoSetaOuNome_viraNomeNightscout() {
        assertEquals("Flat", WearBridge.nightscoutDirection("→"))
        assertEquals("DoubleDown", WearBridge.nightscoutDirection("↓↓"))
        assertEquals("FortyFiveUp", WearBridge.nightscoutDirection("FortyFiveUp"))
        assertEquals("NONE", WearBridge.nightscoutDirection("?"))
        val s = WearBridge.buildSnapshot(reading().copy(direction = "↓↓", arrow = "↓↓"), limits, GlucoseUnit.MG_DL, true, 20)
        assertEquals("DoubleDown", s.direction)
        assertEquals("⇊", s.arrow(now))
    }
}
