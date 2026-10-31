package com.gluview.app.visualaudit

import com.gluview.app.util.GlucoseGraphRenderer
import com.gluview.app.util.GraphReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Caracterização (JVM puro) das contas que o gráfico da Home faz HOJE: padding, escala Y, janela X,
 * borda direita e toque. Os números esperados saem das fórmulas atuais do GlucoseGraphRenderer
 * (labelSz = clamp(h*0,08; 20; 32), padL = labelSz*3,2, padB = labelSz*2,2, padT = 10, padR = 8).
 * Quando o motor for refatorado (Fase 2), estes testes têm que continuar verdes sem mudar um número.
 */
class GraphGeometryCharacterizationTest {

    private val now = GlucoseFixtures.FIXED_NOW
    private val eps = 0.001f

    @Test fun homeFillS23Fe_990x880() {
        // h=880 → labelSz=32 → padL=102,4; padB=70,4; chartW=990-102,4-8=879,6; chartH=880-10-70,4=799,6
        val g = GlucoseGraphRenderer.geometry(990, 880, 3, rightEdgeMs = now)
        assertEquals(now - 3 * 3_600_000L, g.fromMs)
        assertEquals(now, g.nowMs)
        assertEquals(10f, g.yOf(300f), eps)
        assertEquals(809.6f, g.yOf(40f), eps)
        assertEquals(102.4f, g.xOf(g.fromMs), eps)
        assertEquals(982f, g.xOf(now), eps)
        // Fora da escala fica colado na borda (coerceIn), como hoje
        assertEquals(g.yOf(300f), g.yOf(400f), eps)
        assertEquals(g.yOf(40f), g.yOf(20f), eps)
    }

    @Test fun tightAndSmallSizes() {
        // h=289 (notificação) → labelSz=23,12 → padL=73,984; padB=50,864
        val n = GlucoseGraphRenderer.geometry(800, 289, 3, rightEdgeMs = now)
        assertEquals(73.984f, n.xOf(n.fromMs), eps)
        assertEquals(289f - 50.864f, n.yOf(40f), eps)
        // h=250 → labelSz=20 (piso) → padL=64; padB=44
        val s = GlucoseGraphRenderer.geometry(500, 250, 3, rightEdgeMs = now)
        assertEquals(64f, s.xOf(s.fromMs), eps)
        assertEquals(206f, s.yOf(40f), eps)
    }

    @Test fun axesSwitchOffBelow200px() {
        // h=199 → sem eixos: padL=4, padB=6
        val off = GlucoseGraphRenderer.geometry(600, 199, 3, rightEdgeMs = now)
        assertEquals(4f, off.xOf(off.fromMs), eps)
        assertEquals(193f, off.yOf(40f), eps)
        // h=200 → com eixos: labelSz=20 → padL=64
        val on = GlucoseGraphRenderer.geometry(600, 200, 3, rightEdgeMs = now)
        assertEquals(64f, on.xOf(on.fromMs), eps)
        // forceAxes=false (widgets) desliga mesmo com altura grande
        val widget = GlucoseGraphRenderer.geometry(800, 300, 3, forceAxes = false, rightEdgeMs = now)
        assertEquals(4f, widget.xOf(widget.fromMs), eps)
    }

    @Test fun timestampAtIsInverseOfXOf() {
        val g = GlucoseGraphRenderer.geometry(990, 880, 6, rightEdgeMs = now)
        val ts = now - 95 * 60_000L
        assertEquals(ts.toDouble(), g.timestampAt(g.xOf(ts)).toDouble(), 60_000.0)
    }

    @Test fun rightEdgeTolerance() {
        val past = listOf(GraphReading(now - 5 * 60_000L, 120f))
        assertEquals(now, GlucoseGraphRenderer.rightEdgeFor(past, now))
        assertEquals(now, GlucoseGraphRenderer.rightEdgeFor(emptyList(), now))
        // Leitura 3 min à frente (âncora adiantada): a borda acompanha a leitura
        val ahead3 = listOf(GraphReading(now + 3 * 60_000L, 120f))
        assertEquals(now + 3 * 60_000L, GlucoseGraphRenderer.rightEdgeFor(ahead3, now))
        // Leitura 10 min à frente: limitada a FUTURE_TOLERANCE_MS (6 min)
        val ahead10 = listOf(GraphReading(now + 10 * 60_000L, 120f))
        assertEquals(now + GlucoseGraphRenderer.FUTURE_TOLERANCE_MS, GlucoseGraphRenderer.rightEdgeFor(ahead10, now))
    }

    @Test fun nearestReadingByTap() {
        val readings = GlucoseFixtures.series(now, hours = 3)
        val g = GlucoseGraphRenderer.geometry(990, 880, 3, rightEdgeMs = now)
        val target = readings[readings.size / 2]
        assertSame(target, GlucoseGraphRenderer.nearestReading(readings, g.xOf(target.timestampMs), g))
        // Toque longe (> 80 px) de qualquer leitura: nada
        val sparse = listOf(readings.first())
        assertNull(GlucoseGraphRenderer.nearestReading(sparse, g.xOf(now), g))
        // Leituras fora da janela são ignoradas
        val old = listOf(GraphReading(now - 5 * 3_600_000L, 100f))
        assertNull(GlucoseGraphRenderer.nearestReading(old, g.xOf(g.fromMs), g))
    }
}
