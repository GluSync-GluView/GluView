package com.gluview.app.data.libre3

import com.gluview.app.data.datastore.AppPreferences
import com.gluview.app.data.repository.GlucoseRepository
import com.gluview.app.data.sibionics.SibionicsStatusHolder
import com.gluview.app.data.source.DataSourceType
import com.gluview.app.data.source.GlucoseSource
import com.gluview.app.notification.NotificationHelper
import com.gluview.app.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fonte FreeStyle Libre 3 (plano 21, F6.1 refeita em 21/11/2026): liga a sessão salva na ativação
 * NFC ([Libre3NfcActivator]) ao Bluetooth ([Libre3BleManager]) e grava as leituras no mesmo caminho
 * das outras fontes (`storeExternalReading` + [SibionicsStatusHolder]).
 *
 * Linha do tempo = hora da ativação + lifeCount minutos (Juggluco `lifeCount2time`). A leitura do
 * minuto usa a hora de chegada se estiver a até 15 s da esperada (Juggluco `save3current`); senão,
 * a esperada. Histórico (5 em 5 min) e dados clínicos (backfill de 1 min) entram sem virar "atual".
 *
 * Contadores do backfill seguem o "consecutivo" do Juggluco: o último minuto só avança se o
 * anterior já estava salvo — um buraco fica para o pedido de dados clínicos preencher.
 */
@Singleton
class Libre3Source @Inject constructor(
    private val ble: Libre3BleManager,
    private val repository: GlucoseRepository,
    private val statusHolder: SibionicsStatusHolder,
    private val preferences: AppPreferences,
    private val notificationHelper: NotificationHelper,
    private val appLogger: AppLogger
) : GlucoseSource, Libre3BleManager.Listener, Libre3BleManager.Counters {

    private companion object {
        const val LOG = "LIBRE3_SRC"
        /** 5, 10, 20 s e depois a cada 60 s (Juggluco: recuo 5→10→20 s; sensor manda 1 leitura/min). */
        val RETRY_MS = longArrayOf(5_000L, 10_000L, 20_000L)
        const val RETRY_STEADY_MS = 60_000L
        const val WARN_AFTER_MS = 30 * 60_000L
    }

    @Volatile private var scope: CoroutineScope? = null
    @Volatile private var reconnectJob: Job? = null
    @Volatile private var stopped = true

    @Volatile private var mac = ""
    @Volatile private var pin = ByteArray(4)
    @Volatile private var startMs = 0L
    @Volatile private var lastSgv = -1
    @Volatile private var lastConnectedMs = 0L
    private val failures = AtomicInteger(0)
    private val lastLife = AtomicInteger(0)
    private val lastHistoric = AtomicInteger(0)
    private val lastEvent = AtomicInteger(0)

    override fun start(type: DataSourceType) {
        if (scope != null) return
        stopped = false
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s
        s.launch {
            if (!Libre3Native.init(appLogger)) {
                appLogger.error(LOG, "Libre 3 indisponível: biblioteca nativa não carregou neste aparelho")
                return@launch
            }
            val serial = preferences.libre3Serial.first()
            mac = preferences.libre3Mac.first()
            pin = hexToBytes(preferences.libre3Pin.first())
            startMs = preferences.libre3SensorStartMs.first()
            lastLife.set(preferences.libre3LastLifeCount.first())
            lastHistoric.set(preferences.libre3LastHistoric.first())
            lastEvent.set(preferences.libre3LastEvent.first())
            if (serial.isBlank() || mac.isBlank() || pin.size != 4 || startMs <= 0L) {
                appLogger.error(LOG, "Sem sessão do Libre 3: encoste o celular no sensor (ativação por NFC)")
                return@launch
            }
            if (startMs > 0L) statusHolder.update { it.copy(sensorStartMs = startMs, serial = serial) }
            ble.setListener(this@Libre3Source, this@Libre3Source)
            connectNow()
        }
    }

    override fun stop() {
        stopped = true
        reconnectJob?.cancel(); reconnectJob = null
        ble.setListener(null, null)
        ble.disconnect()
        statusHolder.onConnectionChanged(false)
        notificationHelper.cancelSensorWarning()
        scope?.cancel(); scope = null
        lastSgv = -1
        synchronized(storedLives) { storedLives.clear() }
    }

    private suspend fun connectNow() {
        val kAuthHex = preferences.libre3SavedAuth.first()
        val kAuth = kAuthHex.takeIf { it.length == Libre3Native.LEN_SAVED_AUTHORIZATION * 2 }?.let(::hexToBytes)
        ble.connect(mac, pin, kAuth)
    }

    private fun scheduleReconnect() {
        if (stopped) return
        reconnectJob?.cancel()
        reconnectJob = scope?.launch {
            val n = failures.getAndIncrement()
            val wait = if (n < RETRY_MS.size) RETRY_MS[n] else RETRY_STEADY_MS
            delay(wait)
            if (!isActive || stopped) return@launch
            if (lastConnectedMs > 0 && System.currentTimeMillis() - lastConnectedMs > WARN_AFTER_MS) {
                notificationHelper.showSensorWarning(
                    "Libre 3 sem conexão",
                    "O GluView não fala com o sensor há mais de 30 minutos. Deixe o celular perto do sensor e o Bluetooth ligado."
                )
            }
            connectNow()
        }
    }

    // ------------------------------------------------------------------ Listener
    override fun onConnectionChanged(connected: Boolean) {
        statusHolder.onConnectionChanged(connected)
        if (connected) {
            failures.set(0)
            lastConnectedMs = System.currentTimeMillis()
            reconnectJob?.cancel(); reconnectJob = null
            notificationHelper.cancelSensorWarning()
        } else scheduleReconnect()
    }

    override fun onFailure(reason: String) {
        appLogger.error(LOG, "Libre 3: $reason")
        scheduleReconnect()
    }

    override fun onRssi(rssi: Int) = statusHolder.onRssi(rssi)

    override fun onAuthorized(kAuth: ByteArray) {
        scope?.launch {
            preferences.saveLibre3SavedAuth(bytesToHex(kAuth))
            preferences.saveLibre3StreamingValid(true)
        }
    }

    override fun onSavedAuthorizationRejected() {
        appLogger.error(LOG, "O sensor recusou a autorização salva; a próxima conexão faz autorização nova")
        scope?.launch { preferences.saveLibre3SavedAuth("") }
    }

    override fun onMinute(minute: Libre3Protocol.OneMinute, receivedAtMs: Long) {
        scope?.launch {
            val expected = timeOf(minute.lifeCount)
            val at = if (receivedAtMs - expected > 15_000L) expected else receivedAtMs
            val mgdl = minute.currentMgDl
            if (mgdl != null) {
                persist(mgdl, at, minute.lifeCount, arrowFor(minute), makeCurrent = true)
            } else {
                appLogger.usage(LOG, "Minuto sem glicemia válida (aquecimento ou erro do sensor)",
                    mapOf("lifeCount" to minute.lifeCount, "valor" to minute.uncappedCurrentMgDl))
            }
            advanceLife(minute.lifeCount)
            minute.historicMgDl?.let { persist(it, timeOf(minute.historicalLifeCount), minute.historicalLifeCount, "", makeCurrent = false) }
            if (minute.historicalLifeCount > 0) advanceHistoric(minute.historicalLifeCount)
            saveCounters()
        }
    }

    override fun onHistory(entries: List<Pair<Int, Int>>) {
        scope?.launch {
            for ((life, mgdl) in entries) {
                persist(mgdl, timeOf(life), life, "", makeCurrent = false)
                advanceHistoricMax(life)
            }
            saveCounters()
        }
    }

    override fun onClinical(fast: Libre3Protocol.FastData) {
        scope?.launch {
            fast.current?.let { persist(it, timeOf(fast.lifeCount), fast.lifeCount, "", makeCurrent = false) }
            if (fast.lifeCount >= 0) advanceLifeMax(fast.lifeCount)
            fast.historic?.let { if (fast.historicLifeCount >= 0) { persist(it, timeOf(fast.historicLifeCount), fast.historicLifeCount, "", false); advanceHistoric(fast.historicLifeCount) } }
            saveCounters()
        }
    }

    override fun onPatchStatus(status: Libre3Protocol.PatchStatus) {
        appLogger.usage(LOG, "Status do sensor", mapOf(
            "estado" to status.patchState, "lifeCount" to status.currentLifeCount, "eventos" to status.index + 1))
    }

    override fun onEvents(lastIndex: Int) {
        lastEvent.set(lastIndex)
        scope?.launch { preferences.saveLibre3Counters(lastEvent = lastIndex) }
    }

    // ------------------------------------------------------------------ Counters (thread do BLE)
    override fun nextLifeCountWanted(): Int = lastLife.get() + 1
    override fun lastHistoricLifeCount(): Int = lastHistoric.get()
    override fun lastEventReceived(): Int = lastEvent.get()

    /** Minuto ao vivo: só avança se for o seguinte (Juggluco `consecutivelifecount`, começa em 0). */
    private fun advanceLife(life: Int) {
        lastLife.updateAndGet { cur -> if (life == cur + 1) life else cur }
    }
    /** Dado clínico do backfill: avança pelo máximo (Juggluco `fastupdatelifecount`). */
    private fun advanceLifeMax(life: Int) {
        lastLife.updateAndGet { cur -> maxOf(cur, life) }
    }
    /** Histórico do minuto: consecutivo de 5 em 5 (Juggluco `consecutivehistorylifecount`). */
    private fun advanceHistoric(life: Int) {
        lastHistoric.updateAndGet { cur -> if (life == cur + Libre3Protocol.HISTORY_INTERVAL_MIN) life else cur }
    }
    /** Lote de histórico pedido: avança pelo máximo (Juggluco `updateHistorylifecount`). */
    private fun advanceHistoricMax(life: Int) {
        lastHistoric.updateAndGet { cur -> maxOf(cur, life) }
    }
    private suspend fun saveCounters() = preferences.saveLibre3Counters(lastLife.get(), lastHistoric.get())

    // ------------------------------------------------------------------ gravação
    private fun timeOf(lifeCount: Int): Long = startMs + lifeCount * 60_000L

    /**
     * Um lifeCount = uma leitura (no Juggluco cada minuto tem uma posição só). Sem isto, o mesmo
     * minuto entrava duas vezes — com a hora de chegada (ao vivo) e com a hora esperada (reenvio
     * depois de reconectar, ou backfill que cobre um minuto já recebido). Achado com o simulador.
     */
    private val storedLives = LinkedHashSet<Int>()
    private fun firstTime(lifeCount: Int): Boolean = synchronized(storedLives) {
        if (!storedLives.add(lifeCount)) return false
        if (storedLives.size > 4000) storedLives.remove(storedLives.first())
        true
    }

    private suspend fun persist(mgdl: Int, atMs: Long, lifeCount: Int, arrow: String, makeCurrent: Boolean) {
        if (!firstTime(lifeCount)) return
        val ageMin = lifeCount.coerceAtLeast(0)
        val delta = if (makeCurrent && lastSgv >= 0) mgdl - lastSgv else 0
        repository.storeExternalReading(
            dateMs = atMs,
            sgvMgDl = mgdl.toDouble(),
            arrow = arrow,
            delta = delta,
            makeCurrent = makeCurrent,
            rawMmol = mgdl / 18.0182,
            calibFactor = 1.0,
            sensorMac = mac,
            sensorAgeMin = ageMin,
            confirmed = null
        )
        if (makeCurrent) {
            lastSgv = mgdl
            statusHolder.onReading(ageMin, startMs, atMs)
        }
    }

    /** Seta do próprio sensor (1..5, a mesma escala de `trend2rate` do Juggluco); sem ela, pela taxa. */
    private fun arrowFor(m: Libre3Protocol.OneMinute): String = when (m.trend) {
        1 -> "↓"
        2 -> "↘"
        3 -> "→"
        4 -> "↗"
        5 -> "↑"
        else -> m.rateMgDlPerMin?.let { r ->
            when { r <= -2 -> "↓"; r <= -1 -> "↘"; r < 1 -> "→"; r < 2 -> "↗"; else -> "↑" }
        } ?: ""
    }

    private fun hexToBytes(hex: String): ByteArray =
        if (hex.length % 2 != 0) ByteArray(0) else ByteArray(hex.length / 2) { hex.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    private fun bytesToHex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
}
