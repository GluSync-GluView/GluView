package com.gluview.app.data.medlevensohn

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
 * Orquestrador da leitura direta do Smart 2.0 (MedLevensohn) — mesmo papel do
 * [com.gluview.app.data.accuchek.AccuChekSource]/[com.gluview.app.data.libre2.Libre2Source]:
 * conecta, reconecta (mesma grade tiered backoff), decodifica e cai no MESMO pipeline
 * (`storeExternalReading` + [SibionicsStatusHolder]).
 *
 * Diferença de fluxo: aqui o "cadastro" do sensor é feito por SCAN pelo nome BLE
 * (`AiDEX X-`/`LinX-`/`Lumi-`/`Smart-` + serial), não por NFC nem input manual — o serial é
 * extraído do próprio nome do anúncio ([MedLevensohnGatt.extractSerial]) na primeira vez que o
 * sensor é encontrado, e persistido para reconexões futuras sem novo scan.
 *
 * ★ SEM HARDWARE PARA TESTE (27/07/2026): ver header de [MedLevensohnBleManager] para o que já
 * está validado (cripto, com vetor real) vs. o que só foi portado estruturalmente (state machine
 * do handshake/opcodes) e precisa de validação com o sensor físico.
 */
@Singleton
class MedLevensohnSource @Inject constructor(
    private val ble: MedLevensohnBleManager,
    private val repository: GlucoseRepository,
    private val statusHolder: SibionicsStatusHolder,
    private val preferences: AppPreferences,
    private val notificationHelper: NotificationHelper,
    private val appLogger: AppLogger
) : MedLevensohnBleManager.Listener, GlucoseSource {

    private companion object {
        const val LOG = "MEDLEV_SRC"
        const val MMOL_TO_MGDL = 18.0182
        val PLAUSIBLE_MGDL = 20..600
        val RECONNECT_SCHEDULE_MS = buildList {
            repeat(5) { add(TimeUnit.MINUTES.toMillis(1)) }
            repeat(3) { add(TimeUnit.MINUTES.toMillis(5)) }
            repeat(4) { add(TimeUnit.MINUTES.toMillis(10)) }
        }
        const val GATT_HANDSHAKE_MS = 20_000L
    }

    @Volatile private var scope: CoroutineScope? = null
    @Volatile private var reconnectJob: Job? = null
    @Volatile private var currentMac: String? = null
    @Volatile private var stopped = false
    @Volatile private var lastSgv: Int = -1

    override fun start(type: DataSourceType) {
        if (scope != null) return
        stopped = false
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s

        s.launch {
            val savedSerial = preferences.medLevensohnSerial.first()
            val savedMac = preferences.medLevensohnMac.first()
            val savedSessionKey = preferences.medLevensohnSessionKey.first()

            appLogger.usage(LOG, "Iniciando Smart 2.0 (MedLevensohn)",
                mapOf("serialSalvo" to savedSerial.isNotBlank(), "macSalvo" to savedMac.isNotBlank(),
                      "sessaoSalva" to savedSessionKey.isNotBlank()))

            ble.setListener(this@MedLevensohnSource)

            if (savedSerial.isNotBlank() && savedMac.isNotBlank()) {
                ble.configure(savedSerial, savedSessionKey.ifBlank { null })
                currentMac = savedMac
                ble.connect(savedMac)
            } else {
                appLogger.usage(LOG, "Sensor não cadastrado — buscando por scan (nome AiDEX X/LinX/Lumi/Smart)")
                ble.startScan { name, mac, _ ->
                    ble.stopScan()
                    val serial = MedLevensohnGatt.extractSerial(name)
                    appLogger.usage(LOG, "Sensor encontrado", mapOf("name" to name, "mac" to mac, "serial" to serial))
                    currentMac = mac
                    ble.configure(serial, null)
                    s.launch {
                        preferences.saveMedLevensohnSensor(serial = serial, mac = mac, sessionKeyHex = "")
                    }
                    ble.connect(mac)
                }
            }
        }
    }

    override fun stop() {
        appLogger.usage(LOG, "Parando Smart 2.0 (MedLevensohn)")
        stopped = true
        reconnectJob?.cancel(); reconnectJob = null
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
            appLogger.usage(LOG, "Sensor conectado (GATT + handshake OK)")
            // sessionKey só existe depois do bootstrap — persiste pra pular o handshake completo na próxima.
            ble.currentSessionKeyHex()?.let { keyHex ->
                scope?.launch { preferences.saveMedLevensohnSessionKey(keyHex) }
            }
        } else if (!stopped) {
            appLogger.usage(LOG, "Sensor desconectado — agendando reconexão", mapOf("mac" to (currentMac ?: "?")))
            currentMac?.let { startReconnect(it) }
        }
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

    override fun onCurrentReading(reading: CurrentGlucose) {
        scope?.launch { persistCurrent(reading) }
    }

    override fun onHistoricalReading(minFromStart: Int, mgdl: Int) {
        scope?.launch { persistHistorical(minFromStart, mgdl) }
    }

    // ── Persistência ─────────────────────────────────────────────────────────────

    private suspend fun persistCurrent(reading: CurrentGlucose) {
        val mgdl = reading.glucoseMgDl
        if (!reading.valid || mgdl !in PLAUSIBLE_MGDL) {
            appLogger.usage(LOG, "Leitura atual descartada (inválida ou fora da faixa)", mapOf("mgdl" to mgdl, "valid" to reading.valid))
            return
        }
        val startMs = statusHolder.status.value.sensorStartMs
        val atMs = if (startMs > 0L) startMs + reading.minFromStart * 60_000L else System.currentTimeMillis()
        val ageMin = ((atMs - (startMs.takeIf { it > 0L } ?: atMs)) / 60_000L).toInt().coerceAtLeast(0)
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
            calibFactor  = 1.0, // calibração de fábrica do próprio sensor
            sensorMac    = currentMac ?: "",
            sensorAgeMin = ageMin,
            confirmed    = null
        )
        lastSgv = mgdl
        statusHolder.onReading(ageMin, startMs.takeIf { it > 0L } ?: atMs, atMs)
        appLogger.usage(LOG, "Glicemia atual salva", mapOf("mgdl" to mgdl, "trend" to reading.trend, "minFromStart" to reading.minFromStart))
    }

    private suspend fun persistHistorical(minFromStart: Int, mgdl: Int) {
        if (mgdl !in PLAUSIBLE_MGDL) return
        val startMs = statusHolder.status.value.sensorStartMs.takeIf { it > 0L } ?: return
        val atMs = startMs + minFromStart * 60_000L
        repository.storeExternalReading(
            dateMs       = atMs,
            sgvMgDl      = mgdl.toDouble(),
            arrow        = "→",
            delta        = 0,
            makeCurrent  = false,
            rawMmol      = mgdl / MMOL_TO_MGDL,
            calibFactor  = 1.0,
            sensorMac    = currentMac ?: "",
            sensorAgeMin = (minFromStart).coerceAtLeast(0),
            confirmed    = null
        )
        appLogger.usage(LOG, "Glicemia histórica salva", mapOf("mgdl" to mgdl, "minFromStart" to minFromStart))
    }

    // ── Reconexão (mesma grade dos outros sensores) ───────────────────────────────

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
                    "Não foi possível reconectar ao Smart 2.0 após 60 minutos. " +
                        "Verifique se o sensor está próximo e o Bluetooth está ativo."
                )
            }
        }
    }

    private fun arrowFromTrend(trend: Int): String = when {
        // trend está em unidades de 0.1 mg/dL/min (AidexTrend2RateOfChange do Juggluco)
        trend >= 30  -> "↑↑"
        trend >= 15  -> "↑"
        trend >= 7   -> "↗"
        trend > -7   -> "→"
        trend > -15  -> "↘"
        trend > -30  -> "↓"
        else         -> "↓↓"
    }
}
