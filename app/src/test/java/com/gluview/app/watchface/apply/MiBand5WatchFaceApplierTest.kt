package com.gluview.app.watchface.apply

import com.gluview.app.watchface.WearableCatalog
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fase 5 do plano 06_watchfaces_v2_arquitetura.md — segundo applier real (Mi Band 5). */
class MiBand5WatchFaceApplierTest {

    @Test
    fun `aceita apenas mi_band_5`() {
        assertTrue(MiBand5WatchFaceApplier.matchesWearable("mi_band_5"))
    }

    @Test
    fun `rejeita as outras Mi Band do catalogo, incluindo a 6`() {
        val outras = WearableCatalog.items.map { it.id }.filter { it.startsWith("mi_band") && it != "mi_band_5" }
        assertTrue(outras.isNotEmpty())
        outras.forEach { id -> assertFalse(id, MiBand5WatchFaceApplier.matchesWearable(id)) }
    }

    @Test
    fun `mi_band_5 e mi_band_6 nunca sao aceitos pelo applier um do outro`() {
        assertFalse(MiBand5WatchFaceApplier.matchesWearable("mi_band_6"))
        assertFalse(MiBand6WatchFaceApplier.matchesWearable("mi_band_5"))
    }
}
