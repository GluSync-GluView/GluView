package com.gluview.app.watchface

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Migração v1->v2 (coordenadas fracionárias) e as funções puras de conversão fração<->px, usando
 * os presets reais do editor (WatchFacePresets) como fixtures. Sem Android, sem org.json — mesmo
 * princípio já usado em Libre2CryptoTest: só as partes determinísticas/puras rodam em JVM.
 */
class WatchFaceMigrationsTest {

    private val tolerancePx = 1.0f

    @Test
    fun `migrate preenche fracao para todos os componentes dos presets`() {
        WatchFacePresets.all.forEach { preset ->
            val design = WatchFaceDesign(
                wearable = WearableCatalog.starter,
                components = preset.build(WearableCatalog.starter),
                schemaVersion = 1
            )
            val migrated = WatchFaceMigrations.migrate(design)

            assertEquals(WatchFaceMigrations.CURRENT_SCHEMA_VERSION, migrated.schemaVersion)
            migrated.components.forEach { c ->
                assertTrue("${preset.id}/${c.label}: xFrac ausente", c.xFrac != null)
                assertTrue("${preset.id}/${c.label}: yFrac ausente", c.yFrac != null)
                assertTrue("${preset.id}/${c.label}: widthFrac ausente", c.widthFrac != null)
                assertTrue("${preset.id}/${c.label}: heightFrac ausente", c.heightFrac != null)
            }
        }
    }

    @Test
    fun `migrate nao altera posicao px absoluta`() {
        WatchFacePresets.all.forEach { preset ->
            val original = preset.build(WearableCatalog.starter)
            val design = WatchFaceDesign(wearable = WearableCatalog.starter, components = original, schemaVersion = 1)
            val migrated = WatchFaceMigrations.migrate(design)

            original.zip(migrated.components).forEach { (before, after) ->
                assertEquals(before.x, after.x, 0.0001f)
                assertEquals(before.y, after.y, 0.0001f)
                assertEquals(before.width, after.width, 0.0001f)
                assertEquals(before.height, after.height, 0.0001f)
            }
        }
    }

    @Test
    fun `migrate e idempotente`() {
        val design = WatchFaceDesign(
            wearable = WearableCatalog.starter,
            components = WatchFacePresets.all.first().build(WearableCatalog.starter),
            schemaVersion = 1
        )
        val once = WatchFaceMigrations.migrate(design)
        val twice = WatchFaceMigrations.migrate(once)
        assertEquals(once, twice)
    }

    @Test
    fun `round-trip px menos fracao menos px preserva posicao com tolerancia de 1px`() {
        val wearable = WearableCatalog.starter
        WatchFacePresets.all.forEach { preset ->
            preset.build(wearable).forEach { original ->
                val roundTripped = original.withFractional(wearable).resolvedFor(wearable)
                assertTrue(
                    "${preset.id}/${original.label}: x divergiu ${original.x} vs ${roundTripped.x}",
                    abs(original.x - roundTripped.x) <= tolerancePx
                )
                assertTrue(
                    "${preset.id}/${original.label}: y divergiu ${original.y} vs ${roundTripped.y}",
                    abs(original.y - roundTripped.y) <= tolerancePx
                )
                assertTrue(
                    "${preset.id}/${original.label}: width divergiu",
                    abs(original.width - roundTripped.width) <= tolerancePx
                )
                assertTrue(
                    "${preset.id}/${original.label}: height divergiu",
                    abs(original.height - roundTripped.height) <= tolerancePx
                )
            }
        }
    }

    @Test
    fun `resolvedFor recalcula proporcionalmente para wearable de resolucao diferente`() {
        val small = WearableCatalog.starter // mi_band_6, 152x240
        val big = WearableCatalog.items.first { it.id == "galaxy_watch_7_44" } // 480x480

        val component = WatchFaceComponentLibrary.definitionsFor(GlucoseCurrentKind)
            .first()
            .createComponent(small, zIndex = 0)
            .withFractional(small)

        val resized = component.resolvedFor(big)

        assertEquals(component.xFrac!! * big.widthPx, resized.x, 0.01f)
        assertEquals(component.yFrac!! * big.heightPx, resized.y, 0.01f)
        assertEquals(component.widthFrac!! * big.widthPx, resized.width, 0.01f)
        assertEquals(component.heightFrac!! * big.heightPx, resized.height, 0.01f)
    }

    @Test
    fun `resolvedFor sem dado fracionario devolve componente inalterado`() {
        val component = WatchFaceComponentLibrary.definitionsFor(TimeHmKind)
            .first()
            .createComponent(WearableCatalog.starter, zIndex = 0)
        // Nunca chamou withFractional() — xFrac etc. continuam null.
        val resolved = component.resolvedFor(WearableCatalog.items.first { it.id == "pixel_watch_1" })
        assertEquals(component, resolved)
    }
}
