package com.gluview.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** "gerado" do relatório: ISO-8601 UTC do GluSync atual e o "dd/MM/yyyy HH:mm" (UTC) do antigo. */
class ServerDatesTest {
    private val ms = 1_791_072_000_000L // 2026-10-04T00:00:00Z

    @Test fun `iso e formato antigo viram o mesmo instante`() {
        assertEquals(ms, ServerDates.toEpochMs("2026-10-04T00:00:00Z"))
        assertEquals(ms, ServerDates.toEpochMs(" 04/10/2026 00:00 "))
    }

    @Test fun `texto que nao e data devolve null`() {
        assertNull(ServerDates.toEpochMs("ontem"))
        assertEquals("ontem", ServerDates.utcToLocal("ontem"))
    }
}
