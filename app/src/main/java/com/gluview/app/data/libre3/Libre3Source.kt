package com.gluview.app.data.libre3

import android.content.Context
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
 * Orquestrador da leitura direta do FreeStyle Libre 3 / Lingo — espelha o papel do
 * [Libre2Source] e [SibionicsBleSource]: conecta, reconecta (tiered backoff), decodifica,
 * e cai no MESMO pipeline (`storeExternalReading` + `SibionicsStatusHolder`).
 *
 * Pré-requisito: a sessão já ter sido lida por NFC (ver [Libre3NfcActivator]) e salva em preferences.
 * Sem sessão, loga erro claro e não conecta.
 *
 * Reutiliza a MESMA grade de reconexão do Sibionics/Libre2 (5�-1min + 3�-5min + 4�-10min ≈ 60 min).
 */
@Singleton
class Libre3Source @Inject constructor(
    private val gattCallback: Libre3GattCallback,
    private val repository: GlucoseRepository,
    private val statusHolder: SibionicsStatusHolder,
    private val preferences: AppPreferences,
    private val notificationHelper: NotificationHelper,
    private val appLogger: AppLogger,
    @ApplicationContext private val context: Context
) : Libre3GattCallback.Listener, GlucoseSource {

    private companion object {
        const val LOG = "LIBRE3_SRC"
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
    @Volatile private var lastSgv = -1

    override fun start(type: DataSourceType) {
        if (scope != null) return
        stopped = false

        val nativeOk = Libre3Native.init(appLogger)
        appLogger.usage(LOG, "Ponte nativa Libre 3", mapOf("disponivel" to nativeOk))

        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s

        s.launch {
            // Carrega sessão das preferences
            val serial = preferences.libre3Serial.first()
            val patchInfoHex = preferences.libre3PatchInfo.first()
            val savedAuthHex = preferences.libre3SavedAuth.first()
            val sensorStartMs = preferences.libre3SensorStartMs.first()
            val bleAddressHex = preferences.libre3BleAddress.first()
            val pinHex = preferences.libre3Pin.first()
            val lingoVersion = preferences.libre3LingoVersion.first()

            if (serial.isBlank() || patchInfoHex.isBlank()) {
                appLogger.error(LOG, "Sessão Libre 3 ausente — ative o sensor por NFC em Configurações")
                return@launch
            }

            val patchInfo = runCatching { hexToBytes(patchInfoHex) }.getOrDefault(ByteArray(0))
            val savedAuth = if (savedAuthHex.isNotBlank()) hexToBytes(savedAuthHex) else null
            val bleAddress = if (bleAddressHex.isNotBlank()) hexToBytes(bleAddressHex) else ByteArray(0)
            val pin = if (pinHex.isNotBlank()) hexToBytes(pinHex) else ByteArray(0)

            // Restaura sensorStartMs no holder compartilhado
            if (sensorStartMs > 0L && statusHolder.status.value.sensorStartMs <= 0L) {
                statusHolder.update { it.copy(sensorStartMs = sensorStartMs) }
            }

            appLogger.usage(LOG, "Iniciando Libre 3", mapOf(
                "serial" to serial,
                "lingoVersion" to lingoVersion,
                "temSavedAuth" to (savedAuth != null),
                "macSalvo" to (currentMac?.isNotBlank() == true)
            ))

            gattCallback.setListener(this@Libre3Source)
            gattCallback.start(serial, currentMac)
        }
    }

    override fun stop() {
        appLogger.usage(LOG, "Parando Libre 3")
        stopped = true
        reconnectJob?.cancel(); reconnectJob = null
        gattCallback.setListener(null)
        gattCallback.stop()
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
            appLogger.usage(LOG, "Sensor conectado (GATT + serviços OK)")
        } else if (!stopped) {
            appLogger.usage(LOG, "Sensor desconectado — agendando reconexão", mapOf("mac" to (currentMac ?: "?")))
            currentMac?.let { startReconnect(it) }
        }
    }

    override fun onRssi(rssi: Int) {
        appLogger.usage(LOG, "RSSI recebido", mapOf("rssi" to rssi))
        statusHolder.onRssi(rssi)
    }

    override fun onRawPacket(uuid: java.util.UUID, value: ByteArray) {
        // Para debug / decoder experimental futuro
    }

    override fun onReady() {
        appLogger.usage(LOG, "BLE pronto (handshake completo) — aguardando pacotes")
        scope?.launch { preferences.saveLibre3StreamingValid(true) }
    }

    override fun onHandshakeFailed() {
        appLogger.error(LOG, "Handshake BLE falhou — sessão persistida marcada inválida; novo NFC exigido")
        scope?.launch { preferences.saveLibre3StreamingValid(false) }
    }

    override fun onReading(reading: Libre3Reading, atMs: Long) {
        scope?.launch { persist(reading, atMs) }
    }

    override fun onHistoricalReadings(entries: List<Pair<Libre3Reading, Long>>) {
        scope?.launch { entries.forEach { (reading, atMs) -> persistHistorical(reading, atMs) } }
    }

    // ── Persistência ─────────────────────────────────────────────────────────────

    /**
     * Persiste UMA leitura já final (mg/dL) vinda do nativo — o algoritmo já aplicou
     * a calibração. Cai no MESMO pipeline (`storeExternalReading` + `SibionicsStatusHolder`).
     */
    private suspend fun persist(reading: Libre3Reading, atMs: Long) {
        val mgdl = reading.mgdl
        if (mgdl !in PLAUSIBLE_MGDL) {
            appLogger.usage(LOG, "Leitura descartada (fora da faixa)", mapOf("mgdl" to mgdl))
            return
        }
        val startMs = statusHolder.status.value.sensorStartMs.takeIf { it > 0L } ?: atMs
        val ageMin = ((atMs - startMs) / 60_000L).toInt().coerceAtLeast(0)
        val delta = if (lastSgv >= 0) mgdl - lastSgv else 0
        val mmol = mgdl / 18.0182

        statusHolder.onRawMmol(mmol)
        repository.storeExternalReading(
            dateMs = atMs,
            sgvMgDl = mgdl.toDouble(),
            arrow = arrowFromDelta(delta),
            delta = delta,
            makeCurrent = true,
            rawMmol = mmol,
            calibFactor = 1.0,
            sensorMac = currentMac ?: "",
            sensorAgeMin = ageMin,
            confirmed = null
        )
        lastSgv = mgdl
        statusHolder.onReading(ageMin, startMs, atMs)
        appLogger.usage(LOG, "Glicemia salva", mapOf(
            "mgdl" to mgdl,
            "rate" to "%.2f".format(reading.rate),
            "alarm" to reading.alarm,
            "sensorDias" to "%.1f".format((atMs - startMs) / 86_400_000.0)
        ))
    }

    private suspend fun persistHistorical(reading: Libre3Reading, atMs: Long) {
        val mgdl = reading.mgdl
        if (mgdl !in PLAUSIBLE_MGDL) return
        val startMs = statusHolder.status.value.sensorStartMs.takeIf { it > 0L } ?: atMs
        val ageMin = ((atMs - startMs) / 60_000L).toInt().coerceAtLeast(0)
        repository.storeExternalReading(
            dateMs = atMs,
            sgvMgDl = mgdl.toDouble(),
            arrow = arrowFromDelta(0),
            delta = 0,
            makeCurrent = false,
            rawMmol = mgdl / 18.0182,
            calibFactor = 1.0,
            sensorMac = currentMac ?: "",
            sensorAgeMin = ageMin,
            confirmed = null
        )
        appLogger.usage(LOG, "Glicemia histórica salva", mapOf("mgdl" to mgdl, "atMs" to atMs))
    }

    // ── Reconexão (mesma grade do Sibionics/Libre2) ──────────────────────────────

    private fun startReconnect(mac: String) {
        reconnectJob?.cancel()
        reconnectJob = scope?.launch {
            appLogger.usage(LOG, "Agenda de reconexão iniciada", mapOf("mac" to mac, "tentativas" to RECONNECT_SCHEDULE_MS.size))
            RECONNECT_SCHEDULE_MS.forEachIndexed { idx, delayMs ->
                delay(delayMs)
                if (!isActive) return@launch
                appLogger.usage(LOG, "Tentativa de reconexão BLE", mapOf("tentativa" to "${idx + 1}/${RECONNECT_SCHEDULE_MS.size}"))
                gattCallback.start(serial = preferences.libre3Serial.first(), mac = mac)
                delay(GATT_HANDSHAKE_MS)
                if (!isActive) return@launch
            }
            if (!stopped) {
                appLogger.error(LOG, "Sensor não reconectou após 60 min", mapOf("mac" to mac))
                notificationHelper.showSensorWarning(
                    "Sensor desconectado",
                    "Não foi possível reconectar ao Libre 3 após 60 minutos. " +
                        "Verifique se o sensor está próximo e o Bluetooth está ativo."
                )
            }
        }
    }

    private fun arrowFromDelta(delta: Int): String = when {
        delta >= 15 -> "↑↑"
        delta >= 7 -> "↑"
        delta >= 3 -> "�-"
        delta > -3 -> "→"
        delta > -7 -> "↘"
        delta > -15 -> "↓"
        else -> "↓↓"
    }

    private fun hexToBytes(hex: String): ByteArray {
        return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
