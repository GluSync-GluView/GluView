package com.gluview.app.data.remote

import com.gluview.app.data.datastore.AppPreferences
import com.gluview.app.domain.model.GlucoseReading
import com.gluview.app.domain.model.GlucoseZone
import com.gluview.app.util.GlucoseUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class IftttWebhookClient @Inject constructor(
    private val prefs: AppPreferences
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json".toMediaType()

    fun zoneSlug(zone: GlucoseZone): String = when (zone) {
        GlucoseZone.URGENT_LOW  -> "urgent_low"
        GlucoseZone.LOW         -> "low"
        GlucoseZone.HIGH        -> "high"
        GlucoseZone.URGENT_HIGH -> "urgent_high"
        GlucoseZone.STALE       -> "stale"
        GlucoseZone.QUICK_DROP  -> "quick_drop"
        GlucoseZone.NORMAL      -> "normal"
    }

    private fun zoneLabel(zone: GlucoseZone): String = when (zone) {
        GlucoseZone.URGENT_LOW  -> "Hipoglicemia urgente"
        GlucoseZone.LOW         -> "Hipoglicemia"
        GlucoseZone.HIGH        -> "Hiperglicemia"
        GlucoseZone.URGENT_HIGH -> "Hiperglicemia urgente"
        GlucoseZone.STALE       -> "Sensor sem sinal"
        GlucoseZone.QUICK_DROP  -> "Queda rápida"
        GlucoseZone.NORMAL      -> ""
    }

    /** Dispara webhook para uma zona de alerta. Erros são silenciados — nunca bloqueia alertas locais. */
    suspend fun triggerZone(zone: GlucoseZone, reading: GlucoseReading) {
        if (!prefs.iftttEnabled.first()) return
        if (!prefs.iftttZoneEnabled(zone).first()) return
        val key = prefs.iftttApiKey.first().trim()
        if (key.isBlank()) return
        val prefix = prefs.iftttEventPrefix.first().trim().ifBlank { "gluview_" }
        val unit = GlucoseUnit.fromString(prefs.glucoseUnit.first())
        val v1 = with(unit) { "${reading.sgvMgDl.toDisplay()} $label" }
        val v2 = "${reading.arrow} ${with(unit) { reading.sgvMgDl.deltaToDisplay(reading.delta) }}"
        post("$prefix${zoneSlug(zone)}", key, v1, v2, zoneLabel(zone))
    }

    /** Dispara webhook para o alerta preditivo. */
    suspend fun triggerPredictive(reading: GlucoseReading, minutesToHypo: Int) {
        if (!prefs.iftttEnabled.first()) return
        if (!prefs.iftttPredictiveEnabled.first()) return
        val key = prefs.iftttApiKey.first().trim()
        if (key.isBlank()) return
        val prefix = prefs.iftttEventPrefix.first().trim().ifBlank { "gluview_" }
        val unit = GlucoseUnit.fromString(prefs.glucoseUnit.first())
        val v1 = with(unit) { "${reading.sgvMgDl.toDisplay()} $label" }
        val v2 = "${reading.arrow} em ~$minutesToHypo min"
        post("${prefix}predictive", key, v1, v2, "Hipo preditiva")
    }

    /** Envia evento de teste com valores fictícios. Retorna true se o servidor respondeu sem exceção. */
    suspend fun triggerTest(apiKey: String, prefix: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val event = "${prefix.trim().ifBlank { "gluview_" }}test"
            post(event, apiKey.trim(), "GluView", "Teste OK", "Integração IFTTT configurada")
            true
        }.getOrDefault(false)
    }

    private fun post(event: String, apiKey: String, v1: String, v2: String, v3: String) {
        runCatching {
            val body = JSONObject()
                .put("value1", v1).put("value2", v2).put("value3", v3)
                .toString().toRequestBody(jsonMedia)
            val req = Request.Builder()
                .url("https://maker.ifttt.com/trigger/$event/with/key/$apiKey")
                .post(body)
                .build()
            http.newCall(req).execute().use { }
        }
    }
}
