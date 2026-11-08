package com.gluview.wear.complication

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationText
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.NoDataComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.data.SmallImage
import androidx.wear.watchface.complications.data.SmallImageComplicationData
import androidx.wear.watchface.complications.data.SmallImageType
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import com.gluview.wear.MainActivity
import com.gluview.wear.R
import com.gluview.wear.contract.SnapshotSource
import com.gluview.wear.contract.SnapshotUnit
import com.gluview.wear.contract.SnapshotZone
import com.gluview.wear.contract.WatchFaceSnapshot
import com.gluview.wear.data.SnapshotStore
import com.gluview.wear.ui.Display

/**
 * Complication do GluView (Fase 6) — a peça que põe a glicemia em QUALQUER mostrador, inclusive os
 * nativos da Samsung. Tipos: texto curto ("112↘"), texto longo, arco (faixa entre urgente baixo e
 * urgente alto) e imagem pequena (minigráfico desenhado no celular).
 *
 * Regra de saúde: leitura velha (contrato :wear-contract) vira "--", sem seta, delta, arco ou gráfico.
 * O sistema chama a cada 5 min (UPDATE_PERIOD_SECONDS) mesmo sem leitura nova, e o valor envelhece sozinho.
 */
class GlucoseComplicationService : SuspendingComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        build(this, type, PREVIEW, PREVIEW.measuredAtMs + 60_000, graph = null)

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val snapshot = SnapshotStore.load(this)
        val graph = if (request.complicationType == ComplicationType.SMALL_IMAGE) SnapshotStore.graphBitmap(this) else null
        return build(this, request.complicationType, snapshot, System.currentTimeMillis(), graph)
    }

    companion object {
        private val PREVIEW = WatchFaceSnapshot(
            sgvMgdl = 112, deltaMgdl = -3, direction = "FortyFiveDown", measuredAtMs = 1_794_150_000_000L,
            zone = SnapshotZone.NORMAL, urgentLowMgdl = 55, lowMgdl = 70, highMgdl = 180, urgentHighMgdl = 250,
            unit = SnapshotUnit.MG_DL, use24h = true, source = SnapshotSource.PHONE,
        )

        private fun text(s: String): ComplicationText = PlainComplicationText.Builder(s).build()

        fun build(context: Context, type: ComplicationType, snapshot: WatchFaceSnapshot?, nowMs: Long, graph: Bitmap?): ComplicationData? {
            val stale = snapshot == null || snapshot.isStale(nowMs)
            val value = snapshot?.displayValue(nowMs) ?: WatchFaceSnapshot.PLACEHOLDER
            val arrow = snapshot?.arrow(nowMs).orEmpty()
            val delta = snapshot?.deltaText(nowMs).orEmpty()
            val tap = MainActivity.pendingIntent(context)
            val description = text(
                if (stale) context.getString(R.string.stale)
                else "${context.getString(R.string.complication_label)}: $value ${Display.unitLabel(snapshot!!)} $arrow"
            )

            return when (type) {
                ComplicationType.SHORT_TEXT ->
                    ShortTextComplicationData.Builder(text("$value$arrow"), description)
                        .apply { if (delta.isNotEmpty()) setTitle(text(delta)) }
                        .setTapAction(tap).build()

                ComplicationType.LONG_TEXT -> {
                    val main = if (stale) "$value · ${context.getString(R.string.stale)}"
                    else "$value ${Display.unitLabel(snapshot!!)} $arrow $delta"
                    val title = if (!stale) context.getString(R.string.measured_at, Display.measuredTime(snapshot!!)) else null
                    LongTextComplicationData.Builder(text(main.trim()), description)
                        .apply { title?.let { setTitle(text(it)) } }
                        .setTapAction(tap).build()
                }

                ComplicationType.RANGED_VALUE -> {
                    val s = snapshot ?: PREVIEW
                    val min = (s.urgentLowMgdl - 15).coerceAtLeast(20).toFloat()
                    val max = (s.urgentHighMgdl + 50).toFloat()
                    // Velho: arco vazio (no mínimo) e "--" — nunca um arco cheio de dado antigo.
                    val v = if (stale) min else s.sgvMgdl.toFloat().coerceIn(min, max)
                    RangedValueComplicationData.Builder(v, min, max, description)
                        .setText(text("$value$arrow"))
                        .setTapAction(tap).build()
                }

                ComplicationType.SMALL_IMAGE -> {
                    if (stale || graph == null) NoDataComplicationData()
                    else SmallImageComplicationData.Builder(
                        SmallImage.Builder(Icon.createWithBitmap(graph), SmallImageType.PHOTO).build(), description
                    ).setTapAction(tap).build()
                }

                else -> null
            }
        }
    }
}
