package com.gluview.app.watchface

import org.junit.Assert.assertEquals
import org.junit.Test

/** L27: o wearable personalizado volta com o tipo e a forma escolhidos ao reabrir o visor. */
class WearableCustomIdTest {

    @Test fun `padrao mantem o id antigo`() {
        assertEquals("custom_454_454", WearableCatalog.customId(454, 454, WearableKind.SMARTWATCH, WearableShape.ROUND))
    }

    @Test fun `tipo e forma voltam pelo id`() {
        val id = WearableCatalog.customId(200, 400, WearableKind.SMARTBAND, WearableShape.RECTANGLE)
        val spec = WearableCatalog.find(id, 200, 400, "200 x 400")
        assertEquals(WearableKind.SMARTBAND, spec.kind)
        assertEquals(WearableShape.RECTANGLE, spec.shape)
        assertEquals(200, spec.widthPx)
        assertEquals(400, spec.heightPx)
    }

    @Test fun `id antigo sem sufixo continua relogio redondo`() {
        val spec = WearableCatalog.find("custom_300_300", 300, 300)
        assertEquals(WearableKind.SMARTWATCH, spec.kind)
        assertEquals(WearableShape.ROUND, spec.shape)
    }
}
