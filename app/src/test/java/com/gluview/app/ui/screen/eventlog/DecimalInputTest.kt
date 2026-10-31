package com.gluview.app.ui.screen.eventlog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** L23: valor de evento com vírgula decimal. */
class DecimalInputTest {
    @Test fun `virgula e ponto valem`() {
        assertEquals(4.5, parseDecimalInput("4,5")!!, 0.0)
        assertEquals(4.5, parseDecimalInput(" 4.5 ")!!, 0.0)
        assertEquals(60.0, parseDecimalInput("60")!!, 0.0)
    }

    @Test fun `texto que nao e numero`() {
        assertNull(parseDecimalInput("abc"))
        assertNull(parseDecimalInput(""))
    }
}
