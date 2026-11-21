package com.gluview.app.data.librebridge

import com.gluview.app.data.datastore.AppPreferences
import com.gluview.app.data.repository.GlucoseRepository
import com.gluview.app.data.sibionics.SibionicsSensorStatus
import com.gluview.app.data.sibionics.SibionicsStatusHolder
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
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fonte de glicemia para leitores acoplados ao Libre: MiaoMiao/Tomato, Bubble e Atom (F6.4).
 *
 * Porte fiel do fluxo xDrip (GPLv3):
 * - DexCollectionService: conecta ao periférico BLE, habilita notify (HM10/NUS) e envia comandos de
 *   inicialização; encaminha pacotes ao decoder do dispositivo (Tomato/Bubble/Atom) que acumula FRAM.
 * - Tomato.java/Bubble.java/Atom.java: montagem de FRAM (344 bytes, Libre 1/2) + patchUid/patchInfo/serial.
 * - NFCReaderX/LibreUtils: validação (CRC/status) e extração de glicemia do FRAM (Libre 1 em claro);
 *   Libre 2 cifrado exige decodificação compatível (tentativa via ponte nativa Abbott quando disponível).
 *
 * Limites (documentados em F064_protocolo/relatorio, A.7): apenas Libre 1 e Libre 2; NUNCA anuncia Libre 2
 * Plus/3. Fonte marcada "Sem teste" e NÃO confiável para futura alça (leitura passa por periférico externo,
 * BLE e reconstrução de FRAM). Sem hardware/conta: implementado com testes de vetor/simulador de pacotes;
 * pendências reais registradas no relatório.
 *
 * Leituras gravam via GlucoseRepository.storeExternalReading (ponto único; calibração F3 aplicada ali).
 */
@Singleton
class LibreBridgeSource @Inject constructor(
    private val ble: LibreBridgeBleManager,
    private val repository: GlucoseRepository,
    private val statusHolder: SibionicsStatusHolder,
    private val preferences: AppPreferences,
    private val appLogger: AppLogger
) : LibreBridgeBleManager.Listener, GlucoseSource {

    companion object {
        private const val LOG = "BRIDGE_SRC"
        /** Fonte não confiável para alça/automação — constante explícita (A.7/F6.4). */
        const val RELIABLE_FOR_LOOP = false
        private val RECONNECT_SCHEDULE_MS = longArrayOf(60_000, 60_000, 60_000, 300_000, 300_000, 600_000)
        private const val PREF_MAC = "libre_bridge_mac"
        private const val PREF_KIND = "libre_bridge_kind"
    }

    @Volatile private var scope: CoroutineScope? = null
    @Volatile private var reconnectJob: Job? = null
    @Volatile private var activeType: DataSourceType? = null
    @Volatile private var kind: LibreBridgeBleManager.BridgeKind = LibreBridgeBleManager.BridgeKind.MIAOMIAO
    @Volatile private var tomato: TomatoDecoder? = null
    @Volatile private var bubble: BubbleDecoder? = null
    @Volatile private var atom: AtomDecoder? = null
    @Volatile private var mac: String? = null
    @Volatile private var lastSgv: Int = -1

    override fun start(type: DataSourceType) {
        if (scope != null && activeType == type) return
        stop()
        activeType = type
        kind = when (type) {
            DataSourceType.LIBRE_BRIDGE_BUBBLE -> LibreBridgeBleManager.BridgeKind.BUBBLE
            DataSourceType.LIBRE_BRIDGE_ATOM -> LibreBridgeBleManager.BridgeKind.ATOM
            else -> LibreBridgeBleManager.BridgeKind.MIAOMIAO
        }
        tomato = TomatoDecoder(appLogger); bubble = BubbleDecoder(appLogger); atom = AtomDecoder(appLogger)
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s
        ble.setListener(this)
        s.launch {
            val savedMac = preferences.libreBridgeMac.first()
            val savedKind = preferences.libreBridgeKind.first()
            if (savedKind.isNotBlank()) {
                kind = runCatching { LibreBridgeBleManager.BridgeKind.valueOf(savedKind) }.getOrDefault(kind)
            }
            mac = savedMac.ifBlank { null }
            if (savedMac.isBlank()) {
                appLogger.error(LOG, "Nenhum MAC de leitor salvo — configure o leitor em Sensores (Sem teste)")
                return@launch
            }
            appLogger.usage(LOG, "Iniciando bridge", mapOf("tipo" to type.id, "mac" to savedMac, "kind" to kind.name, "confiavelParaAlca" to RELIABLE_FOR_LOOP))
            ble.connect(savedMac, kind)
        }
    }

    override fun stop() {
        reconnectJob?.cancel(); reconnectJob = null
        ble.setListener(null)
        ble.disconnect()
        scope?.cancel(); scope = null
        activeType = null
        lastSgv = -1
    }

    override fun onConnectionChanged(connected: Boolean) {
        statusHolder.onConnectionChanged(connected)
        if (!connected) scheduleReconnect()
    }

    override fun onReady(kind: LibreBridgeBleManager.BridgeKind) {
        this.kind = kind
        reconnectJob?.cancel(); reconnectJob = null
        val cmds = when (kind) {
            LibreBridgeBleManager.BridgeKind.MIAOMIAO -> tomato?.initializeCommands().orEmpty()
            LibreBridgeBleManager.BridgeKind.BUBBLE -> bubble?.initializeCommands().orEmpty()
            LibreBridgeBleManager.BridgeKind.ATOM -> atom?.initializeCommands().orEmpty()
        }
        cmds.forEach { ble.write(it) }
        appLogger.usage(LOG, "Bridge pronto, comandos iniciais enviados", mapOf("kind" to kind.name, "cmds" to cmds.size))
    }

    override fun onPacket(data: ByteArray) {
        val (reply, reading) = when (kind) {
            LibreBridgeBleManager.BridgeKind.MIAOMIAO -> (tomato ?: TomatoDecoder(appLogger)).decode(data)
            LibreBridgeBleManager.BridgeKind.BUBBLE -> (bubble ?: BubbleDecoder(appLogger)).decode(data)
            LibreBridgeBleManager.BridgeKind.ATOM -> (atom ?: AtomDecoder(appLogger)).decode(data)
        }
        reply.commands.forEach { ble.write(it) }
        if (reply.hasError) {
            appLogger.error(LOG, "Erro do leitor", mapOf("erro" to (reply.errorMessage ?: "?"), "kind" to kind.name))
            return
        }
        reading?.let { handleFram(it) }
    }

    override fun onRssi(rssi: Int) { statusHolder.onRssi(rssi) }

    private fun handleFram(reading: BridgeFramReading) {
        scope?.launch {
            val decoded = LibreBridgeFramDecoder.decode(reading, appLogger) ?: run {
                appLogger.error(LOG, "FRAM recebido mas não decodificado — sem gravação", mapOf("serial" to reading.serial, "fonte" to reading.sourceHint))
                return@launch
            }
            val now = System.currentTimeMillis()
            // Âncora simples: sem idade real do sensor no bridge genérico, usamos sensorAgeMin=0 e start=now-? não confiável;
            // registramos timestamp de recepção. Leitura velha nunca tratada como atual além do pipeline normal.
            val delta = if (lastSgv > 0) decoded.mgdl - lastSgv else 0
            repository.storeExternalReading(
                dateMs = reading.captureTimeMs,
                sgvMgDl = decoded.mgdl.toDouble(),
                arrow = arrowForDelta(delta),
                delta = delta,
                makeCurrent = true,
                rawMmol = decoded.mgdl / 18.0182,
                calibFactor = null,
                sensorMac = mac ?: "",
                sensorAgeMin = decoded.sensorTimeMinutes,
                confirmed = null
            )
            lastSgv = decoded.mgdl
            statusHolder.onReading(index = decoded.sensorTimeMinutes, sensorStartMs = now - decoded.sensorTimeMinutes * 60_000L, nowMs = now)
            statusHolder.update { it.copy(serial = decoded.serial.ifBlank { it.serial }) }
            appLogger.usage(LOG, "Leitura bridge gravada", mapOf("mgdl" to decoded.mgdl, "serial" to decoded.serial, "kind" to kind.name))
        }
    }

    private fun arrowForDelta(delta: Int): String = when {
        delta <= -15 -> "DoubleDown"
        delta <= -7 -> "SingleDown"
        delta <= -3 -> "FortyFiveDown"
        delta < 3 -> "Flat"
        delta < 7 -> "FortyFiveUp"
        delta < 15 -> "SingleUp"
        else -> "DoubleUp"
    }

    private fun scheduleReconnect() {
        val m = mac ?: return
        reconnectJob?.cancel()
        reconnectJob = scope?.launch {
            for (d in RECONNECT_SCHEDULE_MS) {
                if (!isActive) return@launch
                delay(d)
                if (!isActive) return@launch
                appLogger.usage(LOG, "Tentando reconectar bridge", mapOf("mac" to m, "kind" to kind.name))
                ble.connect(m, kind)
            }
        }
    }
}
