package com.gluview.app.data.libre2

import com.gluview.app.data.datastore.AppPreferences
import com.gluview.app.data.source.DataSourceType
import com.gluview.app.data.source.GlucoseSource
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
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fonte Libre 2 Plus experimental em background.
 *
 * Como o decoder ainda nao foi validado, esta fonte nao escreve Room, statusHolder,
 * notificacoes ou GluSync. Ela somente coleta e registra o fluxo BLE. O Sibionics
 * permanece em outra fonte, com manager, protocolo e ciclo de reconexao proprios.
 */
@Singleton
class Libre2ExperimentalSource @Inject constructor(
    private val ble: Libre2ExperimentalBleManager,
    private val preferences: AppPreferences,
    private val appLogger: AppLogger
) : Libre2ExperimentalBleManager.Listener, GlucoseSource {
    private companion object {
        const val LOG = "LIBRE2_EXP_SRC"
        const val GATT_HANDSHAKE_MS = 20_000L
        val RECONNECT_SCHEDULE_MS = buildList {
            repeat(5) { add(TimeUnit.MINUTES.toMillis(1)) }
            repeat(3) { add(TimeUnit.MINUTES.toMillis(5)) }
            repeat(4) { add(TimeUnit.MINUTES.toMillis(10)) }
        }
    }

    @Volatile private var scope: CoroutineScope? = null
    @Volatile private var reconnectJob: Job? = null
    @Volatile private var currentMac: String? = null
    @Volatile private var stopped = false
    @Volatile private var decoder: Libre2ExperimentalDecoder? = null

    override fun start(type: DataSourceType) {
        if (scope != null) return
        stopped = false
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = newScope
        newScope.launch {
            val mac = preferences.libre2Mac.first()
            val uid = parseHexPreference("uid", preferences.libre2Uid.first())
            val patchInfo = parseHexPreference("patchInfo", preferences.libre2PatchInfo.first())
            decoder = Libre2ExperimentalDecoder(appLogger)
            appLogger.usage(LOG, "Coletor Libre 2 Plus experimental iniciado",
                mapOf("fonte" to type.label, "macSalvo" to mac.isNotBlank(),
                      "uidBytes" to uid.size, "patchInfoBytes" to patchInfo.size,
                      "persisteLeitura" to false, "usaNative" to false))

            if (mac.isBlank()) {
                appLogger.error(LOG, "Coletor experimental sem MAC salvo; nenhum BLE sera iniciado")
                return@launch
            }
            currentMac = mac
            ble.setListener(this@Libre2ExperimentalSource)
            ble.connect(mac)
        }
    }

    private fun parseHexPreference(name: String, value: String): ByteArray =
        runCatching { if (value.isBlank()) ByteArray(0) else hexToBytes(value) }
            .onFailure { error ->
                appLogger.error(LOG, "Preferencia hex invalida no coletor experimental",
                    mapOf("campo" to name, "erro" to (error.message ?: error::class.simpleName.orEmpty())))
            }
            .getOrDefault(ByteArray(0))

    override fun stop() {
        stopped = true
        reconnectJob?.cancel()
        reconnectJob = null
        ble.setListener(null)
        ble.stopScan()
        ble.disconnect()
        scope?.cancel()
        scope = null
        decoder = null
        currentMac = null
        appLogger.usage(LOG, "Coletor Libre 2 Plus experimental parado")
    }

    override fun onConnectionChanged(connected: Boolean) {
        appLogger.usage(LOG, "Estado do coletor experimental",
            mapOf("connected" to connected, "mac" to (currentMac ?: "?")))
        if (connected) {
            reconnectJob?.cancel()
            reconnectJob = null
        } else if (!stopped) {
            currentMac?.let { startReconnect(it) }
        }
    }

    override fun onPacket(uuid: UUID, value: ByteArray) {
        runCatching { decoder?.accept(uuid, value) }
            .onFailure { error ->
                appLogger.error(LOG, "Erro do decoder capturado sem interromper a fonte",
                    mapOf("uuid" to uuid, "bytes" to value.size,
                          "erro" to (error.message ?: error::class.simpleName.orEmpty())))
            }
    }

    override fun onRssi(rssi: Int) {
        appLogger.usage(LOG, "RSSI do coletor experimental", mapOf("rssi" to rssi))
    }

    override fun onReady() {
        appLogger.usage(LOG, "Coletor experimental pronto; aguardando f002 sem persistencia")
    }

    private fun startReconnect(mac: String) {
        reconnectJob?.cancel()
        reconnectJob = scope?.launch {
            appLogger.usage(LOG, "Reconexao experimental agendada",
                mapOf("mac" to mac, "tentativas" to RECONNECT_SCHEDULE_MS.size))
            RECONNECT_SCHEDULE_MS.forEachIndexed { index, waitMs ->
                delay(waitMs)
                if (!isActive) return@launch
                appLogger.usage(LOG, "Tentativa de reconexao experimental",
                    mapOf("tentativa" to "${index + 1}/${RECONNECT_SCHEDULE_MS.size}"))
                runCatching { ble.connect(mac) }
                    .onFailure { error ->
                        appLogger.error(LOG, "Falha isolada ao reconectar experimental",
                            mapOf("erro" to (error.message ?: error::class.simpleName.orEmpty())))
                    }
                delay(GATT_HANDSHAKE_MS)
            }
            if (!stopped) appLogger.error(LOG, "Reconexao experimental esgotada",
                mapOf("mac" to mac, "tentativas" to RECONNECT_SCHEDULE_MS.size))
        }
    }
}
