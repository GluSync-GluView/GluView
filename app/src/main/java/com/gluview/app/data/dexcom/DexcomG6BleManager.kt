package com.gluview.app.data.dexcom

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
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import com.gluview.app.util.AppLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.security.SecureRandom
import java.util.ArrayDeque
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * GATT BLE do Dexcom G6 — porte do `Ob1G5StateMachine` do xDrip (`com.eveningoutpost.dexdrip.g5model`,
 * GPLv3), revisado contra ele em 15/11/2026 (plano 14_, V3a). Ver [DexcomGatt]/[DexcomG6Crypto]/
 * [DexcomG6Tx]/[DexcomG6Rx] para UUIDs, cripto e pacotes.
 *
 * O G6 não fica conectado: ele anuncia a cada ~5 min, aceita uma conexão curta, entrega a leitura e
 * desconecta. Cada ciclo:
 *  1. Conecta (a primeira vez por busca do nome "Dexcom"+2 últimos do ID; depois pelo endereço com
 *     `autoConnect`, que espera o próximo anúncio sem gastar bateria com busca).
 *  2. INDICAÇÃO em Authentication (o G6 usa indicação, não notificação) → AuthRequest (0x01).
 *  3. 0x03 (tokenHash + desafio): o tokenHash prova que o transmissor tem a mesma chave do ID
 *     digitado; responde 0x04. Não bate = ID errado ([LinkEvent.WrongTransmitterId]).
 *  4. 0x05 (autenticado, pareado). Sem pareamento: KeepAlive(60) + createBond + BondRequest (0x07),
 *     espera o Android parear (até 10 s) e relê o status (`doKeepAliveAndBondRequest`).
 *  5. Pareado: INDICAÇÃO em Control → Glicemia (0x30) → 0x31. Se a fonte pedir: Hora (0x24 → 0x25)
 *     e Backfill (0x50 → 0x51 em Control + dados por notificação em Backfill).
 *  6. Disconnect (0x09) — o transmissor encerra e volta a anunciar no próximo ciclo.
 *
 * Operações GATT vão numa fila (uma por vez, a próxima no callback da anterior).
 */
@Singleton
class DexcomG6BleManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appLogger: AppLogger
) {
    interface Listener {
        fun onConnectionChanged(connected: Boolean)
        fun onAuthenticated()
        /** Leitura atual. Devolve o que pedir em seguida nesta conexão. */
        fun onGlucose(reading: DexcomGlucose): FollowUp
        fun onTransmitterTime(time: TransmitterTime)
        /** Janela de backfill (segundos do transmissor) agora que o relógio dele é conhecido, ou null. */
        fun backfillWindow(): Pair<Int, Int>?
        fun onBackfill(records: List<DexcomBackfillRecord>)
        fun onWrongTransmitterId() {}
        fun onRssi(rssi: Int) {}
        fun onBluetoothTurnedOn() {}
    }

    /** O que pedir depois da glicemia. */
    data class FollowUp(val askTime: Boolean)

    /** Eventos para quem espera o primeiro ciclo (o assistente). */
    sealed interface LinkEvent {
        data object Authenticated : LinkEvent
        data object WrongTransmitterId : LinkEvent
        data object GotGlucose : LinkEvent
    }

    companion object {
        private const val LOG = "DEXCOM_G6_BLE"
        private const val BOND_WAIT_MS = 10_000L
        /** Sem nada útil nesse tempo depois de conectar, a conexão é encerrada (o transmissor volta em 5 min). */
        private const val CYCLE_TIMEOUT_MS = 60_000L
        /** Silêncio na characteristic Backfill que conta como "acabou o backfill". */
        private const val BACKFILL_QUIET_MS = 1_500L
        private const val BACKFILL_MAX_MS = 15_000L

        /** Nome anunciado pelo transmissor: "Dexcom" + 2 últimos caracteres do Transmitter ID (`AdvMatcher`). */
        fun advertisedName(transmitterId: String): String = "Dexcom" + transmitterId.takeLast(2).uppercase()
    }

    private val adapter: BluetoothAdapter? by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }
    private val handler = Handler(Looper.getMainLooper())

    private var gatt: BluetoothGatt? = null
    private var chars: Map<UUID, BluetoothGattCharacteristic> = emptyMap()
    private var listener: Listener? = null

    private var transmitterId: String = ""
    private var key: ByteArray = ByteArray(0)
    private var myToken: ByteArray = ByteArray(0)
    @Volatile private var targetMac: String? = null
    @Volatile var isAuthenticated = false
        private set
    private var bondAttempted = false
    private var backfill: DexcomBackfillStream? = null
    private var backfillAccepted = false

    private val _events = MutableSharedFlow<LinkEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<LinkEvent> = _events.asSharedFlow()

    fun setListener(l: Listener?) { listener = l }

    /** Deve ser chamado ANTES de [connect] — o Transmitter ID (6 caracteres) deriva a chave AES. */
    fun configure(transmitterId: String) {
        this.transmitterId = transmitterId.uppercase()
        this.key = DexcomG6Crypto.deriveKey(this.transmitterId)
    }

    // ── Rádio do sistema e pareamento do Android ─────────────────────────────────────────────
    init {
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED).apply {
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    BluetoothAdapter.ACTION_STATE_CHANGED -> when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                        BluetoothAdapter.STATE_OFF, BluetoothAdapter.STATE_TURNING_OFF -> {
                            if (gatt != null) { appLogger.error(LOG, "Bluetooth do sistema desligado"); disconnect() }
                        }
                        BluetoothAdapter.STATE_ON -> {
                            appLogger.usage(LOG, "Bluetooth do sistema religado")
                            listener?.onBluetoothTurnedOn()
                        }
                    }
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                        @Suppress("DEPRECATION")
                        val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                        if (device?.address != targetMac) return
                        val bond = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)
                        appLogger.usage(LOG, "Pareamento do Android mudou", mapOf("estado" to bond))
                        if (bond == BluetoothDevice.BOND_BONDED && bondAttempted) handler.post { afterBondWait() }
                    }
                }
            }
        }
        runCatching {
            ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        }.onFailure { e -> appLogger.error(LOG, "Falha ao registrar receiver BT", mapOf("erro" to (e::class.simpleName ?: "?"))) }
    }

    // ── Busca (só na primeira vez: nome "DexcomXX" + serviço 0xFEBC) ──────────────────────────
    private var scanCallback: ScanCallback? = null

    @SuppressLint("MissingPermission")
    fun startScan(onFound: (name: String?, mac: String, rssi: Int) -> Unit) {
        val scanner = adapter?.bluetoothLeScanner ?: run { appLogger.error(LOG, "bluetoothLeScanner indisponível"); return }
        stopScan()
        val wanted = advertisedName(transmitterId)
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(DexcomGatt.ADVERTISEMENT_SERVICE)).build())
        val cb = object : ScanCallback() {
            @SuppressLint("MissingPermission")
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.scanRecord?.deviceName ?: result.device.name
                // Outro transmissor por perto (troca de transmissor, alguém da família) não serve.
                if (name == null || !name.equals(wanted, ignoreCase = true)) return
                appLogger.usage(LOG, "Transmissor encontrado na busca", mapOf("nome" to name, "mac" to result.device.address))
                onFound(name, result.device.address, result.rssi)
            }
            override fun onScanFailed(errorCode: Int) { appLogger.error(LOG, "Busca BLE falhou", mapOf("errorCode" to errorCode)) }
        }
        scanCallback = cb
        scanner.startScan(filters, settings, cb)
        appLogger.usage(LOG, "Busca iniciada", mapOf("nome" to wanted))
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        scanCallback?.let { runCatching { adapter?.bluetoothLeScanner?.stopScan(it) } }
        scanCallback = null
    }

    // ── Conexão ──────────────────────────────────────────────────────────────────────────────
    /**
     * [waitForAdvert] = `autoConnect`: o Android espera o próximo anúncio do transmissor (ciclo de 5 min)
     * sem prazo. A primeira conexão, logo depois da busca, é direta.
     */
    @SuppressLint("MissingPermission")
    fun connect(mac: String, waitForAdvert: Boolean) {
        val a = adapter ?: run { appLogger.error(LOG, "BluetoothAdapter==null"); return }
        closeGatt()
        targetMac = mac
        val device = runCatching { a.getRemoteDevice(mac) }.getOrNull() ?: run {
            appLogger.error(LOG, "MAC inválido", mapOf("mac" to mac)); return
        }
        appLogger.usage(LOG, "Conectando", mapOf("mac" to mac, "esperaAnuncio" to waitForAdvert))
        gatt = device.connectGatt(context, waitForAdvert, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    /** Encerra tudo (parar a fonte). */
    fun disconnect() {
        appLogger.usage(LOG, "Desconectando (pedido interno)")
        targetMac = null
        val had = gatt != null
        closeGatt()
        if (had) listener?.onConnectionChanged(false)
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        handler.removeCallbacksAndMessages(null)
        gatt?.let { runCatching { it.disconnect(); it.close() } }
        gatt = null
        resetCycle()
    }

    private fun resetCycle() {
        chars = emptyMap()
        queue.clear(); busy = false
        isAuthenticated = false
        bondAttempted = false
        bondReread = false
        backfill = null; backfillAccepted = false
    }

    /** Fim do ciclo pedido pelo app: manda 0x09 e fecha se o transmissor não fechar em 2 s. */
    private fun endCycle(reason: String) {
        appLogger.usage(LOG, "Fim do ciclo", mapOf("motivo" to reason))
        val control = chars[DexcomGatt.CONTROL]
        if (control != null && isAuthenticated) enqueue(Op.Write(control, DexcomG6Tx.disconnect()))
        handler.postDelayed({ gatt?.let { runCatching { it.disconnect() } } }, 2_000L)
    }

    // ── Fila GATT ────────────────────────────────────────────────────────────────────────────
    private sealed interface Op {
        val ch: BluetoothGattCharacteristic
        data class Subscribe(override val ch: BluetoothGattCharacteristic, val indicate: Boolean) : Op
        class Write(override val ch: BluetoothGattCharacteristic, val bytes: ByteArray) : Op
        data class Read(override val ch: BluetoothGattCharacteristic) : Op
    }

    private val queue = ArrayDeque<Op>()
    private var busy = false

    private fun enqueue(op: Op) { queue.addLast(op); if (!busy) next() }

    @SuppressLint("MissingPermission")
    private fun next() {
        val g = gatt ?: return
        val op = queue.pollFirst() ?: run { busy = false; return }
        busy = true
        val ok = when (op) {
            is Op.Subscribe -> {
                g.setCharacteristicNotification(op.ch, true)
                val cccd = op.ch.getDescriptor(DexcomGatt.CCCD)
                val value = if (op.indicate) BluetoothGattDescriptor.ENABLE_INDICATION_VALUE else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                when {
                    cccd == null -> false
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> g.writeDescriptor(cccd, value) == BluetoothGatt.GATT_SUCCESS
                    else -> @Suppress("DEPRECATION") run { cccd.value = value; g.writeDescriptor(cccd) }
                }
            }
            is Op.Write -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(op.ch, op.bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothGatt.GATT_SUCCESS
            } else @Suppress("DEPRECATION") run {
                op.ch.value = op.bytes; op.ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT; g.writeCharacteristic(op.ch)
            }
            is Op.Read -> g.readCharacteristic(op.ch)
        }
        if (!ok) {
            appLogger.error(LOG, "Operação GATT recusada", mapOf("op" to op::class.simpleName.orEmpty(), "uuid" to op.ch.uuid.toString()))
            busy = false
            next()
        }
    }

    private fun opDone() = handler.post { busy = false; next() }

    // ── GATT callback ────────────────────────────────────────────────────────────────────────
    private val gattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            handler.post {
                if (g !== gatt) return@post
                appLogger.usage(LOG, "Estado GATT mudou", mapOf("novoEstado" to newState, "status" to status))
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    resetCycle()
                    handler.postDelayed({ if (g === gatt) endCycle("tempo do ciclo esgotado") }, CYCLE_TIMEOUT_MS)
                    if (!g.discoverServices()) { appLogger.error(LOG, "discoverServices() falhou"); g.disconnect() }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    handler.removeCallbacksAndMessages(null)
                    resetCycle()
                    runCatching { g.close() }
                    gatt = null
                    listener?.onConnectionChanged(false)
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            handler.post {
                if (g !== gatt) return@post
                if (status != BluetoothGatt.GATT_SUCCESS) { appLogger.error(LOG, "Descoberta de serviços falhou", mapOf("status" to status)); g.disconnect(); return@post }
                val service = g.getService(DexcomGatt.CGM_SERVICE)
                chars = listOf(DexcomGatt.COMMUNICATION, DexcomGatt.CONTROL, DexcomGatt.AUTHENTICATION, DexcomGatt.BACKFILL)
                    .mapNotNull { u -> service?.getCharacteristic(u)?.let { u to it } }.toMap()
                val auth = chars[DexcomGatt.AUTHENTICATION]
                if (auth == null || chars[DexcomGatt.CONTROL] == null) {
                    appLogger.error(LOG, "Serviço CGM sem Authentication/Control — não é um G6")
                    g.disconnect(); return@post
                }
                listener?.onConnectionChanged(true)
                g.readRemoteRssi()
                myToken = ByteArray(8).also { SecureRandom().nextBytes(it) }
                enqueue(Op.Subscribe(auth, indicate = true))
                enqueue(Op.Write(auth, DexcomG6Tx.authRequest(myToken)))
                appLogger.usage(LOG, "AuthRequest na fila")
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) appLogger.error(LOG, "Assinatura falhou", mapOf("uuid" to descriptor.characteristic.uuid.toString(), "status" to status))
            opDone()
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                appLogger.error(LOG, "Escrita falhou", mapOf("uuid" to characteristic.uuid.toString(), "status" to status))
                // 8/5/15 = sem autorização/criptografia: o pareamento não está valendo (xDrip: authResult(false)).
            }
            opDone()
        }

        override fun onCharacteristicRead(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) handler.post { dispatch(characteristic.uuid, value) }
            opDone()
        }

        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                val v = characteristic.value
                if (status == BluetoothGatt.GATT_SUCCESS && v != null) handler.post { dispatch(characteristic.uuid, v) }
                opDone()
            }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            handler.post { if (g === gatt) dispatch(characteristic.uuid, value) }
        }

        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                val v = characteristic.value?.copyOf() ?: return
                handler.post { if (g === gatt) dispatch(characteristic.uuid, v) }
            }
        }

        override fun onReadRemoteRssi(g: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) listener?.onRssi(rssi)
        }
    }

    // ── Mensagens recebidas (sempre na thread principal) ─────────────────────────────────────
    private fun dispatch(uuid: UUID, value: ByteArray) {
        when (uuid) {
            DexcomGatt.AUTHENTICATION -> onAuth(value)
            DexcomGatt.CONTROL -> onControl(value)
            DexcomGatt.BACKFILL -> onBackfillPacket(value)
        }
    }

    @SuppressLint("MissingPermission")
    private fun onAuth(value: ByteArray) {
        val auth = chars[DexcomGatt.AUTHENTICATION] ?: return
        when (value.getOrNull(0)?.toInt()?.and(0xFF)) {
            0x03 -> when (val r = DexcomG6Rx.parseAuthChallenge(value)) {
                is AuthChallengeResult.Parsed -> {
                    if (!DexcomG6Crypto.challengeHash(myToken, key).contentEquals(r.tokenHash)) {
                        appLogger.error(LOG, "tokenHash não bateu — Transmitter ID errado")
                        listener?.onWrongTransmitterId()
                        _events.tryEmit(LinkEvent.WrongTransmitterId)
                        gatt?.disconnect()
                        return
                    }
                    appLogger.usage(LOG, "tokenHash confere — respondendo ao desafio")
                    enqueue(Op.Write(auth, DexcomG6Tx.authChallengeResponse(DexcomG6Crypto.challengeHash(r.challenge, key))))
                }
                AuthChallengeResult.Invalid -> appLogger.error(LOG, "AuthChallenge inválido", mapOf("hex" to value.toHexLog()))
            }
            0x05 -> {
                val st = DexcomG6Rx.parseAuthStatus(value) ?: return
                appLogger.usage(LOG, "AuthStatus", mapOf("autenticado" to st.authenticated, "pareado" to st.bonded))
                if (!st.authenticated) {
                    appLogger.error(LOG, "Transmissor recusou a autenticação")
                    listener?.onWrongTransmitterId()
                    _events.tryEmit(LinkEvent.WrongTransmitterId)
                    gatt?.disconnect(); return
                }
                if (st.bonded) { startData(); return }
                if (bondAttempted) {
                    appLogger.error(LOG, "Continua sem pareamento — tenta no próximo ciclo")
                    gatt?.disconnect(); return
                }
                bondAttempted = true
                appLogger.usage(LOG, "Autenticado sem pareamento — KeepAlive + BondRequest + createBond")
                enqueue(Op.Write(auth, DexcomG6Tx.keepAlive(60)))
                runCatching { gatt?.device?.createBond() }
                enqueue(Op.Write(auth, DexcomG6Tx.bondRequest()))
                handler.postDelayed(bondWait, BOND_WAIT_MS)
            }
        }
    }

    /** Depois do pedido de pareamento (concluído ou não em 10 s): relê o status no transmissor, uma vez. */
    private fun afterBondWait() {
        handler.removeCallbacks(bondWait)
        if (isAuthenticated || bondReread || !bondAttempted) return
        val auth = chars[DexcomGatt.AUTHENTICATION] ?: return
        bondReread = true
        enqueue(Op.Read(auth))
    }
    private val bondWait = Runnable { afterBondWait() }
    private var bondReread = false

    private fun startData() {
        if (isAuthenticated) return
        isAuthenticated = true
        listener?.onAuthenticated()
        _events.tryEmit(LinkEvent.Authenticated)
        val control = chars[DexcomGatt.CONTROL] ?: return
        enqueue(Op.Subscribe(control, indicate = true))
        enqueue(Op.Write(control, DexcomG6Tx.glucose()))
    }

    private fun onControl(value: ByteArray) {
        val control = chars[DexcomGatt.CONTROL] ?: return
        when (value.getOrNull(0)?.toInt()?.and(0xFF)) {
            0x31, 0x4F -> {
                val reading = DexcomG6Rx.parseGlucose(value)
                if (reading == null) {
                    appLogger.error(LOG, "Glicemia inválida (CRC)", mapOf("hex" to value.toHexLog()))
                    endCycle("glicemia inválida"); return
                }
                _events.tryEmit(LinkEvent.GotGlucose)
                val follow = listener?.onGlucose(reading) ?: FollowUp(askTime = false)
                if (follow.askTime) enqueue(Op.Write(control, DexcomG6Tx.transmitterTime())) else afterTime()
            }
            0x25 -> {
                DexcomG6Rx.parseTransmitterTime(value)?.let { listener?.onTransmitterTime(it) }
                    ?: appLogger.error(LOG, "Hora do transmissor inválida")
                afterTime()
            }
            DexcomG6Rx.OPCODE_BACKFILL_ACK -> {
                backfillAccepted = DexcomG6Rx.isBackfillAccepted(value)
                appLogger.usage(LOG, "Backfill aceito", mapOf("valido" to backfillAccepted))
                if (!backfillAccepted) finishBackfill() else scheduleBackfillEnd()
            }
            else -> appLogger.usage(LOG, "Opcode de Control não tratado", mapOf("hex" to value.toHexLog()))
        }
    }

    /** Depois da glicemia (e da hora, se pedida): backfill se houver furo, senão encerra o ciclo. */
    private fun afterTime() {
        val window = listener?.backfillWindow()
        val control = chars[DexcomGatt.CONTROL]
        val bf = chars[DexcomGatt.BACKFILL]
        if (window == null || control == null || bf == null) { endCycle("leitura entregue"); return }
        appLogger.usage(LOG, "Pedindo backfill", mapOf("de" to window.first, "ate" to window.second))
        backfill = DexcomBackfillStream()
        enqueue(Op.Subscribe(bf, indicate = false))
        enqueue(Op.Write(control, DexcomG6Tx.backfill(window.first, window.second)))
        handler.postDelayed({ finishBackfill() }, BACKFILL_MAX_MS)
    }

    private fun onBackfillPacket(value: ByteArray) {
        backfill?.push(value) ?: return
        scheduleBackfillEnd()
    }

    private val backfillEnd = Runnable { finishBackfill() }
    private fun scheduleBackfillEnd() {
        handler.removeCallbacks(backfillEnd)
        handler.postDelayed(backfillEnd, BACKFILL_QUIET_MS)
    }

    private fun finishBackfill() {
        val stream = backfill ?: return
        backfill = null
        handler.removeCallbacks(backfillEnd)
        val records = stream.decode()
        appLogger.usage(LOG, "Backfill recebido", mapOf("registros" to records.size))
        if (records.isNotEmpty()) listener?.onBackfill(records)
        endCycle("backfill entregue")
    }
}

private fun ByteArray.toHexLog(maxBytes: Int = size): String =
    take(maxBytes).joinToString(" ") { "%02X".format(it) } +
        if (size > maxBytes) " …+${size - maxBytes}" else ""
