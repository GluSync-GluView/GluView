package com.gluview.app.watchface.apply

import com.gluview.app.watchface.WatchFaceDesign
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ponto único de entrada para "aplicar" um visor. Escolhe a estratégia ([WatchFaceApplier])
 * pelo wearable do design. Para adicionar uma plataforma (ZeppOS, Wear OS), basta registrar
 * mais um applier aqui — o editor/gerenciador não muda.
 */
@Singleton
class WatchFaceApplierRegistry @Inject constructor(
    miBand6: MiBand6WatchFaceApplier,
    miBand5: MiBand5WatchFaceApplier
) {
    private val appliers: List<WatchFaceApplier> = listOf(miBand6, miBand5)

    fun applierFor(design: WatchFaceDesign): WatchFaceApplier? =
        appliers.firstOrNull { it.supports(design) }

    suspend fun apply(design: WatchFaceDesign): ApplyResult =
        applierFor(design)?.apply(design)
            ?: ApplyResult.Unsupported(
                "Envio automático ainda não disponível para ${design.wearable.brand} ${design.wearable.name}."
            )
}
