package com.gluview.app.data.medlevensohn

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
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.gluview.app.util.AppLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bluetooth do Smart 2.0 (MedLevensohn) / família Microtech MD (AiDEX X, LinX, Lumi) — porte fiel
 * de `AidexXGattCallback.java` do Juggluco 11.5.7 (GPLv3). A lógica de cada mensagem fica em
 * [MedLevensohnSession] (pura, testada); aqui só a ordem do GATT, a cifra e o envio.
 *
 * Chaves (corrigido em 07/11/2026): o que se guarda é a **chave-mestra** (f001). A cada conexão o app
 * lê f002 (17 bytes), decifra com a mestra e obtém uma **chave de sessão nova** (CRC8 confere) —
 * `aidexXSaveKey` + `aidexXstartCommand`. A versão anterior guardava a sessão e a reaproveitava.
 *
 * Ordem (Juggluco):
 *  - Sem chave ou sem pareamento: notify f001 → notify f002 → escreve o pedido de chave em f001 (com
 *    resposta) → quando a escrita confirmou E a chave chegou por f001 (em qualquer ordem) → lê f002.
 *  - Com chave-mestra salva E pareado (`keywhenconnecting`): notify f003 → notify f002 → lê f002.
 *  - Lido f002: deriva a sessão e manda o comando de início ([MedLevensohnSession.startCommand]).
 *  - Notificações f002 → [MedLevensohnSession.onMain]; f003 → [MedLevensohnSession.onCurrent].
 *  - Escritas em f002 sem resposta, com pelo menos 20 ms entre elas (`writeChar(…, 20)`).
 */
@Singleton
class MedLevensohnBleManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appLogger: AppLogger
) {
    interface Listener {
        /** true quando a sessão foi aberta (comando de início enviado); false em qualquer queda. */
        fun onConnectionChanged(connected: Boolean)
        fun onReadings(readings: List<MedLevReading>, state: MedLevState)
        /** Estado novo para persistir (início, último minuto, hora conhecida). */
        fun onStateChanged(state: MedLevState) {}
        fun onNotice(notice: MedLevNotice) {}
        /** Chave-mestra para guardar (hex) — ou "" para esquecer (falha de sessão ou sensor limpo). */
        fun onMasterKey(masterKeyHex: String) {}
        fun onRssi(rssi: Int) {}
        fun onBluetoothTurnedOn() {}
    }

    private companion object {
        const val LOG = "MEDLEV_BLE"
        const val MIN_WRITE_GAP_MS = 20L
        const val SCAN_TIMEOUT_MS = 30_000L
    }

    private val adapter: BluetoothAdapter? by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }
    private val handler = Handler(Looper.getMainLooper())

    @Volatile private var listener: Listener? = null
    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var targetMac: String? = null

    private var charMain: BluetoothGattCharacteristic? = null           // f002
    private var charPrivateUnbonded: BluetoothGattCharacteristic? = null // f001
    private var charPrivateBonded: BluetoothGattCharacteristic? = null   // f003

    private var serial: String = ""
    private var iv: ByteArray = ByteArray(0)
    @Volatile private var masterKey: ByteArray? = null
    @Volatile private var sessionKey: ByteArray? = null
    @Volatile var state: MedLevState = MedLevState()
        private set

    // Por conexão (nomes do Juggluco).
    @Volatile private var keyWhenConnecting = false
    @Volatile private var unbondedWrite = false
    @Volatile private var unbondedChange = false
    @Volatile private var sessionOpen = false
    @Volatile private var lastWriteAt = 0L

    fun setListener(l: Listener?) { listener = l }

    /** Antes de [connect]: serial (deriva IV e pedido de chave), chave-mestra salva e estado persistido. */
    fun configure(serial: String, masterKeyHex: String?, persisted: MedLevState) {
        this.serial = serial
        this.iv = MedLevensohnCrypto.deriveIv(serial)
        this.masterKey = masterKeyHex?.takeIf { it.length == 32 }?.let(::hexToBytes)
        this.state = persisted
    }

    /** Pede para desparear (ou limpar) na próxima conexão — o comando vai no lugar do de início. */
    fun requestUnpair(clear: Boolean) {
        state = if (clear) state.copy(clearPending = true) else state.copy(unpairPending = true)
        appLogger.usage(LOG, if (clear) "Limpar o sensor na próxima conexão" else "Desparear na próxima conexão")
    }

    val isConnected: Boolean get() = sessionOpen

    // ── Bluetooth ligado/desligado ───────────────────────────────────────────────────────────────
    init {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                    BluetoothAdapter.STATE_OFF, BluetoothAdapter.STATE_TURNING_OFF -> if (gatt != null) {
                        appLogger.error(LOG, "Bluetooth do sistema desligado — fechando a conexão")
                        disconnect()
                    }
                    BluetoothAdapter.STATE_ON -> listener?.onBluetoothTurnedOn()
                }
            }
        }
        runCatching {
            ContextCompat.registerReceiver(context, receiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED)
        }
    }

    // ── Busca ────────────────────────────────────────────────────────────────────────────────────
    private var scanCallback: ScanCallback? = null
    private val scanTimeout = Runnable { if (scanCallback != null) { appLogger.usage(LOG, "Busca terminou sem achar o sensor"); stopScan() } }

    /**
     * Busca um sensor da família (nome `AiDEX X-`/`LinX-`/`Lumi-`/`Smart-`). Com [serial], só o que termina
     * nele (Juggluco `matchDeviceName`). Para sozinha em 30 s.
     */
    @SuppressLint("MissingPermission")
    fun startScan(serial: String? = null, onFound: (name: String, mac: String, rssi: Int) -> Unit) {
        val scanner = adapter?.bluetoothLeScanner ?: run { appLogger.error(LOG, "Scanner Bluetooth indisponível"); return }
        stopScan()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.scanRecord?.deviceName ?: runCatching { result.device.name }.getOrNull() ?: return
                if (!MedLevensohnGatt.isMicrotechName(name)) return
                if (!serial.isNullOrBlank() && !MedLevensohnGatt.matchesSerial(name, serial)) return
                onFound(name, result.device.address, result.rssi)
            }
            override fun onScanFailed(errorCode: Int) { appLogger.error(LOG, "Busca Bluetooth falhou", mapOf("errorCode" to errorCode)) }
        }
        scanCallback = cb
        runCatching { scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb) }
            .onFailure { scanCallback = null; appLogger.error(LOG, "startScan lançou exceção"); return }
        handler.removeCallbacks(scanTimeout)
        handler.postDelayed(scanTimeout, SCAN_TIMEOUT_MS)
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        handler.removeCallbacks(scanTimeout)
        scanCallback?.let { cb -> runCatching { adapter?.bluetoothLeScanner?.stopScan(cb) } }
        scanCallback = null
    }

    /** Leitura passiva do anúncio (sem conectar) — diagnóstico; o Juggluco só usa com hora conhecida. */
    fun tryReadFromAdvertisement(scanRecordBytes: ByteArray): MedLevensohnAdvertisement.Advertisement? =
        MedLevensohnAdvertisement.parse(scanRecordBytes)?.takeIf { it.crcOk }

    // ── Conexão ──────────────────────────────────────────────────────────────────────────────────
    @SuppressLint("MissingPermission")
    fun connect(mac: String) {
        val a = adapter ?: run { appLogger.error(LOG, "BluetoothAdapter indisponível"); return }
        closeGatt(notify = false)
        val device = runCatching { a.getRemoteDevice(mac) }.getOrNull() ?: run { appLogger.error(LOG, "Endereço inválido"); return }
        targetMac = mac
        appLogger.usage(LOG, "Conectando", mapOf("mac" to mac, "chaveMestraSalva" to (masterKey != null)))
        gatt = runCatching { device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE) }.getOrNull()
    }

    fun disconnect() {
        appLogger.usage(LOG, "Desconectando")
        stopScan()
        closeGatt(notify = true)
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt(notify: Boolean) {
        val g = gatt
        gatt = null
        charMain = null; charPrivateUnbonded = null; charPrivateBonded = null
        sessionKey = null
        val wasOpen = sessionOpen
        sessionOpen = false
        g?.let { runCatching { it.disconnect() }; runCatching { it.close() } }
        if (notify && (g != null || wasOpen)) listener?.onConnectionChanged(false)
    }

    // ── GATT ─────────────────────────────────────────────────────────────────────────────────────
    private val gattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (g !== gatt) { runCatching { g.close() }; return }
            val bond = g.device.bondState
            appLogger.usage(LOG, "Estado da conexão mudou", mapOf("novoEstado" to newState, "status" to status, "pareamento" to bond))
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                keyWhenConnecting = masterKey != null && bond == BluetoothDevice.BOND_BONDED
                unbondedWrite = false; unbondedChange = false
                if (!g.discoverServices()) { appLogger.error(LOG, "discoverServices() recusado"); closeGatt(true) }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                closeGatt(notify = true)
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (g !== gatt) return
            if (status != BluetoothGatt.GATT_SUCCESS) { appLogger.error(LOG, "Descoberta de serviços falhou", mapOf("status" to status)); closeGatt(true); return }
            val service = g.getService(MedLevensohnGatt.SERVICE)
            charMain = service?.getCharacteristic(MedLevensohnGatt.CHAR_MAIN)
            charPrivateUnbonded = service?.getCharacteristic(MedLevensohnGatt.CHAR_PRIVATE_UNBONDED)
            charPrivateBonded = service?.getCharacteristic(MedLevensohnGatt.CHAR_PRIVATE_BONDED)
            if (charMain == null || charPrivateUnbonded == null || charPrivateBonded == null) {
                appLogger.error(LOG, "Serviço 0x181F sem f001/f002/f003 — não é um sensor da família")
                closeGatt(true); return
            }
            if (keyWhenConnecting) unbondedChange = true
            appLogger.usage(LOG, "Serviços descobertos", mapOf("comChave" to keyWhenConnecting))
            enableNotification(g, if (keyWhenConnecting) charPrivateBonded!! else charPrivateUnbonded!!)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (g !== gatt) return
            val uuid = descriptor.characteristic.uuid
            if (status != BluetoothGatt.GATT_SUCCESS) {
                // Juggluco: tenta de novo a mesma característica.
                appLogger.error(LOG, "Falha ao ligar avisos — tentando de novo", mapOf("char" to uuid.short(), "status" to status))
                enableNotification(g, descriptor.characteristic)
                return
            }
            when (uuid) {
                MedLevensohnGatt.CHAR_PRIVATE_BONDED, MedLevensohnGatt.CHAR_PRIVATE_UNBONDED -> charMain?.let { enableNotification(g, it) }
                MedLevensohnGatt.CHAR_MAIN -> if (keyWhenConnecting) readMain(g) else askKey(g)
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (g !== gatt) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                appLogger.error(LOG, "Escrita recusada", mapOf("char" to c.uuid.short(), "status" to status)); return
            }
            if (c.uuid == MedLevensohnGatt.CHAR_PRIVATE_UNBONDED) {
                if (unbondedChange) readMain(g) else unbondedWrite = true
            }
        }

        @Deprecated("API < 33")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            @Suppress("DEPRECATION") handleRead(g, c.uuid, c.value ?: ByteArray(0), status)
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            handleRead(g, c.uuid, value, status)
        }

        @Deprecated("API < 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION") dispatch(g, c.uuid, c.value ?: return)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            dispatch(g, c.uuid, value)
        }

        override fun onReadRemoteRssi(g: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) listener?.onRssi(rssi)
        }
    }

    @SuppressLint("MissingPermission")
    private fun askKey(g: BluetoothGatt) {
        val c = charPrivateUnbonded ?: return
        unbondedWrite = false; unbondedChange = false
        appLogger.usage(LOG, "Pedindo a chave-mestra (f001)")
        writeNow(g, c, MedLevensohnCrypto.deriveAskKeyPayload(serial), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
    }

    @SuppressLint("MissingPermission")
    private fun readMain(g: BluetoothGatt) {
        val c = charMain ?: return
        if (!g.readCharacteristic(c)) appLogger.error(LOG, "Leitura de f002 recusada")
    }

    /** Lido f002: deriva a chave de sessão com a mestra e manda o comando de início (`aidexXstartCommand`). */
    private fun handleRead(g: BluetoothGatt, uuid: UUID, value: ByteArray, status: Int) {
        if (g !== gatt || uuid != MedLevensohnGatt.CHAR_MAIN) return
        if (status != BluetoothGatt.GATT_SUCCESS) { appLogger.error(LOG, "Leitura de f002 falhou", mapOf("status" to status)); return }
        val master = masterKey
        val decrypted = master?.takeIf { value.size == 17 }?.let { runCatching { MedLevensohnCrypto.aesCfb128Decrypt(value, it, iv) }.getOrNull() }
        val key = decrypted?.copyOfRange(0, 16)
        if (key == null || MedLevensohnCrc.crc8Maxim(key) != (decrypted[16].toInt() and 0xFF)) {
            // Juggluco: chave inválida → apaga a mestra e desconecta (a próxima conexão pede a chave de novo).
            appLogger.error(LOG, "Chave de sessão inválida — esquecendo a chave-mestra", mapOf("tamanho" to value.size, "temMestra" to (master != null)))
            masterKey = null
            listener?.onMasterKey("")
            closeGatt(true)
            return
        }
        sessionKey = key
        val (next, cmd) = MedLevensohnSession.startCommand(state, keyWhenConnecting)
        state = next
        sessionOpen = true
        appLogger.usage(LOG, "Sessão aberta", mapOf("comChave" to keyWhenConnecting, "horaConhecida" to state.hasTime, "cmd" to cmd.toHex()))
        writeMain(g, cmd)
        listener?.onConnectionChanged(true)
        g.readRemoteRssi()
    }

    private fun dispatch(g: BluetoothGatt, uuid: UUID, value: ByteArray) {
        if (g !== gatt) return
        when (uuid) {
            MedLevensohnGatt.CHAR_PRIVATE_UNBONDED -> {
                if (!unbondedChange) {
                    if (value.size != 16) { appLogger.error(LOG, "Chave-mestra com tamanho inesperado", mapOf("tamanho" to value.size)); closeGatt(true); return }
                    masterKey = value
                    listener?.onMasterKey(value.toHex(separator = ""))
                    appLogger.usage(LOG, "Chave-mestra recebida (f001)")
                }
                if (unbondedWrite) readMain(g) else unbondedChange = true
            }
            MedLevensohnGatt.CHAR_MAIN -> {
                val plain = decrypt(value) ?: return
                appLogger.usage(LOG, "f002", mapOf("hex" to plain.toHex()))
                @SuppressLint("MissingPermission")
                val bondedNow = g.device.bondState != BluetoothDevice.BOND_NONE
                apply(g, MedLevensohnSession.onMain(plain, state, bondedNow, System.currentTimeMillis(), ZoneId.systemDefault()))
            }
            MedLevensohnGatt.CHAR_PRIVATE_BONDED -> {
                val plain = decrypt(value) ?: return
                appLogger.usage(LOG, "f003", mapOf("hex" to plain.toHex()))
                apply(g, MedLevensohnSession.onCurrent(plain, state, System.currentTimeMillis()))
            }
        }
    }

    private fun decrypt(value: ByteArray): ByteArray? {
        val key = sessionKey ?: run { appLogger.error(LOG, "Mensagem sem chave de sessão"); return null }
        return runCatching { MedLevensohnCrypto.aesCfb128Decrypt(value, key, iv) }.getOrNull()
    }

    private fun apply(g: BluetoothGatt, step: MedLevStep) {
        val changed = step.state != state
        state = step.state
        if (step.forgetKey) { masterKey = null; listener?.onMasterKey("") }
        if (step.readings.isNotEmpty()) listener?.onReadings(step.readings, state)
        if (changed) listener?.onStateChanged(state)
        step.notice?.let { appLogger.usage(LOG, "Aviso do sensor", mapOf("aviso" to it.name)); listener?.onNotice(it) }
        step.reply?.let { writeMain(g, it) }
        if (step.disconnect) {
            appLogger.usage(LOG, "Sessão pediu para desconectar")
            closeGatt(true)
        }
    }

    // ── Escritas ─────────────────────────────────────────────────────────────────────────────────

    /** Cifra e escreve em f002, sem resposta, respeitando 20 ms desde a escrita anterior (Juggluco). */
    private fun writeMain(g: BluetoothGatt, plainCmd: ByteArray) {
        val c = charMain ?: return
        val key = sessionKey ?: return
        val bytes = MedLevensohnCrypto.aesCfb128Encrypt(plainCmd, key, iv)
        val wait = MIN_WRITE_GAP_MS - (SystemClock.elapsedRealtime() - lastWriteAt)
        if (wait > 0) handler.postDelayed({ if (gatt === g) writeNow(g, c, bytes, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) }, wait)
        else writeNow(g, c, bytes, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
    }

    @SuppressLint("MissingPermission")
    private fun writeNow(g: BluetoothGatt, c: BluetoothGattCharacteristic, bytes: ByteArray, type: Int): Boolean {
        lastWriteAt = SystemClock.elapsedRealtime()
        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(c, bytes, type) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION") run { c.writeType = type; c.value = bytes; g.writeCharacteristic(c) }
        }
        if (!ok) appLogger.error(LOG, "Escrita não aceita pelo Android", mapOf("char" to c.uuid.short()))
        return ok
    }

    @SuppressLint("MissingPermission")
    private fun enableNotification(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
        g.setCharacteristicNotification(c, true)
        val cccd = c.getDescriptor(MedLevensohnGatt.CCCD) ?: run { appLogger.error(LOG, "CCCD ausente", mapOf("char" to c.uuid.short())); return }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        } else {
            @Suppress("DEPRECATION") run { cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; g.writeDescriptor(cccd) }
        }
    }
}

private fun UUID.short(): String = toString().substring(4, 8)

private fun ByteArray.toHex(separator: String = " "): String = joinToString(separator) { "%02x".format(it) }

private fun hexToBytes(hex: String): ByteArray =
    ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
