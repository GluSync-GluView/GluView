package com.gluview.app.data.medlevensohn

import android.content.Context
import com.gluview.app.R
import com.gluview.app.data.accuchek.cgm.SmartGuideRules
import com.gluview.app.data.datastore.AppPreferences
import com.gluview.app.data.repository.GlucoseRepository
import com.gluview.app.data.repository.SensorHistoryRepository
import com.gluview.app.data.sibionics.SibionicsStatusHolder
import com.gluview.app.data.source.DataSourceType
import com.gluview.app.data.source.GlucoseSource
import com.gluview.app.notification.NotificationHelper
import com.gluview.app.util.AppLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fonte de glicemia do Smart 2.0 (MedLevensohn) / AiDEX X / LinX / Lumi.
 *
 * - Horário: início do sensor vindo do próprio sensor (0x121, [MedLevState.startSec]) + minutos da
 *   leitura; sem hora conhecida não grava (Juggluco `saveGlucose`).
 * - Histórico: a sessão pede e pagina sozinha ([MedLevensohnSession]); aqui só grava.
 * - Seta: tendência do sensor (décimos de mg/dL/min) com os cortes do Juggluco.
 * - Sensor encerrado (tipo 3): "Sensor expirado" na Home + notificação.
 * - Estado (chave-mestra, início, último minuto) persistido a cada mudança.
 *
 * Até o teste de campo, segue "Em breve" (doc `Projeto DM1/GluView/13_medlevensohn_sibionics_sa_lacunas.md`).
 */
@Singleton
class MedLevensohnSource @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ble: MedLevensohnBleManager,
    private val repository: GlucoseRepository,
    private val statusHolder: SibionicsStatusHolder,
    private val preferences: AppPreferences,
    private val sensorHistory: SensorHistoryRepository,
    private val notificationHelper: NotificationHelper,
    private val appLogger: AppLogger
) : MedLevensohnBleManager.Listener, GlucoseSource {

    private companion object {
        const val LOG = "MEDLEV_SRC"
        const val MMOL_TO_MGDL = 18.0182
        val LIVE_MAX_AGE_MS = TimeUnit.MINUTES.toMillis(6)
        /** Primeira tentativa rápida: a sessão desconecta de propósito (ex.: para voltar já pareada). */
        val RECONNECT_SCHEDULE_MS = buildList {
            add(TimeUnit.SECONDS.toMillis(5))
            repeat(4) { add(TimeUnit.MINUTES.toMillis(1)) }
            repeat(3) { add(TimeUnit.MINUTES.toMillis(5)) }
            repeat(4) { add(TimeUnit.MINUTES.toMillis(10)) }
        }
        const val GATT_HANDSHAKE_MS = 30_000L
    }

    @Volatile private var scope: CoroutineScope? = null
    @Volatile private var reconnectJob: Job? = null
    @Volatile private var stopped = false
    @Volatile private var serial = ""
    @Volatile private var mac = ""
    @Volatile private var lastSgv = -1
    @Volatile private var knownStartSec = 0L
    private val mutex = Mutex()

    override fun start(type: DataSourceType) {
        if (scope != null) return
        stopped = false
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s
        s.launch {
            serial = preferences.medLevensohnSerial.first()
            mac = preferences.medLevensohnMac.first()
            val persisted = MedLevState(
                hasTime = preferences.medLevensohnHasTime.first(),
                startSec = preferences.medLevensohnStartSec.first(),
                lastReceivedId = preferences.medLevensohnLastId.first()
            )
            knownStartSec = persisted.startSec
            if (persisted.startSec > 0) statusHolder.restoreAnchor(serial, persisted.startSec * 1000) else statusHolder.resetForNewSensor(serial)
            ble.setListener(this@MedLevensohnSource)
            appLogger.usage(LOG, "Iniciando Smart 2.0 (MedLevensohn)", mapOf(
                "serialSalvo" to serial.isNotBlank(), "enderecoSalvo" to mac.isNotBlank(),
                "horaConhecida" to persisted.hasTime, "ultimoMinuto" to persisted.lastReceivedId
            ))
            if (serial.isNotBlank() && mac.isNotBlank()) {
                ble.configure(serial, preferences.medLevensohnMasterKey.first().ifBlank { null }, persisted)
                ble.connect(mac)
            } else {
                findAndConnect(persisted)
            }
        }
    }

    /** Sem sensor salvo: busca o primeiro da família, guarda serial (10 últimos do nome) e endereço. */
    private fun findAndConnect(persisted: MedLevState) {
        appLogger.usage(LOG, "Sensor não configurado — procurando AiDEX X/LinX/Lumi/Smart")
        ble.startScan(serial.ifBlank { null }) { name, foundMac, _ ->
            ble.stopScan()
            val found = MedLevensohnGatt.extractSerial(name)
            appLogger.usage(LOG, "Sensor encontrado", mapOf("nome" to name))
            val isNew = found != serial
            serial = found; mac = foundMac
            scope?.launch {
                preferences.saveMedLevensohnSensor(found, foundMac)
                val state = if (isNew) MedLevState() else persisted
                if (isNew) { knownStartSec = 0; statusHolder.resetForNewSensor(found) }
                ble.configure(found, if (isNew) null else preferences.medLevensohnMasterKey.first().ifBlank { null }, state)
                ble.connect(foundMac)
            }
        }
    }

    override fun stop() {
        appLogger.usage(LOG, "Parando Smart 2.0 (MedLevensohn)")
        stopped = true
        reconnectJob?.cancel(); reconnectJob = null
        ble.setListener(null)
        ble.disconnect()
        statusHolder.onConnectionChanged(false)
        notificationHelper.cancelSensorWarning()
        scope?.cancel(); scope = null
        lastSgv = -1
    }

    // ── Listener ─────────────────────────────────────────────────────────────────────────────────

    override fun onConnectionChanged(connected: Boolean) {
        statusHolder.onConnectionChanged(connected)
        if (connected) {
            reconnectJob?.cancel(); reconnectJob = null
            if (!statusHolder.status.value.sessionEnded) notificationHelper.cancelSensorWarning()
            scope?.launch { registerSensorPeriodIfNeeded() }
        } else if (!stopped && reconnectJob?.isActive != true) {
            startReconnect()
        }
    }

    override fun onMasterKey(masterKeyHex: String) {
        scope?.launch { preferences.saveMedLevensohnMasterKey(masterKeyHex) }
    }

    override fun onStateChanged(state: MedLevState) {
        val s = scope ?: return
        s.launch {
            preferences.saveMedLevensohnProgress(state.startSec, state.lastReceivedId, state.hasTime)
            if (state.startSec > 0 && state.startSec != knownStartSec) {
                appLogger.usage(LOG, "Início do sensor definido pelo sensor", mapOf("inicioSec" to state.startSec, "anterior" to knownStartSec))
                knownStartSec = state.startSec
                statusHolder.restoreAnchor(serial, state.startSec * 1000)
            }
        }
    }

    override fun onNotice(notice: MedLevNotice) {
        when (notice) {
            MedLevNotice.SENSOR_ENDED -> {
                statusHolder.onSensorLifetime(sessionEnded = true)
                notificationHelper.showSensorWarning(
                    context.getString(R.string.medlev_warn_ended_title), context.getString(R.string.medlev_warn_ended_body))
            }
            MedLevNotice.UNPAIRED, MedLevNotice.CLEARED -> notificationHelper.showSensorWarning(
                context.getString(R.string.medlev_warn_unpaired_title), context.getString(R.string.medlev_warn_unpaired_body))
            MedLevNotice.UNPAIR_FAILED, MedLevNotice.CLEAR_FAILED -> notificationHelper.showSensorWarning(
                context.getString(R.string.medlev_warn_unpair_failed_title), context.getString(R.string.medlev_warn_unpair_failed_body))
            MedLevNotice.START_TIME_CHANGED -> Unit
        }
    }

    override fun onRssi(rssi: Int) { statusHolder.onRssi(rssi) }

    override fun onBluetoothTurnedOn() {
        if (stopped || mac.isBlank()) return
        reconnectJob?.cancel(); reconnectJob = null
        ble.connect(mac)
    }

    override fun onReadings(readings: List<MedLevReading>, state: MedLevState) {
        val s = scope ?: return
        s.launch { mutex.withLock { persist(readings, state) } }
    }

    // ── Persistência ─────────────────────────────────────────────────────────────────────────────

    private suspend fun persist(readings: List<MedLevReading>, state: MedLevState) {
        if (state.startSec <= 0) return
        val now = System.currentTimeMillis()
        val newestId = readings.maxOf { it.id }
        for (r in readings.sortedBy { it.id }) {
            val atMs = MedLevensohnSession.readingTimeMs(state, r.id, now)
            val isCurrent = r.live && r.id == newestId && now - atMs <= LIVE_MAX_AGE_MS
            val delta = if (lastSgv >= 0) r.mgdl - lastSgv else 0
            val mmol = r.mgdl / MMOL_TO_MGDL
            repository.storeExternalReading(
                dateMs       = atMs,
                sgvMgDl      = r.mgdl.toDouble(),
                arrow        = r.trendTenths?.let { SmartGuideRules.arrowFor(it / 10f) } ?: arrowFromDelta(delta),
                delta        = delta,
                makeCurrent  = isCurrent,
                rawMmol      = mmol,
                calibFactor  = 1.0,
                sensorMac    = mac,
                sensorAgeMin = r.id,
                confirmed    = null
            )
            lastSgv = r.mgdl
            if (isCurrent) {
                statusHolder.onRawMmol(mmol)
                statusHolder.onReading(r.id, state.startSec * 1000, atMs)
            }
        }
        appLogger.usage(LOG, "Leituras gravadas", mapOf("quantidade" to readings.size, "ultimoMinuto" to newestId))
    }

    /** Período no histórico de sensores (Room + GluSync) quando o ativo não é este sensor. */
    private suspend fun registerSensorPeriodIfNeeded() {
        val active = sensorHistory.observeActive().first()
        val source = DataSourceType.MEDLEVENSOHN_SMART2.id
        if (active != null && active.source == source && active.serial == serial) return
        runCatching { sensorHistory.startNew(mac, serial.ifBlank { null }, null, source) }
            .onFailure { appLogger.error(LOG, "Falha ao registrar sensor no histórico") }
    }

    private fun arrowFromDelta(delta: Int): String = when {
        delta >= 15 -> "↑↑"; delta >= 7 -> "↑"; delta >= 3 -> "↗"
        delta > -3 -> "→"; delta > -7 -> "↘"; delta > -15 -> "↓"; else -> "↓↓"
    }

    // ── Reconexão ────────────────────────────────────────────────────────────────────────────────

    private fun startReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope?.launch {
            RECONNECT_SCHEDULE_MS.forEachIndexed { idx, delayMs ->
                delay(delayMs)
                if (!isActive || ble.isConnected) return@launch
                appLogger.usage(LOG, "Tentativa de reconexão", mapOf("tentativa" to "${idx + 1}/${RECONNECT_SCHEDULE_MS.size}"))
                if (mac.isNotBlank()) ble.connect(mac)
                delay(GATT_HANDSHAKE_MS)
                if (!isActive || ble.isConnected) return@launch
            }
            if (!stopped) notificationHelper.showSensorWarning(
                context.getString(R.string.medlev_warn_disconnected_title), context.getString(R.string.medlev_warn_disconnected_body))
        }
    }

    /** Desparear o sensor (para usar em outro celular): o comando vai na próxima conexão. */
    fun requestUnpair() {
        ble.requestUnpair(clear = false)
        if (!ble.isConnected && mac.isNotBlank()) ble.connect(mac) else ble.disconnect()
    }
}
