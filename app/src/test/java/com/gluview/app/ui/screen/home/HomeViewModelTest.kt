package com.gluview.app.ui.screen.home

import com.gluview.app.domain.model.GlucoseReading
import com.gluview.app.domain.model.GlucoseZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Reproduz o bug relatado: no sensor BLE, só a leitura de maior índice de CADA pacote vira
 * "atual" (SibionicsBleSource.persist/makeCurrent); se o algoritmo do sensor entregar essa
 * leitura um ciclo atrasada, o histórico local (mesma fonte do gráfico) já tem uma leitura
 * mais nova do que o card do topo. pickFreshestReading é a salvaguarda: nunca deixa o card
 * mostrar algo mais velho do que o próprio histórico já tem.
 */
class HomeViewModelTest {

    private fun reading(epochMs: Long, sgv: Double) = GlucoseReading(
        sgvMgDl    = sgv,
        direction  = "Flat",
        arrow      = "→",
        timestamp  = "",
        zone       = GlucoseZone.NORMAL,
        delta      = 0,
        isStale    = false,
        minutesAgo = 0,
        epochMs    = epochMs
    )

    @Test
    fun `historico mais novo que o card atual vence (bug real)`() {
        // 2:10 PM, 61 mg/dL "atual" vs 2:20 PM, 59 mg/dL já no histórico/gráfico
        val current = reading(epochMs = 1_000L, sgv = 61.0)
        val latestDb = reading(epochMs = 2_000L, sgv = 59.0)

        val freshest = HomeViewModel.pickFreshestReading(current, latestDb)

        assertSame("deveria escolher a leitura do histórico, mais recente", latestDb, freshest)
        assertEquals(59.0, freshest?.sgvMgDl)
    }

    @Test
    fun `card atual mais novo que o historico permanece`() {
        val current = reading(epochMs = 5_000L, sgv = 100.0)
        val latestDb = reading(epochMs = 3_000L, sgv = 95.0)

        val freshest = HomeViewModel.pickFreshestReading(current, latestDb)

        assertSame(current, freshest)
    }

    @Test
    fun `mesmo epochMs mantem o card atual (nao regride por empate)`() {
        val current = reading(epochMs = 4_000L, sgv = 110.0)
        val latestDb = reading(epochMs = 4_000L, sgv = 110.0)

        assertSame(current, HomeViewModel.pickFreshestReading(current, latestDb))
    }

    @Test
    fun `sem leitura atual usa o historico (seed inicial)`() {
        val latestDb = reading(epochMs = 1_000L, sgv = 88.0)

        assertSame(latestDb, HomeViewModel.pickFreshestReading(null, latestDb))
    }

    @Test
    fun `sem historico usa o card atual`() {
        val current = reading(epochMs = 1_000L, sgv = 88.0)

        assertSame(current, HomeViewModel.pickFreshestReading(current, null))
    }

    @Test
    fun `sem nenhum dos dois resulta em null (loading)`() {
        assertNull(HomeViewModel.pickFreshestReading(null, null))
    }
}
