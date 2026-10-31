package com.gluview.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class AuthEmailNormalizerTest {

    @Test
    fun `normaliza maiusculas e espacos antes da autenticacao`() {
        assertEquals(
            "usuario.teste@example.com",
            normalizeAuthEmail("  Usuario.Teste@Example.COM  ")
        )
    }

    @Test
    fun `normalizacao nao depende do locale do aparelho`() {
        assertEquals("info@example.com", normalizeAuthEmail("INFO@EXAMPLE.COM"))
    }
}
