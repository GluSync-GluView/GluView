package com.gluview.app.ui.screen.terms

import org.junit.Assert.assertEquals
import org.junit.Test

/** A divisão em seções não pode mudar uma vírgula do texto aceito (senão exigiria novo aceite). */
class TermsSectionsTest {

    private fun check(text: String) {
        val doc = parseTerms(text)
        assertEquals(text.lines().filter { it.isNotBlank() }, doc.allLines())
        assertEquals(7, doc.sections.size)
        assertEquals(1, doc.intro.size)
    }

    @Test fun `pt sem perder linha`() = check(TERMS_TEXT)

    @Test fun `en sem perder linha`() = check(TERMS_TEXT_EN)

    @Test fun `titulo e versao saem das duas primeiras linhas`() {
        val doc = parseTerms(TERMS_TEXT)
        assertEquals("Versão $TERMS_VERSION", doc.version)
        assertEquals("1. NATUREZA DO APLICATIVO", doc.sections.first().title)
    }
}
