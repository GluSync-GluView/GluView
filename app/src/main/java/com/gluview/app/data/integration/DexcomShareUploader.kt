package com.gluview.app.data.integration

import com.gluview.app.data.datastore.AppPreferences
import com.gluview.app.domain.model.GlucoseReading
import com.gluview.app.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Envio da glicemia do GluView PARA o Dexcom Share (plano 21, F8.3; decisão do Felipe em 21/11/2026:
 * não há API oficial de escrita, então pela API não oficial, com aviso na tela). Porte do xDrip
 * (GPLv3) `sharemodels/ShareRest.java` + `DexcomShare.java` + `models/Egv.java`:
 *
 *  1. General/LoginPublisherAccountByName {accountName, password, applicationId} → sessionId;
 *  2. General/AuthenticatePublisherAccount, Publisher/StartRemoteMonitoringSession e, se o receptor
 *     não estiver "AssignedToYou", Publisher/ReplacePublisherAccountMonitoredReceiver — com o número
 *     de série de um RECEPTOR Dexcom da conta (exigência do Share; sem ele a Dexcom recusa);
 *  3. Publisher/PostReceiverEgvRecords?sessionId=… {SN, Egvs:[{Trend, Value, ST, DT}], TA:-5};
 *     HTTP 500 = sessão vencida → um novo login e mais uma tentativa (AuthenticatingCallback).
 *
 * Diferença proposital do xDrip: a verificação TLS fica LIGADA (o xDrip aceita qualquer certificado).
 * Só leituras do sensor local são enviadas (o chamador filtra), nunca dados vindos da nuvem.
 */
@Singleton
class DexcomShareUploader @Inject constructor(
    private val preferences: AppPreferences,
    private val appLogger: AppLogger
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
        .build()
    private val mutex = Mutex()
    @Volatile private var sessionId: String? = null
    @Volatile private var lastSentMs = 0L

    data class Config(val username: String, val password: String, val receiverSerial: String, val us: Boolean)

    suspend fun onNewReading(reading: GlucoseReading) = mutex.withLock {
        if (!preferences.dexcomUploadEnabled.first()) return@withLock
        if (reading.epochMs <= lastSentMs || reading.sgvMgDl < 39) return@withLock
        val cfg = config() ?: run { status("Configuração incompleta (conta, senha e série do receptor)", false); return@withLock }
        val ok = withContext(Dispatchers.IO) { runCatching { upload(cfg, listOf(reading)) }.getOrElse {
            appLogger.error(LOG, "Envio ao Dexcom Share falhou", mapOf("erro" to (it.message ?: it::class.simpleName.orEmpty())))
            false
        } }
        if (ok) { lastSentMs = reading.epochMs; status("Enviado", true) }
    }

    /** Teste da tela de ajustes: só faz o login e a ligação do receptor, sem enviar leitura. */
    suspend fun testLogin(): String = mutex.withLock {
        val cfg = config() ?: return@withLock "Preencha conta, senha e série do receptor."
        withContext(Dispatchers.IO) {
            runCatching { sessionId = login(cfg); "Login aceito pela Dexcom." }
                .getOrElse { "Falhou: ${it.message ?: it::class.simpleName}" }
        }
    }

    private suspend fun config(): Config? {
        val u = preferences.dexcomUploadUsername.first().trim()
        val p = preferences.dexcomUploadPassword()
        val s = preferences.dexcomUploadReceiverSerial.first().trim()
        if (u.isBlank() || p.isBlank() || s.isBlank()) return null
        return Config(u, p, s, preferences.dexcomUploadUs.first())
    }

    private suspend fun status(text: String, ok: Boolean) =
        preferences.saveDexcomUploadStatus(System.currentTimeMillis(), text, ok)

    private fun upload(cfg: Config, readings: List<GlucoseReading>): Boolean {
        val body = payload(cfg.receiverSerial, readings)
        repeat(2) { attempt ->
            val sid = sessionId ?: login(cfg).also { sessionId = it }
            val code = post(cfg, "Publisher/PostReceiverEgvRecords?sessionId=$sid", body).first
            when {
                code in 200..299 -> return true
                code == 500 && attempt == 0 -> sessionId = null      // sessão vencida: novo login
                else -> throw IllegalStateException("HTTP $code no envio")
            }
        }
        return false
    }

    private fun login(cfg: Config): String {
        val auth = JSONObject().put("accountName", cfg.username).put("password", cfg.password)
            .put("applicationId", APPLICATION_ID).toString()
        val (code, text) = post(cfg, "General/LoginPublisherAccountByName", auth)
        if (code !in 200..299) throw IllegalStateException("login recusado (HTTP $code)")
        val sid = text.trim().trim('"')
        if (sid.isBlank() || sid == NULL_SESSION) throw IllegalStateException("conta ou senha recusadas pela Dexcom")
        val sn = cfg.receiverSerial
        post(cfg, "General/AuthenticatePublisherAccount?sessionId=$sid&serialNumber=$sn", auth)
        post(cfg, "Publisher/StartRemoteMonitoringSession?sessionId=$sid&serialNumber=$sn", "")
        val assignment = post(cfg, "Publisher/CheckMonitoredReceiverAssignmentStatus?sessionId=$sid&serialNumber=$sn", "").second.trim().trim('"')
        if (assignment != "AssignedToYou") {
            post(cfg, "Publisher/ReplacePublisherAccountMonitoredReceiver?sessionId=$sid&serialNumber=$sn", "")
        }
        appLogger.usage(LOG, "Login no Dexcom Share", mapOf("receptor" to assignment))
        return sid
    }

    private fun post(cfg: Config, path: String, json: String): Pair<Int, String> {
        val req = Request.Builder()
            .url((if (cfg.us) US_BASE else NON_US_BASE) + path)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .post(json.toRequestBody(JSON))
            .build()
        client.newCall(req).execute().use { r -> return r.code to (r.body?.string() ?: "") }
    }

    companion object {
        private const val LOG = "DEXCOM_UPLOAD"
        const val US_BASE = "https://share2.dexcom.com/ShareWebServices/Services/"
        const val NON_US_BASE = "https://shareous1.dexcom.com/ShareWebServices/Services/"
        /** xDrip ShareAuthenticationBody.applicationId. */
        const val APPLICATION_ID = "d89443d2-327c-4a6f-89e5-496bbb0317db"
        private const val NULL_SESSION = "00000000-0000-0000-0000-000000000000"
        private const val USER_AGENT = "CGM-Store-1.2/22 CFNetwork/711.5.6 Darwin/14.0.0"
        private val JSON = "application/json".toMediaType()

        /** xDrip ShareUploadPayload/Egv: "/Date(ms)/" com o ms truncado ao segundo, TA = -5. */
        fun payload(serial: String, readings: List<GlucoseReading>): String {
            val egvs = JSONArray()
            readings.forEach { r ->
                val date = "/Date(${(r.epochMs / 1000) * 1000})/"
                egvs.put(JSONObject().put("Trend", trendOrdinal(r.direction.ifBlank { r.arrow })).put("Value", Math.round(r.sgvMgDl).toInt())
                    .put("ST", date).put("DT", date))
            }
            return JSONObject().put("SN", serial).put("Egvs", egvs).put("TA", -5).toString()
        }

        /** xDrip ShareUploadableBg ordinais: 1 ↑↑ … 4 → … 7 ↓↓, 0 sem seta. */
        fun trendOrdinal(arrow: String): Int = when (arrow.trim()) {
            "↑↑", "DoubleUp" -> 1
            "↑", "SingleUp" -> 2
            "↗", "FortyFiveUp" -> 3
            "→", "Flat" -> 4
            "↘", "FortyFiveDown" -> 5
            "↓", "SingleDown" -> 6
            "↓↓", "DoubleDown" -> 7
            else -> 0
        }
    }
}
