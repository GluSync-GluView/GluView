package com.gluview.app.watchface

import com.gluview.app.data.local.entity.WatchFaceEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fase 5 do plano 06_watchfaces_v2_arquitetura.md — WatchfaceCompatibility: um design pode
 * declarar wearables compatíveis além do principal. Cobre a função pura [WatchFaceDesign.resolvedFor]
 * (recalcula TODO o design pro novo wearable — o gap que existia desde a Fase 1: trocar `wearable`
 * sem recalcular os componentes) e a persistência de [WatchFaceDesign.compatibleWearableIds].
 */
class WatchFaceCompatibilityTest {

    private val miBand6 = WearableCatalog.items.first { it.id == "mi_band_6" } // 152x240
    private val miBand5 = WearableCatalog.items.first { it.id == "mi_band_5" } // 126x170

    @Test
    fun `resolvedFor no design recalcula todos os componentes para o novo wearable`() {
        val components = WatchFacePresets.all.first().build(miBand6)
        val design = WatchFaceDesign(wearable = miBand6, components = components)

        val resolved = design.resolvedFor(miBand5)

        assertEquals(miBand5, resolved.wearable)
        assertEquals(components.size, resolved.components.size)
        components.zip(resolved.components).forEach { (before, after) ->
            val expectedX = (before.x / miBand6.widthPx) * miBand5.widthPx
            val expectedY = (before.y / miBand6.heightPx) * miBand5.heightPx
            assertEquals(expectedX, after.x, 0.5f)
            assertEquals(expectedY, after.y, 0.5f)
        }
    }

    @Test
    fun `resolvedFor funciona mesmo em componente recem-criado sem fracao preenchida`() {
        val component = WatchFaceComponentLibrary.definitionsFor(TimeHmKind).first().createComponent(miBand6, zIndex = 0)
        assertEquals(null, component.xFrac) // nunca passou por withFractional/migrate

        val design = WatchFaceDesign(wearable = miBand6, components = listOf(component))
        val resolved = design.resolvedFor(miBand5)

        val expectedX = (component.x / miBand6.widthPx) * miBand5.widthPx
        assertEquals(expectedX, resolved.components.single().x, 0.5f)
    }

    @Test
    fun `applicableWearableIds combina o principal com os compativeis, sem duplicar`() {
        val design = WatchFaceDesign(wearable = miBand6, compatibleWearableIds = listOf("mi_band_5", "mi_band_6"))
        assertEquals(listOf("mi_band_6", "mi_band_5"), design.applicableWearableIds())
    }

    @Test
    fun `applicableWearableIds sem compativeis declarados devolve so o principal`() {
        val design = WatchFaceDesign(wearable = miBand6)
        assertEquals(listOf("mi_band_6"), design.applicableWearableIds())
    }

    @Test
    fun `compatibleWearableIds sobrevive ao round-trip Entity menos Design`() {
        val design = WatchFaceDesign(
            wearable = miBand6,
            components = WatchFacePresets.all.first().build(miBand6),
            compatibleWearableIds = listOf("mi_band_5")
        )

        val entity = design.toEntity()
        assertTrue(entity.compatibleWearableIdsJson.contains("mi_band_5"))

        val roundTripped = entity.toDesign()
        assertEquals(listOf("mi_band_5"), roundTripped.compatibleWearableIds)
    }

    @Test
    fun `applicableWearableTargets so lista quem tem compilador real hoje`() {
        // mi_band_7 é catalogado (resolução conhecida) mas não tem WatchFaceApplier — achado P7.
        val design = WatchFaceDesign(wearable = miBand6, compatibleWearableIds = listOf("mi_band_5", "mi_band_7"))
        val targets = design.applicableWearableTargets()
        assertEquals(setOf("mi_band_6", "mi_band_5"), targets.map { it.id }.toSet())
    }

    @Test
    fun `applicableWearableTargets sem compativel nenhum devolve so o principal`() {
        val design = WatchFaceDesign(wearable = miBand6)
        assertEquals(listOf("mi_band_6"), design.applicableWearableTargets().map { it.id })
    }

    @Test
    fun `entidade antiga sem a coluna nova (default) decodifica lista vazia`() {
        val legacyJsonDefault = WatchFaceEntity(
            name = "antigo",
            wearableId = miBand6.id,
            wearableName = miBand6.name,
            wearableWidthPx = miBand6.widthPx,
            wearableHeightPx = miBand6.heightPx,
            componentsJson = WatchFaceJson.encodeComponents(emptyList())
        )
        assertFalse(legacyJsonDefault.toDesign().compatibleWearableIds.contains("qualquer_coisa"))
        assertEquals(emptyList<String>(), legacyJsonDefault.toDesign().compatibleWearableIds)
    }
}
