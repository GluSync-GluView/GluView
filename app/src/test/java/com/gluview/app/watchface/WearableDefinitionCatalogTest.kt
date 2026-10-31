package com.gluview.app.watchface

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Documenta/protege a realidade atual (achado P7 do plano 06_watchfaces_v2_arquitetura.md):
 * catálogo de resolução (38 wearables) != destinos com compilador real. Fase 5 do plano: Mi Band
 * 5 e 6 têm hoje (mesmo transporte Huami, ver MiBand5WatchFaceApplier/MiBand6WatchFaceApplier).
 * Se este teste começar a falhar por "mais wearables com compilerAvailable", é sinal de que um
 * WearableAdapter novo foi adicionado — hora de atualizar este catálogo junto (não silenciosamente).
 */
class WearableDefinitionCatalogTest {

    @Test
    fun `todos os itens do WearableCatalog tem uma WearableDefinition correspondente`() {
        assertEquals(WearableCatalog.items.size, WearableDefinitionCatalog.all.size)
        WearableCatalog.items.forEach { spec ->
            assertTrue(spec.id, WearableDefinitionCatalog.definitionFor(spec.id) != null)
        }
    }

    @Test
    fun `so a Mi Band 5 e a 6 tem compilador real hoje`() {
        val comCompilador = WearableDefinitionCatalog.all.filter { it.capabilities.compilerAvailable }
        assertEquals(setOf("mi_band_5", "mi_band_6"), comCompilador.map { it.spec.id }.toSet())
    }

    @Test
    fun `hasRealCompiler bate com os appliers registrados`() {
        assertTrue(WearableDefinitionCatalog.hasRealCompiler("mi_band_6"))
        assertTrue(WearableDefinitionCatalog.hasRealCompiler("mi_band_5"))
        assertFalse(WearableDefinitionCatalog.hasRealCompiler("mi_band_7"))
        assertFalse(WearableDefinitionCatalog.hasRealCompiler("id_inexistente"))
    }

    @Test
    fun `resolucao da Mi Band 5 bate com o canvas real do template (nao com a ficha tecnica)`() {
        val spec = WearableCatalog.items.first { it.id == "mi_band_5" }
        assertEquals(126, spec.widthPx)
        assertEquals(170, spec.heightPx)
    }
}
