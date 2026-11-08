package com.gluview.app.data.accuchek

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.gluview.app.data.accuchek.cgm.CgmFeature
import com.gluview.app.data.accuchek.cgm.CgmFeatureParser
import com.gluview.app.data.accuchek.cgm.CgmMeasurement
import com.gluview.app.data.accuchek.cgm.CgmMeasurementParser
import com.gluview.app.data.accuchek.cgm.CgmParse
import com.gluview.app.data.accuchek.cgm.CgmStatus
import com.gluview.app.data.accuchek.cgm.CgmStatusParser
import com.gluview.app.data.accuchek.cgm.CrcState
import com.gluview.app.data.accuchek.cgm.Racp
import com.gluview.app.data.accuchek.cgm.SessionRunTime
import com.gluview.app.data.accuchek.cgm.SessionRunTimeParser
import com.gluview.app.data.accuchek.cgm.SessionStartTime
import com.gluview.app.data.accuchek.cgm.SessionStartTimeParser
import com.gluview.app.data.accuchek.cgm.SmartGuideGatt
import com.gluview.app.data.accuchek.cgm.SmartGuideIdentityMatcher
import com.gluview.app.data.accuchek.cgm.Socp
import com.gluview.app.data.sensor.SensorCode
import com.gluview.app.util.AppLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.ArrayDeque
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bluetooth do Accu-Chek SmartGuide — serviço CGM padrão (0x181F). Ver o protocolo em
 * [com.gluview.app.data.accuchek.cgm] e o plano em `Projeto DM1/GluView/12_sprint_accuchek_smartguide.md`.
 *
 * Referências: Juggluco `AccuGattCallback.java` (ordem dos comandos) e xDrip `libglupro`
 * `GluProBle.java` (pareamento por PIN, reconexão alternando conexão direta e busca).
 *
 * Fluxo:
 *  1. Achar o sensor: pareado no celular (`AC-<serial>` em bondedDevices) → endereço salvo →
 *     busca pelo nome. O endereço Bluetooth do sensor muda (privado resolvível); depois de pareado
 *     o Android resolve pelo pareamento, então a reconexão usa o aparelho pareado. Tentativas
 *     alternam conexão direta e "buscar e conectar" (xDrip `useScanConnectStrategy`).
 *  2. Conectar → descobrir serviços. Sem pareamento: `createBond()` com o PIN de 6 dígitos da tampa
 *     do aplicador já armado — o app responde ao pedido de pareamento do Android sozinho
 *     ([BluetoothDevice.setPin]); sem PIN, ou se falhar, aparece a janela do sistema.
 *  3. Pareado: liga notify no CGM Measurement e indicate no RACP e no SOCP; lê Feature (E2E-CRC),
 *     Status (hora de início), Session Start/Run Time, serial/firmware; pede o intervalo ao SOCP
 *     (só diagnóstico, como o Juggluco). Então [Listener.onReady] — a fonte pede o histórico com
 *     [requestHistory].
 *
 * Todas as operações GATT passam por uma fila (o Android só aceita uma por vez); cada uma tem
 * prazo de [OP_TIMEOUT_MS].
 */
@Singleton
class AccuChekBleManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appLogger: AppLogger
) {
    enum class PairingState { BONDING, BONDED, FAILED, LOST }

    /** Eventos para quem acompanha o pareamento de fora da fonte (assistente de configuração). */
    sealed interface LinkEvent {
        data class Pairing(val state: PairingState) : LinkEvent
        data object Ready : LinkEvent
    }

    private val _events = MutableSharedFlow<LinkEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<LinkEvent> = _events.asSharedFlow()

    private fun notifyPairing(state: PairingState) {
        listener?.onPairingStateChanged(state)
        _events.tryEmit(LinkEvent.Pairing(state))
    }

    interface Listener {
        /** true só depois da configuração completa ([onReady]); false em qualquer queda. */
        fun onConnectionChanged(connected: Boolean)
        fun onPairingStateChanged(state: PairingState) {}
        fun onFeature(feature: CgmFeature) {}
        /** [receivedAtMs] = quando o status chegou (o início do sensor é este instante − minutos). */
        fun onStatus(status: CgmStatus, receivedAtMs: Long)
        fun onSessionStartTime(start: SessionStartTime) {}
        fun onSessionRunTime(runTime: SessionRunTime) {}
        fun onDeviceInfo(serial: String?, firmware: String?) {}
        /** Configuração concluída; [address] é o endereço do aparelho pareado (para reconectar). */
        fun onReady(address: String, advertisedName: String?)
        fun onMeasurements(records: List<CgmMeasurement>, receivedAtMs: Long)
        fun onHistoryComplete(response: Racp.Response.Code) {}
        fun onRssi(rssi: Int) {}
        fun onBluetoothTurnedOn() {}
    }

    private companion object {
        const val LOG = "ACCUCHEK_BLE"
        const val OP_TIMEOUT_MS = 10_000L
        const val SCAN_TIMEOUT_MS = 30_000L
        const val DISCOVER_DELAY_MS = 600L
        const val MAX_START_TRIES = 8
        const val START_RETRY_MS = 100L

        // Falhas de segurança do GATT (sensor que não reconhece mais o pareamento deste celular).
        const val GATT_INSUFFICIENT_AUTHENTICATION = 5
        const val GATT_INSUFFICIENT_ENCRYPTION = 15
        const val GATT_AUTH_FAIL = 137
    }

    private val adapter: BluetoothAdapter? by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }
    private val handler = Handler(Looper.getMainLooper())
    private val matcher = SmartGuideIdentityMatcher()
    private val lock = Any()

    @Volatile private var listener: Listener? = null
    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var targetSerial: String? = null
    @Volatile private var targetAddress: String? = null
    @Volatile private var pairingPin: String? = null
    @Volatile private var e2eCrc = true
    @Volatile private var ready = false
    @Volatile private var setupStarted = false
    @Volatile private var bondRequested = false
    @Volatile private var deviceInfoRead = false
    @Volatile private var connectAttempt = 0

    private val chars = HashMap<UUID, BluetoothGattCharacteristic>()

    fun setListener(l: Listener?) { listener = l }

    /** PIN de 6 dígitos da tampa do aplicador; null/vazio = deixa a janela de pareamento do sistema. */
    fun setPairingPin(pin: String?) {
        pairingPin = pin?.trim()?.takeIf { it.length == 6 && it.all(Char::isDigit) }
    }

    val isReady: Boolean get() = ready

    // ── Receivers do sistema ─────────────────────────────────────────────────────────────────────
    init {
        register(IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), exported = false) { intent ->
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                BluetoothAdapter.STATE_OFF, BluetoothAdapter.STATE_TURNING_OFF -> if (gatt != null) {
                    appLogger.error(LOG, "Bluetooth do sistema desligado — fechando a conexão")
                    closeGatt(notify = true)
                }
                BluetoothAdapter.STATE_ON -> {
                    appLogger.usage(LOG, "Bluetooth do sistema religado")
                    listener?.onBluetoothTurnedOn()
                }
            }
        }
        register(IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED), exported = true) { intent ->
            val device = intent.device() ?: return@register
            if (!device.address.equals(targetAddress, ignoreCase = true)) return@register
            val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
            val previous = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.ERROR)
            appLogger.usage(LOG, "Estado do pareamento mudou", mapOf("estado" to state, "anterior" to previous))
            onBondStateChanged(state, previous)
        }
        // Pedido de pareamento: ordered broadcast. Prioridade logo abaixo da do sistema para responder
        // com o PIN antes do app Configurações e interromper o broadcast (sem janela) — mesmo
        // mecanismo do cgm-probe e do xDrip (`setPin`).
        register(
            IntentFilter(BluetoothDevice.ACTION_PAIRING_REQUEST).apply { priority = IntentFilter.SYSTEM_HIGH_PRIORITY - 1 },
            exported = true
        ) { intent -> onPairingRequest(intent) }
    }

    private fun register(filter: IntentFilter, exported: Boolean, body: BroadcastReceiver.(Intent) -> Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) = body(this, intent)
        }
        val flag = if (exported) ContextCompat.RECEIVER_EXPORTED else ContextCompat.RECEIVER_NOT_EXPORTED
        runCatching { ContextCompat.registerReceiver(context, receiver, filter, flag) }
            .onFailure { e -> appLogger.error(LOG, "Falha ao registrar receiver", mapOf("acao" to filter.getAction(0), "erro" to (e::class.simpleName ?: "?"))) }
    }

    private fun Intent.device(): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= 34) getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        else @Suppress("DEPRECATION") getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)

    @SuppressLint("MissingPermission")
    private fun BroadcastReceiver.onPairingRequest(intent: Intent) {
        val device = intent.device() ?: return
        if (!device.address.equals(targetAddress, ignoreCase = true)) return
        val variant = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, -1)
        val pin = pairingPin
        appLogger.usage(LOG, "Pedido de pareamento do Android", mapOf("variante" to variant, "pinArmado" to (pin != null)))
        // O SmartGuide pede passkey, que o Android entrega como a variante PIN. Outras: o sistema cuida.
        if (pin == null || variant != BluetoothDevice.PAIRING_VARIANT_PIN) return
        // Só no build de teste: simula a falha da resposta automática (cenário 3 do plano 14_) para
        // exercitar a janela do sistema. Ligado pelo arquivo files/dev-sem-pin-automatico.
        if (com.gluview.app.BuildConfig.DEV_SENSOR_UNLOCK &&
            java.io.File(context.getExternalFilesDir(null), "dev-sem-pin-automatico").exists()) {
            appLogger.usage(LOG, "PIN automático desligado (teste) — fica a janela do sistema")
            return
        }
        runCatching {
            if (device.setPin(pin.toByteArray(Charsets.US_ASCII))) {
                if (isOrderedBroadcast) abortBroadcast()
                appLogger.usage(LOG, "PIN enviado pelo app (sem janela do sistema)")
            } else {
                appLogger.error(LOG, "setPin recusado — fica a janela do sistema")
            }
        }.onFailure { e -> appLogger.error(LOG, "setPin falhou", mapOf("erro" to (e::class.simpleName ?: "?"))) }
    }

    private fun onBondStateChanged(state: Int, previous: Int) {
        when (state) {
            BluetoothDevice.BOND_BONDING -> notifyPairing(PairingState.BONDING)
            BluetoothDevice.BOND_BONDED -> {
                notifyPairing(PairingState.BONDED)
                val g = gatt
                if (g != null && chars.isNotEmpty() && !setupStarted) startSetup(g)
            }
            BluetoothDevice.BOND_NONE -> if (previous == BluetoothDevice.BOND_BONDING) {
                appLogger.error(LOG, "Pareamento falhou (PIN errado, cancelado ou sensor longe)")
                notifyPairing(PairingState.FAILED)
                closeGatt(notify = true)
            }
        }
    }

    // ── Localizar e conectar ─────────────────────────────────────────────────────────────────────

    /**
     * Conecta ao sensor de [serial] (pode ser null enquanto não há sensor configurado: aceita o
     * primeiro `AC-…` encontrado). [savedAddress] = endereço salvo da última configuração.
     * Tentativas ímpares conectam direto; pares buscam pelo nome antes (o endereço pode ter mudado).
     */
    @SuppressLint("MissingPermission")
    fun connectToSensor(serial: String?, savedAddress: String?) {
        targetSerial = serial?.takeIf { it.isNotBlank() }
        val attempt = ++connectAttempt
        val bonded = findBondedSensor(targetSerial)
        val direct = bonded?.address ?: savedAddress?.takeIf { it.isNotBlank() }
        val scanFirst = direct == null || (attempt % 2 == 0 && bonded == null)
        appLogger.usage(LOG, "Procurando o sensor", mapOf(
            "tentativa" to attempt, "pareadoNoCelular" to (bonded != null),
            "enderecoSalvo" to !savedAddress.isNullOrBlank(), "modo" to if (scanFirst) "busca" else "direto"
        ))
        if (scanFirst) {
            startScan(targetSerial, onTimeout = { listener?.onConnectionChanged(false) }) { _, address, _ ->
                stopScan()
                connect(address)
            }
        } else {
            connect(direct!!)
        }
    }

    /** Sensor já pareado com este celular (por este app ou pelo app oficial — o pareamento é do celular). */
    @SuppressLint("MissingPermission")
    fun findBondedSensor(serial: String?): BluetoothDevice? = runCatching {
        adapter?.bondedDevices?.firstOrNull { d -> nameMatches(d.name, serial) }
    }.getOrNull()

    private fun nameMatches(name: String?, serial: String?): Boolean =
        if (serial.isNullOrBlank()) SmartGuideGatt.looksLikeSmartGuide(name)
        else matcher.matches(name, SensorCode(connectionCode = serial, serial = serial))

    private var scanCallback: ScanCallback? = null
    @Volatile private var scanTimeoutAction: (() -> Unit)? = null
    private val scanTimeout = Runnable {
        if (scanCallback != null) {
            appLogger.usage(LOG, "Busca terminou sem achar o sensor", mapOf("serial" to (targetSerial ?: "qualquer")))
            val action = scanTimeoutAction
            stopScan()
            action?.invoke()
        }
    }

    /**
     * Busca `AC-<serial>` (ou qualquer `AC-…` sem serial). Para sozinha em [SCAN_TIMEOUT_MS] e então
     * chama [onTimeout] (a reconexão usa isso para agendar a próxima tentativa).
     */
    @SuppressLint("MissingPermission")
    fun startScan(
        serial: String?,
        onTimeout: (() -> Unit)? = null,
        onFound: (name: String?, address: String, rssi: Int) -> Unit
    ) {
        val scanner = adapter?.bluetoothLeScanner ?: run {
            appLogger.error(LOG, "Scanner Bluetooth indisponível (Bluetooth desligado?)")
            return
        }
        stopScan()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.scanRecord?.deviceName ?: runCatching { result.device.name }.getOrNull()
                if (!nameMatches(name, serial)) return
                appLogger.usage(LOG, "Busca achou o sensor", mapOf("nome" to (name ?: "?"), "rssi" to result.rssi))
                onFound(name, result.device.address, result.rssi)
            }
            override fun onScanFailed(errorCode: Int) {
                appLogger.error(LOG, "Busca Bluetooth falhou", mapOf("errorCode" to errorCode))
            }
        }
        scanCallback = cb
        // Sem filtro no rádio: nem todo sensor põe o 0x181F no anúncio; filtra pelo nome aqui.
        runCatching { scanner.startScan(null, settings, cb) }
            .onFailure { e -> appLogger.error(LOG, "startScan lançou exceção", mapOf("erro" to (e::class.simpleName ?: "?"))); scanCallback = null; return }
        scanTimeoutAction = onTimeout
        handler.removeCallbacks(scanTimeout)
        handler.postDelayed(scanTimeout, SCAN_TIMEOUT_MS)
        appLogger.usage(LOG, "Busca iniciada", mapOf("serial" to (serial ?: "qualquer AC-")))
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        handler.removeCallbacks(scanTimeout)
        scanTimeoutAction = null
        scanCallback?.let { cb -> runCatching { adapter?.bluetoothLeScanner?.stopScan(cb) } }
        scanCallback = null
    }

    @SuppressLint("MissingPermission")
    fun connect(address: String) {
        val a = adapter ?: run { appLogger.error(LOG, "BluetoothAdapter indisponível"); return }
        closeGatt(notify = false)
        val device = runCatching { a.getRemoteDevice(address) }.getOrNull() ?: run {
            appLogger.error(LOG, "Endereço inválido", mapOf("endereco" to address))
            return
        }
        targetAddress = device.address
        appLogger.usage(LOG, "Conectando", mapOf("endereco" to device.address, "pareado" to (device.bondState == BluetoothDevice.BOND_BONDED)))
        gatt = runCatching { device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE) }
            .onFailure { e -> appLogger.error(LOG, "connectGatt lançou exceção", mapOf("erro" to (e::class.simpleName ?: "?"))) }
            .getOrNull()
    }

    /** Desconecta a pedido (troca de fonte, app parando). Não mexe no pareamento. */
    fun disconnect() {
        appLogger.usage(LOG, "Desconectando (pedido)")
        stopScan()
        closeGatt(notify = true)
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt(notify: Boolean) {
        val g = gatt
        gatt = null
        synchronized(lock) {
            queue.clear()
            current = null
        }
        handler.removeCallbacks(opTimeout)
        handler.removeCallbacks(retryNext)
        chars.clear()
        val wasReady = ready
        ready = false
        setupStarted = false
        bondRequested = false
        g?.let { runCatching { it.disconnect() }; runCatching { it.close() } }
        if (notify && (g != null || wasReady)) listener?.onConnectionChanged(false)
    }

    // ── Histórico ────────────────────────────────────────────────────────────────────────────────

    /**
     * Pede ao sensor as leituras a partir do minuto [fromMinute] (null = tudo o que ele guarda,
     * as últimas 8 h). Os registros chegam por [Listener.onMeasurements] e o fim por
     * [Listener.onHistoryComplete].
     */
    fun requestHistory(fromMinute: Int?) {
        val cmd = fromMinute?.let { Racp.reportFromMinute(it) } ?: Racp.reportAll()
        appLogger.usage(LOG, "Pedindo histórico", mapOf("aPartirDoMinuto" to (fromMinute ?: "tudo"), "cmd" to cmd.toHexLog()))
        enqueue(GattOp.Write(SmartGuideGatt.RACP, cmd))
    }

    // ── Fila de operações GATT ───────────────────────────────────────────────────────────────────

    private sealed class GattOp(val label: String) {
        class Notify(val uuid: UUID, val indicate: Boolean, val required: Boolean)
            : GattOp("${if (indicate) "indicate" else "notify"} ${uuid.short()}")
        class Read(val uuid: UUID) : GattOp("read ${uuid.short()}")
        class Write(val uuid: UUID, val bytes: ByteArray) : GattOp("write ${uuid.short()}")
        class Rssi : GattOp("rssi")
        class Action(label: String, val block: () -> Unit) : GattOp(label)

        /** Tentativas de iniciar (o Android recusa enquanto outra operação termina). */
        var startTries = 0
    }

    private val queue = ArrayDeque<GattOp>()
    private var current: GattOp? = null
    private val retryNext = Runnable { next() }
    private val opTimeout = Runnable {
        val op = synchronized(lock) { current }
        appLogger.error(LOG, "Operação GATT sem resposta — reconectando", mapOf("op" to (op?.label ?: "?")))
        closeGatt(notify = true)
    }

    private fun enqueue(op: GattOp) {
        synchronized(lock) { queue.addLast(op) }
        next()
    }

    private fun completeCurrent() {
        handler.removeCallbacks(opTimeout)
        synchronized(lock) { current = null }
        next()
    }

    @SuppressLint("MissingPermission")
    private fun next() {
        val g = gatt ?: return
        val op = synchronized(lock) {
            if (current != null) return
            queue.pollFirst()?.also { current = it }
        } ?: return
        val started: Boolean = when (op) {
            is GattOp.Action -> { op.block(); synchronized(lock) { current = null }; next(); return }
            is GattOp.Notify -> {
                val c = chars[op.uuid]
                if (c == null) {
                    if (op.required) { appLogger.error(LOG, "Característica obrigatória ausente", mapOf("op" to op.label)); closeGatt(true); return }
                    false
                } else writeCccd(g, c, op.indicate)
            }
            is GattOp.Read -> chars[op.uuid]?.let { g.readCharacteristic(it) } ?: false
            is GattOp.Write -> chars[op.uuid]?.let { writeChar(g, it, op.bytes) } ?: false
            is GattOp.Rssi -> g.readRemoteRssi()
        }
        if (!started) {
            val missing = when (op) {
                is GattOp.Notify -> chars[op.uuid] == null
                is GattOp.Read -> chars[op.uuid] == null
                is GattOp.Write -> chars[op.uuid] == null
                else -> false
            }
            // Recusa do Android (ocupado): tenta de novo, como o `tryer` do Juggluco.
            if (!missing && ++op.startTries < MAX_START_TRIES) {
                synchronized(lock) { current = null; queue.addFirst(op) }
                handler.postDelayed(retryNext, START_RETRY_MS)
                return
            }
            appLogger.usage(LOG, "Operação pulada", mapOf("op" to op.label, "motivo" to if (missing) "característica ausente" else "recusada ${op.startTries}x"))
            completeCurrent()
            return
        }
        handler.removeCallbacks(opTimeout)
        handler.postDelayed(opTimeout, OP_TIMEOUT_MS)
    }

    /** Erro de segurança num sensor que o celular acha que está pareado = o sensor foi pareado em outro lugar. */
    private fun handleOpError(op: String, status: Int): Boolean {
        val authError = status == GATT_INSUFFICIENT_AUTHENTICATION || status == GATT_INSUFFICIENT_ENCRYPTION || status == GATT_AUTH_FAIL
        if (!authError) return false
        val bonded = gatt?.device?.bondState == BluetoothDevice.BOND_BONDED
        appLogger.error(LOG, "Sensor recusou por segurança", mapOf("op" to op, "status" to status, "pareadoNoCelular" to bonded))
        if (bonded) {
            notifyPairing(PairingState.LOST)
            closeGatt(notify = true)
        } else {
            requestBond(gatt ?: return true)
        }
        return true
    }

    // ── Configuração depois de conectar ──────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun requestBond(g: BluetoothGatt) {
        if (bondRequested) return
        bondRequested = true
        appLogger.usage(LOG, "Pareando com o sensor", mapOf("pinArmado" to (pairingPin != null)))
        val ok = runCatching { g.device.createBond() }.getOrDefault(false)
        if (!ok) {
            appLogger.error(LOG, "createBond recusado")
            notifyPairing(PairingState.FAILED)
            closeGatt(notify = true)
        }
    }

    private fun startSetup(g: BluetoothGatt) {
        if (setupStarted) return
        setupStarted = true
        appLogger.usage(LOG, "Configurando o sensor (pareado)")
        enqueue(GattOp.Notify(SmartGuideGatt.CGM_MEASUREMENT, indicate = false, required = true))
        enqueue(GattOp.Notify(SmartGuideGatt.RACP, indicate = true, required = true))
        enqueue(GattOp.Notify(SmartGuideGatt.SOCP, indicate = true, required = false))
        enqueue(GattOp.Read(SmartGuideGatt.CGM_FEATURE))
        enqueue(GattOp.Read(SmartGuideGatt.CGM_STATUS))
        enqueue(GattOp.Read(SmartGuideGatt.SESSION_START_TIME))
        enqueue(GattOp.Read(SmartGuideGatt.SESSION_RUN_TIME))
        if (!deviceInfoRead) {
            enqueue(GattOp.Read(SmartGuideGatt.SERIAL_NUMBER))
            enqueue(GattOp.Read(SmartGuideGatt.FIRMWARE_REVISION))
            enqueue(GattOp.Read(SmartGuideGatt.MANUFACTURER_NAME))
        }
        enqueue(GattOp.Action("pedir intervalo ao SOCP") {
            enqueue(GattOp.Write(SmartGuideGatt.SOCP, Socp.getCommunicationInterval(e2eCrc)))
            enqueue(GattOp.Action("pronto") { onSetupDone(g) })
        })
    }

    @SuppressLint("MissingPermission")
    private fun onSetupDone(g: BluetoothGatt) {
        if (gatt !== g) return
        ready = true
        connectAttempt = 0
        val name = runCatching { g.device.name }.getOrNull()
        appLogger.usage(LOG, "Sensor pronto", mapOf("endereco" to g.device.address, "nome" to (name ?: "?"), "e2eCrc" to e2eCrc))
        listener?.onConnectionChanged(true)
        listener?.onReady(g.device.address, name)
        _events.tryEmit(LinkEvent.Ready)
    }

    /**
     * Desfaz o pareamento do SmartGuide de [serial] neste celular (para parear de novo depois que o
     * sensor foi levado a outro aparelho, ou ao esquecer o sensor). Usa `removeBond`, que não é API
     * pública; devolve false se o Android não deixar — aí o usuário esquece o aparelho nos ajustes
     * de Bluetooth.
     */
    @SuppressLint("MissingPermission")
    fun forgetPairing(serial: String?): Boolean {
        val device = findBondedSensor(serial) ?: return true // nada pareado: nada a desfazer
        val ok = runCatching { device.javaClass.getMethod("removeBond").invoke(device) as? Boolean == true }.getOrDefault(false)
        appLogger.usage(LOG, "Desfazendo pareamento", mapOf("ok" to ok))
        return ok
    }

    // ── Callback GATT ────────────────────────────────────────────────────────────────────────────

    private val gattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (g !== gatt) { runCatching { g.close() }; return }
            appLogger.usage(LOG, "Estado da conexão mudou", mapOf(
                "novoEstado" to newState, "status" to status, "pareamento" to g.device.bondState
            ))
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    // Pequena espera antes de descobrir serviços: alguns aparelhos ainda estão
                    // trocando parâmetros/criptografia logo após conectar (sensor já pareado).
                    handler.postDelayed({
                        if (gatt === g && !g.discoverServices()) {
                            appLogger.error(LOG, "discoverServices() recusado")
                            closeGatt(notify = true)
                        }
                    }, DISCOVER_DELAY_MS)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (handleOpError("conexão", status)) return
                    closeGatt(notify = true)
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (g !== gatt) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                appLogger.error(LOG, "Descoberta de serviços falhou", mapOf("status" to status))
                closeGatt(notify = true)
                return
            }
            val cgm = g.getService(SmartGuideGatt.CGM_SERVICE)
            if (cgm == null) {
                appLogger.error(LOG, "Serviço CGM 0x181F ausente — não é um SmartGuide",
                    mapOf("servicos" to g.services.joinToString { it.uuid.short() }))
                closeGatt(notify = true)
                return
            }
            chars.clear()
            cgm.characteristics.forEach { chars[it.uuid] = it }
            g.getService(SmartGuideGatt.DEVICE_INFO_SERVICE)?.characteristics?.forEach { chars[it.uuid] = it }
            appLogger.usage(LOG, "Serviços descobertos", mapOf("caracteristicas" to chars.keys.joinToString { it.short() }))

            when (g.device.bondState) {
                BluetoothDevice.BOND_BONDED -> startSetup(g)
                BluetoothDevice.BOND_BONDING -> appLogger.usage(LOG, "Aguardando o pareamento terminar")
                else -> requestBond(g)
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (g !== gatt) return
            val uuid = descriptor.characteristic.uuid
            if (status != BluetoothGatt.GATT_SUCCESS) {
                if (handleOpError("cccd ${uuid.short()}", status)) return
                appLogger.error(LOG, "Falha ao ligar avisos", mapOf("char" to uuid.short(), "status" to status))
                val required = uuid == SmartGuideGatt.CGM_MEASUREMENT || uuid == SmartGuideGatt.RACP
                if (required) { closeGatt(notify = true); return }
            }
            completeCurrent()
        }

        @Deprecated("API < 33")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            @Suppress("DEPRECATION")
            handleRead(g, c.uuid, c.value ?: ByteArray(0), status)
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            handleRead(g, c.uuid, value, status)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (g !== gatt) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                if (handleOpError("write ${c.uuid.short()}", status)) return
                appLogger.error(LOG, "Escrita recusada", mapOf("char" to c.uuid.short(), "status" to status))
            }
            completeCurrent()
        }

        @Deprecated("API < 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            dispatch(g, c.uuid, c.value ?: return)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            dispatch(g, c.uuid, value)
        }

        override fun onReadRemoteRssi(g: BluetoothGatt, rssi: Int, status: Int) {
            if (g !== gatt) return
            if (status == BluetoothGatt.GATT_SUCCESS) listener?.onRssi(rssi)
            if (synchronized(lock) { current } is GattOp.Rssi) completeCurrent()
        }
    }

    private fun handleRead(g: BluetoothGatt, uuid: UUID, value: ByteArray, status: Int) {
        if (g !== gatt) return
        if (status != BluetoothGatt.GATT_SUCCESS) {
            if (handleOpError("read ${uuid.short()}", status)) return
            appLogger.error(LOG, "Leitura recusada", mapOf("char" to uuid.short(), "status" to status))
            completeCurrent()
            return
        }
        appLogger.usage(LOG, "Leitura", mapOf("char" to uuid.short(), "hex" to value.toHexLog()))
        val now = System.currentTimeMillis()
        when (uuid) {
            SmartGuideGatt.CGM_FEATURE -> CgmFeatureParser.parse(value).ok("Feature")?.let { f ->
                e2eCrc = f.e2eCrcSupported
                appLogger.usage(LOG, "Capacidades do sensor", mapOf(
                    "e2eCrc" to f.e2eCrcSupported, "variosPareamentos" to f.multipleBondSupported,
                    "tendencia" to f.trendSupported, "qualidade" to f.qualitySupported
                ))
                listener?.onFeature(f)
            }
            SmartGuideGatt.CGM_STATUS -> CgmStatusParser.parse(value).ok("Status")?.let { s ->
                if (s.crc == CrcState.INVALID) appLogger.error(LOG, "Status com CRC errado — ignorado")
                else {
                    appLogger.usage(LOG, "Status do sensor", mapOf("minuto" to s.timeOffsetMinutes, "avisos" to s.annunciation.toString()))
                    listener?.onStatus(s, now)
                }
            }
            SmartGuideGatt.SESSION_START_TIME -> SessionStartTimeParser.parse(value).ok("Start Time")?.let { listener?.onSessionStartTime(it) }
            SmartGuideGatt.SESSION_RUN_TIME -> SessionRunTimeParser.parse(value).ok("Run Time")?.let { listener?.onSessionRunTime(it) }
            SmartGuideGatt.SERIAL_NUMBER -> {
                deviceInfoRead = true
                listener?.onDeviceInfo(serial = value.toAscii(), firmware = null)
            }
            SmartGuideGatt.FIRMWARE_REVISION -> listener?.onDeviceInfo(serial = null, firmware = value.toAscii())
            SmartGuideGatt.MANUFACTURER_NAME -> appLogger.usage(LOG, "Fabricante", mapOf("nome" to value.toAscii()))
        }
        completeCurrent()
    }

    @SuppressLint("MissingPermission")
    private fun dispatch(g: BluetoothGatt, uuid: UUID, value: ByteArray) {
        if (g !== gatt) return
        val now = System.currentTimeMillis()
        when (uuid) {
            SmartGuideGatt.CGM_MEASUREMENT -> {
                appLogger.usage(LOG, "Medição recebida", mapOf("hex" to value.toHexLog()))
                when (val r = CgmMeasurementParser.parse(value)) {
                    is CgmParse.Ok -> {
                        r.value.filter { it.crc == CrcState.INVALID }.forEach {
                            appLogger.error(LOG, "Registro com CRC errado — descartado", mapOf("minuto" to it.timeOffsetMinutes))
                        }
                        val good = r.value.filter { it.crc != CrcState.INVALID }
                        if (good.isNotEmpty()) listener?.onMeasurements(good, now)
                    }
                    is CgmParse.Invalid -> appLogger.error(LOG, "Medição ilegível", mapOf("motivo" to r.reason))
                }
                if (ready) enqueue(GattOp.Rssi())
            }
            SmartGuideGatt.RACP -> {
                appLogger.usage(LOG, "Resposta do RACP", mapOf("hex" to value.toHexLog()))
                when (val r = Racp.parseResponse(value)) {
                    is CgmParse.Ok -> when (val resp = r.value) {
                        is Racp.Response.Code -> listener?.onHistoryComplete(resp)
                        is Racp.Response.NumberOfRecords -> appLogger.usage(LOG, "Registros guardados", mapOf("total" to resp.count))
                    }
                    is CgmParse.Invalid -> appLogger.error(LOG, "Resposta do RACP ilegível", mapOf("motivo" to r.reason))
                }
            }
            SmartGuideGatt.SOCP -> {
                val r = Socp.parseResponse(value, e2eCrc)
                appLogger.usage(LOG, "Resposta do SOCP", mapOf("hex" to value.toHexLog(), "lido" to (r.getOrNull()?.toString() ?: r.toString())))
            }
        }
    }

    private fun <T> CgmParse<T>.ok(what: String): T? = when (this) {
        is CgmParse.Ok -> value
        is CgmParse.Invalid -> { appLogger.error(LOG, "$what ilegível", mapOf("motivo" to reason)); null }
    }

    // ── Escritas de baixo nível ──────────────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun writeCccd(g: BluetoothGatt, c: BluetoothGattCharacteristic, indicate: Boolean): Boolean {
        val cccd = c.getDescriptor(SmartGuideGatt.CCCD) ?: return false
        if (!g.setCharacteristicNotification(c, true)) return false
        val value = if (indicate) BluetoothGattDescriptor.ENABLE_INDICATION_VALUE else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(cccd, value) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run { cccd.value = value; g.writeDescriptor(cccd) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeChar(g: BluetoothGatt, c: BluetoothGattCharacteristic, bytes: ByteArray): Boolean {
        appLogger.usage(LOG, "Escrevendo", mapOf("char" to c.uuid.short(), "hex" to bytes.toHexLog()))
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(c, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                c.value = bytes
                c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                g.writeCharacteristic(c)
            }
        }
    }
}

private fun UUID.short(): String = toString().substring(4, 8)

private fun ByteArray.toAscii(): String = String(this, Charsets.US_ASCII).trim { it <= ' ' }

private fun ByteArray.toHexLog(maxBytes: Int = 64): String =
    take(maxBytes).joinToString(" ") { "%02X".format(it) } +
        if (size > maxBytes) " …+${size - maxBytes}" else ""
