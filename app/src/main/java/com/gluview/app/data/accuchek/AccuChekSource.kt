package com.gluview.app.data.accuchek

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.gluview.app.R
import com.gluview.app.data.accuchek.cgm.CgmFeature
import com.gluview.app.data.accuchek.cgm.CgmMeasurement
import com.gluview.app.data.accuchek.cgm.CgmStatus
import com.gluview.app.data.accuchek.cgm.MalfunctionStreak
import com.gluview.app.data.accuchek.cgm.Racp
import com.gluview.app.data.accuchek.cgm.SensorAnnunciation
import com.gluview.app.data.accuchek.cgm.SessionRunTime
import com.gluview.app.data.accuchek.cgm.SessionStartTime
import com.gluview.app.data.accuchek.cgm.SmartGuideAlert
import com.gluview.app.data.accuchek.cgm.SmartGuideClock
import com.gluview.app.data.accuchek.cgm.SmartGuidePackageParser
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
 * Fonte de glicemia do Accu-Chek SmartGuide (serviço CGM 0x181F) — mesmo papel do
 * [com.gluview.app.data.libre2.Libre2Source]: conecta, reconecta, data as leituras e as entrega ao
 * pipeline comum (`storeExternalReading` + [SibionicsStatusHolder]).
 *
 * - Horário: o sensor manda "minutos desde o início"; o início vem do CGM Status
 *   ([SmartGuideClock]). Leituras que chegam antes do status esperam por ele.
 * - Histórico: ao ficar pronto, pede tudo depois do último minuto salvo (o sensor guarda 8 h).
 * - LO/HI/erro: [SmartGuideRules.glucoseToStore] (LO = 39 com a regra do Juggluco, HI = 401).
 * - Avisos do sensor (sessão encerrada, bateria, temperatura, calibração) viram notificação na
 *   hora em que aparecem; defeito só depois de 15 min seguidos ([MalfunctionStreak]).
 * - Aquecimento e fim de vida aparecem na Home pelo [SibionicsStatusHolder] (minutos do sensor,
 *   vida útil do Session Run Time e o bit "sessão encerrada").
 * - Histórico de sensores (local + GluSync) registra um período novo a cada sensor.
 *
 * Até a validação com o sensor real (F5 de `12_sprint_accuchek_smartguide.md`) a fonte segue "Em breve".
 */
@Singleton
class AccuChekSource @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ble: AccuChekBleManager,
    private val repository: GlucoseRepository,
    private val statusHolder: SibionicsStatusHolder,
    private val preferences: AppPreferences,
    private val sensorHistory: SensorHistoryRepository,
    private val notificationHelper: NotificationHelper,
    private val appLogger: AppLogger
) : AccuChekBleManager.Listener, GlucoseSource {

    private companion object {
        const val LOG = "ACCUCHEK_SRC"
        const val MMOL_TO_MGDL = 18.0182
        val PLAUSIBLE_MGDL = 20..600
        /** Leitura mais velha que isto é histórico: não vira a "atual" nem conta para avisos ao vivo. */
        val LIVE_MAX_AGE_MS = TimeUnit.MINUTES.toMillis(6)
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
    /** O sensor recusou este celular (pareado em outro aparelho) e ainda não voltou a parear aqui. */
    @Volatile private var pairingLost = false
    /** Âncora do relógio: hora de parede e monotônica do momento em que [startMs] foi confirmado. */
    @Volatile private var anchorWallMs = 0L
    @Volatile private var anchorElapsedMs = 0L
    private var timeChangeReceiver: BroadcastReceiver? = null

    @Volatile private var serial: String = ""
    @Volatile private var address: String = ""
    @Volatile private var startMs: Long = 0L
    @Volatile private var lastMinute: Int = -1
    /** Última leitura gravada (valida LO/HI e calcula o delta). */
    @Volatile private var previous: SmartGuideRules.Previous? = null
    /** Sensor novo detectado pelo status: abrir um período novo no histórico de sensores. */
    @Volatile private var newSensorPending = false

    private val mutex = Mutex()
    /** Leituras que chegaram antes do CGM Status (sem início conhecido ainda não dá para datar). */
    private val pending = mutableListOf<CgmMeasurement>()
    private val malfunction = MalfunctionStreak()
    /** Avisos já notificados e ainda ativos (não repete a notificação a cada leitura). */
    private val activeAlerts = mutableSetOf<SmartGuideAlert>()

    override fun start(type: DataSourceType) {
        if (scope != null) return
        stopped = false
        pairingLost = false
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
            anchorClock()
            appLogger.usage(LOG, "Iniciando Accu-Chek SmartGuide", mapOf(
                "serialConfigurado" to serial.isNotBlank(), "enderecoSalvo" to address.isNotBlank(),
                "ultimoMinuto" to lastMinute, "inicioConhecido" to (startMs > 0)
            ))
            if (!hasSensorToConnect()) {
                appLogger.usage(LOG, "Sem sensor configurado — esperando o assistente (não procura sensor desconhecido)")
                return@launch
            }
            if (serial.isBlank()) appLogger.usage(LOG, "Sem serial configurado — usa o SmartGuide já pareado neste celular")
            ble.connectToSensor(serial, address)
        }
        registerTimeChange()
    }

    override fun stop() {
        appLogger.usage(LOG, "Parando Accu-Chek SmartGuide")
        stopped = true
        reconnectJob?.cancel(); reconnectJob = null
        ble.setListener(null)
        ble.disconnect()
        statusHolder.onConnectionChanged(false)
        notificationHelper.cancelSensorWarning()
        timeChangeReceiver?.let { r -> runCatching { context.unregisterReceiver(r) } }
        timeChangeReceiver = null
        scope?.cancel(); scope = null
        synchronized(pending) { pending.clear() }
        synchronized(activeAlerts) { activeAlerts.clear() }
        malfunction.reset()
        previous = null
    }

    // ── Conexão e pareamento ─────────────────────────────────────────────────────────────────────

    override fun onConnectionChanged(connected: Boolean) {
        statusHolder.onConnectionChanged(connected)
        if (connected) {
            reconnectJob?.cancel(); reconnectJob = null
            // Some o aviso de desconexão/pareamento, mas não um aviso do sensor ainda valendo
            // (a notificação é uma só para todos os avisos de sensor).
            if (!sensorAlertActive()) notificationHelper.cancelSensorWarning()
        } else if (!stopped && reconnectJob?.isActive != true) {
            appLogger.usage(LOG, "Sensor desconectado — agendando reconexão")
            startReconnect()
        }
    }

    override fun onPairingStateChanged(state: AccuChekBleManager.PairingState) {
        appLogger.usage(LOG, "Pareamento", mapOf("estado" to state.name))
        when (state) {
            // Depois de um LOST o Android apaga o pareamento e a próxima tentativa pede PIN de novo; se o
            // sensor recusar, o motivo continua sendo o outro aparelho — "confira o PIN" confundiria.
            // (Achado no sensor virtual, plano 14_, cenário 14.)
            AccuChekBleManager.PairingState.FAILED ->
                if (pairingLost) warn(R.string.accuchek_warn_pairing_lost_title, R.string.accuchek_warn_pairing_lost_body)
                else warn(R.string.accuchek_warn_pairing_failed_title, R.string.accuchek_warn_pairing_failed_body)
            AccuChekBleManager.PairingState.LOST -> {
                pairingLost = true
                warn(R.string.accuchek_warn_pairing_lost_title, R.string.accuchek_warn_pairing_lost_body)
            }
            AccuChekBleManager.PairingState.BONDED -> {
                pairingLost = false
                notificationHelper.cancelSensorWarning()
            }
            else -> Unit
        }
    }

    override fun onRssi(rssi: Int) { statusHolder.onRssi(rssi) }

    override fun onBluetoothTurnedOn() {
        if (stopped || !hasSensorToConnect()) return
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
            registerSensorPeriodIfNeeded(address)
        }
    }

    /**
     * Abre um período no histórico de sensores (Room + GluSync) quando o sensor ativo registrado não
     * é este — primeiro uso, troca de sensor ou contagem que recomeçou. Fora do [mutex]: faz rede.
     */
    private suspend fun registerSensorPeriodIfNeeded(address: String) {
        val active = sensorHistory.observeActive().first()
        val source = DataSourceType.ACCU_CHEK_SMARTGUIDE.id
        val sameSensor = active != null && active.source == source &&
            (serial.isBlank() || active.serial == serial) && !newSensorPending
        if (sameSensor) return
        newSensorPending = false
        appLogger.usage(LOG, "Registrando sensor no histórico de sensores", mapOf("temSerial" to serial.isNotBlank()))
        runCatching { sensorHistory.startNew(address, serial.ifBlank { null }, null, source) }
            .onFailure { e -> appLogger.error(LOG, "Falha ao registrar sensor no histórico", mapOf("erro" to (e::class.simpleName ?: "?"))) }
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
                    previous = null
                    startMs = 0L
                    newSensorPending = true
                    malfunction.reset()
                    synchronized(activeAlerts) { activeAlerts.clear() }
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
                anchorClock()
                // Minutos do sensor mesmo sem glicemia: é o que faz a contagem do aquecimento andar.
                statusHolder.onSensorClock(status.timeOffsetMinutes, startMs)
                // Depois de reiniciar o app, a leitura anterior (que valida LO/HI) vem da última gravada.
                if (previous == null && lastMinute >= 0) {
                    repository.currentReading.value?.takeIf { it.epochMs > startMs }?.let { r ->
                        val minute = ((r.epochMs - startMs) / 60_000L).toInt()
                        if (minute in (lastMinute - 2)..lastMinute) previous = SmartGuideRules.Previous(r.sgvMgDl.toInt(), lastMinute)
                    }
                }
                applyAnnunciation(status.annunciation)
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
        if (runTime.hours > 0) statusHolder.onSensorLifetime(expiryMinutes = runTime.hours * 60)
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
        val hadSensorAlert = sensorAlertActive()
        for (m in fresh) {
            val atMs = SmartGuideClock.readingTimeMs(startMs, m.timeOffsetMinutes, now)
            val live = now - atMs <= LIVE_MAX_AGE_MS
            val mgdl = SmartGuideRules.glucoseToStore(m, previous)?.takeIf { it in PLAUSIBLE_MGDL }
            if (live) {
                if (malfunction.onLiveRecord(stored = mgdl != null, malfunction = SmartGuideRules.isMalfunction(m.annunciation))) {
                    appLogger.error(LOG, "Defeito do sensor há 15 min", mapOf("avisos" to m.annunciation.toString()))
                    warn(R.string.accuchek_warn_malfunction_title, R.string.accuchek_warn_malfunction_body)
                }
                if (m.timeOffsetMinutes == newestMinute) applyAnnunciation(m.annunciation)
            }
            if (mgdl == null) {
                appLogger.usage(LOG, "Leitura sem valor para gravar", mapOf(
                    "minuto" to m.timeOffsetMinutes, "lo" to m.isLow, "hi" to m.isHigh,
                    "mgdl" to m.glucoseMgDl, "avisos" to m.annunciation.toString()
                ))
                continue
            }
            val isCurrent = live && m.timeOffsetMinutes == newestMinute
            val prev = previous
            val delta = if (prev != null) mgdl - prev.mgdl else 0
            val mmol = mgdl / MMOL_TO_MGDL
            repository.storeExternalReading(
                dateMs       = atMs,
                sgvMgDl      = mgdl.toDouble(),
                arrow        = SmartGuideRules.arrowFor(m.trendMgDlPerMin) ?: arrowFromDelta(delta),
                delta        = delta,
                makeCurrent  = isCurrent,
                rawMmol      = mmol,
                calibFactor  = 1.0,
                sensorMac    = address,
                sensorAgeMin = m.timeOffsetMinutes,
                confirmed    = null
            )
            previous = SmartGuideRules.Previous(mgdl, m.timeOffsetMinutes)
            if (isCurrent) {
                statusHolder.onRawMmol(mmol)
                statusHolder.onReading(m.timeOffsetMinutes, startMs, atMs)
            }
            appLogger.usage(LOG, "Glicemia salva", mapOf(
                "mgdl" to mgdl, "bruto" to m.glucoseMgDl, "minuto" to m.timeOffsetMinutes,
                "tendencia" to (m.trendMgDlPerMin ?: "?"), "qualidade" to (m.qualityPercent ?: "?"), "atual" to isCurrent
            ))
        }
        // Defeito/temperatura/bateria/calibração acabaram com a conexão aberta: tira a notificação na
        // hora (antes só saía na próxima reconexão). Achado no sensor virtual, plano 14_, cenários 10–11.
        if (hadSensorAlert && !sensorAlertActive()) {
            appLogger.usage(LOG, "Avisos do sensor resolvidos — notificação removida")
            notificationHelper.cancelSensorWarning()
        }
        lastMinute = newestMinute
        preferences.saveAccuChekLastMinute(newestMinute)
    }

    private fun sensorAlertActive(): Boolean =
        synchronized(activeAlerts) { activeAlerts.isNotEmpty() } || malfunction.isActive

    // ── Avisos ───────────────────────────────────────────────────────────────────────────────────

    /** Notifica os avisos que acabaram de aparecer; esquece os que sumiram (para avisar de novo se voltarem). */
    private fun applyAnnunciation(a: SensorAnnunciation) {
        val now = SmartGuideRules.alertsFor(a)
        val appeared = synchronized(activeAlerts) {
            val new = now - activeAlerts
            activeAlerts.retainAll(now)
            activeAlerts.addAll(now)
            new
        }
        if (SmartGuideAlert.SESSION_ENDED in now) statusHolder.onSensorLifetime(sessionEnded = true)
        for (alert in appeared) {
            appLogger.error(LOG, "Aviso do sensor", mapOf("aviso" to alert.name, "anunciacao" to a.toString()))
            when (alert) {
                SmartGuideAlert.SESSION_ENDED ->
                    warn(R.string.accuchek_warn_session_ended_title, R.string.accuchek_warn_session_ended_body)
                SmartGuideAlert.BATTERY_LOW ->
                    warn(R.string.accuchek_warn_battery_title, R.string.accuchek_warn_battery_body)
                SmartGuideAlert.TEMPERATURE ->
                    warn(R.string.accuchek_warn_temperature_title, R.string.accuchek_warn_temperature_body)
                SmartGuideAlert.CALIBRATION_REQUIRED ->
                    warn(R.string.accuchek_warn_calibration_title, R.string.accuchek_warn_calibration_body)
                SmartGuideAlert.MALFUNCTION -> Unit // vem do MalfunctionStreak
            }
        }
    }

    /**
     * Há um sensor para procurar: serial do assistente, ou um `AC-…` já pareado neste celular.
     * Sem nenhum dos dois (depois de "Esquecer este sensor") não busca: aceitar o primeiro `AC-`
     * por perto pareava — ou abria a janela de PIN — com o sensor de outra pessoa.
     */
    private fun hasSensorToConnect(): Boolean =
        serial.isNotBlank() || ble.findBondedSensor(null) != null

    // ── Relógio do celular ───────────────────────────────────────────────────────────────────────

    private fun anchorClock() {
        anchorWallMs = System.currentTimeMillis()
        anchorElapsedMs = SystemClock.elapsedRealtime()
    }

    /** Hora do celular acertada (pelo usuário ou pela rede) com a conexão aberta: desloca o início. */
    private fun registerTimeChange() {
        if (timeChangeReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val s = scope ?: return
                s.launch { mutex.withLock { onWallClockChanged() } }
            }
        }
        runCatching {
            ContextCompat.registerReceiver(context, receiver, IntentFilter(Intent.ACTION_TIME_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED)
            timeChangeReceiver = receiver
        }.onFailure { e -> appLogger.error(LOG, "Falha ao ouvir mudança de hora", mapOf("erro" to (e::class.simpleName ?: "?"))) }
    }

    private suspend fun onWallClockChanged() {
        if (startMs <= 0 || anchorWallMs <= 0) { anchorClock(); return }
        val jump = SmartGuideClock.wallClockJumpMs(anchorWallMs, anchorElapsedMs,
            System.currentTimeMillis(), SystemClock.elapsedRealtime())
        if (kotlin.math.abs(jump) < SmartGuideClock.CLOCK_JUMP_MIN_MS) return
        val newStart = startMs + jump
        appLogger.usage(LOG, "Hora do celular mudou — início do sensor ajustado", mapOf(
            "puloS" to jump / 1000, "inicioMs" to newStart, "anteriorMs" to startMs
        ))
        startMs = newStart
        preferences.saveAccuChekStartMs(newStart)
        statusHolder.restoreAnchor(serial, newStart)
        anchorClock()
        if (jump < 0) {
            val moved = repository.shiftFutureSensorReadings(address, System.currentTimeMillis(), jump)
            if (moved > 0) appLogger.usage(LOG, "Leituras gravadas com a hora adiantada reposicionadas", mapOf("leituras" to moved))
        }
    }

    private fun warn(titleRes: Int, bodyRes: Int) =
        notificationHelper.showSensorWarning(context.getString(titleRes), context.getString(bodyRes))

    /** Sem tendência do sensor: diferença para a leitura anterior (mesma regra do Libre2Source). */
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
                if (ble.isReady || !hasSensorToConnect()) return@launch
                appLogger.usage(LOG, "Tentativa de reconexão", mapOf("tentativa" to "${idx + 1}/${RECONNECT_SCHEDULE_MS.size}"))
                ble.connectToSensor(serial, address)
                delay(GATT_HANDSHAKE_MS)
                if (!isActive || ble.isReady) return@launch
            }
            if (!stopped) {
                appLogger.error(LOG, "Sensor não reconectou após 60 min")
                warn(R.string.accuchek_warn_disconnected_title, R.string.accuchek_warn_disconnected_body)
            }
        }
    }
}
