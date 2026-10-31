package com.gluview.app.ui.screen.report

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.gluview.app.util.monthLabel
import java.time.YearMonth
import java.util.Locale

/** Seletor de mês do Relatório: nome no idioma e nenhum mês futuro. */
class ReportMonthsTest {

    @Test fun `nome do mes no idioma`() {
        assertEquals("Outubro 2026", monthLabel(YearMonth.of(2026, 10), Locale.forLanguageTag("pt-BR")))
        assertEquals("October 2026", monthLabel(YearMonth.of(2026, 10), Locale.US))
    }

    @Test fun `do mes atual ate janeiro de dois anos atras, sem futuro`() {
        val now = YearMonth.of(2026, 10)
        val months = selectableMonths(now)
        assertEquals(now, months.first())
        assertEquals(YearMonth.of(2024, 1), months.last())
        assertTrue(months.none { it.isAfter(now) })
        assertEquals(10 + 12 + 12, months.size)
    }
}
