package com.gluview.app.watchface.miband

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Fase 5 do plano 06_watchfaces_v2_arquitetura.md. Valores conferidos por evidência (lendo o
 * cabeçalho IHDR real de cada canvas.png do WatchDrip em `_research/wd_src`), não copiados de
 * ficha técnica — ver comentário em WatchFaceDesignGenerator.kt.
 */
class MiBandTemplateSpecTest {

    @Test
    fun `MI_BAND_6 aponta pro asset dir e resolucao certos`() {
        assertEquals("miband_watchface_parts/miband6/", MiBandTemplateSpec.MI_BAND_6.assetDir)
        assertEquals(152, MiBandTemplateSpec.MI_BAND_6.canvasWidth)
        assertEquals(240, MiBandTemplateSpec.MI_BAND_6.canvasHeight)
    }

    @Test
    fun `MI_BAND_5 aponta pro asset dir e resolucao certos`() {
        assertEquals("miband_watchface_parts/miband5/", MiBandTemplateSpec.MI_BAND_5.assetDir)
        assertEquals(126, MiBandTemplateSpec.MI_BAND_5.canvasWidth)
        assertEquals(170, MiBandTemplateSpec.MI_BAND_5.canvasHeight)
    }
}
