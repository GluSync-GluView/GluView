package com.gluview.app.data.libre2

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
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orquestrador da leitura direta do FreeStyle Libre 2 Plus — espelha o papel do
 * [com.gluview.app.data.sibionics.SibionicsBleSource]: conecta, reconecta (tiered backoff), decodifica,
 * calibra e cai no MESMO pipeline (`storeExternalReading` + `SibionicsStatusHolder`).
 *
 * Pré-requisito: a sessão já ter sido lida por NFC (ver [Libre2NfcActivator]) e salva em preferences.
 * Sem sessão, loga erro claro e não conecta.
 *
 * Reutiliza a MESMA grade de reconexão do Sibionics (5×1min + 3×5min + 4×10min ≈ 60 min).
 */
@Singleton
class Libre2Source @Inject constructor(
    private val ble: Libre2BleManager,
    private val repository: GlucoseRepository,
    private val statusHolder: SibionicsStatusHolder,
    private val preferences: AppPreferences,
    private val notificationHelper: NotificationHelper,
    private val appLogger: AppLogger,
    @ApplicationContext private val context: Context
) : Libre2BleManager.Listener, GlucoseSource {

    private companion object {
        const val LOG = "LIBRE2_SRC"
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
    @Volatile private var experimentalDecoder: Libre2ExperimentalDecoder? = null

    // Contexto da sessão (carregado no start), lido na thread da GATT callback → @Volatile.
    @Volatile private var uid = ByteArray(0)
    @Volatile private var patchInfo = ByteArray(0)
    @Volatile private var calibration = Libre2Calibration()
    @Volatile private var anchor = 1.0
    @Volatile private var unlockCount = 0

    override fun start(type: DataSourceType) {
        if (scope != null) return
        stopped = false
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s

        // Fase 1 do port fiel do Juggluco: carrega a ponte nativa (libgluviewlibre2.so → libDataProcessing
        // da Abbott, modo DYNLINK + debugclone). Enquanto o .so correto (com.freestylelibre2.app.us 2.7.1)
        // não estiver presente, init() retorna false e o caminho Libre 2 Plus fica inerte — sem glicemia,
        // mas sem quebrar o app.
        val nativeOk = Libre2Native.init(appLogger)
        appLogger.usage(LOG, "Ponte nativa Libre2", mapOf("disponivel" to nativeOk))

        s.launch {
            val uidHex   = preferences.libre2Uid.first()
            val piHex    = preferences.libre2PatchInfo.first()
            val calJson  = preferences.libre2Calibration.first()
            val savedMac = preferences.libre2Mac.first()
            anchor       = preferences.libre2CalFactor.first().takeIf { it > 0.0 } ?: 1.0
            unlockCount  = preferences.libre2UnlockCount.first()
            val persistedStartMs = preferences.libre2SensorStartMs.first()

            // Restaura no holder compartilhado o início real do sensor (persistido na ativação NFC).
            // Sem isso, `statusHolder` reseta a cada restart do processo (é só um StateFlow em memória)
            // e a 1ª leitura pós-restart caía no fallback `?: atMs` de `persist()`, fazendo a idade do
            // sensor exibida na Home voltar sempre para perto de 0.
            if (persistedStartMs > 0L && statusHolder.status.value.sensorStartMs <= 0L) {
                statusHolder.update { it.copy(sensorStartMs = persistedStartMs) }
            }

            if (uidHex.isBlank() || piHex.isBlank()) {
                appLogger.error(LOG, "Sessão Libre 2 ausente — ative o sensor por NFC em Configurações")
                return@launch
            }

            uid         = runCatching { hexToBytes(uidHex) }.getOrDefault(ByteArray(0))
            patchInfo   = runCatching { hexToBytes(piHex) }.getOrDefault(ByteArray(0))
            calibration = Libre2Calibration.fromJson(calJson)
            experimentalDecoder = Libre2ExperimentalDecoder(appLogger)
            appLogger.usage(LOG, "Tap experimental Libre2 habilitado em paralelo",
                mapOf("usaNativePrincipal" to true, "persisteExperimental" to false,
                      "conexaoBLECompartilhada" to true))

            // Restaura a sessão do sensor no nativo (Juggluco USenabledStreaming). O estado nativo é
            // em memória e se perde ao reiniciar o processo; a ativação NFC persistiu uid+auth+MAC nas
            // prefs, então reinjetamos aqui para o handshake BLE (sensorIdent/streamingAuthData) funcionar
            // SEM re-encostar o sensor. O `address` não é necessário para o handshake (o MAC vem das prefs).
            if (nativeOk && uid.isNotEmpty()) {
                val authHex = preferences.libre2SessionKey.first()
                val auth = runCatching { hexToBytes(authHex) }.getOrDefault(ByteArray(0))
                val startMs = persistedStartMs
                val fram = runCatching { hexToBytes(preferences.libre2Fram.first()) }.getOrDefault(ByteArray(0))
                if (auth.isNotEmpty()) {
                    Libre2Native.usEnabledStreaming(uid, patchInfo, auth, macToBytes(savedMac))
                    if (fram.size >= 344) {
                        val scan = Libre2Native.processScan(
                            uid = uid,
                            patchInfo = patchInfo,
                            fram = fram,
                            scanEpochSec = if (startMs > 0L) startMs / 1000 else System.currentTimeMillis() / 1000
                        )
                        appLogger.usage(LOG, "scanstate restaurado via processScan",
                            mapOf("status" to (scan?.status ?: "null"), "mgdl" to (scan?.reading?.mgdl ?: 0)))
                    }
                    if (startMs > 0L) Libre2Native.setSensorStart(startMs / 1000)
                    appLogger.usage(LOG, "Sessão nativa restaurada das prefs",
                        mapOf("authBytes" to auth.size, "mac" to savedMac, "startMs" to startMs))
                } else {
                    appLogger.error(LOG, "Sessão nativa NÃO restaurada — auth ausente nas prefs; " +
                        "re-ative o sensor por NFC")
                }
            }

            // O re-unlock do streaming é feito NATIVAMENTE (getStreamingUnlockPayload) durante a
            // ativação/reconexão na Fase 2 — não há mais payload placeholder aqui.
            appLogger.usage(LOG, "Iniciando Libre 2 Plus",
                mapOf("temParamsFabrica" to calibration.hasFactoryParams, "unlockCount" to unlockCount,
                      "macSalvo" to savedMac.isNotBlank()))

            ble.setListener(this@Libre2Source)
            if (savedMac.isNotBlank()) {
                appLogger.usage(LOG, "Conectando com MAC salvo (sem scan)", mapOf("mac" to savedMac))
                currentMac = savedMac
                ble.connect(savedMac)
            } else {
                // Sem MAC: descobre pelo nome "ABBOTT…" / serviço fde3 e persiste. Passa o MAC
                // derivado pelo NFC (Libre2Native.deviceAddress) como fallback de casamento exato —
                // cobre a família "7F", que anuncia o próprio MAC como nome.
                val nfcMac = Libre2Native.deviceAddress()?.takeIf { it.isNotBlank() }
                appLogger.usage(LOG, "MAC não salvo — buscando sensor por scan",
                    mapOf("macNfc" to (nfcMac ?: "-")))
                ble.startScan(savedMac = nfcMac) { name, mac, _ ->
                    ble.stopScan()
                    appLogger.usage(LOG, "Sensor encontrado", mapOf("name" to (name ?: "?"), "mac" to mac))
                    currentMac = mac
                    s.launch { preferences.saveLibre2Session(
                        mac = mac,
                        serial = preferences.libre2Serial.first(),
                        uidHex = uidHex, patchInfoHex = piHex,
                        sessionKeyHex = preferences.libre2SessionKey.first(),
                        calibrationJson = calJson,
                        framHex = preferences.libre2Fram.first(),
                        sensorStartMs = preferences.libre2SensorStartMs.first()
                    ) }
                    ble.connect(mac)
                }
            }
        }
    }

    override fun stop() {
        appLogger.usage(LOG, "Parando Libre 2 Plus")
        stopped = true
        reconnectJob?.cancel(); reconnectJob = null
        ble.setListener(null)
        ble.stopScan()
        ble.disconnect()
        statusHolder.onConnectionChanged(false)
        notificationHelper.cancelSensorWarning()
        scope?.cancel(); scope = null
        lastSgv = -1
        experimentalDecoder = null
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

    /**
     * Rádio do sistema religado (ver [Libre2BleManager.Listener.onBluetoothTurnedOn]). Enquanto
     * ele esteve desligado, a conexão pode ter caído sem nunca disparar [onConnectionChanged]
     * (bug conhecido do stack BLE de vários aparelhos) — sem isto, o app ficaria preso esperando
     * um callback que nunca chega. Cancela qualquer backoff em andamento e tenta na hora; se
     * falhar, [onConnectionChanged] reagenda o cronograma normal a partir do zero.
     */
    override fun onBluetoothTurnedOn() {
        if (stopped) return
        val mac = currentMac ?: return
        appLogger.usage(LOG, "Bluetooth religado pelo sistema — reconectando imediatamente", mapOf("mac" to mac))
        reconnectJob?.cancel(); reconnectJob = null
        ble.connect(mac)
    }

    override fun onRawPacket(uuid: UUID, value: ByteArray) {
        runCatching { experimentalDecoder?.accept(uuid, value) }
            .onFailure { error ->
                appLogger.error(LOG, "Decoder experimental falhou sem afetar o Libre principal",
                    mapOf("uuid" to uuid, "bytes" to value.size,
                          "erro" to (error.message ?: error::class.simpleName.orEmpty())))
            }
    }

    override fun onReady() {
        appLogger.usage(LOG, "BLE pronto (unlock escrito) — aguardando pacotes")
        scope?.launch { preferences.saveLibre2StreamingValid(true) }
    }

    override fun onHandshakeFailed() {
        appLogger.error(LOG, "Handshake BLE falhou — sessão persistida marcada inválida; novo NFC exigirá 0x1b/0x1e")
        scope?.launch { preferences.saveLibre2StreamingValid(false) }
    }

    override fun onReading(reading: Libre2Reading, atMs: Long) {
        scope?.launch { persist(reading, atMs) }
    }

    /**
     * Pontos de trend/histórico do sensor (21/07/2026) — glicemias de quando o app esteve
     * desconectado, com timestamp real (não "agora"). Persiste no MESMO pipeline de `persist()`
     * mas com `makeCurrent=false` (nunca sobrescreve a leitura atual exibida) e sem tendência
     * própria (o algoritmo da Abbott não devolve `rate`/`trendArrow` pra pontos históricos —
     * mesma escolha do Juggluco em `savehistory.cpp::addStreamHistory`, `trend=0`).
     */
    override fun onHistoricalReadings(entries: List<Pair<Libre2Reading, Long>>) {
        scope?.launch { entries.forEach { (reading, atMs) -> persistHistorical(reading, atMs) } }
    }

    private suspend fun persistHistorical(reading: Libre2Reading, atMs: Long) {
        val mgdl = reading.mgdl
        if (mgdl !in PLAUSIBLE_MGDL) return
        val startMs = statusHolder.status.value.sensorStartMs.takeIf { it > 0L } ?: atMs
        val ageMin  = ((atMs - startMs) / 60_000L).toInt().coerceAtLeast(0)
        repository.storeExternalReading(
            dateMs       = atMs,
            sgvMgDl      = mgdl.toDouble(),
            arrow        = arrowFromDelta(0),
            delta        = 0,
            makeCurrent  = false,
            rawMmol      = mgdl / 18.0182,
            calibFactor  = 1.0,
            sensorMac    = currentMac ?: "",
            sensorAgeMin = ageMin,
            confirmed    = null
        )
        appLogger.usage(LOG, "Glicemia histórica salva", mapOf("mgdl" to mgdl, "atMs" to atMs))
    }

    // ── Persistência ─────────────────────────────────────────────────────────────

    /**
     * Persiste UMA leitura já final (mg/dL) vinda de `processTooth` — o algoritmo da Abbott já aplicou
     * a calibração de fábrica, então NÃO há mais `rawToMgdl` aqui (diferente do Sibionics). Cai no
     * mesmo pipeline (`storeExternalReading` + `SibionicsStatusHolder`).
     */
    private suspend fun persist(reading: Libre2Reading, atMs: Long) {
        val mgdl = reading.mgdl
        if (mgdl !in PLAUSIBLE_MGDL) {
            appLogger.usage(LOG, "Leitura descartada (fora da faixa)", mapOf("mgdl" to mgdl))
            return
        }
        val startMs = statusHolder.status.value.sensorStartMs.takeIf { it > 0L } ?: atMs
        val ageMin  = ((atMs - startMs) / 60_000L).toInt().coerceAtLeast(0)
        val delta   = if (lastSgv >= 0) mgdl - lastSgv else 0
        val mmol    = mgdl / 18.0182

        statusHolder.onRawMmol(mmol)  // mesmo seam da tela de calibração usado pelo Sibionics
        repository.storeExternalReading(
            dateMs       = atMs,
            sgvMgDl      = mgdl.toDouble(),
            arrow        = arrowFromDelta(delta),
            delta        = delta,
            makeCurrent  = true,
            rawMmol      = mmol,
            calibFactor  = 1.0,          // calibração feita pelo algoritmo da Abbott (processTooth)
            sensorMac    = currentMac ?: "",
            sensorAgeMin = ageMin,
            confirmed    = null
        )
        lastSgv = mgdl
        statusHolder.onReading(ageMin, startMs, atMs)
        appLogger.usage(LOG, "Glicemia salva",
            mapOf("mgdl" to mgdl, "rate" to "%.2f".format(reading.rate), "alarm" to reading.alarm,
                  "sensorDias" to "%.1f".format((atMs - startMs) / 86_400_000.0)))
    }

    // ── Reconexão (mesma grade do Sibionics) ──────────────────────────────────────

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
                    "Não foi possível reconectar ao Libre 2 após 60 minutos. " +
                        "Verifique se o sensor está próximo e o Bluetooth está ativo."
                )
            }
        }
    }

    /** Seta a partir do delta (mg/dL por leitura). Aproximação até o trend nativo ser validado. */
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

/**
 * MAC "XX:XX:XX:XX:XX:XX" → bleAddress de 6 bytes com ordem invertida (inverso de
 * `Libre2NfcActivator.bytesToMac`/`setDeviceAddressB` do Juggluco). Vazio se o MAC for inválido —
 * o handshake BLE não usa o address (só uid+auth), então isto é só para consistência do nativo.
 */
private fun macToBytes(mac: String): ByteArray {
    val parts = mac.split(':')
    if (parts.size != 6) return ByteArray(0)
    return runCatching {
        ByteArray(6) { parts[5 - it].toInt(16).toByte() }
    }.getOrDefault(ByteArray(0))
}
