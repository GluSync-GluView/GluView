package com.gluview.app.util.graph

import com.gluview.app.util.GlucoseUnit
import com.gluview.app.util.GraphReading
import com.gluview.app.util.TimeFormat
import com.gluview.app.visualaudit.GlucoseFixtures
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

/**
 * Layout do gráfico em JVM pura (Fase 2). Os defaults reproduzem o desenho de hoje (o oráculo
 * prova os pixels); cada extensão é ligada sozinha e conferida aqui.
 */
class GraphLayoutTest {

    private val now = GlucoseFixtures.FIXED_NOW
    private lateinit var savedZone: TimeZone
    private lateinit var savedLocale: Locale

    @Before fun fix() {
        savedZone = TimeZone.getDefault(); savedLocale = Locale.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(GlucoseFixtures.ZONE))
        Locale.setDefault(Locale.forLanguageTag("pt-BR"))
    }

    @After fun restore() { TimeZone.setDefault(savedZone); Locale.setDefault(savedLocale) }

    private fun spec(hours: Int = 3, w: Int = 990, h: Int = 880) =
        GraphSpec(w, h, GraphWindow.Trailing(hours), nowMs = now)

    private fun layout(s: GraphSpec, r: List<GraphReading> = GlucoseFixtures.series(now), m: (String, Float) -> Float = GraphLayout.ESTIMATED_TEXT_WIDTH) =
        GraphLayout.compute(r, s, m)

    private fun GraphLayout.texts() = ops.filterIsInstance<DrawOp.Text>()
    private fun GraphLayout.yLabels() = texts().filter { it.align == DrawOp.Align.RIGHT }.map { it.text }
    private fun GraphLayout.xLabels() = texts().filter { it.align == DrawOp.Align.CENTER }

    @Test fun `defaults - o desenho de hoje`() {
        val l = layout(spec())
        assertEquals(DrawOp.Fill(GraphPalette.BG_DARK), l.ops.first())
        assertEquals(listOf("70", "100", "150", "180", "250"), l.yLabels())
        assertTrue(l.xLabels().all { Regex("""\d{2}:00""").matches(it.text) })
        assertEquals(1, l.ops.filterIsInstance<DrawOp.Polyline>().single().segments.size)
        assertTrue(l.showAxes)
        assertEquals(32f, l.labelSizePx)
    }

    @Test fun `mmol - rotulos convertidos e linhas na mesma altura`() {
        val mg = layout(spec())
        val mmol = layout(spec().copy(unit = GlucoseUnit.MMOL_L))
        assertEquals(listOf("3,9", "5,6", "8,3", "10,0", "13,9"), mmol.yLabels())
        assertEquals(mg.ops.filterIsInstance<DrawOp.Line>(), mmol.ops.filterIsInstance<DrawOp.Line>())
    }

    @Test fun `12h - so a hora cheia com AM-PM`() {
        val l = layout(spec(hours = 6).copy(timeFormat = TimeFormat.H12))
        assertTrue(l.xLabels().isNotEmpty())
        assertTrue(l.xLabels().map { it.text }.toString(), l.xLabels().all { Regex("""\d{1,2} (AM|PM)""").matches(it.text) })
    }

    @Test fun `janela fixa nao anda com leitura no futuro`() {
        val from = now - 6 * 3_600_000L
        val l = layout(spec().copy(window = GraphWindow.Fixed(from, now)), GlucoseFixtures.newestInFuture(now))
        assertEquals(from, l.geometry.fromMs)
        assertEquals(now, l.geometry.nowMs)
        assertTrue(l.visible.all { it.timestampMs <= now })
    }

    @Test fun `passo de tick configuravel`() {
        val ticks = layout(spec(hours = 6).copy(xTickStepHours = 2)).ops.filterIsInstance<DrawOp.Line>()
            .filter { it.x0 == it.x1 }.map { it.x0 }
        assertEquals(3, ticks.size)
    }

    @Test fun `rotulo X nao corta na borda direita`() {
        // Relógio 3 min depois da hora cheia: o último tick fica colado na borda direita.
        val atHourPlus3 = now - (now % 3_600_000L) + 3 * 60_000L
        val s = GraphSpec(990, 880, GraphWindow.Trailing(3), nowMs = atHourPlus3)
        val width = { _: String, _: Float -> 80f }
        val plain = GraphLayout.compute(emptyList(), s, width).xLabels().maxOf { it.x }
        val clamped = GraphLayout.compute(emptyList(), s.copy(clampXLabels = true), width).xLabels().maxOf { it.x }
        assertTrue("sem a regra o rótulo passa da borda", plain + 40f > 990f)
        assertTrue("com a regra o rótulo cabe", clamped + 40f <= 990f)
    }

    @Test fun `rotulos Y nao colidem quando o limiar encosta no 100`() {
        val s = spec().copy(hipo = 104f)
        assertTrue(layout(s).yLabels().containsAll(listOf("104", "100")))
        val fixed = layout(s.copy(avoidYLabelCollision = true)).yLabels()
        assertTrue("o limiar do usuário fica", "104" in fixed)
        assertFalse("o neutro 100 sai", "100" in fixed)
    }

    @Test fun `tamanho do rotulo vindo do hospedeiro`() {
        val l = layout(spec().copy(labelSizePx = 40f))
        assertEquals(40f, l.labelSizePx)
        assertEquals(128f, l.geometry.padL, 0.001f)
    }

    @Test fun `fundo transparente e modo sem texto`() {
        assertTrue(layout(spec().copy(transparentBackground = true)).ops.none { it is DrawOp.Fill })
        val noText = layout(spec().copy(drawText = false))
        assertTrue(noText.texts().isEmpty())
        assertTrue(noText.ops.filterIsInstance<DrawOp.Line>().size >= 4)
    }

    @Test fun `linha quebra no buraco so quando pedido`() {
        val gap = GlucoseFixtures.withGap(GlucoseFixtures.series(now), fromMinAgo = 150, toMinAgo = 90)
        assertEquals(1, layout(spec(), gap).ops.filterIsInstance<DrawOp.Polyline>().single().segments.size)
        val broken = layout(spec().copy(gapBreakMs = 15 * 60_000L), gap).ops.filterIsInstance<DrawOp.Polyline>().single()
        assertEquals(2, broken.segments.size)
    }

    @Test fun `marcador LO e HI fora da escala`() {
        val r = GlucoseFixtures.outOfScale(now)
        assertTrue(layout(spec(), r).texts().none { it.text == "LO" || it.text == "HI" })
        val marks = layout(spec().copy(outOfRangeMarkers = true), r).texts().map { it.text }
        assertTrue("LO" in marks && "HI" in marks)
    }

    @Test fun `alto contraste so no grafico claro`() {
        val r = listOf(GraphReading(now - 60_000L, 120f))
        fun dot(s: GraphSpec) = layout(s, r).ops.filterIsInstance<DrawOp.Dot>().single().color
        assertEquals(GraphPalette.ZONE_IN_RANGE_LIGHT_HC, dot(spec().copy(dark = false, highContrastLight = true)))
        assertEquals(GraphPalette.ZONE_IN_RANGE, dot(spec().copy(dark = true, highContrastLight = true)))
        assertEquals(GraphPalette.ZONE_IN_RANGE, dot(spec().copy(dark = false)))
    }

    @Test fun `visor da Mi Band`() {
        val l = layout(GraphSpec(152, 136, GraphWindow.Trailing(3), nowMs = now, variant = GraphVariant.WATCH))
        assertEquals(DrawOp.Fill(GraphPalette.WATCH_BG), l.ops.first())
        assertEquals(listOf("70", "180"), l.texts().filter { it.bold }.map { it.text })
        val dots = l.ops.filterIsInstance<DrawOp.Dot>()
        assertEquals(3.5f, dots.last().radius)
    }
}
