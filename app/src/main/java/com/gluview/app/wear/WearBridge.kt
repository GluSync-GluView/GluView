package com.gluview.app.wear

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.gluview.app.data.datastore.AppPreferences
import com.gluview.app.data.repository.GlucoseRepository
import com.gluview.app.data.sibionics.GlucoseDisplayMode
import com.gluview.app.domain.model.GlucoseReading
import com.gluview.app.domain.model.GlucoseThresholds
import com.gluview.app.domain.model.GlucoseZone
import com.gluview.app.util.GluSyncConstants
import com.gluview.app.util.GlucoseGraphRenderer
import com.gluview.app.util.GlucoseUnit
import com.gluview.app.util.applyDisplayMode
import com.gluview.app.util.toGraphReading
import com.gluview.wear.contract.FaceAck
import com.gluview.wear.contract.SnapshotSource
import com.gluview.wear.contract.SnapshotUnit
import com.gluview.wear.contract.SnapshotZone
import com.gluview.wear.contract.WatchFaceSnapshot
import com.gluview.wear.contract.WearPaths
import com.gluview.wear.contract.WearToken
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/** Situação do relógio Wear OS vista do celular (card da tela Wearables, Fase 8). */
sealed interface WearStatus {
    /** Sem Google Play Services/Wearable no celular (ex.: Android sem serviços do Google). */
    data object Unavailable : WearStatus
    /** Nenhum relógio Wear OS conectado agora. */
    data object NoWatch : WearStatus
    /** Relógio conectado, mas sem o GluView instalado nele. */
    data class AppMissing(val watchName: String) : WearStatus
    /** Relógio conectado com o GluView: tudo pronto. */
    data class Ready(val watchName: String) : WearStatus
}

/**
 * Ponte celular → relógio Wear OS (Fase 5 do plano Projeto DM1/GluView/14_).
 *
 * A cada leitura nova, monta um [WatchFaceSnapshot] (contrato do módulo :wear-contract), desenha o
 * minigráfico AQUI no celular (mesmo [GlucoseGraphRenderer] do visor da Mi Band, variante WATCH — o
 * relógio não roda o renderizador, poupa bateria) e publica em [WearPaths.READING] como DataItem
 * urgente. Junto, entrega o token de acesso em [WearPaths.TOKEN] por mensagem direta (plano B da
 * Fase 7) — ou [WearToken.CLEARED] em modo só-local/sem conta.
 *
 * Tudo é "melhor esforço": sem Play Services, sem relógio ou com falha de rede, nada quebra no
 * celular — só registra no log. O relógio decide sozinho quando o dado ficou velho.
 */
@Singleton
class WearBridge @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: AppPreferences,
    private val repository: GlucoseRepository,
) {
    private val tag = "WearBridge"
    private val mutex = Mutex()
    @Volatile private var lastSentEpochMs = 0L

    private val dataClient by lazy { Wearable.getDataClient(context) }
    private val messageClient by lazy { Wearable.getMessageClient(context) }
    private val capabilityClient by lazy { Wearable.getCapabilityClient(context) }
    private val nodeClient by lazy { Wearable.getNodeClient(context) }

    /** Chamado pelo serviço a cada leitura nova. Ignora repetição da mesma medição. */
    suspend fun onNewReading(reading: GlucoseReading) {
        if (reading.epochMs <= 0L) return
        mutex.withLock {
            if (reading.epochMs == lastSentEpochMs) return
            runCatching {
                // Sem relógio com o app, não gasta nada (nem desenha o gráfico).
                val nodes = watchNodesWithApp() ?: return
                if (nodes.isEmpty()) return
                sendReading(reading)
                sendToken(nodes)
                lastSentEpochMs = reading.epochMs
            }.onFailure { Log.w(tag, "Falha ao enviar ao relógio: ${it.message}") }
        }
    }

    /** Força o reenvio da leitura atual (botão do card; "sincronizar agora" pedido pelo relógio). */
    suspend fun resendCurrent(): Boolean {
        val reading = repository.currentReading.value ?: return false
        lastSentEpochMs = 0L
        onNewReading(reading)
        return lastSentEpochMs == reading.epochMs
    }

    suspend fun status(): WearStatus = runCatching {
        val connected = withTimeoutOrNull(5_000) { nodeClient.connectedNodes.await() }
            ?: return WearStatus.NoWatch
        if (connected.isEmpty()) return WearStatus.NoWatch
        val withApp = watchNodesWithApp().orEmpty()
        val node = withApp.firstOrNull()
        if (node != null) WearStatus.Ready(node.second)
        else WearStatus.AppMissing(connected.first().displayName)
    }.getOrElse {
        Log.i(tag, "Wearable indisponível: ${it.message}")
        WearStatus.Unavailable
    }

    /** Algum relógio conectado tem Watch Face Push (Wear OS 6+ com o GluView)? */
    suspend fun watchSupportsFacePush(): Boolean = runCatching {
        val info = withTimeoutOrNull(5_000) {
            capabilityClient.getCapability(WearPaths.CAPABILITY_FACE_PUSH, CapabilityClient.FILTER_REACHABLE).await()
        }
        info?.nodes?.any { it.isNearby } == true
    }.getOrDefault(false)

    /**
     * Plano 16, W3: entrega o visor (APK validado + token) ao relógio como Asset em
     * [WearPaths.WATCHFACE] e espera a resposta em [WearPaths.WATCHFACE_ACK]. null = sem resposta
     * no prazo (o relógio pode instalar depois, quando o Data Layer entregar).
     */
    suspend fun sendFace(apk: java.io.File, token: String, timeoutMs: Long = 60_000): FaceAck? {
        val answer = kotlinx.coroutines.CompletableDeferred<FaceAck>()
        val listener = com.google.android.gms.wearable.MessageClient.OnMessageReceivedListener { event ->
            if (event.path == WearPaths.WATCHFACE_ACK) FaceAck.decode(event.data)?.let { answer.complete(it) }
        }
        messageClient.addListener(listener).await()
        try {
            val request = PutDataMapRequest.create(WearPaths.WATCHFACE).apply {
                dataMap.putAsset(WearPaths.ASSET_FACE, Asset.createFromBytes(apk.readBytes()))
                dataMap.putString(WearPaths.K_FACE_TOKEN, token)
                dataMap.putLong(WearPaths.K_FACE_SENT_AT, System.currentTimeMillis())
            }.asPutDataRequest().setUrgent()
            dataClient.putDataItem(request).await()
            return withTimeoutOrNull(timeoutMs) { answer.await() }
        } finally {
            messageClient.removeListener(listener)
        }
    }

    /** (id, nome) dos relógios conectados que anunciam o GluView; null se a API não existe aqui. */
    private suspend fun watchNodesWithApp(): List<Pair<String, String>>? = runCatching {
        val info = withTimeoutOrNull(5_000) {
            capabilityClient.getCapability(WearPaths.CAPABILITY, CapabilityClient.FILTER_REACHABLE).await()
        } ?: return emptyList()
        info.nodes.filter { it.isNearby }.map { it.id to it.displayName }
    }.getOrNull()

    private suspend fun sendReading(reading: GlucoseReading) {
        val limits = prefs.glucoseThresholds.first()
        val unit = GlucoseUnit.fromString(prefs.glucoseUnit.first())
        val snapshot = buildSnapshot(
            reading, limits, unit,
            use24h = prefs.timeFormat.first() != "H12",
            staleAfterMin = prefs.sensorNoSignalMin.first(),
        )
        val graphPng = renderGraph(limits, unit)

        val request = PutDataMapRequest.create(WearPaths.READING).apply {
            snapshot.toMap().forEach { (k, v) ->
                when (v) {
                    is Int -> dataMap.putInt(k, v)
                    is Long -> dataMap.putLong(k, v)
                    is Boolean -> dataMap.putBoolean(k, v)
                    is String -> dataMap.putString(k, v)
                }
            }
            graphPng?.let { dataMap.putAsset(WearPaths.ASSET_GRAPH, Asset.createFromBytes(it)) }
        }.asPutDataRequest().setUrgent()
        dataClient.putDataItem(request).await()
    }

    private suspend fun sendToken(nodes: List<Pair<String, String>>) {
        val token = currentToken()
        val bytes = WearToken.encode(token)
        nodes.forEach { (id, _) -> messageClient.sendMessage(id, WearPaths.TOKEN, bytes).await() }
    }

    private suspend fun currentToken(): WearToken {
        if (prefs.localOnlyMode.first()) return WearToken.CLEARED
        val token = prefs.accessToken.first()
        val expires = prefs.accessTokenExpiresAtMs.first()
        if (token.isBlank() || expires <= 0L) return WearToken.CLEARED
        val baseUrl = prefs.baseUrl.first().ifBlank { GluSyncConstants.BASE_URL }
        return WearToken(token, baseUrl.trimEnd('/'), expires)
    }

    /** Minigráfico das últimas 3 h, 400×160, fundo preto (variante WATCH). Falha → sem gráfico. */
    private suspend fun renderGraph(limits: GlucoseThresholds, unit: GlucoseUnit): ByteArray? = runCatching {
        val fromMs = System.currentTimeMillis() - GRAPH_HOURS * 3_600_000L
        val calFactor = prefs.sibionicsCalFactor.first()
        val mode = GlucoseDisplayMode.fromId(prefs.glucoseDisplayMode.first())
        val points = repository.getRecentReadingsForNotification(fromMs)
            .map { it.toGraphReading(calFactor) }.applyDisplayMode(mode)
        if (points.isEmpty()) return@runCatching null
        val bmp = GlucoseGraphRenderer.renderWatchface(
            points, GRAPH_W, GRAPH_H, GRAPH_HOURS,
            hipo = limits.low, hiper = limits.high, unit = unit,
            urgentLow = limits.urgentLow, urgentHigh = limits.urgentHigh,
        )
        ByteArrayOutputStream().use { out ->
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            bmp.recycle()
            out.toByteArray()
        }
    }.getOrNull()

    companion object {
        private const val GRAPH_HOURS = 3
        private const val GRAPH_W = 400
        private const val GRAPH_H = 160

        /** Leitura do app → contrato. Puro, testado em WearBridgeTest. */
        fun buildSnapshot(
            reading: GlucoseReading,
            limits: GlucoseThresholds,
            unit: GlucoseUnit,
            use24h: Boolean,
            staleAfterMin: Int,
        ) = WatchFaceSnapshot(
            sgvMgdl = reading.sgvMgDl.roundToInt(),
            deltaMgdl = reading.delta,
            direction = nightscoutDirection(reading.direction.ifBlank { reading.arrow }),
            measuredAtMs = reading.epochMs,
            zone = zoneOf(reading, limits),
            urgentLowMgdl = limits.urgentLow.roundToInt(),
            lowMgdl = limits.low.roundToInt(),
            highMgdl = limits.high.roundToInt(),
            urgentHighMgdl = limits.urgentHigh.roundToInt(),
            unit = if (unit == GlucoseUnit.MMOL_L) SnapshotUnit.MMOL_L else SnapshotUnit.MG_DL,
            use24h = use24h,
            source = SnapshotSource.PHONE,
            staleAfterMin = staleAfterMin.coerceIn(1, 120),
        )

        /**
         * O app guarda a direção ora como nome Nightscout ("Flat", fontes BLE/entries), ora como a
         * seta ("→", "↓↓", caminho da nuvem). O contrato usa sempre o nome Nightscout.
         */
        fun nightscoutDirection(raw: String): String = when (raw.trim().lowercase()) {
            "doubleup", "↑↑", "⇈" -> "DoubleUp"
            "singleup", "↑" -> "SingleUp"
            "fortyfiveup", "↗" -> "FortyFiveUp"
            "flat", "→" -> "Flat"
            "fortyfivedown", "↘" -> "FortyFiveDown"
            "singledown", "↓" -> "SingleDown"
            "doubledown", "↓↓", "⇊" -> "DoubleDown"
            else -> "NONE"
        }

        // STALE não vai para o relógio: lá a idade é recalculada pelo relógio do próprio relógio.
        private fun zoneOf(reading: GlucoseReading, limits: GlucoseThresholds): SnapshotZone {
            val zone = if (reading.zone == GlucoseZone.STALE) limits.classify(reading.sgvMgDl, reading.arrow) else reading.zone
            return when (zone) {
                GlucoseZone.URGENT_LOW -> SnapshotZone.URGENT_LOW
                GlucoseZone.LOW -> SnapshotZone.LOW
                GlucoseZone.HIGH -> SnapshotZone.HIGH
                GlucoseZone.URGENT_HIGH -> SnapshotZone.URGENT_HIGH
                GlucoseZone.QUICK_DROP -> SnapshotZone.QUICK_DROP
                GlucoseZone.NORMAL, GlucoseZone.STALE -> SnapshotZone.NORMAL
            }
        }
    }
}
