package com.gluview.wear.data

import com.gluview.wear.contract.SnapshotSource
import com.gluview.wear.contract.SnapshotUnit
import com.gluview.wear.contract.SnapshotZone
import com.gluview.wear.contract.WatchFaceSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CloudFetcherTest {
    private val t = 1_794_150_000_000L
    private fun entry(sgv: Int, date: Long, dir: String = "Flat") = """{"sgv":$sgv,"date":$date,"direction":"$dir","type":"sgv"}"""

    private val phone = WatchFaceSnapshot(
        sgvMgdl = 100, deltaMgdl = 0, direction = "Flat", measuredAtMs = t - 600_000, zone = SnapshotZone.NORMAL,
        urgentLowMgdl = 54, lowMgdl = 80, highMgdl = 160, urgentHighMgdl = 240,
        unit = SnapshotUnit.MMOL_L, use24h = false, source = SnapshotSource.PHONE, staleAfterMin = 25,
    )

    @Test fun usaLimitesEPreferenciasDaUltimaLeituraDoCelular() {
        val s = CloudFetcher.parse("[${entry(78, t, "SingleDown")},${entry(86, t - 300_000)}]", phone)!!
        assertEquals(78, s.sgvMgdl)
        assertEquals(-8, s.deltaMgdl)
        assertEquals(t, s.measuredAtMs)
        assertEquals(SnapshotZone.LOW, s.zone)          // 78 <= 80 (limite do usuário, não o padrão 70)
        assertEquals(SnapshotUnit.MMOL_L, s.unit)
        assertEquals(25, s.staleAfterMin)
        assertEquals(SnapshotSource.CLOUD, s.source)
    }

    @Test fun semLeituraDoCelular_usaPadroes() {
        val s = CloudFetcher.parse("[${entry(60, t)}]", null)!!
        assertEquals(SnapshotZone.LOW, s.zone)
        assertNull(s.deltaMgdl)
    }

    @Test fun deltaSoEntreLeiturasProximas() {
        assertNull(CloudFetcher.parse("[${entry(120, t)},${entry(100, t - 40 * 60_000)}]", null)!!.deltaMgdl)
    }

    @Test fun respostaVaziaOuInvalida_null() {
        assertNull(CloudFetcher.parse("[]", null))
        assertNull(CloudFetcher.parse("""[{"sgv":5,"date":$t}]""", null))   // fora da faixa do contrato
        assertNull(CloudFetcher.parse("""[{"date":$t}]""", null))
    }
}
