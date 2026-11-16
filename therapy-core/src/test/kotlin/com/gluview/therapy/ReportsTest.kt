package com.gluview.therapy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class ReportsTest {
    private val z = ZoneOffset.UTC
    private val d0 = LocalDate.of(2026, 11, 16).atStartOfDay(z).toInstant().toEpochMilli() // segunda-feira
    private fun p(h: Int, v: Double, day: Int = 0) = RPoint(d0 + day * 86_400_000L + h * 3_600_000L, v)

    @Test fun estatisticasBatemComCalculoAMao() {
        // Mesmo conjunto usado na auditoria do GluSync: 5×100 + 5×200 + 70/180/69/181
        val pts = List(5) { p(it, 100.0) } + List(5) { p(it + 5, 200.0) } + listOf(p(10, 70.0), p(11, 180.0), p(12, 69.0), p(13, 181.0))
        val s = Reports.stats(pts, RLimits())!!
        assertEquals(14, s.n)
        assertEquals(2000.0 / 14, s.mean, 1e-9)
        assertEquals(100.0 * 5 / 14, s.pInRange, 0.01)        // só os 100 (70 conta como baixa, 180 como alta)
        assertEquals(100.0 * 2 / 14, s.pLow, 0.01)             // 70 e 69
        assertEquals(100.0 * 7 / 14, s.pHigh, 0.01)            // 5×200 + 180 + 181
        assertEquals(3.31 + 0.02392 * s.mean, s.gmi, 1e-9)
        assertNull(Reports.stats(emptyList(), RLimits()))
    }

    @Test fun diarioPorHoraSemanalEMetas() {
        val pts = listOf(p(1, 100.0), p(1, 120.0), p(23, 300.0), p(2, 60.0, day = 1), p(3, 110.0, day = 7))
        val daily = Reports.daily(pts, RLimits(), z)
        assertEquals(3, daily.size)
        val hourly = Reports.hourly(pts, z)
        assertEquals(24, hourly.size)
        assertEquals(110.0, hourly[1].mean, 1e-9)
        assertEquals(0, hourly[5].n)
        val weeks = Reports.weekly(pts, RLimits(), z)
        assertEquals(2, weeks.size)
        assertEquals(LocalDate.of(2026, 11, 16), weeks[0].weekStart)
        val success = Reports.success(daily)
        assertFalse(success[1].lowOk) // dia com 60 mg/dL: 100% abaixo
        assertTrue(Reports.success(Reports.daily(listOf(p(1, 120.0)), RLimits(), z))[0].inRangeOk)
    }

    @Test fun distribuicaoSomaCem() {
        val pts = listOf(p(0, 39.0), p(0, 45.0), p(0, 120.0), p(0, 450.0))
        val b = Reports.distribution(pts)
        assertEquals(4, b.sumOf { it.count })
        assertEquals(100.0, b.sumOf { it.percent }, 1e-9)
        assertEquals(2, b.first().count) // 39 entra no primeiro balde
        assertEquals(1, b.last().count)  // 450 entra no último
    }

    @Test fun quantis() {
        assertEquals(2.5, Reports.quantile(listOf(1.0, 2.0, 3.0, 4.0), 0.5), 1e-9)
        assertEquals(1.3, Reports.quantile(listOf(1.0, 2.0, 3.0, 4.0), 0.1), 1e-9)
    }

    @Test fun idades() {
        val now = d0 + 100 * 3_600_000L
        assertEquals(AgeLevel.UNKNOWN, Ages.level(null, now, 48, 72))
        assertEquals(AgeLevel.OK, Ages.level(now - 10 * 3_600_000L, now, 48, 72))
        assertEquals(AgeLevel.WARN, Ages.level(now - 50 * 3_600_000L, now, 48, 72))
        assertEquals(AgeLevel.URGENT, Ages.level(now - 72 * 3_600_000L, now, 48, 72))
    }

    @Test fun csvSimples() = assertEquals("a;b\n1;2.5", Reports.csv(listOf("a", "b"), listOf(listOf(1, 2.5))))
}
