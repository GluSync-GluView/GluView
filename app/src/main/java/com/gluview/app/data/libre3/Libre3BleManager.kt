package com.gluview.app.data.libre3

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import com.gluview.app.util.AppLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.SecureRandom
import java.util.ArrayDeque
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bluetooth do FreeStyle Libre 3 — porte fiel da máquina de estados do Juggluco 11.5.x (GPLv3)
 * `Common/src/libre3/java/tk/glucodata/Libre3GattCallback.java`, sem o banco de sensores dele.
 *
 * Ordem (igual ao Juggluco):
 *  1. conecta (TRANSPORT_LE, sem autoConnect) → prioridade alta → descobre serviços;
 *  2. notificações: COMMAND_RESPONSE → CERT_DATA → CHALLENGE_DATA;
 *  3. segurança: sem kAuth salva → comandos 1, 2, certificado do app, recebe certificado do sensor
 *     (140), 0x0D, chave efêmera do app, recebe a do sensor (65), deriva a raiz, 17; com kAuth salva
 *     → direto 17. Depois o desafio: recebe r1+nonce (23), responde cifrado (r1, r2, PIN), comando 8,
 *     recebe a resposta (67) → confere r1/r2, tira kEnc/ivEnc, exporta a kAuth;
 *  4. notificações dos dados: PATCH_CONTROL → EVENT_LOG → HISTORIC → CLINICAL → FACTORY → GLUCOSE →
 *     PATCH_STATUS;
 *  5. cada status pede o que falta (histórico, dados clínicos, eventos) por comandos cifrados.
 *
 * Linha do tempo e gravação ficam em [Libre3Source]; aqui só protocolo e cifra.
 * Sem hardware Libre 3 no projeto: maturidade "Sem teste"; conferido com o simulador
 * (tools/virtual-sensors/libre3.py) e testes de unidade dos pacotes.
 */
@Singleton
class Libre3BleManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appLogger: AppLogger
) {
    interface Listener {
        fun onConnectionChanged(connected: Boolean)
        fun onRssi(rssi: Int)
        /** Autorização concluída; [kAuth] (149 bytes) deve ser guardada para os próximos contatos. */
        fun onAuthorized(kAuth: ByteArray)
        /** O sensor recusou a autorização salva: apagar e fazer autorização nova. */
        fun onSavedAuthorizationRejected()
        fun onMinute(minute: Libre3Protocol.OneMinute, receivedAtMs: Long)
        fun onHistory(entries: List<Pair<Int, Int>>)
        fun onClinical(fast: Libre3Protocol.FastData)
        fun onPatchStatus(status: Libre3Protocol.PatchStatus)
        fun onEvents(lastIndex: Int)
        fun onFailure(reason: String)
    }

    /** Estado que o backfill precisa (Juggluco: getlast*LifeCountReceived / lastEventReceived). */
    interface Counters {
        fun nextLifeCountWanted(): Int
        fun lastHistoricLifeCount(): Int
        fun lastEventReceived(): Int
    }

    private companion object {
        const val LOG = "LIBRE3_BLE"
        const val SETUP_TIMEOUT_MS = 90_000L
        const val SCAN_TIMEOUT_MS = 20_000L
        const val CMD_GET_CERT_CHAIN = 1
        const val CMD_READY_FOR_APP_CERT = 2
        const val CMD_APP_CERT_SENT = 3
        const val CMD_CERT_ACCEPTED_NEXT = 9
        const val CMD_EPHEMERAL = 0x0D
        const val CMD_EPHEMERAL_SENT = 14
        const val CMD_CHALLENGE = 17
        const val CMD_CHALLENGE_SENT = 8
    }

    private val lock = Any()
    private val thread = HandlerThread("libre3-ble").apply { start() }
    private val handler = Handler(thread.looper)
    private val random = SecureRandom()

    @Volatile private var listener: Listener? = null
    @Volatile private var counters: Counters? = null

    private var gatt: BluetoothGatt? = null
    private var mac: String? = null
    private var pin = ByteArray(4)
    private var savedKAuth: ByteArray? = null

    private var cPatchControl: BluetoothGattCharacteristic? = null
    private var cPatchStatus: BluetoothGattCharacteristic? = null
    private var cEventLog: BluetoothGattCharacteristic? = null
    private var cGlucose: BluetoothGattCharacteristic? = null
    private var cHistoric: BluetoothGattCharacteristic? = null
    private var cClinical: BluetoothGattCharacteristic? = null
    private var cFactory: BluetoothGattCharacteristic? = null
    private var cCommand: BluetoothGattCharacteristic? = null
    private var cChallenge: BluetoothGattCharacteristic? = null
    private var cCert: BluetoothGattCharacteristic? = null

    private var securityContext = 0L
    private var crypto: Libre3Crypto? = null
    private var isPreAuthorized = false
    private var savedAuthChallengeSent = false
    private var commandPhase = 1
    private var connected = false
    private var subscriptionsReady = false
    private var lastDataMs = 0L

    private var pendingDescriptor: UUID? = null
    private var pendingWrite: UUID? = null

    private var rdtData: ByteArray? = null
    private var rdtBytes = 0
    private var rdtLength = 0
    private var rdtSequence = -1
    private var wrtData: ByteArray? = null
    private var wrtOffset = 0

    private val r1 = ByteArray(16)
    private val r2 = ByteArray(16)
    private val nonce1 = ByteArray(7)

    private val minuteRaw = ByteArray(Libre3Protocol.MINUTE_PACKET_LEN)
    private var minuteSize = 0

    private val sendQueue = ArrayDeque<ByteArray>()
    private var wroteCharacter = false
    private var backFillInProgress = false

    private val setupDeadline = Runnable {
        synchronized(lock) {
            if (connected && !subscriptionsReady) fail("prazo de configuração (${SETUP_TIMEOUT_MS / 1000}s) estourado")
        }
    }

    fun setListener(l: Listener?, c: Counters?) { listener = l; counters = c }

    // ------------------------------------------------------------------ conexão
    @SuppressLint("MissingPermission")
    fun connect(address: String, pin: ByteArray, savedKAuth: ByteArray?) {
        synchronized(lock) {
            disconnectLocked()
            this.mac = address
            this.pin = pin.copyOf(4)
            this.savedKAuth = savedKAuth?.takeIf { it.size == Libre3Native.LEN_SAVED_AUTHORIZATION }
            resetSession()
            val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            if (adapter == null || !adapter.isEnabled) { listener?.onFailure("Bluetooth desligado"); return }
            // Como o Juggluco (SensorBluetooth): procura o anúncio antes, para o Android saber o tipo de
            // endereço; sem achar em 20 s, conecta direto pelo endereço (o sensor real usa endereço público).
            val scanner = adapter.bluetoothLeScanner
            val cb = object : android.bluetooth.le.ScanCallback() {
                override fun onScanResult(callbackType: Int, result: android.bluetooth.le.ScanResult) {
                    if (!result.device.address.equals(address, ignoreCase = true)) return
                    synchronized(lock) {
                        if (scanCallback !== this) return
                        stopScanLocked()
                        connectGattLocked(result.device, "anúncio encontrado")
                    }
                }
            }
            scanCallback = cb
            val started = runCatching { scanner?.startScan(null,
                android.bluetooth.le.ScanSettings.Builder().setScanMode(android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb) }.isSuccess && scanner != null
            if (!started) { scanCallback = null; connectDirectLocked(adapter, address); return }
            handler.postDelayed(scanTimeout, SCAN_TIMEOUT_MS)
            appLogger.usage(LOG, "Procurando o sensor", mapOf("mac" to address, "autorizacaoSalva" to (this.savedKAuth != null)))
        }
    }

    private var scanCallback: android.bluetooth.le.ScanCallback? = null
    private val scanTimeout = Runnable {
        synchronized(lock) {
            if (scanCallback == null) return@synchronized
            stopScanLocked()
            val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter ?: return@synchronized
            connectDirectLocked(adapter, mac ?: return@synchronized)
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScanLocked() {
        handler.removeCallbacks(scanTimeout)
        val cb = scanCallback ?: return
        scanCallback = null
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        runCatching { adapter?.bluetoothLeScanner?.stopScan(cb) }
    }

    private fun connectDirectLocked(adapter: android.bluetooth.BluetoothAdapter, address: String) {
        val device = runCatching { adapter.getRemoteDevice(address) }.getOrNull()
        if (device == null) { listener?.onFailure("endereço Bluetooth inválido: $address"); return }
        connectGattLocked(device, "sem anúncio em ${SCAN_TIMEOUT_MS / 1000}s, direto pelo endereço")
    }

    @SuppressLint("MissingPermission")
    private fun connectGattLocked(device: BluetoothDevice, why: String) {
        appLogger.usage(LOG, "Conectando", mapOf("mac" to device.address, "motivo" to why))
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE, BluetoothDevice.PHY_LE_1M_MASK, handler)
    }

    fun disconnect() { synchronized(lock) { disconnectLocked() } }

    @SuppressLint("MissingPermission")
    private fun disconnectLocked() {
        stopScanLocked()
        handler.removeCallbacks(setupDeadline)
        gatt?.let { g -> runCatching { g.disconnect() }; runCatching { g.close() } }
        gatt = null
        if (connected) { connected = false; listener?.onConnectionChanged(false) }
        resetSession()
    }

    private fun resetSession() {
        connected = false
        subscriptionsReady = false
        pendingDescriptor = null; pendingWrite = null
        minuteSize = 0
        backFillInProgress = false
        wroteCharacter = false
        savedAuthChallengeSent = false
        wrtData = null; wrtOffset = 0
        rdtData = null; rdtBytes = 0; rdtLength = 0; rdtSequence = -1
        sendQueue.clear()
        crypto = null
    }

    @SuppressLint("MissingPermission")
    private fun fail(reason: String) {
        appLogger.error(LOG, "Falha no protocolo", mapOf("motivo" to reason))
        listener?.onFailure(reason)
        gatt?.let { runCatching { it.disconnect() } }
    }

    // ------------------------------------------------------------------ escrita e notificações
    @SuppressLint("MissingPermission")
    private fun enableNotification(c: BluetoothGattCharacteristic?): Boolean {
        val g = gatt ?: return false
        if (c == null) { fail("característica ausente"); return false }
        pendingDescriptor = c.uuid
        val d = c.getDescriptor(Libre3Protocol.CCCD) ?: run { fail("CCCD ausente em ${c.uuid}"); return false }
        if (!g.setCharacteristicNotification(c, true)) { fail("setCharacteristicNotification ${c.uuid}"); return false }
        val ok = if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            g.writeDescriptor(d)
        }
        if (!ok) fail("pedido de notificação recusado: ${c.uuid}")
        return ok
    }

    @SuppressLint("MissingPermission")
    private fun write(c: BluetoothGattCharacteristic?, value: ByteArray): Boolean {
        val g = gatt ?: return false
        if (c == null) { fail("característica ausente"); return false }
        pendingWrite = c.uuid
        val ok = if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(c, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            c.value = value
            c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            g.writeCharacteristic(c)
        }
        if (!ok) fail("escrita recusada: ${c.uuid}")
        return ok
    }

    private fun sendSecurityCommand(b: Int): Boolean {
        appLogger.usage(LOG, "Comando de segurança", mapOf("cmd" to b, "fase" to commandPhase))
        if (!write(cCommand, byteArrayOf(b.toByte()))) return false
        if (b == CMD_CHALLENGE_SENT && isPreAuthorized) savedAuthChallengeSent = true
        return true
    }

    /** Fragmentos de 20 bytes: deslocamento (uint16 LE) + até 18 bytes (Juggluco `writedata`). 2 = tudo enviado. */
    private fun writeData(c: BluetoothGattCharacteristic?): Int {
        val data = wrtData ?: run { fail("nada para escrever"); return 0 }
        val left = data.size - wrtOffset
        if (left <= 0) return 2
        val n = minOf(left, 18)
        val frag = ByteArray(20)
        frag[0] = (wrtOffset and 0xFF).toByte()
        frag[1] = ((wrtOffset shr 8) and 0xFF).toByte()
        System.arraycopy(data, wrtOffset, frag, 2, n)
        wrtOffset += n
        return if (write(c, frag)) 1 else 0
    }

    // ------------------------------------------------------------------ segurança
    private fun initSecurityKeys(kAuth: ByteArray?): Boolean {
        if (!Libre3Native.available) return false
        val ctx = Libre3Native.beginSecurityHandshake(securityContext)
        if (ctx == 0L) { securityContext = 0L; return false }
        securityContext = ctx
        return Libre3Native.selectAppKeyAndSavedAuthorization(ctx, Libre3Protocol.APP_SECURITY_LEVEL, kAuth)
    }

    private fun onSecurityNotificationsEnabled() {
        savedAuthChallengeSent = false
        isPreAuthorized = false
        val kAuth = savedKAuth
        if (!initSecurityKeys(kAuth)) { fail("início da segurança falhou (biblioteca do Libre 3)"); return }
        if (kAuth == null) {
            commandPhase = 1
            sendSecurityCommand(CMD_GET_CERT_CHAIN)
        } else {
            isPreAuthorized = true
            commandPhase = 5
            sendSecurityCommand(CMD_CHALLENGE)
        }
    }

    /** Juggluco `getsecdata`: junta fragmentos (1 byte de sequência + dados); devolve quanto falta. */
    private fun appendSecurityFragment(value: ByteArray): Int {
        if (value.isEmpty()) { fail("fragmento de segurança vazio"); return Int.MAX_VALUE }
        val seq = value[0].toInt() and 0xFF
        if (seq != rdtSequence + 1) { fail("sequência de segurança $seq ≠ ${rdtSequence + 1}"); return Int.MAX_VALUE }
        val len = value.size - 1
        val buf = rdtData
        if (buf == null || rdtBytes + len > rdtLength) { fail("fragmento de segurança com tamanho inválido"); return Int.MAX_VALUE }
        System.arraycopy(value, 1, buf, rdtBytes, len)
        rdtBytes += len
        rdtSequence = seq
        return rdtLength - rdtBytes
    }

    /** Juggluco `preparedata` (notificação de COMMAND_RESPONSE). */
    private fun onCommandResponse(value: ByteArray) {
        if (value.isEmpty()) { fail("resposta de segurança vazia"); return }
        val sig = value[0].toInt() and 0xFF
        if (value.size == 1) {
            if (sig == 4) { sendSecurityCommand(CMD_CERT_ACCEPTED_NEXT); return }
            fail("resposta de segurança $sig não tratada"); return
        }
        val num = value[1].toInt() and 0xFF
        if (sig != 8 && sig != 10 && sig != 15) { fail("resposta de segurança desconhecida $sig/$num"); return }
        rdtLength = num
        rdtData = ByteArray(num)
        rdtSequence = -1
        rdtBytes = 0
    }

    private fun onCertData() {
        val data = rdtData ?: return
        when (rdtLength) {
            140 -> { // certificado do sensor
                if (!Libre3Native.acceptPatchCertificate(securityContext, data)) {
                    appLogger.error(LOG, "Certificado do sensor recusado pela biblioteca")
                }
                if (sendSecurityCommand(CMD_EPHEMERAL)) commandPhase = 4
            }
            65 -> { // chave efêmera do sensor
                if (Libre3Native.deriveAuthorizationRoot(securityContext, data)) sendSecurityCommand(CMD_CHALLENGE)
                else fail("derivação da autorização falhou")
            }
            else -> fail("certificado com tamanho $rdtLength")
        }
    }

    private fun onChallengeData() {
        val data = rdtData ?: return
        when (rdtLength) {
            23 -> {
                System.arraycopy(data, 0, r1, 0, 16)
                System.arraycopy(data, 16, nonce1, 0, 7)
                random.nextBytes(r2)
                val plain = r1 + r2 + pin
                val enc = Libre3Native.encryptChallengeReply(securityContext, nonce1, plain)
                    ?: run { fail("cifra do desafio falhou"); return }
                wrtData = enc; wrtOffset = 0
                writeData(cChallenge)
            }
            67 -> {
                val first = data.copyOfRange(0, 60)
                val nonce = data.copyOfRange(60, 67)
                val decr = Libre3Native.decryptChallengeResponse(securityContext, nonce, first)
                    ?: run { fail("o sensor não autenticou o desafio"); return }
                if (!decr.copyOfRange(0, 16).contentEquals(r2) || !decr.copyOfRange(16, 32).contentEquals(r1)) {
                    fail("r1/r2 do sensor não conferem"); return
                }
                val kEnc = decr.copyOfRange(32, 48)
                val ivEnc = decr.copyOfRange(48, 56)
                val exported = Libre3Native.exportSavedAuthorization(securityContext)
                    ?: run { fail("exportação da autorização falhou"); return }
                crypto = Libre3Crypto(kEnc, ivEnc)
                savedKAuth = exported
                listener?.onAuthorized(exported)
                appLogger.usage(LOG, "Autorizado", mapOf("salva" to isPreAuthorized))
                enableNotification(cPatchControl)
            }
            else -> fail("desafio com tamanho $rdtLength")
        }
    }

    private fun onCharWrite(uuid: UUID) {
        when (uuid) {
            Libre3Protocol.SEC_CHAR_CERT_DATA -> if (writeData(cCert) == 2) {
                sendSecurityCommand(if (commandPhase == 5) CMD_EPHEMERAL_SENT else CMD_APP_CERT_SENT)
            }
            Libre3Protocol.SEC_CHAR_CHALLENGE_DATA -> if (writeData(cChallenge) == 2) sendSecurityCommand(CMD_CHALLENGE_SENT)
            Libre3Protocol.SEC_CHAR_COMMAND_RESPONSE -> when (commandPhase) {
                1 -> if (sendSecurityCommand(CMD_READY_FOR_APP_CERT)) commandPhase = 2
                2 -> { wrtData = Libre3Protocol.APP_CERTIFICATES[Libre3Protocol.APP_SECURITY_LEVEL]; wrtOffset = 0
                       if (writeData(cCert) != 0) commandPhase = 3 }
                4 -> {
                    val pub = Libre3Native.createEphemeralPublicKey(securityContext)
                        ?: run { fail("chave efêmera do app falhou"); return }
                    wrtData = byteArrayOf(0x04) + pub; wrtOffset = 0
                    if (writeData(cCert) != 0) commandPhase = 5
                }
                else -> Unit
            }
            else -> Unit
        }
    }

    private fun onDescriptorDone(uuid: UUID) {
        when (uuid) {
            Libre3Protocol.SEC_CHAR_COMMAND_RESPONSE -> enableNotification(cCert)
            Libre3Protocol.SEC_CHAR_CERT_DATA -> enableNotification(cChallenge)
            Libre3Protocol.SEC_CHAR_CHALLENGE_DATA -> onSecurityNotificationsEnabled()
            Libre3Protocol.CHAR_PATCH_CONTROL -> enableNotification(cEventLog)
            Libre3Protocol.CHAR_EVENT_LOG -> enableNotification(cHistoric)
            Libre3Protocol.CHAR_HISTORIC_DATA -> enableNotification(cClinical)
            Libre3Protocol.CHAR_CLINICAL_DATA -> enableNotification(cFactory)
            Libre3Protocol.CHAR_FACTORY_DATA -> enableNotification(cGlucose)
            Libre3Protocol.CHAR_GLUCOSE_DATA -> enableNotification(cPatchStatus)
            Libre3Protocol.CHAR_PATCH_STATUS -> {
                subscriptionsReady = true
                handler.removeCallbacks(setupDeadline)
                appLogger.usage(LOG, "Assinaturas prontas")
            }
        }
    }

    // ------------------------------------------------------------------ dados
    private fun decrypt(kind: Int, value: ByteArray): ByteArray? {
        val c = crypto ?: run { fail("dados antes da autorização"); return null }
        return c.decrypt(kind, value) ?: run { fail("decifra falhou (tipo $kind)"); null }
    }

    private fun onGlucoseData(value: ByteArray, nowMs: Long) {
        if (value.isEmpty() || minuteSize + value.size > minuteRaw.size) { fail("fragmento de glicemia inválido"); return }
        System.arraycopy(value, 0, minuteRaw, minuteSize, value.size)
        minuteSize += value.size
        if (minuteSize < minuteRaw.size) return
        minuteSize = 0
        val plain = decrypt(Libre3Protocol.Kind.GLUCOSE, minuteRaw.copyOf()) ?: return
        val m = Libre3Protocol.parseOneMinute(plain) ?: run { fail("glicemia com ${plain.size} bytes"); return }
        lastDataMs = nowMs
        listener?.onMinute(m, nowMs)
        if (pendingDescriptor == null && pendingWrite == null) {
            @SuppressLint("MissingPermission") val ok = runCatching { gatt?.readRemoteRssi() }.getOrNull()
            if (ok != true) appLogger.usage(LOG, "RSSI opcional não pedido")
        }
    }

    private fun onPatchStatus(value: ByteArray) {
        val plain = decrypt(Libre3Protocol.Kind.PATCH_STATUS, value) ?: return
        val st = Libre3Protocol.parsePatchStatus(plain) ?: run { appLogger.error(LOG, "status com ${plain.size} bytes"); return }
        listener?.onPatchStatus(st)
        if (st.currentLifeCount < 0) return
        if (backFillInProgress) return
        val c = counters ?: return
        // fillHistory
        val startHistoric = Libre3Protocol.backfillStartHistoric(st.currentLifeCount)
        val lastHist = c.lastHistoricLifeCount()
        if (startHistoric > lastHist) {
            if (queueCommand(Libre3Protocol.controlHistory(1, maxOf(lastHist, 5)))) backFillInProgress = true
        }
        // fillClinical
        val nextLife = c.nextLifeCountWanted()
        if (nextLife < st.currentLifeCount) {
            if (queueCommand(Libre3Protocol.clinicalControl(1, nextLife))) backFillInProgress = true
        }
        // eventos
        val lastEvent = c.lastEventReceived()
        if (st.index + 1 > lastEvent) queueCommand(Libre3Protocol.eventLogControl(lastEvent))
    }

    private fun onControlResponse(value: ByteArray) {
        decrypt(Libre3Protocol.Kind.CONTROL_RESPONSE, value) ?: return
        backFillInProgress = false
        wroteCharacter = false
        fromQueue()
    }

    private fun queueCommand(plain: ByteArray): Boolean {
        val enc = crypto?.encrypt(Libre3Protocol.Kind.CONTROL, plain) ?: return false
        sendQueue.addLast(enc)
        return if (!wroteCharacter) fromQueue() else false
    }

    private fun fromQueue(): Boolean {
        if (!connected || wroteCharacter || pendingDescriptor != null || pendingWrite != null) return false
        val next = sendQueue.peekFirst() ?: return true
        wroteCharacter = true
        return if (write(cPatchControl, next)) { sendQueue.pollFirst(); true } else false
    }

    // ------------------------------------------------------------------ callback
    private val callback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) = synchronized(lock) {
            if (g != gatt) return@synchronized
            appLogger.usage(LOG, "Conexão", mapOf("status" to status, "estado" to newState))
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                if (connected) return@synchronized
                connected = true
                listener?.onConnectionChanged(true)
                handler.removeCallbacks(setupDeadline)
                handler.postDelayed(setupDeadline, SETUP_TIMEOUT_MS)
                runCatching { g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) }
                if (!g.discoverServices()) fail("discoverServices recusado")
            } else {
                // Juggluco: autorização salva enviada, sem assinatura pronta, status 19 e >59 s sem
                // dados = o sensor recusou a kAuth → próxima vez faz autorização nova.
                val now = System.currentTimeMillis()
                if (savedAuthChallengeSent && !subscriptionsReady && status == 19 && now - lastDataMs >= 59_000L) {
                    savedKAuth = null
                    listener?.onSavedAuthorizationRejected()
                }
                runCatching { g.close() }
                gatt = null
                val was = connected
                resetSession()
                handler.removeCallbacks(setupDeadline)
                if (was) listener?.onConnectionChanged(false) else listener?.onFailure("conexão falhou (status $status)")
            }
            Unit
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) = synchronized(lock) {
            if (g != gatt) return@synchronized
            if (status != BluetoothGatt.GATT_SUCCESS) { fail("descoberta de serviços: status $status"); return@synchronized }
            val data = g.getService(Libre3Protocol.DATA_SERVICE)
            val sec = g.getService(Libre3Protocol.SECURITY_SERVICE)
            cPatchControl = data?.getCharacteristic(Libre3Protocol.CHAR_PATCH_CONTROL)
            cPatchStatus = data?.getCharacteristic(Libre3Protocol.CHAR_PATCH_STATUS)
            cEventLog = data?.getCharacteristic(Libre3Protocol.CHAR_EVENT_LOG)
            cGlucose = data?.getCharacteristic(Libre3Protocol.CHAR_GLUCOSE_DATA)
            cHistoric = data?.getCharacteristic(Libre3Protocol.CHAR_HISTORIC_DATA)
            cClinical = data?.getCharacteristic(Libre3Protocol.CHAR_CLINICAL_DATA)
            cFactory = data?.getCharacteristic(Libre3Protocol.CHAR_FACTORY_DATA)
            cCommand = sec?.getCharacteristic(Libre3Protocol.SEC_CHAR_COMMAND_RESPONSE)
            cChallenge = sec?.getCharacteristic(Libre3Protocol.SEC_CHAR_CHALLENGE_DATA)
            cCert = sec?.getCharacteristic(Libre3Protocol.SEC_CHAR_CERT_DATA)
            val all = listOf(cPatchControl, cPatchStatus, cEventLog, cGlucose, cHistoric, cClinical, cFactory, cCommand, cChallenge, cCert)
            if (all.any { it == null }) { fail("serviços do Libre 3 incompletos"); return@synchronized }
            enableNotification(cCommand)
            Unit
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) = synchronized(lock) {
            if (g != gatt) return@synchronized
            if (status != BluetoothGatt.GATT_SUCCESS) { fail("descritor: status $status"); return@synchronized }
            val uuid = d.characteristic.uuid
            if (uuid != pendingDescriptor) return@synchronized
            pendingDescriptor = null
            onDescriptorDone(uuid)
            fromQueue()
            Unit
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) = synchronized(lock) {
            if (g != gatt) return@synchronized
            if (status != BluetoothGatt.GATT_SUCCESS) { fail("escrita: status $status"); return@synchronized }
            if (c.uuid != pendingWrite) return@synchronized
            pendingWrite = null
            onCharWrite(c.uuid)
            fromQueue()
            Unit
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) =
            handleChanged(g, c, value)

        @Deprecated("API < 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) handleChanged(g, c, c.value ?: ByteArray(0))
        }

        override fun onReadRemoteRssi(g: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) listener?.onRssi(rssi)
        }
    }

    private fun handleChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) = synchronized(lock) {
        if (g != gatt) return@synchronized
        val now = System.currentTimeMillis()
        try {
            when (c.uuid) {
                Libre3Protocol.CHAR_GLUCOSE_DATA -> onGlucoseData(value, now)
                Libre3Protocol.CHAR_PATCH_STATUS -> onPatchStatus(value)
                Libre3Protocol.CHAR_HISTORIC_DATA -> decrypt(Libre3Protocol.Kind.HISTORY, value)?.let { listener?.onHistory(Libre3Protocol.parseHistory(it)) }
                Libre3Protocol.CHAR_PATCH_CONTROL -> onControlResponse(value)
                Libre3Protocol.SEC_CHAR_CERT_DATA -> if (appendSecurityFragment(value) <= 0) onCertData()
                Libre3Protocol.SEC_CHAR_CHALLENGE_DATA -> if (appendSecurityFragment(value) <= 0) onChallengeData()
                Libre3Protocol.SEC_CHAR_COMMAND_RESPONSE -> onCommandResponse(value)
                Libre3Protocol.CHAR_EVENT_LOG -> decrypt(Libre3Protocol.Kind.EVENT_LOG, value)?.let {
                    val idx = Libre3Protocol.lastEventIndex(it); if (idx >= 0) listener?.onEvents(idx)
                }
                Libre3Protocol.CHAR_CLINICAL_DATA -> decrypt(Libre3Protocol.Kind.CLINICAL, value)?.let { p ->
                    Libre3Protocol.parseFastData(p)?.let { listener?.onClinical(it) }
                }
                Libre3Protocol.CHAR_FACTORY_DATA -> Unit
                else -> appLogger.usage(LOG, "Notificação desconhecida", mapOf("uuid" to c.uuid.toString()))
            }
        } catch (t: Throwable) {
            fail("erro ao tratar notificação: ${t.message ?: t::class.simpleName}")
        }
        Unit
    }
}
