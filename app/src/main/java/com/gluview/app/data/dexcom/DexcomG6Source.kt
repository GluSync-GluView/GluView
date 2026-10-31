package com.gluview.app.data.dexcom

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
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orquestrador da leitura direta do Dexcom G6 — mesmo papel do
 * [com.gluview.app.data.accuchek.AccuChekSource]/[com.gluview.app.data.medlevensohn.MedLevensohnSource].
 *
 * Diferença de fluxo: precisa do **Transmitter ID** (6 caracteres, impresso no transmissor) —
 * sem NFC/QR nem valor descoberto por scan, é digitado pelo usuário (persistido em
 * [AppPreferences.dexcomG6TransmitterId]). Sem ele, [start] não faz nada além de logar o erro.
 *
 * ★ SEM HARDWARE PARA TESTE (27/07/2026): handshake portado do driver nativo do xDrip (GPLv3),
 * chave/protocolo documentados publicamente — alta confiança na lógica, mas nunca validado contra
 * um transmissor real. Ver header de [DexcomG6BleManager] para os detalhes.
 */
@Singleton
class DexcomG6Source @Inject constructor(
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
        val PLAUSIBLE_MGDL = 20..600
        val RECONNECT_SCHEDULE_MS = buildList {
            repeat(5) { add(TimeUnit.MINUTES.toMillis(1)) }
            repeat(3) { add(TimeUnit.MINUTES.toMillis(5)) }
            repeat(4) { add(TimeUnit.MINUTES.toMillis(10)) }
        }
        const val GATT_HANDSHAKE_MS = 20_000L
        const val POLL_INTERVAL_MS = 5 * 60_000L // G6 lê a cada 5 min
    }

    @Volatile private var scope: CoroutineScope? = null
    @Volatile private var reconnectJob: Job? = null
    @Volatile private var pollJob: Job? = null
    @Volatile private var currentMac: String? = null
    @Volatile private var stopped = false
    @Volatile private var lastSgv: Int = -1

    override fun start(type: DataSourceType) {
        if (scope != null) return
        stopped = false
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s

        s.launch {
            val transmitterId = preferences.dexcomG6TransmitterId.first()
            val savedMac = preferences.dexcomG6Mac.first()

            if (!DexcomGatt.isValidTransmitterId(transmitterId)) {
                appLogger.error(LOG, "Transmitter ID ausente ou inválido — configure em Ajustes antes de usar Dexcom G6")
                return@launch
            }

            ble.configure(transmitterId)
            ble.setListener(this@DexcomG6Source)
            appLogger.usage(LOG, "Iniciando Dexcom G6", mapOf("macSalvo" to savedMac.isNotBlank()))

            if (savedMac.isNotBlank()) {
                currentMac = savedMac
                ble.connect(savedMac)
            } else {
                appLogger.usage(LOG, "MAC não salvo — buscando transmissor por scan (serviço 0xFEBC)")
                ble.startScan { name, mac, _ ->
                    ble.stopScan()
                    appLogger.usage(LOG, "Transmissor encontrado", mapOf("name" to (name ?: "?"), "mac" to mac))
                    currentMac = mac
                    s.launch { preferences.saveDexcomG6Mac(mac) }
                    ble.connect(mac)
                }
            }
        }
    }

    override fun stop() {
        appLogger.usage(LOG, "Parando Dexcom G6")
        stopped = true
        reconnectJob?.cancel(); reconnectJob = null
        pollJob?.cancel(); pollJob = null
        ble.setListener(null)
        ble.stopScan()
        ble.disconnect()
        statusHolder.onConnectionChanged(false)
        notificationHelper.cancelSensorWarning()
        scope?.cancel(); scope = null
        lastSgv = -1
        currentMac = null
    }

    // ── Listener ─────────────────────────────────────────────────────────────────

    override fun onConnectionChanged(connected: Boolean) {
        statusHolder.onConnectionChanged(connected)
        if (connected) {
            reconnectJob?.cancel(); reconnectJob = null
            notificationHelper.cancelSensorWarning()
            appLogger.usage(LOG, "Sensor conectado (GATT OK, aguardando autenticação)")
        } else {
            pollJob?.cancel(); pollJob = null
            if (!stopped) {
                appLogger.usage(LOG, "Sensor desconectado — agendando reconexão", mapOf("mac" to (currentMac ?: "?")))
                currentMac?.let { startReconnect(it) }
            }
        }
    }

    override fun onAuthenticated() {
        appLogger.usage(LOG, "Autenticado — iniciando polling de glicemia (5 em 5 min)")
        startPolling()
    }

    override fun onRssi(rssi: Int) {
        statusHolder.onRssi(rssi)
    }

    override fun onBluetoothTurnedOn() {
        if (stopped) return
        val mac = currentMac ?: return
        appLogger.usage(LOG, "Bluetooth religado pelo sistema — reconectando imediatamente", mapOf("mac" to mac))
        reconnectJob?.cancel(); reconnectJob = null
        ble.connect(mac)
    }

    override fun onTransmitterTime(time: TransmitterTime) {
        if (!time.sessionInProgress) return
        val startMs = (System.currentTimeMillis() - (time.currentTimeSec - time.sessionStartTimeSec) * 1000L)
        statusHolder.update { it.copy(sensorStartMs = startMs) }
        appLogger.usage(LOG, "Início de sessão calculado", mapOf("startMs" to startMs))
    }

    override fun onGlucose(reading: DexcomGlucose) {
        scope?.launch { persist(reading) }
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope?.launch {
            while (isActive) {
                ble.requestGlucose()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    // ── Persistência ─────────────────────────────────────────────────────────────

    private suspend fun persist(reading: DexcomGlucose) {
        val mgdl = reading.mgdl
        if (mgdl !in PLAUSIBLE_MGDL) {
            appLogger.usage(LOG, "Leitura descartada (fora da faixa)", mapOf("mgdl" to mgdl))
            return
        }
        val startMs = statusHolder.status.value.sensorStartMs
        val atMs = if (startMs > 0L) startMs + reading.timestampSec * 1000L else System.currentTimeMillis()
        val ageMin = if (startMs > 0L) ((atMs - startMs) / 60_000L).toInt().coerceAtLeast(0) else 0
        val delta = if (lastSgv >= 0) mgdl - lastSgv else 0
        val mmol = mgdl / MMOL_TO_MGDL

        statusHolder.onRawMmol(mmol)
        repository.storeExternalReading(
            dateMs       = atMs,
            sgvMgDl      = mgdl.toDouble(),
            arrow        = arrowFromTrend(reading.trend),
            delta        = delta,
            makeCurrent  = true,
            rawMmol      = mmol,
            calibFactor  = 1.0, // calibração de fábrica (algoritmo do transmissor)
            sensorMac    = currentMac ?: "",
            sensorAgeMin = ageMin,
            confirmed    = null
        )
        lastSgv = mgdl
        statusHolder.onReading(ageMin, startMs.takeIf { it > 0L } ?: atMs, atMs)
        appLogger.usage(LOG, "Glicemia salva", mapOf("mgdl" to mgdl, "seq" to reading.sequence, "trend" to reading.trend))
    }

    private fun startReconnect(mac: String) {
        reconnectJob?.cancel()
        reconnectJob = scope?.launch {
            appLogger.usage(LOG, "Agenda de reconexão iniciada", mapOf("mac" to mac, "tentativas" to RECONNECT_SCHEDULE_MS.size))
            RECONNECT_SCHEDULE_MS.forEachIndexed { idx, delayMs ->
                delay(delayMs)
                if (!isActive) return@launch
                appLogger.usage(LOG, "Tentativa de reconexão BLE", mapOf("tentativa" to "${idx + 1}/${RECONNECT_SCHEDULE_MS.size}"))
                ble.connect(mac)
                delay(GATT_HANDSHAKE_MS)
                if (!isActive) return@launch
            }
            if (!stopped) {
                appLogger.error(LOG, "Sensor não reconectou após 60 min", mapOf("mac" to mac))
                notificationHelper.showSensorWarning(
                    "Sensor desconectado",
                    "Não foi possível reconectar ao Dexcom G6 após 60 minutos. " +
                        "Verifique se o transmissor está próximo e o Bluetooth está ativo."
                )
            }
        }
    }

    private fun arrowFromTrend(trend: Int): String = when {
        trend <= -3 -> "↓↓"
        trend == -2 -> "↓"
        trend == -1 -> "↘"
        trend == 0  -> "→"
        trend == 1  -> "↗"
        trend == 2  -> "↑"
        else        -> "↑↑"
    }
}
