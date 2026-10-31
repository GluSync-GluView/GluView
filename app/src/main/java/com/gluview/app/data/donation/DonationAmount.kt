package com.gluview.app.data.donation

import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

/** Valores da doação — mesmas regras do portal (apps/web/src/donation/donationAmount.ts). */
object DonationAmount {

    /** Centavos → "R$ 12,34". Sempre em reais (o Pix é em reais em qualquer idioma). */
    fun format(cents: Int, locale: Locale = Locale.getDefault()): String =
        NumberFormat.getCurrencyInstance(locale).apply {
            currency = Currency.getInstance("BRL")
        }.format(cents / 100.0)

    private val ONLY_DIGITS_AND_SEPARATORS = Regex("^\\d[\\d.,]*$")
    private val THOUSANDS_ONLY = Regex("^\\d{1,3}(\\.\\d{3})+$")
    private val INT_WITH_THOUSANDS = Regex("^\\d{1,3}(\\.\\d{3})*$|^\\d+$")
    private val DECIMAL = Regex("^\\d+(\\.\\d{1,2})?$")

    /** O que foi digitado em "Outro valor" → centavos, ou null se não for um valor. Aceita "10",
     *  "10,5", "10,50", "1.000,00", "R$ 25" e ponto decimal ("10.50", teclado em inglês). Ponto
     *  seguido de exatamente 3 dígitos é milhar ("1.000"). */
    fun parseToCents(input: String): Int? {
        val raw = input.replace(Regex("R\\$", RegexOption.IGNORE_CASE), "").filterNot { it.isWhitespace() }
        if (!ONLY_DIGITS_AND_SEPARATORS.matches(raw)) return null

        val normalized = when {
            raw.contains(',') -> {
                if (raw.indexOf(',') != raw.lastIndexOf(',')) return null
                val (intPart, decPart) = raw.split(',')
                if (!INT_WITH_THOUSANDS.matches(intPart)) return null
                "${intPart.replace(".", "")}.$decPart"
            }
            THOUSANDS_ONLY.matches(raw) -> raw.replace(".", "")
            else -> raw
        }

        if (!DECIMAL.matches(normalized)) return null
        val parts = normalized.split('.')
        val reais = parts[0].toLongOrNull() ?: return null
        val centavos = parts.getOrNull(1)?.padEnd(2, '0')?.toLong() ?: 0L
        val total = reais * 100 + centavos
        return if (total > Int.MAX_VALUE) null else total.toInt()
    }
}
