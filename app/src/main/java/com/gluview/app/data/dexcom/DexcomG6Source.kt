package com.gluview.app.data.dexcom

import android.content.Context
import com.gluview.app.R
import com.gluview.app.data.accuchek.cgm.SmartGuideRules
import com.gluview.app.data.datastore.AppPreferences
import com.gluview.app.data.repository.GlucoseRepository
import com.gluview.app.data.sibionics.SibionicsStatusHolder
import com.gluview.app.data.source.DataSourceType
import com.gluview.app.data.source.GlucoseSource
import com.gluview.app.notification.NotificationHelper
import com.gluview.app.util.AppLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orquestrador da leitura direta do Dexcom G6 — mesmo papel do
 * [com.gluview.app.data.accuchek.AccuChekSource]/[com.gluview.app.data.medlevensohn.MedLevensohnSource].
 *
 * Precisa do **Transmitter ID** (6 caracteres impressos no transmissor), digitado no assistente
 * ([AppPreferences.dexcomG6TransmitterId]). O G6 entrega uma leitura a cada 5 min numa conexão curta
 * ([DexcomG6BleManager]); depois de cada uma a fonte volta a esperar o próximo anúncio.
 *
 * Revisado contra o xDrip em 15/11/2026 (plano 14_, V3): hora real da leitura pelo relógio do
 * transmissor (não pelo início da sessão), estado de calibração (aquecimento, fim, falha), tendência em
 * décimos de mg/dL/min e backfill de furos de até 3 h. Validado no transmissor virtual; ainda sem
 * transmissor real.
 */
@Singleton
class DexcomG6Source @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ble: DexcomG6BleManager,
    private val repository: GlucoseRepository,
    private val statusHolder: SibionicsStatusHolder,
    private val preferences: AppPreferences,
    private val notificationHelper: NotificationHelper,
    private val appLogger: AppLogger
) : DexcomG6BleManager.Listener, GlucoseSource {

    private companion object {
        const val LOG = "DEXCOM_G6_SRC"
        const val MMOL_TO_MGDL = 18.0182
        val PLAUSIBLE_MGDL = 40..400 // faixa que o G6 informa (abaixo/acima disso ele manda LOW/HIGH)
        /** O xDrip pergunta a hora a cada 2 h (e sempre no aquecimento ou sem sessão conhecida). */
        const val TIME_QUERY_EVERY_MS = 2 * 60 * 60_000L
        const val WARMUP_MS = 2 * 60 * 60_000L
        /**
         * Espera entre o fim de um ciclo e voltar a aguardar o anúncio. Rearmar na hora reconectava em
         * milissegundos no mesmo link e virava laço (achado no transmissor virtual com o ID errado); o
         * transmissor só volta a anunciar no próximo ciclo (~5 min).
         */
        const val REARM_DELAY_MS = 30_000L
        /** Sem ciclo nenhum nesse tempo (dois perdidos), a tela passa a "Desconectado". */
        const val OFFLINE_AFTER_MS = 11 * 60_000L
    }

    @Volatile private var scope: CoroutineScope? = null
    @Volatile private var currentMac: String? = null
    @Volatile private var stopped = false
    @Volatile private var lastSgv: Int = -1
    @Volatile private var txStartMs = 0L
    @Volatile private var lastStoredMs = 0L
    @Volatile private var lastTimeQueryMs = 0L
    @Volatile private var lastState: DexcomCalibrationState? = null
    @Volatile private var warnedWrongId = false
    /** Leitura que chegou antes da hora do transmissor (1º ciclo): gravada quando a hora chega. */
    @Volatile private var pendingCurrent: DexcomGlucose? = null
    @Volatile private var lastSequence = -1
    @Volatile private var rearmJob: kotlinx.coroutines.Job? = null
    @Volatile private var offlineJob: kotlinx.coroutines.Job? = null

    override fun start(type: DataSourceType) {
        if (scope != null) return
        stopped = false
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s

        s.launch {
            val transmitterId = preferences.dexcomG6TransmitterId.first()
            val savedMac = preferences.dexcomG6Mac.first()
            txStartMs = preferences.dexcomG6TxStartMs.first()
            lastStoredMs = preferences.dexcomG6LastReadingMs.first()
            lastTimeQueryMs = 0L
            lastState = null
            warnedWrongId = false
            pendingCurrent = null
            lastSequence = -1

            if (!DexcomGatt.isValidTransmitterId(transmitterId)) {
                appLogger.error(LOG, "Transmitter ID ausente ou inválido — configure o G6 no assistente")
                return@launch
            }
            ble.configure(transmitterId)
            ble.setListener(this@DexcomG6Source)
            statusHolder.restoreAnchor(transmitterId, 0L)
            appLogger.usage(LOG, "Iniciando Dexcom G6", mapOf("macSalvo" to savedMac.isNotBlank(), "relogioConhecido" to (txStartMs > 0)))

            if (savedMac.isNotBlank()) {
                currentMac = savedMac
                ble.connect(savedMac, waitForAdvert = true)
            } else {
                ble.startScan { _, mac, _ ->
                    ble.stopScan()
                    currentMac = mac
                    s.launch { preferences.saveDexcomG6Mac(mac) }
                    ble.connect(mac, waitForAdvert = false)
                }
            }
        }
    }

    override fun stop() {
        appLogger.usage(LOG, "Parando Dexcom G6")
        stopped = true
        ble.setListener(null)
        ble.stopScan()
        rearmJob?.cancel(); rearmJob = null
        offlineJob?.cancel(); offlineJob = null
        ble.disconnect()
        statusHolder.onConnectionChanged(false)
        notificationHelper.cancelSensorWarning()
        scope?.cancel(); scope = null
        lastSgv = -1
        currentMac = null
    }

    // ── Listener ─────────────────────────────────────────────────────────────────────────────

    override fun onConnectionChanged(connected: Boolean) {
        // O G6 desconecta a cada leitura: "conectado" na tela = os ciclos estão chegando (ver onAuthenticated).
        if (connected || stopped) return
        val mac = currentMac ?: return
        rearmJob?.cancel()
        rearmJob = scope?.launch {
            kotlinx.coroutines.delay(REARM_DELAY_MS)
            if (!stopped) ble.connect(mac, waitForAdvert = true) // espera o próximo anúncio, sem prazo
        }
    }

    override fun onAuthenticated() {
        warnedWrongId = false
        statusHolder.onConnectionChanged(true)
        offlineJob?.cancel()
        offlineJob = scope?.launch {
            kotlinx.coroutines.delay(OFFLINE_AFTER_MS)
            appLogger.usage(LOG, "Sem ciclo do transmissor há 11 min")
            statusHolder.onConnectionChanged(false)
        }
        appLogger.usage(LOG, "Autenticado — pedindo a glicemia")
    }

    override fun onWrongTransmitterId() {
        if (warnedWrongId) return
        warnedWrongId = true
        notificationHelper.showSensorWarning(
            context.getString(R.string.dexcom_warn_wrong_id_title), context.getString(R.string.dexcom_warn_wrong_id_body))
    }

    override fun onRssi(rssi: Int) {
        statusHolder.onRssi(rssi)
    }

    override fun onBluetoothTurnedOn() {
        if (stopped) return
        val mac = currentMac ?: return
        appLogger.usage(LOG, "Bluetooth religado — voltando a esperar o transmissor", mapOf("mac" to mac))
        rearmJob?.cancel(); rearmJob = null
        ble.connect(mac, waitForAdvert = true)
    }

    override fun onGlucose(reading: DexcomGlucose): DexcomG6BleManager.FollowUp {
        val now = System.currentTimeMillis()
        val state = reading.calibrationState
        appLogger.usage(LOG, "Glicemia do transmissor", mapOf(
            "mgdl" to reading.mgdl, "estado" to state.name, "seq" to reading.sequence, "tendencia" to reading.trend))
        if (state != lastState) onStateChanged(state)
        lastState = state
        // Backfill só com o sensor medindo (`readyForBackfill` do xDrip); a mesma leitura entregue de novo
        // (duas conexões no mesmo ciclo) não grava nem conta como furo.
        backfillAllowed = state.usable
        if (state.usable && reading.sequence == lastSequence) lastStoredBeforeCurrent = lastStoredMs
        if (state.usable && reading.sequence != lastSequence) {
            if (txStartMs > 0L) storeCurrent(reading, now)
            else pendingCurrent = reading // sem relógio ainda: a hora é pedida agora (askTime) e grava em seguida
        }
        val askTime = txStartMs <= 0L || now - lastTimeQueryMs > TIME_QUERY_EVERY_MS ||
            state.warmingUp || statusHolder.status.value.sensorStartMs <= 0L
        return DexcomG6BleManager.FollowUp(askTime)
    }

    override fun onTransmitterTime(time: TransmitterTime) {
        val now = System.currentTimeMillis()
        lastTimeQueryMs = now
        txStartMs = DexcomG6Clock.transmitterStartMs(now, time.currentTimeSec)
        val sessionStart = DexcomG6Clock.sessionStartMs(txStartMs, time)
        appLogger.usage(LOG, "Hora do transmissor", mapOf(
            "segundos" to time.currentTimeSec, "sessaoEmAndamento" to time.sessionInProgress, "inicioSessao" to (sessionStart ?: 0L)))
        scope?.launch { preferences.saveDexcomG6TxStartMs(txStartMs) }
        if (sessionStart != null) statusHolder.moveAnchor(sessionStart)
        pendingCurrent?.let { pendingCurrent = null; storeCurrent(it, now) }
    }

    /**
     * Grava a leitura atual com a hora real (relógio do transmissor). Decidido aqui, na hora: a janela de
     * backfill é pedida logo em seguida nesta mesma conexão e depende da última leitura antes desta.
     */
    private fun storeCurrent(reading: DexcomGlucose, now: Long) {
        val atMs = DexcomG6Clock.readingTimeMs(txStartMs, reading.timestampSec, now)
        lastSequence = reading.sequence
        lastStoredBeforeCurrent = lastStoredMs
        if (atMs > lastStoredMs) lastStoredMs = atMs
        scope?.launch { persist(reading.mgdl, atMs, reading.trendPerMinute, current = true) }
    }

    override fun backfillWindow(): Pair<Int, Int>? =
        if (!backfillAllowed) null
        else DexcomG6Backfill.window(lastStoredBeforeCurrent, System.currentTimeMillis(), txStartMs)

    @Volatile private var backfillAllowed = false

    override fun onBackfill(records: List<DexcomBackfillRecord>) {
        if (txStartMs <= 0L) return
        val now = System.currentTimeMillis()
        val start = txStartMs
        scope?.launch {
            records.sortedBy { it.dexTimeSec }.forEach { r ->
                persist(r.mgdl, DexcomG6Clock.readingTimeMs(start, r.dexTimeSec, now),
                    if (r.trend == 127) null else r.trend / 10f, current = false)
            }
        }
        appLogger.usage(LOG, "Backfill gravado", mapOf("leituras" to records.size))
    }

    /** Última leitura gravada ANTES da atual (a janela de backfill é o furo entre as duas). */
    @Volatile private var lastStoredBeforeCurrent = 0L

    private fun onStateChanged(state: DexcomCalibrationState) {
        appLogger.usage(LOG, "Estado do sensor mudou", mapOf("estado" to state.name))
        when {
            state.ended -> {
                statusHolder.onSensorLifetime(sessionEnded = true)
                notificationHelper.showSensorWarning(
                    context.getString(R.string.dexcom_warn_ended_title), context.getString(R.string.dexcom_warn_ended_body))
            }
            state.failed -> {
                statusHolder.onSensorLifetime(sessionEnded = true)
                notificationHelper.showSensorWarning(
                    context.getString(R.string.dexcom_warn_failed_title), context.getString(R.string.dexcom_warn_failed_body))
            }
            else -> {
                statusHolder.onSensorLifetime(sessionEnded = false)
                if (state.usable) notificationHelper.cancelSensorWarning()
            }
        }
    }

    // ── Persistência ─────────────────────────────────────────────────────────────────────────

    private suspend fun persist(mgdl: Int, atMs: Long, trendPerMin: Float?, current: Boolean) {
        if (mgdl !in PLAUSIBLE_MGDL) {
            appLogger.usage(LOG, "Leitura descartada (fora da faixa)", mapOf("mgdl" to mgdl))
            return
        }
        val sessionStart = statusHolder.status.value.sensorStartMs
        val ageMin = if (sessionStart > 0L) ((atMs - sessionStart) / 60_000L).toInt().coerceAtLeast(0) else 0
        val delta = if (current && lastSgv >= 0) mgdl - lastSgv else 0
        val mmol = mgdl / MMOL_TO_MGDL
        repository.storeExternalReading(
            dateMs       = atMs,
            sgvMgDl      = mgdl.toDouble(),
            // Sem tendência do transmissor (127): pela variação desde a última leitura (5 min).
            arrow        = SmartGuideRules.arrowFor(trendPerMin) ?: SmartGuideRules.arrowFor(delta / 5f) ?: "→",
            delta        = delta,
            makeCurrent  = current,
            rawMmol      = mmol,
            calibFactor  = 1.0, // algoritmo do próprio transmissor (calibração de fábrica)
            sensorMac    = currentMac ?: "",
            sensorAgeMin = ageMin,
            confirmed    = null
        )
        if (current) {
            lastSgv = mgdl
            statusHolder.onRawMmol(mmol)
            statusHolder.onReading(ageMin, sessionStart.takeIf { it > 0L } ?: atMs, atMs)
        }
        if (atMs > lastStoredMs) lastStoredMs = atMs
        preferences.saveDexcomG6LastReadingMs(lastStoredMs)
    }
}
