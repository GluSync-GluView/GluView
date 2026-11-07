package com.gluview.app.data.accuchek

import com.gluview.app.data.accuchek.cgm.CgmFeature
import com.gluview.app.data.accuchek.cgm.CgmMeasurement
import com.gluview.app.data.accuchek.cgm.CgmStatus
import com.gluview.app.data.accuchek.cgm.Racp
import com.gluview.app.data.accuchek.cgm.SessionRunTime
import com.gluview.app.data.accuchek.cgm.SessionStartTime
import com.gluview.app.data.accuchek.cgm.SmartGuideClock
import com.gluview.app.data.accuchek.cgm.SmartGuidePackageParser
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fonte de glicemia do Accu-Chek SmartGuide (serviço CGM 0x181F) — mesmo papel do
 * [com.gluview.app.data.libre2.Libre2Source]: conecta, reconecta, data as leituras e as entrega ao
 * pipeline comum (`storeExternalReading` + [SibionicsStatusHolder]).
 *
 * Horários: o sensor manda "minutos desde o início"; o início vem do CGM Status
 * ([SmartGuideClock]). Leituras que chegam antes do status ficam esperando por ele.
 * Histórico: ao ficar pronto, pede ao sensor tudo depois do último minuto salvo (o sensor guarda
 * 8 h); repetidas são ignoradas pelo minuto.
 *
 * F2 do plano `12_sprint_accuchek_smartguide.md`. Ficam para a F3: LO/HI/erro na tela, avisos do
 * sensor (sessão encerrada, defeito, temperatura), aquecimento de 60 min, dias restantes e histórico
 * de sensores no GluSync. Até a validação com o sensor real (F5) a fonte continua "Em breve".
 */
@Singleton
class AccuChekSource @Inject constructor(
    private val ble: AccuChekBleManager,
    private val repository: GlucoseRepository,
    private val statusHolder: SibionicsStatusHolder,
    private val preferences: AppPreferences,
    private val notificationHelper: NotificationHelper,
    private val appLogger: AppLogger
) : AccuChekBleManager.Listener, GlucoseSource {

    private companion object {
        const val LOG = "ACCUCHEK_SRC"
        const val MMOL_TO_MGDL = 18.0182
        val PLAUSIBLE_MGDL = 20..600
        /** Leitura mais velha que isto não vira a "atual" (é histórico). */
        val CURRENT_MAX_AGE_MS = TimeUnit.MINUTES.toMillis(6)
        val RECONNECT_SCHEDULE_MS = buildList {
            repeat(5) { add(TimeUnit.MINUTES.toMillis(1)) }
            repeat(3) { add(TimeUnit.MINUTES.toMillis(5)) }
            repeat(4) { add(TimeUnit.MINUTES.toMillis(10)) }
        }
        const val GATT_HANDSHAKE_MS = 40_000L
    }

    @Volatile private var scope: CoroutineScope? = null
    @Volatile private var reconnectJob: Job? = null
    @Volatile private var stopped = false

    @Volatile private var serial: String = ""
    @Volatile private var address: String = ""
    @Volatile private var startMs: Long = 0L
    @Volatile private var lastMinute: Int = -1
    @Volatile private var lastSgv: Int = -1

    private val mutex = Mutex()
    /** Leituras que chegaram antes do CGM Status (sem início conhecido ainda não dá para datar). */
    private val pending = mutableListOf<CgmMeasurement>()

    override fun start(type: DataSourceType) {
        if (scope != null) return
        stopped = false
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s
        s.launch {
            serial = preferences.accuChekSerial.first()
            address = preferences.accuChekMac.first()
            startMs = preferences.accuChekStartMs.first()
            lastMinute = preferences.accuChekLastMinute.first()
            ble.setPairingPin(preferences.accuChekPin.first())
            ble.setListener(this@AccuChekSource)
            if (startMs > 0) statusHolder.restoreAnchor(serial, startMs) else statusHolder.resetForNewSensor(serial)
            appLogger.usage(LOG, "Iniciando Accu-Chek SmartGuide", mapOf(
                "serialConfigurado" to serial.isNotBlank(), "enderecoSalvo" to address.isNotBlank(),
                "ultimoMinuto" to lastMinute, "inicioConhecido" to (startMs > 0)
            ))
            if (serial.isBlank()) {
                appLogger.usage(LOG, "Sem serial configurado — aceita o primeiro sensor AC- encontrado")
            }
            ble.connectToSensor(serial, address)
        }
    }

    override fun stop() {
        appLogger.usage(LOG, "Parando Accu-Chek SmartGuide")
        stopped = true
        reconnectJob?.cancel(); reconnectJob = null
        ble.setListener(null)
        ble.disconnect()
        statusHolder.onConnectionChanged(false)
        notificationHelper.cancelSensorWarning()
        scope?.cancel(); scope = null
        synchronized(pending) { pending.clear() }
        lastSgv = -1
    }

    // ── Conexão e pareamento ─────────────────────────────────────────────────────────────────────

    override fun onConnectionChanged(connected: Boolean) {
        statusHolder.onConnectionChanged(connected)
        if (connected) {
            reconnectJob?.cancel(); reconnectJob = null
            notificationHelper.cancelSensorWarning()
        } else if (!stopped && reconnectJob?.isActive != true) {
            appLogger.usage(LOG, "Sensor desconectado — agendando reconexão")
            startReconnect()
        }
    }

    override fun onPairingStateChanged(state: AccuChekBleManager.PairingState) {
        appLogger.usage(LOG, "Pareamento", mapOf("estado" to state.name))
        when (state) {
            AccuChekBleManager.PairingState.FAILED -> notificationHelper.showSensorWarning(
                "Pareamento do SmartGuide falhou",
                "Confira o PIN de 6 dígitos da tampa do aplicador e deixe o celular perto do sensor."
            )
            AccuChekBleManager.PairingState.LOST -> notificationHelper.showSensorWarning(
                "SmartGuide pareado em outro aparelho",
                "O sensor só aceita um celular por vez. Pareie de novo neste celular para voltar a receber leituras."
            )
            else -> Unit
        }
    }

    override fun onRssi(rssi: Int) { statusHolder.onRssi(rssi) }

    override fun onBluetoothTurnedOn() {
        if (stopped) return
        appLogger.usage(LOG, "Bluetooth religado — reconectando já")
        reconnectJob?.cancel(); reconnectJob = null
        ble.connectToSensor(serial, address)
    }

    override fun onReady(address: String, advertisedName: String?) {
        val s = scope ?: return
        s.launch {
            mutex.withLock {
                if (address != this@AccuChekSource.address) {
                    this@AccuChekSource.address = address
                    preferences.saveAccuChekMac(address)
                }
                if (serial.isBlank()) {
                    advertisedName?.let(SmartGuidePackageParser::normalizeSerial)?.let {
                        serial = it
                        preferences.saveAccuChekSerial(it)
                        appLogger.usage(LOG, "Serial tirado do nome Bluetooth", mapOf("nome" to advertisedName))
                    }
                }
                ble.requestHistory(SmartGuideClock.historyFromMinute(lastMinute))
            }
        }
    }

    // ── Informações do sensor ────────────────────────────────────────────────────────────────────

    override fun onFeature(feature: CgmFeature) {
        if (!feature.e2eCrcSupported) appLogger.usage(LOG, "Sensor sem E2E-CRC (inesperado no SmartGuide)")
    }

    override fun onStatus(status: CgmStatus, receivedAtMs: Long) {
        val s = scope ?: return
        s.launch {
            mutex.withLock {
                if (SmartGuideClock.isNewSession(lastMinute, status.timeOffsetMinutes)) {
                    appLogger.usage(LOG, "Sensor recomeçou a contar — tratando como sensor novo", mapOf(
                        "ultimoMinutoSalvo" to lastMinute, "minutoDoStatus" to status.timeOffsetMinutes
                    ))
                    lastMinute = -1
                    lastSgv = -1
                    startMs = 0L
                    preferences.saveAccuChekLastMinute(-1)
                    statusHolder.resetForNewSensor(serial)
                }
                val computed = SmartGuideClock.startFromStatus(receivedAtMs, status)
                val chosen = SmartGuideClock.reconcileStart(startMs, computed)
                if (chosen != startMs) {
                    appLogger.usage(LOG, "Início do sensor definido", mapOf(
                        "inicioMs" to chosen, "anteriorMs" to startMs, "minutoAtual" to status.timeOffsetMinutes
                    ))
                    startMs = chosen
                    preferences.saveAccuChekStartMs(chosen)
                    statusHolder.restoreAnchor(serial, chosen)
                }
                val waiting = synchronized(pending) { pending.toList().also { pending.clear() } }
                if (waiting.isNotEmpty()) persistAll(waiting)
            }
        }
    }

    override fun onSessionStartTime(start: SessionStartTime) {
        appLogger.usage(LOG, "Session Start Time do sensor", mapOf(
            "utcMs" to start.utcMillis, "difDoInicioCalculadoS" to if (startMs > 0) (start.utcMillis - startMs) / 1000 else "?"
        ))
    }

    override fun onSessionRunTime(runTime: SessionRunTime) {
        appLogger.usage(LOG, "Vida útil do sensor", mapOf("horas" to runTime.hours))
    }

    override fun onDeviceInfo(serial: String?, firmware: String?) {
        serial?.let { appLogger.usage(LOG, "Serial lido do sensor", mapOf("serialSensor" to it, "serialEmbalagem" to this.serial)) }
        firmware?.let { appLogger.usage(LOG, "Firmware do sensor", mapOf("firmware" to it)) }
    }

    // ── Leituras ─────────────────────────────────────────────────────────────────────────────────

    override fun onMeasurements(records: List<CgmMeasurement>, receivedAtMs: Long) {
        val s = scope ?: return
        s.launch {
            mutex.withLock {
                if (startMs <= 0L) {
                    synchronized(pending) { pending.addAll(records) }
                    appLogger.usage(LOG, "Leituras aguardando o início do sensor", mapOf("quantidade" to records.size))
                    return@withLock
                }
                persistAll(records)
            }
        }
    }

    override fun onHistoryComplete(response: Racp.Response.Code) {
        appLogger.usage(LOG, "Histórico concluído", mapOf(
            "sucesso" to response.isSuccess, "semRegistros" to response.isNoRecords,
            "codigo" to response.code, "ultimoMinuto" to lastMinute
        ))
    }

    /** Chamar com [mutex] travado e [startMs] conhecido. */
    private suspend fun persistAll(records: List<CgmMeasurement>) {
        val now = System.currentTimeMillis()
        val fresh = records.filter { it.timeOffsetMinutes > lastMinute }.sortedBy { it.timeOffsetMinutes }
        if (fresh.isEmpty()) return
        val newestMinute = fresh.last().timeOffsetMinutes
        for (m in fresh) {
            val atMs = SmartGuideClock.readingTimeMs(startMs, m.timeOffsetMinutes, now)
            if (!m.hasValue) {
                // F3: LO/HI/erro aparecerem na tela e nos alertas.
                appLogger.usage(LOG, "Leitura sem valor numérico", mapOf(
                    "minuto" to m.timeOffsetMinutes, "lo" to m.isLow, "hi" to m.isHigh, "avisos" to m.annunciation.toString()
                ))
                continue
            }
            val mgdl = m.glucoseMgDl
            if (mgdl.toInt() !in PLAUSIBLE_MGDL) {
                appLogger.usage(LOG, "Leitura descartada (fora da faixa)", mapOf("mgdl" to mgdl, "minuto" to m.timeOffsetMinutes))
                continue
            }
            val isNewest = m.timeOffsetMinutes == newestMinute && now - atMs <= CURRENT_MAX_AGE_MS
            val delta = if (lastSgv >= 0) mgdl.toInt() - lastSgv else 0
            val mmol = mgdl / MMOL_TO_MGDL
            repository.storeExternalReading(
                dateMs       = atMs,
                sgvMgDl      = mgdl.toDouble(),
                arrow        = arrowFor(m.trendMgDlPerMin, delta),
                delta        = delta,
                makeCurrent  = isNewest,
                rawMmol      = mmol,
                calibFactor  = 1.0,
                sensorMac    = address,
                sensorAgeMin = m.timeOffsetMinutes,
                confirmed    = null
            )
            lastSgv = mgdl.toInt()
            if (isNewest) {
                statusHolder.onRawMmol(mmol)
                statusHolder.onReading(m.timeOffsetMinutes, startMs, atMs)
            }
            appLogger.usage(LOG, "Glicemia salva", mapOf(
                "mgdl" to mgdl, "minuto" to m.timeOffsetMinutes, "tendencia" to (m.trendMgDlPerMin ?: "?"),
                "qualidade" to (m.qualityPercent ?: "?"), "atual" to isNewest
            ))
        }
        lastMinute = newestMinute
        preferences.saveAccuChekLastMinute(newestMinute)
    }

    /**
     * Seta pela tendência do próprio sensor (mg/dL/min), nos limites usuais de CGM (1, 2, 3 mg/dL/min).
     * Sem tendência, cai na diferença para a leitura anterior. Revisar junto com os outros sensores na F3.
     */
    private fun arrowFor(trend: Float?, delta: Int): String {
        if (trend == null || trend.isNaN() || trend.isInfinite()) return arrowFromDelta(delta)
        return when {
            trend >= 3f  -> "↑↑"
            trend >= 2f  -> "↑"
            trend >= 1f  -> "↗"
            trend > -1f  -> "→"
            trend > -2f  -> "↘"
            trend > -3f  -> "↓"
            else         -> "↓↓"
        }
    }

    private fun arrowFromDelta(delta: Int): String = when {
        delta >= 15  -> "↑↑"
        delta >= 7   -> "↑"
        delta >= 3   -> "↗"
        delta > -3   -> "→"
        delta > -7   -> "↘"
        delta > -15  -> "↓"
        else         -> "↓↓"
    }

    // ── Reconexão (mesma grade do Sibionics/Libre2) ──────────────────────────────────────────────

    private fun startReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope?.launch {
            RECONNECT_SCHEDULE_MS.forEachIndexed { idx, delayMs ->
                delay(delayMs)
                if (!isActive) return@launch
                if (ble.isReady) return@launch
                appLogger.usage(LOG, "Tentativa de reconexão", mapOf("tentativa" to "${idx + 1}/${RECONNECT_SCHEDULE_MS.size}"))
                ble.connectToSensor(serial, address)
                delay(GATT_HANDSHAKE_MS)
                if (!isActive || ble.isReady) return@launch
            }
            if (!stopped) {
                appLogger.error(LOG, "Sensor não reconectou após 60 min")
                notificationHelper.showSensorWarning(
                    "Sensor desconectado",
                    "Não foi possível reconectar ao Accu-Chek SmartGuide após 60 minutos. " +
                        "Verifique se o sensor está próximo e o Bluetooth está ativo."
                )
            }
        }
    }
}
