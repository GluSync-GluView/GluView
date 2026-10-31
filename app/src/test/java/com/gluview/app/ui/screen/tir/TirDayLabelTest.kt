package com.gluview.app.ui.screen.tir

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.util.Locale

/** Datas das barras do TIR: o GluSync manda "dd/MM/yyyy"; o eixo mostra dia e mês no idioma. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TirDayLabelTest {

    @Test fun `le o formato do servidor e o ISO`() {
        assertEquals(LocalDate.of(2026, 10, 3), parseTirDay("03/10/2026"))
        assertEquals(LocalDate.of(2026, 10, 3), parseTirDay("2026-10-03"))
        assertNull(parseTirDay("ontem"))
    }

    @Test fun `dia e mes no formato do idioma`() {
        assertEquals("03/10", tirDayLabel("03/10/2026", Locale.forLanguageTag("pt-BR")))
        assertEquals("10/3", tirDayLabel("03/10/2026", Locale.US))
    }

    @Test fun `texto que nao e data passa como veio`() {
        assertEquals("ontem", tirDayLabel("ontem", Locale.US))
    }
}
