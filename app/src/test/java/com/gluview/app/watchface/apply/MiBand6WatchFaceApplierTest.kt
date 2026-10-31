package com.gluview.app.watchface.apply

import com.gluview.app.watchface.WearableCatalog
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regressão do achado P1 (plano 06_watchfaces_v2_arquitetura.md): supports() aceitava qualquer
 * id começando com "mi_band_" (Mi Band 1/4/5/7/8/9...), mas o gerador por trás só sabe desenhar
 * pro canvas 152x240 da Mi Band 6. matchesWearable() é a função pura extraída de supports() —
 * testável sem instanciar a classe (que exige Context/Hilt).
 */
class MiBand6WatchFaceApplierTest {

    @Test
    fun `aceita apenas mi_band_6`() {
        assertTrue(MiBand6WatchFaceApplier.matchesWearable("mi_band_6"))
    }

    @Test
    fun `rejeita as outras Mi Band do catalogo`() {
        val outrasMiBand = WearableCatalog.items
            .map { it.id }
            .filter { it.startsWith("mi_band") && it != "mi_band_6" }

        // Confere que o catálogo realmente tem outras Mi Band (senão o teste não prova nada).
        assertTrue("catálogo deveria ter outras Mi Band além da 6", outrasMiBand.isNotEmpty())

        outrasMiBand.forEach { id ->
            assertFalse("id=$id não deveria ser aceito", MiBand6WatchFaceApplier.matchesWearable(id))
        }
    }

    @Test
    fun `rejeita wearables de outros fabricantes`() {
        WearableCatalog.items
            .filter { it.brand != "Xiaomi" }
            .forEach { assertFalse(it.id, MiBand6WatchFaceApplier.matchesWearable(it.id)) }
    }

    @Test
    fun `rejeita wearable desconhecido`() {
        assertFalse(MiBand6WatchFaceApplier.matchesWearable("custom_999_999"))
    }
}
