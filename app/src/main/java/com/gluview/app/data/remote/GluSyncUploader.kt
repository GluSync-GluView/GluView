package com.gluview.app.data.remote

import com.gluview.app.data.datastore.AppPreferences
import com.gluview.app.data.remote.auth.TokenRefresher
import com.gluview.app.util.AppLogger
import com.google.gson.annotations.SerializedName
import com.microsoft.signalr.HubConnection
import com.microsoft.signalr.HubConnectionBuilder
import com.microsoft.signalr.HubConnectionState
import io.reactivex.rxjava3.core.Single
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import com.gluview.app.data.repository.CloudAccessRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Envia leituras de glicemia ao GluSync via WebSocket (SignalR — hub `ws/live`, método
 * `GravarLeitura`). Usado quando o GluView é a ORIGEM dos dados (fonte BLE Sibionics): a leitura
 * é gravada localmente e também publicada na nuvem, que a persiste e retransmite aos seguidores.
 *
 * Conexão própria e preguiçosa (separada do feed de recepção). O servidor faz upsert por
 * (usuário, date), então reenvios são idempotentes — não duplicam.
 */
@Singleton
class GluSyncUploader @Inject constructor(
    private val preferences: AppPreferences,
    private val appLogger: AppLogger,
    private val tokenRefresher: TokenRefresher,
    private val cloudAccess: CloudAccessRepository
) {
    // SignalR/Gson serializa em camelCase; o servidor (System.Text.Json case-insensitive)
    // mapeia para GlucoseEntry(Date, Sgv, Direction, Device, Type). @SerializedName (e a regra keep
    // no proguard-rules.pro) porque no release o R8 renomearia os campos para a..e (E8, plano OTA).
    private data class Entry(
        @SerializedName("date") val date: Long,
        @SerializedName("sgv") val sgv: Double,
        @SerializedName("direction") val direction: String,
        @SerializedName("device") val device: String,
        @SerializedName("type") val type: String = "sgv"
    )

    @Volatile private var hub: HubConnection? = null
    private val mutex = Mutex()
    private val LOG = "WS_UP"

    suspend fun uploadReading(
        dateMs: Long,
        sgvMgDl: Double,
        direction: String,
        device: String
    ): Boolean = withContext(Dispatchers.IO) {
        val baseUrl = preferences.baseUrl.first().trimEnd('/')
        val token = preferences.accessToken.first()
        if (baseUrl.isBlank() || token.isBlank()) {
            appLogger.usage(LOG, "Envio ignorado — sem servidor/login configurado")
            return@withContext false
        }
        mutex.withLock {
            attemptUpload(baseUrl, dateMs, sgvMgDl, direction, device, allowRefresh = true)
        }
    }

    /**
     * O SignalR (negotiate) NÃO tem retry automático de token como o OkHttp/TokenAuthenticator
     * usado pelo REST — se o access token (validade de 1h) expirar sem nenhuma chamada REST
     * acontecendo nesse meio tempo (comum no modo sensor BLE, sem polling), a conexão passa a
     * falhar com "negotiate: 401" para sempre. Aqui replicamos o mesmo refresh: na 1ª falha de
     * autenticação, renova o token (refresh token) e tenta de novo uma única vez.
     */
    private suspend fun attemptUpload(
        baseUrl: String,
        dateMs: Long,
        sgvMgDl: Double,
        direction: String,
        device: String,
        allowRefresh: Boolean
    ): Boolean {
        try {
            val conn = ensureConnected(baseUrl)
            val ok = conn.invoke(
                Boolean::class.javaObjectType,
                "GravarLeitura",
                Entry(dateMs, sgvMgDl, direction, device)
            ).blockingGet()
            appLogger.usage(LOG, "Leitura enviada ao GluSync", mapOf("sgv" to sgvMgDl, "ok" to (ok ?: false)))
            return ok ?: false
        } catch (e: Exception) {
            val message = e.message ?: "desconhecido"
            runCatching { hub?.stop() }
            hub = null
            if (allowRefresh && tokenRefresher.isAuthFailure(message) && tokenRefresher.refresh()) {
                appLogger.usage(LOG, "Token renovado — reenviando")
                return attemptUpload(baseUrl, dateMs, sgvMgDl, direction, device, allowRefresh = false)
            }
            // ADR-0030 — envio à nuvem não liberado para esta conta: não é falha de rede, então
            // para de tentar até a próxima consulta da liberação (sem alerta de erro de gravação).
            if (message.contains("feature_not_entitled")) {
                appLogger.usage(LOG, "Envio à nuvem não liberado para esta conta")
                cloudAccess.markNotEntitled()
                return false
            }
            appLogger.error(LOG, "Falha ao enviar leitura", mapOf("erro" to message))
            return false
        }
    }

    private fun ensureConnected(baseUrl: String): HubConnection {
        hub?.let { if (it.connectionState == HubConnectionState.CONNECTED) return it }
        runCatching { hub?.stop() }
        val conn = HubConnectionBuilder
            .create("$baseUrl/ws/live")
            .withServerTimeout(300_000)
            .withKeepAliveInterval(15_000)
            .withAccessTokenProvider(Single.fromCallable {
                runBlocking { preferences.accessToken.first() }
            })
            .build()
        // blockingAwait() SEM timeout pode travar pra sempre se a tentativa de conexão nem
        // sucede nem falha explicitamente (comum sob restrição de rede em segundo plano do
        // Android/Doze) — como isso roda dentro do mutex, TODOS os envios futuros ficariam
        // bloqueados atrás dessa única chamada travada. Foi exatamente isso que causou ~11h42
        // sem nenhum envio em 01/07/2026 (silêncio total nos logs do servidor até o app voltar
        // pro primeiro plano). Com timeout, uma tentativa travada vira falha normal — solta o
        // mutex e a próxima leitura tenta de novo.
        val connected = conn.start().blockingAwait(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
        if (!connected) {
            runCatching { conn.stop() }
            throw TimeoutException("Timeout ($CONNECT_TIMEOUT_S s) ao conectar em $baseUrl/ws/live")
        }
        hub = conn
        return conn
    }

    private companion object {
        const val CONNECT_TIMEOUT_S = 20L
    }

    fun stop() {
        runCatching { hub?.stop() }
        hub = null
    }
}
