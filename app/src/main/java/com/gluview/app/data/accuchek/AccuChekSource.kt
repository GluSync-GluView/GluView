package com.gluview.app.data.accuchek

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
 * Orquestrador da leitura direta do Accu-Chek SmartGuide — mesmo papel do
 * [com.gluview.app.data.libre2.Libre2Source]/[com.gluview.app.data.sibionics.SibionicsBleSource]:
 * conecta, reconecta (mesma grade tiered backoff), decodifica e cai no MESMO pipeline
 * (`storeExternalReading` + [SibionicsStatusHolder]).
 *
 * Diferença de fluxo em relação aos outros dois: aqui não há sessão prévia (NFC) nem cripto —
 * o sensor é lido puramente via o perfil Bluetooth SIG padrão (ver [AccuChekBleManager]), e o
 * histórico é buscado via RACP com um CURSOR de número de sequência persistido (não um timestamp).
 *
 * ★ SEM HARDWARE PARA TESTE (27/07/2026): todo este pipeline foi construído a partir da spec
 * oficial + do código-fonte do Juggluco (ver [AccuChekBleManager]), mas nunca rodou contra um
 * SmartGuide real. Por isso [DataSourceType.ACCU_CHEK_SMARTGUIDE] continua com `comingSoon=true`
 * (mesmo padrão já usado para SIBIONICS_NACIONAL: implementado, não validado). Quando o sensor
 * chegar: parear pelas Configurações do Android (PIN de 6 dígitos da tampa azul do aplicador),
 * então trocar a fonte aqui e validar os logs `ACCUCHEK_BLE`/`ACCUCHEK_SRC` antes de tirar a flag.
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
    @Volatile private var highestSeqSeen: Int = -1

    override fun start(type: DataSourceType) {
        if (scope != null) return
        stopped = false
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s

        s.launch {
            val savedMac = preferences.accuChekMac.first()
            val lastSeq  = preferences.accuChekLastSequence.first()
            highestSeqSeen = lastSeq - 1
            ble.setFetchFromSequence(lastSeq)
            ble.setListener(this@AccuChekSource)

            appLogger.usage(LOG, "Iniciando Accu-Chek SmartGuide",
                mapOf("macSalvo" to savedMac.isNotBlank(), "cursorSequencia" to lastSeq))

            if (savedMac.isNotBlank()) {
                appLogger.usage(LOG, "Conectando com MAC salvo (sem scan)", mapOf("mac" to savedMac))
                currentMac = savedMac
                ble.connect(savedMac)
            } else {
                appLogger.usage(LOG, "MAC não salvo — buscando sensor por scan (nome ACCU-CHEK…)")
                ble.startScan { name, mac, _ ->
                    ble.stopScan()
                    appLogger.usage(LOG, "Sensor encontrado", mapOf("name" to (name ?: "?"), "mac" to mac))
                    currentMac = mac
                    s.launch { preferences.saveAccuChekMac(mac) }
                    ble.connect(mac)
                }
            }
        }
    }

    override fun stop() {
        appLogger.usage(LOG, "Parando Accu-Chek SmartGuide")
        stopped = true
        reconnectJob?.cancel(); reconnectJob = null
        ble.setListener(null)
        ble.stopScan()
        ble.disconnect()
        statusHolder.onConnectionChanged(false)
        notificationHelper.cancelSensorWarning()
        scope?.cancel(); scope = null
        lastSgv = -1
        highestSeqSeen = -1
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
        statusHolder.onRssi(rssi)
    }

    override fun onBluetoothTurnedOn() {
        if (stopped) return
        val mac = currentMac ?: return
        appLogger.usage(LOG, "Bluetooth religado pelo sistema — reconectando imediatamente", mapOf("mac" to mac))
        reconnectJob?.cancel(); reconnectJob = null
        ble.connect(mac)
    }

    override fun onSerialNumber(serial: String) {
        scope?.launch { preferences.saveAccuChekSerial(serial) }
    }

    override fun onReading(reading: AccuChekReading) {
        scope?.launch { persist(reading) }
    }

    /**
     * Avança o cursor de sequência persistido para depois do MAIOR índice visto nesta busca —
     * `highestSeqSeen` já foi atualizado por [persist] a cada `onReading` recebido antes deste
     * evento (o RACP só indica "Response Code" depois de notificar todos os registros).
     */
    override fun onFetchComplete() {
        val next = highestSeqSeen + 1
        if (next <= 0) {
            appLogger.usage(LOG, "RACP: fetch concluído sem registros novos")
            return
        }
        scope?.launch { preferences.saveAccuChekLastSequence(next) }
        ble.setFetchFromSequence(next)
        appLogger.usage(LOG, "RACP: fetch concluído", mapOf("proximaSequencia" to next))
    }

    // ── Persistência ─────────────────────────────────────────────────────────────

    /**
     * Persiste UMA leitura já em mg/dL — o SmartGuide entrega a glicemia já calibrada de fábrica
     * (perfil Bluetooth SIG padrão), então não há `rawToMgdl` aqui (igual ao Libre 2 Plus).
     */
    private suspend fun persist(reading: AccuChekReading) {
        val mgdl = reading.mgdl
        if (mgdl.isNaN() || mgdl.toInt() !in PLAUSIBLE_MGDL) {
            appLogger.usage(LOG, "Leitura descartada (fora da faixa ou NaN)",
                mapOf("mgdl" to mgdl, "seq" to reading.sequenceNumber))
            return
        }
        if (reading.sequenceNumber > highestSeqSeen) highestSeqSeen = reading.sequenceNumber

        val atMs = reading.epochMillis()
        // Sem ativação NFC (diferente do Libre2), não há timestamp de início conhecido a priori —
        // usa o primeiro registro observado nesta sessão como âncora aproximada (só afeta a "idade" exibida).
        val startMs = statusHolder.status.value.sensorStartMs.takeIf { it > 0L } ?: atMs
        val ageMin  = ((atMs - startMs) / 60_000L).toInt().coerceAtLeast(0)
        val delta   = if (lastSgv >= 0) mgdl.toInt() - lastSgv else 0
        val mmol    = mgdl / MMOL_TO_MGDL

        statusHolder.onRawMmol(mmol)
        repository.storeExternalReading(
            dateMs       = atMs,
            sgvMgDl      = mgdl.toDouble(),
            arrow        = arrowFromDelta(delta),
            delta        = delta,
            makeCurrent  = true,
            rawMmol      = mmol,
            calibFactor  = 1.0,
            sensorMac    = currentMac ?: "",
            sensorAgeMin = ageMin,
            confirmed    = null
        )
        lastSgv = mgdl.toInt()
        statusHolder.onReading(ageMin, startMs, atMs)
        appLogger.usage(LOG, "Glicemia salva",
            mapOf("mgdl" to mgdl, "seq" to reading.sequenceNumber, "ageMin" to ageMin))
    }

    // ── Reconexão (mesma grade do Sibionics/Libre2) ───────────────────────────────

    private fun startReconnect(mac: String) {
        reconnectJob?.cancel()
        reconnectJob = scope?.launch {
            appLogger.usage(LOG, "Agenda de reconexão iniciada",
                mapOf("mac" to mac, "tentativas" to RECONNECT_SCHEDULE_MS.size))
            RECONNECT_SCHEDULE_MS.forEachIndexed { idx, delayMs ->
                delay(delayMs)
                if (!isActive) return@launch
                appLogger.usage(LOG, "Tentativa de reconexão BLE",
                    mapOf("tentativa" to "${idx + 1}/${RECONNECT_SCHEDULE_MS.size}"))
                ble.connect(mac)
                delay(GATT_HANDSHAKE_MS)
                if (!isActive) return@launch
            }
            if (!stopped) {
                appLogger.error(LOG, "Sensor não reconectou após 60 min", mapOf("mac" to mac))
                notificationHelper.showSensorWarning(
                    "Sensor desconectado",
                    "Não foi possível reconectar ao Accu-Chek SmartGuide após 60 minutos. " +
                        "Verifique se o sensor está próximo e o Bluetooth está ativo."
                )
            }
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
}
