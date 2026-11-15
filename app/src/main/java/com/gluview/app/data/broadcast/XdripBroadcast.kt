package com.gluview.app.data.broadcast

import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.gluview.app.data.datastore.AppPreferences
import com.gluview.app.domain.model.GlucoseReading
import com.gluview.app.util.AppLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PT-7 Fase A — envio local de cada leitura no formato que o ecossistema já consome: o broadcast
 * `com.eveningoutpost.dexdrip.BgEstimate` do xDrip+ (constantes de `utilitymodels/Intents.java` e
 * `BroadcastGlucose.java` do xDrip; o mesmo subconjunto que o Juggluco manda em `SendLikexDrip`).
 * Recebem: AndroidAPS e outros apps que leem "xDrip+ (broadcast)".
 *
 * Proteção: nada vai "para todo mundo". Só para os pacotes que a pessoa escolheu (destino explícito
 * com `setPackage`, como o Juggluco) — a glicemia não vaza para outros apps.
 *
 * Nunca manda leitura velha nem a mesma leitura duas vezes (o xDrip recusa leituras a menos de
 * 1 min da anterior).
 */
object XdripBroadcastFormat {
    const val ACTION = "com.eveningoutpost.dexdrip.BgEstimate"
    const val EXTRA_BG_ESTIMATE = "com.eveningoutpost.dexdrip.Extras.BgEstimate"
    const val EXTRA_BG_SLOPE = "com.eveningoutpost.dexdrip.Extras.BgSlope"
    const val EXTRA_BG_SLOPE_NAME = "com.eveningoutpost.dexdrip.Extras.BgSlopeName"
    const val EXTRA_TIMESTAMP = "com.eveningoutpost.dexdrip.Extras.Time"
    const val EXTRA_SENSOR_STARTED_AT = "com.eveningoutpost.dexdrip.Extras.SensorStartedAt"
    const val EXTRA_SOURCE_DESC = "com.eveningoutpost.dexdrip.Extras.SourceDesc"
    const val EXTRA_SOURCE_INFO = "com.eveningoutpost.dexdrip.Extras.SourceInfo"

    private const val MIN_GAP_MS = 60_000L
    private const val MAX_SLOPE_GAP_MS = 15 * 60_000L

    /**
     * Nome de tendência do xDrip (`Dex_Constants.TREND_ARROW_VALUES.getTrend`): primeira faixa cujo
     * limite a taxa (mg/dL/min) ultrapassa. Acima de 40 mg/dL/min = "NONE" (não computável).
     */
    fun slopeName(ratePerMin: Double?): String = when {
        ratePerMin == null || !ratePerMin.isFinite() -> "NONE"
        ratePerMin > 40 -> "NONE"
        ratePerMin > 3.5 -> "DoubleUp"
        ratePerMin > 2 -> "SingleUp"
        ratePerMin > 1 -> "FortyFiveUp"
        ratePerMin > -1 -> "Flat"
        ratePerMin > -2 -> "FortyFiveDown"
        ratePerMin > -3.5 -> "SingleDown"
        else -> "DoubleDown"
    }

    /** Taxa em mg/dL/min entre a leitura anterior enviada e esta; null se longe demais ou inválida. */
    fun rate(prevMgdl: Double?, prevMs: Long?, mgdl: Double, atMs: Long): Double? {
        if (prevMgdl == null || prevMs == null) return null
        val dt = atMs - prevMs
        if (dt <= 0 || dt > MAX_SLOPE_GAP_MS) return null
        return (mgdl - prevMgdl) / (dt / 60_000.0)
    }

    /** true = esta leitura pode sair (válida, atual e não repetida). */
    fun shouldSend(reading: GlucoseReading, lastSentMs: Long): Boolean =
        !reading.isStale && reading.sgvMgDl > 0 && reading.epochMs > 0 &&
            kotlin.math.abs(reading.epochMs - lastSentMs) >= MIN_GAP_MS

    /** Extras do broadcast (mapa simples, testável sem Android). */
    fun extras(reading: GlucoseReading, ratePerMin: Double?, sensorStartMs: Long, sourceName: String): Map<String, Any> = buildMap {
        put(EXTRA_BG_ESTIMATE, reading.sgvMgDl)
        put(EXTRA_BG_SLOPE_NAME, slopeName(ratePerMin))
        // O xDrip manda a inclinação por milissegundo.
        put(EXTRA_BG_SLOPE, (ratePerMin ?: 0.0) / 60_000.0)
        put(EXTRA_TIMESTAMP, reading.epochMs)
        if (sensorStartMs > 0) put(EXTRA_SENSOR_STARTED_AT, sensorStartMs)
        put(EXTRA_SOURCE_DESC, "GluView")
        put(EXTRA_SOURCE_INFO, sourceName)
    }
}

@Singleton
class XdripBroadcaster @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: AppPreferences,
    private val appLogger: AppLogger
) {
    @Volatile private var lastSentMs = 0L
    @Volatile private var lastSentMgdl: Double? = null

    /** Apps instalados que recebem o broadcast do xDrip (para a pessoa escolher). */
    fun installedReceivers(): List<Pair<String, String>> = runCatching {
        val pm = context.packageManager
        pm.queryBroadcastReceivers(Intent(XdripBroadcastFormat.ACTION), 0)
            .map { it.activityInfo.packageName }
            .distinct()
            .filter { it != context.packageName }
            .map { pkg -> pkg to (runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)) }
            .sortedBy { it.second.lowercase() }
    }.getOrDefault(emptyList())

    suspend fun onNewReading(reading: GlucoseReading, sensorStartMs: Long, sourceName: String) {
        if (!preferences.localBroadcastEnabled.first()) return
        val targets = preferences.localBroadcastPackages.first()
        if (targets.isEmpty() || !XdripBroadcastFormat.shouldSend(reading, lastSentMs)) return
        val rate = XdripBroadcastFormat.rate(lastSentMgdl, lastSentMs.takeIf { it > 0 }, reading.sgvMgDl, reading.epochMs)
        val bundle = Bundle().apply {
            XdripBroadcastFormat.extras(reading, rate, sensorStartMs, sourceName).forEach { (k, v) ->
                when (v) {
                    is Double -> putDouble(k, v)
                    is Long -> putLong(k, v)
                    is String -> putString(k, v)
                }
            }
        }
        targets.forEach { pkg ->
            runCatching {
                context.sendBroadcast(Intent(XdripBroadcastFormat.ACTION)
                    .setPackage(pkg)
                    .putExtras(bundle)
                    .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES))
            }.onFailure { appLogger.error("XDRIP_BCAST", "Envio local falhou", mapOf("app" to pkg, "erro" to (it.message ?: ""))) }
        }
        lastSentMs = reading.epochMs
        lastSentMgdl = reading.sgvMgDl
    }
}
