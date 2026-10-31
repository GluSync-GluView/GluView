package com.gluview.app.data.donation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class DonationAmountTest {

    @Test
    fun `aceita os formatos que uma pessoa digita`() {
        mapOf(
            "10" to 1000, "10,5" to 1050, "10,50" to 1050, "10.50" to 1050, "1.000" to 100_000,
            "1.000,00" to 100_000, "R$ 25" to 2500, " 7 " to 700, "0,99" to 99
        ).forEach { (input, cents) -> assertEquals(input, cents, DonationAmount.parseToCents(input)) }
    }

    @Test
    fun `recusa o que nao e valor`() {
        listOf("", "abc", "10,555", "10,5,0", "-5", "1,000.00", "12.3456", "10.5.0", "99999999999")
            .forEach { assertNull(it, DonationAmount.parseToCents(it)) }
    }

    @Test
    fun `formata sempre em reais`() {
        assertEquals("R$ 12,34", DonationAmount.format(1234, Locale.forLanguageTag("pt-BR")).replace(' ', ' '))
        assertTrue(DonationAmount.format(1234, Locale.US).contains("12.34"))
    }
}
