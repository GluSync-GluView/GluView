package com.gluview.wear.contract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class WatchFaceSnapshotTest {

    private val now = 1_794_150_000_000L
    private val ptBr = Locale.forLanguageTag("pt-BR")

    private fun snap(
        sgv: Int = 112,
        delta: Int? = -3,
        measuredAt: Long = now - 2 * 60_000,
        unit: SnapshotUnit = SnapshotUnit.MG_DL,
    ) = WatchFaceSnapshot(
        sgvMgdl = sgv, deltaMgdl = delta, direction = "FortyFiveDown", measuredAtMs = measuredAt,
        zone = SnapshotZone.NORMAL, urgentLowMgdl = 55, lowMgdl = 70, highMgdl = 180, urgentHighMgdl = 250,
        unit = unit, use24h = true, source = SnapshotSource.PHONE,
    )

    // ── Regra de saúde: dado velho nunca aparece como atual ─────────────────────

    @Test fun recente_mostraValorSetaEDelta() {
        val s = snap()
        assertFalse(s.isStale(now))
        assertEquals("112", s.displayValue(now, ptBr))
        assertEquals("↘", s.arrow(now))
        assertEquals("-3", s.deltaText(now, ptBr))
    }

    @Test fun limiteDe15Min_aindaVale_umMsDepoisJaEhVelho() {
        val s = snap(measuredAt = now - 15 * 60_000)
        assertFalse(s.isStale(now))
        assertTrue(s.isStale(now + 1))
    }

    @Test fun velho_viraTracos_semSetaNemDelta() {
        val s = snap(measuredAt = now - 16 * 60_000)
        assertEquals(WatchFaceSnapshot.PLACEHOLDER, s.displayValue(now, ptBr))
        assertEquals("", s.arrow(now))
        assertEquals("", s.deltaText(now, ptBr))
    }

    @Test fun medicaoNoFuturo_alemDe5Min_ehTratadaComoVelha() {
        assertFalse(snap(measuredAt = now + 4 * 60_000).isStale(now))
        assertTrue(snap(measuredAt = now + 6 * 60_000).isStale(now))
    }

    // ── Unidade ─────────────────────────────────────────────────────────────────

    @Test fun mmol_usaFator18EVirgulaNoPortugues() {
        val s = snap(sgv = 112, delta = -7, unit = SnapshotUnit.MMOL_L)
        assertEquals("6,2", s.displayValue(now, ptBr))
        assertEquals("-0,4", s.deltaText(now, ptBr))
        assertEquals("6.2", s.displayValue(now, Locale.US))
    }

    @Test fun deltaZeroEPositivo() {
        assertEquals("0", snap(delta = 0).deltaText(now, ptBr))
        assertEquals("+5", snap(delta = 5).deltaText(now, ptBr))
        assertEquals("", snap(delta = null).deltaText(now, ptBr))
    }

    // ── Ida e volta pelo DataMap ────────────────────────────────────────────────

    @Test fun toMapEFromMap_idaEVolta() {
        val s = snap()
        assertEquals(s, WatchFaceSnapshot.fromMap(s.toMap()))
    }

    @Test fun fromMap_semDelta_aceita() {
        val s = snap(delta = null)
        assertEquals(s, WatchFaceSnapshot.fromMap(s.toMap()))
    }

    @Test fun fromMap_campoObrigatorioFaltando_null() {
        for (k in listOf(WatchFaceSnapshot.K_VERSION, WatchFaceSnapshot.K_SGV, WatchFaceSnapshot.K_MEASURED_AT,
                         WatchFaceSnapshot.K_ZONE, WatchFaceSnapshot.K_LOW)) {
            assertNull("sem $k", WatchFaceSnapshot.fromMap(snap().toMap() - k))
        }
    }

    @Test fun fromMap_valorImpossivel_null() {
        assertNull(WatchFaceSnapshot.fromMap(snap().toMap() + (WatchFaceSnapshot.K_SGV to 5)))
        assertNull(WatchFaceSnapshot.fromMap(snap().toMap() + (WatchFaceSnapshot.K_SGV to 900)))
        assertNull(WatchFaceSnapshot.fromMap(snap().toMap() + (WatchFaceSnapshot.K_SGV to "112")))
        // Limites fora de ordem (baixo acima do alto) — config corrompida.
        assertNull(WatchFaceSnapshot.fromMap(snap().toMap() + (WatchFaceSnapshot.K_LOW to 200)))
    }

    @Test fun fromMap_versaoFutura_recusada() {
        assertNull(WatchFaceSnapshot.fromMap(snap().toMap() + (WatchFaceSnapshot.K_VERSION to 2)))
    }

    @Test fun fromMap_zonaDesconhecida_recusada_unidadeDesconhecida_viraMgdl() {
        assertNull(WatchFaceSnapshot.fromMap(snap().toMap() + (WatchFaceSnapshot.K_ZONE to "XYZ")))
        val s = WatchFaceSnapshot.fromMap(snap().toMap() + (WatchFaceSnapshot.K_UNIT to "XYZ"))
        assertEquals(SnapshotUnit.MG_DL, s!!.unit)
    }

    @Test fun fromMap_staleAfterForaDaFaixa_usaPadrao() {
        val s = WatchFaceSnapshot.fromMap(snap().toMap() + (WatchFaceSnapshot.K_STALE_AFTER to 0))
        assertEquals(WatchFaceSnapshot.DEFAULT_STALE_AFTER_MIN, s!!.staleAfterMin)
    }

    @Test fun setas_iguaisAoNightscout() {
        assertEquals("⇈", WatchFaceSnapshot.arrowFor("DoubleUp"))
        assertEquals("→", WatchFaceSnapshot.arrowFor("Flat"))
        assertEquals("⇊", WatchFaceSnapshot.arrowFor("DoubleDown"))
        assertEquals("", WatchFaceSnapshot.arrowFor("NOT COMPUTABLE"))
    }

    @Test fun classify_limitesInclusivos_eQuedaRapida() {
        fun z(v: Int, dir: String = "Flat") = SnapshotZone.classify(v, 55, 70, 180, 250, dir)
        assertEquals(SnapshotZone.URGENT_LOW, z(55))
        assertEquals(SnapshotZone.LOW, z(70))
        assertEquals(SnapshotZone.NORMAL, z(71))
        assertEquals(SnapshotZone.HIGH, z(180))
        assertEquals(SnapshotZone.URGENT_HIGH, z(250))
        assertEquals(SnapshotZone.QUICK_DROP, z(150, "DoubleDown"))
    }

    @Test fun caminhos_naoMudam() {
        // Renomear quebra relógios já instalados — este teste existe para isso falhar alto.
        assertEquals("/gluview/reading", WearPaths.READING)
        assertEquals("gluview_wear", WearPaths.CAPABILITY)
    }
}
