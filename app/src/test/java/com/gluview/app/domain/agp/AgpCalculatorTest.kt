package com.gluview.app.domain.agp

import com.gluview.app.domain.agp.AgpCalculator.Sample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/** PT-4 — cálculos do AGP com séries sintéticas de resultado conhecido. */
class AgpCalculatorTest {

    private val utc: ZoneId = ZoneOffset.UTC
    private val day = 86_400_000L
    private val start = LocalDateTime.of(2026, 11, 1, 0, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    /** Uma leitura a cada [stepMin] minutos por [days] dias, valor dado pela hora do dia. */
    private fun series(days: Int, stepMin: Int = 5, value: (minuteOfDay: Int, dayIndex: Int) -> Double): List<Sample> =
        (0 until days * 1440 step stepMin).map { m ->
            Sample(start + m * 60_000L, value(m % 1440, m / 1440))
        }

    @Test fun `percentil tipo 7 com interpolacao linear`() {
        val s = (1..11).map { it.toDouble() } // 1..11
        assertEquals(1.0, AgpCalculator.percentile(s, 0.0), 1e-9)
        assertEquals(6.0, AgpCalculator.percentile(s, 50.0), 1e-9)
        assertEquals(11.0, AgpCalculator.percentile(s, 100.0), 1e-9)
        assertEquals(1.5, AgpCalculator.percentile(s, 5.0), 1e-9)   // rank 0,5
        assertEquals(3.5, AgpCalculator.percentile(s, 25.0), 1e-9)  // rank 2,5
        assertEquals(10.5, AgpCalculator.percentile(s, 95.0), 1e-9) // rank 9,5
        assertEquals(42.0, AgpCalculator.percentile(listOf(42.0), 75.0), 1e-9)
    }

    @Test fun `GMI pela formula do consenso`() {
        assertEquals(3.31 + 0.02392 * 154, AgpCalculator.gmiPercent(154.0), 1e-9)
        assertEquals(7.0, AgpCalculator.gmiPercent(154.26), 0.001)
    }

    @Test fun `media, desvio e CV conhecidos`() {
        // Alterna 100 e 200: média 150, DP amostral ~50 (n grande), CV ~33,3%.
        val samples = series(14) { m, _ -> if ((m / 5) % 2 == 0) 100.0 else 200.0 }
        val r = AgpCalculator.compute(samples, start, start + 14 * day, utc)
        assertEquals(150.0, r.meanMgDl!!, 1e-9)
        assertEquals(33.33, r.cvPercent!!, 0.01)
        assertTrue(r.stable!!)
        assertEquals(AgpCalculator.gmiPercent(150.0), r.gmiPercent!!, 1e-9)
    }

    @Test fun `tempo nas faixas com limites do consenso`() {
        val v = listOf(40.0, 53.9, 54.0, 69.0, 69.9, 70.0, 180.0, 180.5, 250.0, 250.1, 300.0)
        val t = AgpCalculator.timeInRanges(v)
        val n = v.size.toDouble()
        assertEquals(2 * 100 / n, t.veryLowPercent, 1e-9)  // 40, 53.9
        assertEquals(3 * 100 / n, t.lowPercent, 1e-9)      // 54, 69, 69.9
        assertEquals(2 * 100 / n, t.targetPercent, 1e-9)   // 70, 180
        assertEquals(2 * 100 / n, t.highPercent, 1e-9)     // 180.5, 250
        assertEquals(2 * 100 / n, t.veryHighPercent, 1e-9) // 250.1, 300
        assertEquals(100.0, t.veryLowPercent + t.lowPercent + t.targetPercent + t.highPercent + t.veryHighPercent, 1e-9)
    }

    @Test fun `faixa alvo personalizada muda so o alvo`() {
        val r = AgpRanges.withTarget(80.0, 160.0)
        val t = AgpCalculator.timeInRanges(listOf(75.0, 100.0, 170.0), r)
        assertEquals(100.0 / 3, t.lowPercent, 1e-9)
        assertEquals(100.0 / 3, t.targetPercent, 1e-9)
        assertEquals(100.0 / 3, t.highPercent, 1e-9)
        assertEquals(54.0, r.veryLowBelow, 0.0)
        assertFalse(r.isConsensus)
    }

    @Test fun `percentis por hora do dia sobre todos os dias`() {
        // Hora h vale 100 + h no dia par e 100 + h + 20 no dia ímpar → mediana = 110 + h.
        val samples = series(14, stepMin = 15) { m, d -> 100.0 + m / 60 + if (d % 2 == 1) 20.0 else 0.0 }
        val r = AgpCalculator.compute(samples, start, start + 14 * day, utc)
        assertEquals(24, r.hourly.size)
        r.hourly.forEach { h ->
            assertEquals(110.0 + h.hour, h.p50!!, 1e-9)
            assertEquals(100.0 + h.hour, h.p5!!, 1e-9)
            assertEquals(120.0 + h.hour, h.p95!!, 1e-9)
            assertTrue(h.p25!! <= h.p50!! && h.p50!! <= h.p75!!)
            assertEquals(14 * 4, h.count)
        }
    }

    @Test fun `hora do dia segue o fuso`() {
        // Uma leitura às 03:00 UTC = 00:00 em São Paulo (UTC-3, sem horário de verão em 2026).
        val sp = ZoneId.of("America/Sao_Paulo")
        val r = AgpCalculator.compute(listOf(Sample(start + 3 * 3_600_000L, 123.0)), start, start + day, sp)
        assertEquals(123.0, r.hourly[0].p50!!, 1e-9)
        assertNull(r.hourly[3].p50)
    }

    @Test fun `sensor ativo e suficiencia de dados`() {
        // 14 dias completos a cada 5 min: 100% ativo, suficiente.
        val full = AgpCalculator.compute(series(14) { _, _ -> 120.0 }, start, start + 14 * day, utc)
        assertEquals(100.0, full.activePercent, 1e-9)
        assertTrue(full.sufficient)
        assertEquals(14, full.dataDays)

        // Leitura a cada 1 min não passa de 100%.
        val dense = AgpCalculator.compute(series(14, stepMin = 1) { _, _ -> 120.0 }, start, start + 14 * day, utc)
        assertEquals(100.0, dense.activePercent, 1e-9)

        // Só 9 dos 14 dias com sensor: 64% < 70% → aviso.
        val partial = AgpCalculator.compute(series(9) { _, _ -> 120.0 }, start, start + 14 * day, utc)
        assertEquals(9 * 100.0 / 14, partial.activePercent, 1e-6)
        assertTrue(partial.lowSensorTime)
        assertFalse(partial.sufficient)

        // Período de 7 dias: o AGP sai, mas com aviso de período curto.
        val week = AgpCalculator.compute(series(7) { _, _ -> 120.0 }, start, start + 7 * day, utc)
        assertTrue(week.periodTooShort)
        assertFalse(week.sufficient)
        assertTrue(week.hasData)
    }

    @Test fun `sem leituras nao quebra`() {
        val r = AgpCalculator.compute(emptyList(), start, start + 14 * day, utc)
        assertFalse(r.hasData)
        assertNull(r.meanMgDl); assertNull(r.gmiPercent); assertNull(r.cvPercent)
        assertEquals(0.0, r.activePercent, 0.0)
        assertTrue(r.hourly.all { it.p50 == null })
    }

    @Test fun `leituras fora do periodo ou invalidas sao ignoradas`() {
        val samples = listOf(
            Sample(start - 1, 300.0), Sample(start + 14 * day, 300.0),
            Sample(start + 1000, 0.0), Sample(start + 2000, Double.NaN), Sample(start + 3000, 100.0)
        )
        val r = AgpCalculator.compute(samples, start, start + 14 * day, utc)
        assertEquals(1, r.readings)
        assertEquals(100.0, r.meanMgDl!!, 0.0)
        assertNull(r.cvPercent)
    }
}
