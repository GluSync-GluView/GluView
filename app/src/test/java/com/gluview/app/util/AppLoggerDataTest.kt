package com.gluview.app.util

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** Registro com NaN/infinito não pode lançar exceção (travava o app na leitura com erro do sensor). */
class AppLoggerDataTest {

    @Test
    fun `NaN e infinito viram texto em vez de lancar excecao`() {
        val json = JSONObject(logDataToJson(mapOf(
            "mgdl" to Float.NaN, "lo" to Float.NEGATIVE_INFINITY, "hi" to Double.POSITIVE_INFINITY,
            "ok" to 120.5f, "n" to 3, "s" to "texto"
        )))
        assertEquals("NaN", json.getString("mgdl"))
        assertEquals("-Infinity", json.getString("lo"))
        assertEquals("Infinity", json.getString("hi"))
        assertEquals(120.5, json.getDouble("ok"), 0.001)
        assertEquals(3, json.getInt("n"))
        assertEquals("texto", json.getString("s"))
    }

    @Test
    fun `sem dados devolve vazio`() {
        assertEquals("", logDataToJson(emptyMap()))
    }
}
