package com.gluview.wear.contract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plano 17, Fase 4 — contrato do diário entre relógio e celular. */
class WearDiaryTest {
    @Test fun `registro do relogio vai e volta`() {
        val e = WearDiaryEntry("u1", "insulin", 4.5, 1000L, "rapid", "Humalog")
        assertEquals(e, WearDiaryEntry.fromMap(e.toMap()))
    }

    @Test fun `recusa tipo desconhecido, valor fora da faixa e versao futura`() {
        assertNull(WearDiaryEntry.fromMap(WearDiaryEntry("u", "note", 1.0, 1L).toMap()))
        assertNull(WearDiaryEntry.fromMap(WearDiaryEntry("u", "meal", 900.0, 1L).toMap()))
        assertNull(WearDiaryEntry.fromMap(WearDiaryEntry("u", "meal", 30.0, 1L).toMap() + (WearDiaryEntry.K_VERSION to 99)))
    }

    @Test fun `coroa anda no passo e para nos limites`() {
        assertEquals(35.0, WearDiaryLimits.turn("meal", 30.0, 5), 0.0)
        assertEquals(4.0, WearDiaryLimits.turn("insulin", 4.5, -1), 0.0)
        assertEquals(0.5, WearDiaryLimits.turn("insulin", 1.0, -10), 0.0)
        assertEquals(0.6, WearDiaryLimits.turn("ketone", 0.5, 1), 1e-9)
        assertEquals(600.0, WearDiaryLimits.turn("fingerstick", 598.0, 10), 0.0)
        assertTrue(WearDiaryLimits.valid("exercise", 30.0))
        assertFalse(WearDiaryLimits.valid("exercise", 0.0))
    }

    @Test fun `resumo do dia vai e volta`() {
        val d = WearDayInfo(45.0, 6.5, 18.0, "Basal", 5000L, listOf("Alerta: baixa 62", "Lembrete adiado"),
            listOf(WearDayInfo.WearItem("i1", "Tresiba", "basal")), listOf(WearDayInfo.WearItem("f1", "Café", "40")), 99L)
        assertEquals(d, WearDayInfo.fromMap(d.toMap()))
    }
}
