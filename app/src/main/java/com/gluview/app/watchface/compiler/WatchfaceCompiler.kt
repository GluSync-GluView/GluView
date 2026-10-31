package com.gluview.app.watchface.compiler

import android.graphics.Bitmap
import com.gluview.app.ui.screen.watchface.WatchFaceRuntimeData
import com.gluview.app.watchface.WatchFaceDesign

/**
 * Contratos do pipeline de compilação (plano 06_watchfaces_v2_arquitetura.md, §8.2/§24 Fase 3).
 * Separa em 3 papéis o que hoje, para a Mi Band 6, está fundido dentro de um único
 * `WatchFaceDesignGenerator.generate()`:
 *
 *   [WatchfaceCompiler]      — IR (WatchFaceDesign) -> bitmap desenhado na resolução do wearable
 *   [WatchfacePackageBuilder] — bitmap desenhado -> bytes no formato final do dispositivo
 *   [WatchfaceInstaller]     — bytes finais -> enviados ao dispositivo
 *
 * Wear OS (que não gera arquivo, envia payload de Data Layer — ver 05_sprint_wearos.md) usaria os
 * mesmos 3 papéis com implementações diferentes, sem o Compiler/editor conhecerem a diferença.
 */
interface WatchfaceCompiler {
    fun draw(design: WatchFaceDesign, runtime: WatchFaceRuntimeData, canvasWidth: Int, canvasHeight: Int): Bitmap
}

interface WatchfacePackageBuilder {
    fun buildPackage(screen: Bitmap): ByteArray
}

interface WatchfaceInstaller {
    suspend fun install(packageBytes: ByteArray, onLog: (String) -> Unit = {}): Boolean
}
