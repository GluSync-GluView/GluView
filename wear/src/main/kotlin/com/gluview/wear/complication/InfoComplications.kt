package com.gluview.wear.complication

import android.content.Context
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationText
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.CountUpTimeReference
import androidx.wear.watchface.complications.data.NoDataComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.data.TimeDifferenceComplicationText
import androidx.wear.watchface.complications.data.TimeDifferenceStyle
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import com.gluview.wear.MainActivity
import com.gluview.wear.R
import com.gluview.wear.contract.SnapshotSource
import com.gluview.wear.contract.SnapshotUnit
import com.gluview.wear.contract.SnapshotZone
import com.gluview.wear.contract.WatchFaceSnapshot
import com.gluview.wear.data.SnapshotStore
import java.time.Instant

/**
 * Complications de informação do GluView (pedido do Felipe, 14/11/2026): os componentes do construtor de
 * visor que não existiam no Wear OS — idade da leitura, alerta de baixa e de alta, sensor (dias e ON/OFF)
 * e bateria do celular. Cada uma é um provedor próprio, então também funcionam em qualquer mostrador.
 *
 * Mesmas regras do visor da Mi Band (ComponentKind no :app): "⚠ Baixo" abaixo do limite baixo, "⚠ Alto"
 * acima do alto; "Sensor 5d" e "Sensor ON/OFF"; bateria em %. Sem dado (ou leitura velha, nos alertas) →
 * sem conteúdo: um alerta nunca fica na tela por causa de um valor antigo.
 */
object InfoTexts {
    /** Texto do alerta de baixa/alta, ou null quando não há alerta (ou a leitura está velha). */
    fun alert(snapshot: WatchFaceSnapshot?, nowMs: Long, low: Boolean): String? {
        val s = snapshot ?: return null
        if (s.isStale(nowMs)) return null
        return when {
            low && s.sgvMgdl < s.lowMgdl -> "⚠ Baixo"
            !low && s.sgvMgdl > s.highMgdl -> "⚠ Alto"
            else -> null
        }
    }

    /** Dias do sensor ("5d") ou "--" quando o início não veio do celular. */
    fun sensorDays(snapshot: WatchFaceSnapshot?, nowMs: Long): String =
        snapshot?.sensorAgeDays(nowMs)?.let { "${it}d" } ?: WatchFaceSnapshot.PLACEHOLDER

    /** "ON"/"OFF" do sensor, ou null quando o celular não informou. */
    fun sensorOnOff(snapshot: WatchFaceSnapshot?): String? =
        snapshot?.sensorConnected?.let { if (it) "ON" else "OFF" }

    /** Bateria do celular ("82%"), ou null quando não veio. */
    fun phoneBattery(snapshot: WatchFaceSnapshot?): String? = snapshot?.phoneBatteryPct?.let { "$it%" }

    internal val PREVIEW = WatchFaceSnapshot(
        sgvMgdl = 112, deltaMgdl = -3, direction = "FortyFiveDown", measuredAtMs = 1_794_150_000_000L,
        zone = SnapshotZone.NORMAL, urgentLowMgdl = 55, lowMgdl = 70, highMgdl = 180, urgentHighMgdl = 250,
        unit = SnapshotUnit.MG_DL, use24h = true, source = SnapshotSource.PHONE,
        sensorStartMs = 1_794_150_000_000L - 5 * 86_400_000L, sensorConnected = true, phoneBatteryPct = 82,
    )
}

private fun plain(s: String): ComplicationText = PlainComplicationText.Builder(s).build()

private fun shortText(context: Context, text: String, description: String, title: String? = null): ComplicationData =
    ShortTextComplicationData.Builder(plain(text), plain(description))
        .apply { title?.let { setTitle(plain(it)) } }
        .setTapAction(MainActivity.pendingIntent(context)).build()

/** Idade da leitura: o próprio relógio conta os minutos desde a medição ("5 min"), sem esperar o celular. */
class ReadingAgeComplicationService : SuspendingComplicationDataSourceService() {
    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        build(this, InfoTexts.PREVIEW)

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? =
        build(this, SnapshotStore.load(this))

    private fun build(context: Context, s: WatchFaceSnapshot?): ComplicationData {
        if (s == null) return NoDataComplicationData()
        val text = TimeDifferenceComplicationText.Builder(TimeDifferenceStyle.SHORT_SINGLE_UNIT, CountUpTimeReference(Instant.ofEpochMilli(s.measuredAtMs)))
            .build()
        return ShortTextComplicationData.Builder(text, plain(context.getString(R.string.info_reading_age)))
            .setTapAction(MainActivity.pendingIntent(context)).build()
    }
}

/** Alerta de baixa: "⚠ Baixo" só enquanto a leitura atual estiver abaixo do limite baixo do usuário. */
class LowAlertComplicationService : SuspendingComplicationDataSourceService() {
    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        shortText(this, "⚠ Baixo", getString(R.string.info_low_alert))

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? =
        InfoTexts.alert(SnapshotStore.load(this), System.currentTimeMillis(), low = true)
            ?.let { shortText(this, it, getString(R.string.info_low_alert)) } ?: NoDataComplicationData()
}

/** Alerta de alta: "⚠ Alto" só enquanto a leitura atual estiver acima do limite alto do usuário. */
class HighAlertComplicationService : SuspendingComplicationDataSourceService() {
    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        shortText(this, "⚠ Alto", getString(R.string.info_high_alert))

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? =
        InfoTexts.alert(SnapshotStore.load(this), System.currentTimeMillis(), low = false)
            ?.let { shortText(this, it, getString(R.string.info_high_alert)) } ?: NoDataComplicationData()
}

/** Sensor: texto = dias de uso ("5d"), título = "ON"/"OFF" (conexão do celular com o sensor). */
class SensorComplicationService : SuspendingComplicationDataSourceService() {
    override fun getPreviewData(type: ComplicationType): ComplicationData? = build(this, InfoTexts.PREVIEW, InfoTexts.PREVIEW.measuredAtMs)

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? =
        build(this, SnapshotStore.load(this), System.currentTimeMillis())

    private fun build(context: Context, s: WatchFaceSnapshot?, nowMs: Long): ComplicationData {
        if (s == null || (s.sensorStartMs == null && s.sensorConnected == null)) return NoDataComplicationData()
        return shortText(context, InfoTexts.sensorDays(s, nowMs), context.getString(R.string.info_sensor), InfoTexts.sensorOnOff(s))
    }
}

/** Bateria do celular, em % (texto curto ou arco). */
class PhoneBatteryComplicationService : SuspendingComplicationDataSourceService() {
    override fun getPreviewData(type: ComplicationType): ComplicationData? = build(this, type, InfoTexts.PREVIEW)

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? =
        build(this, request.complicationType, SnapshotStore.load(this))

    private fun build(context: Context, type: ComplicationType, s: WatchFaceSnapshot?): ComplicationData {
        val pct = s?.phoneBatteryPct ?: return NoDataComplicationData()
        val label = context.getString(R.string.info_phone_battery)
        return when (type) {
            ComplicationType.RANGED_VALUE -> RangedValueComplicationData.Builder(pct.toFloat(), 0f, 100f, plain(label))
                .setText(plain("$pct%")).setTapAction(MainActivity.pendingIntent(context)).build()
            else -> shortText(context, "$pct%", label)
        }
    }
}
