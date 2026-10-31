package com.gluview.app.domain.model

import com.gluview.app.util.graph.GraphPalette
import org.junit.Assert.assertEquals
import org.junit.Test

/** L2: uma regra só de zona, igual ao AlertEngine.ClassifyZone do GluSync (o limite conta como fora). */
class GlucoseThresholdsTest {

    private val padrao = GlucoseThresholds.DEFAULT

    @Test fun `limites exatos contam como fora`() {
        assertEquals(GlucoseZone.URGENT_LOW, padrao.classify(55.0))
        assertEquals(GlucoseZone.LOW, padrao.classify(70.0))
        assertEquals(GlucoseZone.NORMAL, padrao.classify(70.5))
        assertEquals(GlucoseZone.NORMAL, padrao.classify(179.9))
        assertEquals(GlucoseZone.HIGH, padrao.classify(180.0))
        assertEquals(GlucoseZone.URGENT_HIGH, padrao.classify(250.0))
    }

    @Test fun `usa os limites do usuario`() {
        val meus = GlucoseThresholds(urgentLow = 60f, low = 80f, high = 160f, urgentHigh = 220f)
        assertEquals(GlucoseZone.URGENT_LOW, meus.classify(58.0))
        assertEquals(GlucoseZone.LOW, meus.classify(75.0))
        assertEquals(GlucoseZone.HIGH, meus.classify(170.0))
        assertEquals(GlucoseZone.URGENT_HIGH, meus.classify(230.0))
        assertEquals("low", meus.status(80.0))
    }

    @Test fun `seta de queda rapida e sem sinal tem prioridade`() {
        assertEquals(GlucoseZone.QUICK_DROP, padrao.classify(120.0, "↓↓"))
        assertEquals(GlucoseZone.STALE, padrao.classify(50.0, "↓↓", stale = true))
    }

    @Test fun `ponto do grafico segue a mesma regra do card`() {
        // Antes: 70 saía verde no gráfico com o card dizendo "baixa".
        assertEquals(GraphPalette.zoneColor(69f, 70f, 180f), GraphPalette.zoneColor(70f, 70f, 180f))
        assertEquals(GraphPalette.zoneColor(181f, 70f, 180f), GraphPalette.zoneColor(180f, 70f, 180f))
        assertEquals(GraphPalette.zoneColor(40f, 70f, 180f), GraphPalette.zoneColor(55f, 70f, 180f))
        assertEquals(GraphPalette.zoneColor(40f, 80f, 160f, urgentLow = 60f), GraphPalette.zoneColor(60f, 80f, 160f, urgentLow = 60f))
    }
}
