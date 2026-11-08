package com.gluview.app.watchface.apply

import android.content.Context
import com.gluview.app.R
import com.gluview.app.data.datastore.AppPreferences
import com.gluview.app.util.GlucoseUnit
import com.gluview.app.watchface.WatchFaceDesign
import com.gluview.app.watchface.wff.WatchFaceBuilder
import com.gluview.app.watchface.wff.WffCompiler
import com.gluview.app.wear.WearBridge
import com.gluview.app.wear.WearStatus
import com.gluview.wear.contract.FaceAck
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * Relógios Wear OS (plano 14 Fase 8 + plano 16 W3). Envia o visor desenhado no construtor como um
 * mostrador Watch Face Format, instalado pelo app GluView do relógio via Watch Face Push (Wear OS 6+).
 * Antes disso guia o usuário: sem relógio, sem o app nele, ou relógio sem Wear OS 6 (a glicemia
 * continua pela complication do GluView em qualquer mostrador).
 *
 * Fora daqui (registry → "sem envio automático"): Apple, Garmin, Galaxy Watch 3 ou anterior (Tizen)
 * e relógios Xiaomi com sistema próprio (Watch S1/S3).
 */
@Singleton
class WearOsWatchFaceApplier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bridge: WearBridge,
    private val prefs: AppPreferences,
) : WatchFaceApplier {
    override val id = "wear_os"
    override val displayName = "Wear OS"

    override fun supports(design: WatchFaceDesign): Boolean = isWearOs(design.wearable.id)

    override suspend fun apply(design: WatchFaceDesign): ApplyResult = when (bridge.status()) {
        WearStatus.Unavailable -> ApplyResult.Unsupported("Play Services indisponível", R.string.wear_apply_unavailable)
        WearStatus.NoWatch -> ApplyResult.NeedsSetup("Nenhum relógio conectado", R.string.wear_apply_no_watch)
        is WearStatus.AppMissing -> ApplyResult.NeedsSetup("Instale o GluView no relógio", R.string.wear_apply_needs_app)
        is WearStatus.Ready -> sendFace(design)
    }

    private suspend fun sendFace(design: WatchFaceDesign): ApplyResult {
        if (!bridge.watchSupportsFacePush()) {
            return ApplyResult.Unsupported("Relógio sem Wear OS 6", R.string.wear_apply_needs_wear6)
        }
        val limits = prefs.glucoseThresholds.first()
        val unit = GlucoseUnit.fromString(prefs.glucoseUnit.first())
        val options = WffCompiler.Options(
            appPackage = context.packageName,
            unitLabel = unit.label,
            targetLow = limits.low.roundToInt(),
            targetHigh = limits.high.roundToInt(),
        )
        // Traduzir + assinar + validar é pesado (validador oficial): fora da thread principal.
        val built = withContext(Dispatchers.IO) { runCatching { WatchFaceBuilder(context).build(design, options) } }
            .getOrElse { return ApplyResult.Failure(it.message ?: it::class.java.simpleName) }
        return when (built) {
            is WatchFaceBuilder.Result.Rejected -> ApplyResult.Failure(built.problems.joinToString("; ").take(200))
            is WatchFaceBuilder.Result.Built -> when (val ack = bridge.sendFace(built.apk, built.token)) {
                null -> ApplyResult.Delivered(R.string.wear_face_sent_waiting)
                is FaceAck.Installed -> ApplyResult.Delivered(
                    if (ack.active) R.string.wear_face_installed_active else R.string.wear_face_installed_activate
                )
                FaceAck.Unsupported -> ApplyResult.Unsupported("Relógio sem Wear OS 6", R.string.wear_apply_needs_wear6)
                FaceAck.NoSlot -> ApplyResult.Failure(context.getString(R.string.wear_face_no_slot))
                is FaceAck.Failed -> ApplyResult.Failure(ack.reason.take(200))
            }
        }
    }

    companion object {
        /** Ids do catálogo que rodam Wear OS (Galaxy Watch 4+, Pixel Watch, Xiaomi Watch 2). */
        fun isWearOs(wearableId: String): Boolean =
            wearableId.startsWith("galaxy_watch_") || wearableId.startsWith("pixel_watch_") || wearableId == "xiaomi_watch_2"
    }
}
