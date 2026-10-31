package com.gluview.app.watchface.miband

import android.content.Context
import com.gluview.app.ui.screen.watchface.WatchFaceRuntimeData
import com.gluview.app.watchface.WatchFaceDesign

/**
 * Qual template Huami usar — pasta de assets + resolução do canvas nativo desse recurso. Fase 5
 * do plano 06_watchfaces_v2_arquitetura.md: prova que o mesmo [MiBandCanvasCompiler]/
 * [MiBandPackageBuilder] (extraídos na Fase 3, código nenhum específico de Mi Band 6) atende
 * outro wearable só trocando estes 3 números — a independência de resolução da IR não é teórica.
 *
 * Resoluções verificadas byte a byte lendo o cabeçalho IHDR de cada `canvas.png` do WatchDrip
 * (`_research/wd_src/.../miband_watchface_parts/`), não copiadas de uma ficha técnica — miband5
 * divergia do que estava catalogado em WearableCatalog (126x294 catalogado vs 126x170 real, ver
 * achado corrigido junto com esta mudança).
 */
data class MiBandTemplateSpec(val assetDir: String, val canvasWidth: Int, val canvasHeight: Int) {
    companion object {
        val MI_BAND_6 = MiBandTemplateSpec("miband_watchface_parts/miband6/", 152, 240)
        val MI_BAND_5 = MiBandTemplateSpec("miband_watchface_parts/miband5/", 126, 170)
    }
}

/**
 * Gera o `.bin` de watchface Huami a partir de um [WatchFaceDesign] LIVRE do editor — caminho novo
 * e paralelo ao [MiBandWatchFaceGenerator] (que continua servindo só o envio automático de layout
 * fixo, intocado).
 *
 * Fase 3 do plano: orquestrador fino sobre [MiBandCanvasCompiler] (desenho) + [MiBandPackageBuilder]
 * (empacotamento `.bin`) — nenhum dos dois conhece Mi Band 6 especificamente, só o [template]
 * passado aqui determina isso. Fase 5: por causa dessa separação, suportar a Mi Band 5 foi só
 * adicionar um [MiBandTemplateSpec] novo — zero mudança em Compiler/PackageBuilder.
 */
class WatchFaceDesignGenerator(context: Context, private val template: MiBandTemplateSpec = MiBandTemplateSpec.MI_BAND_6) {

    private val compiler = MiBandCanvasCompiler(context)
    private val packageBuilder = MiBandPackageBuilder(context, template.assetDir)

    fun generate(design: WatchFaceDesign, runtime: WatchFaceRuntimeData): ByteArray {
        val screen = compiler.draw(design, runtime, template.canvasWidth, template.canvasHeight)
        val packageBytes = packageBuilder.buildPackage(screen)
        screen.recycle()
        return packageBytes
    }
}
