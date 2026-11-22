package com.gluview.app.ui.screen.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSearchIndexTest {

    @Test fun `nenhum titulo se repete na mesma tela`() {
        val dup = SettingsSearchIndex.items.groupBy { it.screen to it.titleRes }.filterValues { it.size > 1 }
        assertTrue("repetidos: ${dup.keys}", dup.isEmpty())
    }

    @Test fun `toda tela tem rota e caminho que termina nela`() {
        SearchScreen.entries.forEach { s ->
            assertTrue("${s.name} sem rota", s.route.isNotBlank())
            assertEquals(s, s.path.last())
        }
        assertEquals(listOf(SearchScreen.ALERTS, SearchScreen.ALERT_IFTTT), SearchScreen.ALERT_IFTTT.path)
    }

    @Test fun `busca ignora acento e maiusculas e exige todas as palavras`() {
        assertTrue(SettingsSearchIndex.matches("calibracao", "Calibração e suavização"))
        assertTrue(SettingsSearchIndex.matches("ALERTAS ifttt", "IFTTT Webhooks Ajustes › Configurar alertas"))
        assertFalse(SettingsSearchIndex.matches("alertas pushover", "IFTTT Webhooks Ajustes › Configurar alertas"))
        assertFalse(SettingsSearchIndex.matches("   ", "qualquer"))
    }

    @Test fun `gates escondem o que a tela esconde`() {
        val follower = SearchContext(isFollowerOnly = true, isLocalOnly = false, isAdmin = false, showDonate = false, dataSource = "SIBIONICS_CHINES")
        assertFalse(follower.allows(SearchGate.PATIENT))
        assertFalse(follower.allows(SearchGate.SIBIONICS_CN))
        val patient = follower.copy(isFollowerOnly = false)
        assertTrue(patient.allows(SearchGate.SIBIONICS_CN))
        assertFalse(patient.allows(SearchGate.SIBIONICS_GS3))
        assertFalse(patient.copy(isLocalOnly = true).allows(SearchGate.CLOUD))
    }
}
