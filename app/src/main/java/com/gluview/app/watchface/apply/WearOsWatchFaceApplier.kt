package com.gluview.app.watchface.apply

import com.gluview.app.R
import com.gluview.app.watchface.WatchFaceDesign
import com.gluview.app.wear.WearBridge
import com.gluview.app.wear.WearStatus
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Relógios Wear OS (Fase 8 do plano 14). Hoje NÃO envia o visor desenhado — isso é o plano 16
 * (Watch Face Format + Watch Face Push, Wear OS 6). O que já funciona no Wear OS é a glicemia pela
 * complication/tile do app GluView do relógio, em qualquer mostrador; este applier diz isso com
 * clareza em vez de falhar em silêncio, e guia o usuário quando falta relógio ou o app nele.
 *
 * Fora daqui (registry → "sem envio automático"): Apple, Garmin, Galaxy Watch 3 ou anterior (Tizen)
 * e relógios Xiaomi com sistema próprio (Watch S1/S3).
 */
@Singleton
class WearOsWatchFaceApplier @Inject constructor(
    private val bridge: WearBridge
) : WatchFaceApplier {
    override val id = "wear_os"
    override val displayName = "Wear OS"

    override fun supports(design: WatchFaceDesign): Boolean = isWearOs(design.wearable.id)

    override suspend fun apply(design: WatchFaceDesign): ApplyResult = when (bridge.status()) {
        WearStatus.Unavailable -> ApplyResult.Unsupported("Play Services indisponível", R.string.wear_apply_unavailable)
        WearStatus.NoWatch -> ApplyResult.NeedsSetup("Nenhum relógio conectado", R.string.wear_apply_no_watch)
        is WearStatus.AppMissing -> ApplyResult.NeedsSetup("Instale o GluView no relógio", R.string.wear_apply_needs_app)
        is WearStatus.Ready -> ApplyResult.Unsupported("Visor personalizado no Wear OS: em breve", R.string.wear_apply_coming)
    }

    companion object {
        /** Ids do catálogo que rodam Wear OS (Galaxy Watch 4+, Pixel Watch, Xiaomi Watch 2). */
        fun isWearOs(wearableId: String): Boolean =
            wearableId.startsWith("galaxy_watch_") || wearableId.startsWith("pixel_watch_") || wearableId == "xiaomi_watch_2"
    }
}
