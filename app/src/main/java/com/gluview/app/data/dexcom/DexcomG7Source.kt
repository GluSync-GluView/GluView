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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PT-3 — leitura direta do Dexcom G7, mesmo papel do [DexcomG6Source]. Pede o código de pareamento
 * (4 dígitos) e exige o G7 já pareado pelo app oficial da Dexcom neste celular (ver [DexcomG7Auth]).
 *
 * Regras de saúde: aquecimento, fim, expiração e falha nunca viram glicemia ([G7SensorState.usable]);
 * a leitura é datada pela idade que o próprio G7 informa; buracos são preenchidos pelo histórico do
 * sensor (backfill), sem duplicar o que já está gravado. SEM TESTE COM SENSOR REAL.
 */
@Singleton
class DexcomG7Source @Inject constructor(
    private val ble: DexcomG7BleManager,
    private val repository: GlucoseRepository,
    private val statusHolder: SibionicsStatusHolder,
    private val preferences: AppPreferences,
    private val notificationHelper: NotificationHelper,
    private val appLogger: AppLogger
) : DexcomG7BleManager.Listener, GlucoseSource {

    private companion object {
        const val LOG = "DEXCOM_G7_SRC"
        const val MMOL_TO_MGDL = 18.0182
        const val PERIOD_MS = 5 * 60_000L
        /** O G7 guarda até 24 h de histórico. */
        const val MAX_BACKFILL_MS = 24 * 3_600_000L
    }

    @Volatile private var scope: CoroutineScope? = null
    @Volatile private var currentMac: String? = null
    @Volatile private var stopped = false
    @Volatile private var lastSgv = -1
    @Volatile private var lastStoredMs = 0L
    @Volatile private var sensorStartMs = 0L
    @Volatile private var lastState: G7SensorState? = null
    /** Buraco pedido ao sensor: só entra no histórico o que cai dentro dele. */
    @Volatile private var backfillWindow: LongRange? = null

    override fun start(type: DataSourceType) {
        if (scope != null) return
        stopped = false
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s
        s.launch {
            val code = preferences.dexcomG7Code.first()
            if (!DexcomG7Auth.isValidPairingCode(code)) {
                appLogger.error(LOG, "Código de pareamento do G7 ausente — configure pelo assistente de sensor")
                return@launch
            }
            val key = preferences.dexcomG7Key.first().takeIf { it.length == 32 }?.let(::hexToBytes)
            lastStoredMs = repository.getLatestOnce()?.epochMs ?: 0L
            ble.configure(code, key)
            ble.setListener(this@DexcomG7Source)

            val saved = preferences.dexcomG7Mac.first()
            val bonded = ble.bondedG7s()
            val mac = saved.takeIf { s -> s.isNotBlank() && bonded.any { it.second == s } } ?: bonded.firstOrNull()?.second
            if (mac == null) {
                appLogger.error(LOG, "Nenhum G7 pareado neste celular")
                warnOfficialPairing()
                return@launch
            }
            if (mac != saved) preferences.saveDexcomG7Mac(mac)
            currentMac = mac
            appLogger.usage(LOG, "Iniciando Dexcom G7", mapOf("mac" to mac, "chaveSalva" to (key != null)))
            ble.connect(mac)
        }
    }

    override fun stop() {
        appLogger.usage(LOG, "Parando Dexcom G7")
        stopped = true
        ble.setListener(null)
        ble.disconnect()
        statusHolder.onConnectionChanged(false)
        notificationHelper.cancelSensorWarning()
        scope?.cancel(); scope = null
        lastSgv = -1
        currentMac = null
        lastState = null
    }

    override fun onConnectionChanged(connected: Boolean) {
        statusHolder.onConnectionChanged(connected)
    }

    override fun onRssi(rssi: Int) = statusHolder.onRssi(rssi)

    override fun onAuthenticated(sharedKey: ByteArray?) {
        notificationHelper.cancelSensorWarning()
        if (sharedKey != null) scope?.launch { preferences.saveDexcomG7Key(bytesToHex(sharedKey)) }
    }

    override fun onNeedsOfficialPairing() = warnOfficialPairing()

    override fun onWrongCode() {
        scope?.launch { preferences.saveDexcomG7Key("") }
        notificationHelper.showSensorWarning(
            "Código do Dexcom G7 não confere",
            "Confira o código de pareamento de 4 dígitos do aplicador e configure o sensor de novo."
        )
    }

    private fun warnOfficialPairing() {
        notificationHelper.showSensorWarning(
            "Pareie o G7 no app oficial",
            "O GluView lê o Dexcom G7 depois que ele foi pareado pelo app oficial da Dexcom neste celular. " +
                "Pareie lá e mantenha o app oficial como referência."
        )
    }

    override fun onReading(packet: ByteArray) {
        val now = System.currentTimeMillis()
        val r = DexcomG7Data.reading(packet, now) ?: run {
            appLogger.error(LOG, "Registro de glicemia do G7 inválido"); return
        }
        scope?.launch { handle(r, now) }
    }

    override fun onBackfill(stream: ByteArray) {
        val start = sensorStartMs
        if (start <= 0) return
        val window = backfillWindow ?: return
        backfillWindow = null
        scope?.launch {
            val items = DexcomG7Data.backfill(stream, start)
                .filter { it.atMs in window }
                .sortedBy { it.atMs }
            items.forEach { store(it, makeCurrent = false) }
            appLogger.usage(LOG, "Histórico do G7 gravado", mapOf("leituras" to items.size))
        }
    }

    private suspend fun handle(r: G7Reading, now: Long) {
        sensorStartMs = r.sensorStartMs
        statusHolder.onSensorClock(((now - r.sensorStartMs) / 60_000L).toInt().coerceAtLeast(0), r.sensorStartMs)
        statusHolder.onSensorLifetime(
            expiryMinutes = ((G7Lifecycle.LIFE_SEC + G7Lifecycle.GRACE_SEC) / 60),
            sessionEnded = r.state == G7SensorState.EXPIRED || r.state == G7SensorState.ENDED
        )
        if (r.state != lastState) {
            lastState = r.state
            appLogger.usage(LOG, "Estado do G7", mapOf("estado" to r.state.name))
            when (r.state) {
                G7SensorState.WARMING_UP -> notificationHelper.showSensorWarning("Dexcom G7 aquecendo",
                    "As leituras aparecem depois do aquecimento (cerca de 30 min).")
                G7SensorState.EXPIRED, G7SensorState.ENDED -> notificationHelper.showSensorWarning("Dexcom G7 expirou",
                    "O sensor chegou ao fim da vida útil. Troque o sensor.")
                G7SensorState.FAILED -> notificationHelper.showSensorWarning("Falha no Dexcom G7",
                    "O sensor informou uma falha. Confira no app oficial e troque o sensor se for o caso.")
                G7SensorState.OK -> notificationHelper.cancelSensorWarning()
                else -> {}
            }
        }
        if (!r.valid) {
            appLogger.usage(LOG, "Leitura do G7 não usável", mapOf("estado" to r.state.name, "mgdl" to r.mgdl))
            return
        }
        // Buraco desde a última leitura gravada: pede o histórico do sensor (até 24 h).
        val gapFrom = maxOf(lastStoredMs, r.sensorStartMs, now - MAX_BACKFILL_MS)
        if (lastStoredMs > 0 && r.atMs - gapFrom > PERIOD_MS * 2) {
            backfillWindow = (gapFrom + PERIOD_MS / 2)..(r.atMs - PERIOD_MS / 2)
            ble.requestBackfill(DexcomG7Data.dexTime(r.sensorStartMs, gapFrom), DexcomG7Data.dexTime(r.sensorStartMs, r.atMs - PERIOD_MS / 2))
        }
        store(r, makeCurrent = true)
    }

    private suspend fun store(r: G7Reading, makeCurrent: Boolean) {
        val delta = if (makeCurrent && lastSgv >= 0) r.mgdl - lastSgv else 0
        val ageMin = ((r.atMs - r.sensorStartMs) / 60_000L).toInt().coerceAtLeast(0)
        repository.storeExternalReading(
            dateMs = r.atMs,
            sgvMgDl = r.mgdl.toDouble(),
            arrow = if (makeCurrent) DexcomG7Data.arrow(r.ratePerMin) else "",
            delta = delta,
            makeCurrent = makeCurrent,
            rawMmol = r.mgdl / MMOL_TO_MGDL,
            calibFactor = 1.0,
            sensorMac = currentMac ?: "",
            sensorAgeMin = ageMin,
            confirmed = null
        )
        if (makeCurrent) {
            lastSgv = r.mgdl
            statusHolder.onReading(ageMin, r.sensorStartMs, r.atMs)
        }
        if (r.atMs > lastStoredMs) lastStoredMs = r.atMs
    }

    private fun bytesToHex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun hexToBytes(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
