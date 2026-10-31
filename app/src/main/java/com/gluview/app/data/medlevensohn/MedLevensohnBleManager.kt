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
import androidx.core.content.ContextCompat
import com.gluview.app.util.AppLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * GATT BLE do Smart 2.0 (MedLevensohn / Microtech MD AiDEX X-LinX-Lumi) — porta o fluxo real do
 * Juggluco (`AidexXGattCallback.java` + `aidexx/java.cpp`, GPLv3). Ver [MedLevensohnGatt]/
 * [MedLevensohnCrypto]/[MedLevensohnCrc] para UUIDs, cripto e checksums.
 *
 * Fluxo de handshake (sensor novo, sem chave salva):
 *  1. connectGatt → discoverServices → habilita notify em f001 (Private Unbonded).
 *  2. Ack de f001 → habilita notify em f002 (canal principal) também.
 *  3. Ack de f002 → escreve em f001 o payload MD5 (`deriveAskKeyPayload(serial)`) pedindo a masterKey.
 *  4. Sensor responde via NOTIFY em f001 com a masterKey em claro (16 bytes, sem cifra).
 *  5. App faz UMA leitura (read, não notify) de f002 → 17 bytes cifrados com AES-CFB128(masterKey,iv)
 *     → decifra → primeiros 16 bytes = sessionKey, byte 17 = CRC8-Maxim de conferência.
 *  6. Daí em diante, TUDO em f002 (write/notify) usa a sessionKey (não mais a masterKey) + o MESMO
 *     iv. Cada payload decifrado termina com CRC16-CCITT-false de conferência e começa com um
 *     opcode de 2 bytes (LE) que identifica o tipo de resposta.
 *  7. f003 (Private Bonded) entrega a glicemia ATUAL ao vivo, cifrada com a MESMA sessionKey.
 *
 * IMPORTANTE (sem hardware para validar ainda, 27/07/2026): a cripto (AES-CFB128 + MD5 + CRCs) foi
 * validada com um vetor REAL capturado de um sensor físico embutido no próprio código do Juggluco
 * (ver [MedLevensohnProtocolTest]) — alta confiança nessa parte. A MÁQUINA DE ESTADOS do GATT
 * (ordem exata de comandos, todos os opcodes de histórico) é um porte estrutural fiel mas SÓ
 * cobre o caminho essencial (handshake + glicemia atual + um lote de histórico); opcodes
 * secundários (unpair/clear/OTA/paginação completa) ficam para depois de validar o básico com o
 * sensor real — ver TODOs abaixo.
 */
@Singleton
class MedLevensohnBleManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appLogger: AppLogger
) {
    interface Listener {
        fun onConnectionChanged(connected: Boolean)
        fun onCurrentReading(reading: CurrentGlucose)
        fun onHistoricalReading(minFromStart: Int, mgdl: Int)
        /** Pacote cru pré-decifra — diagnóstico/oráculo para quando o sensor chegar. */
        fun onRawPacket(uuid: java.util.UUID, value: ByteArray) {}
        fun onRssi(rssi: Int) {}
        fun onBluetoothTurnedOn() {}
    }

    companion object {
        private const val LOG = "MEDLEV_BLE"
        // Comandos fixos (já com CRC16 embutido), fiéis a aidexx/java.cpp — não recalculados em runtime.
        private val CMD_GREETING = byteArrayOf(0x10, 0xC1.toByte(), 0xF3.toByte())          // 1ª resposta pós-handshake (sem sessão prévia)
        private val CMD_RECONNECT = byteArrayOf(0x35, 0x01, 0x4E, 0xF7.toByte())            // resposta pós-handshake (sessão/hora já conhecidas)
        private val CMD_ASK_START_TIME = byteArrayOf(0x21, 0xB3.toByte(), 0xD5.toByte())    // pede hora de início ao sensor
    }

    private val adapter: BluetoothAdapter? by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }

    private var gatt: BluetoothGatt? = null
    private var charMain: BluetoothGattCharacteristic? = null           // f002
    private var charPrivateUnbonded: BluetoothGattCharacteristic? = null // f001
    private var charPrivateBonded: BluetoothGattCharacteristic? = null   // f003
    private var listener: Listener? = null

    private var serial: String = ""
    private var iv: ByteArray = ByteArray(0)
    private var masterKey: ByteArray? = null
    private var sessionKey: ByteArray? = null
    private var hasStartTime = false
    @Volatile private var targetMac: String? = null

    fun setListener(l: Listener?) { listener = l }

    /** Deve ser chamado ANTES de [connect] — [serial] deriva o IV/payloads da masterKey. */
    fun configure(serial: String, savedSessionKeyHex: String?) {
        this.serial = serial
        this.iv = MedLevensohnCrypto.deriveIv(serial)
        this.sessionKey = savedSessionKeyHex?.takeIf { it.isNotBlank() }?.let(::hexToBytes)
    }

    /** Chave de sessão atual (persistir para reconectar sem repetir o handshake completo). */
    fun currentSessionKeyHex(): String? = sessionKey?.joinToString("") { "%02x".format(it) }

    // ── Rádio do sistema ligado/desligado (mesmo padrão dos outros BLE managers) ──
    init {
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                    BluetoothAdapter.STATE_OFF, BluetoothAdapter.STATE_TURNING_OFF -> {
                        if (gatt != null) { appLogger.error(LOG, "Bluetooth do sistema desligado"); disconnect() }
                    }
                    BluetoothAdapter.STATE_ON -> {
                        appLogger.usage(LOG, "Bluetooth do sistema religado")
                        listener?.onBluetoothTurnedOn()
                    }
                }
            }
        }
        runCatching {
            ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        }.onFailure { e -> appLogger.error(LOG, "Falha ao registrar receiver BT", mapOf("erro" to (e::class.simpleName ?: "?"))) }
    }

    // ── Scan (nome "AiDEX X-"/"LinX-"/"Lumi-"/"Smart-", terminando no serial conhecido) ──
    private var scanCallback: ScanCallback? = null

    @SuppressLint("MissingPermission")
    fun startScan(onFound: (name: String, mac: String, rssi: Int) -> Unit) {
        val scanner = adapter?.bluetoothLeScanner ?: run { appLogger.error(LOG, "bluetoothLeScanner indisponível"); return }
        stopScan()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.device.name ?: result.scanRecord?.deviceName ?: return
                if (MedLevensohnGatt.isMicrotechName(name)) {
                    appLogger.usage(LOG, "Scan encontrou possível Smart 2.0/AiDEX X", mapOf("name" to name, "mac" to result.device.address))
                    onFound(name, result.device.address, result.rssi)
                }
            }
            override fun onScanFailed(errorCode: Int) { appLogger.error(LOG, "Scan BLE falhou", mapOf("errorCode" to errorCode)) }
        }
        scanCallback = cb
        scanner.startScan(null, settings, cb)
        appLogger.usage(LOG, "Scan iniciado (filtro por nome AiDEX X/LinX/Lumi/Smart)")
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        scanCallback?.let { adapter?.bluetoothLeScanner?.stopScan(it) }
        scanCallback = null
    }

    /**
     * Leitura passiva SEM conectar — parseia o advertisement (ver [MedLevensohnAdvertisement]).
     * Não precisa de handshake/chave. Use como caminho rápido/"quick read"; a conexão GATT
     * continua sendo necessária para histórico completo e atualização contínua.
     */
    fun tryReadFromAdvertisement(scanRecordBytes: ByteArray): MedLevensohnAdvertisement.Advertisement? {
        val adv = MedLevensohnAdvertisement.parse(scanRecordBytes) ?: return null
        if (!adv.crcOk) {
            appLogger.error(LOG, "Advertisement com CRC32 inválido — descartado")
            return null
        }
        return adv
    }

    // ── Conexão ──────────────────────────────────────────────────────────────────
    @SuppressLint("MissingPermission")
    fun connect(mac: String) {
        val a = adapter ?: run { appLogger.error(LOG, "BluetoothAdapter==null"); return }
        gatt?.let { runCatching { it.close() } }
        resetCharacteristics()
        targetMac = mac
        val device = runCatching { a.getRemoteDevice(mac) }.getOrNull() ?: run {
            appLogger.error(LOG, "MAC inválido", mapOf("mac" to mac)); return
        }
        appLogger.usage(LOG, "Conectando", mapOf("mac" to mac))
        gatt = device.connectGatt(context, true, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        appLogger.usage(LOG, "Desconectando (solicitação interna)")
        gatt?.let { runCatching { it.disconnect(); it.close() } }
        gatt = null
        resetCharacteristics()
        listener?.onConnectionChanged(false)
    }

    private fun resetCharacteristics() {
        charMain = null; charPrivateUnbonded = null; charPrivateBonded = null
    }

    // ── GATT callback ────────────────────────────────────────────────────────────
    private val gattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            appLogger.usage(LOG, "Estado GATT mudou", mapOf("mac" to (targetMac ?: "?"), "newState" to newState, "gattStatus" to status))
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                if (!g.discoverServices()) { appLogger.error(LOG, "discoverServices() falhou"); disconnect() }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                resetCharacteristics()
                runCatching { g.close() }
                gatt = null
                listener?.onConnectionChanged(false)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) { appLogger.error(LOG, "Descoberta de serviços falhou", mapOf("status" to status)); disconnect(); return }
            val service = g.getService(MedLevensohnGatt.SERVICE)
            charMain = service?.getCharacteristic(MedLevensohnGatt.CHAR_MAIN)
            charPrivateUnbonded = service?.getCharacteristic(MedLevensohnGatt.CHAR_PRIVATE_UNBONDED)
            charPrivateBonded = service?.getCharacteristic(MedLevensohnGatt.CHAR_PRIVATE_BONDED)

            if (charMain == null || charPrivateUnbonded == null) {
                appLogger.error(LOG, "Serviço 0x181F ou characteristics f001/f002 ausentes")
                disconnect(); return
            }
            appLogger.usage(LOG, "Serviços GATT descobertos — habilitando notify f001")
            enableNotification(g, charPrivateUnbonded!!)
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            val uuid = descriptor.characteristic.uuid
            if (status != BluetoothGatt.GATT_SUCCESS) { appLogger.error(LOG, "onDescriptorWrite falhou", mapOf("uuid" to uuid, "status" to status)); return }
            when (uuid) {
                MedLevensohnGatt.CHAR_PRIVATE_UNBONDED -> {
                    // f001 notify habilitada -> habilita f002 notify também, depois pede a masterKey.
                    val main = charMain ?: return
                    enableNotification(g, main)
                }
                MedLevensohnGatt.CHAR_MAIN -> {
                    val existingSession = sessionKey
                    if (existingSession != null) {
                        // Reconexão com sessão já conhecida: lê f002 direto (fast-path, sem re-pedir masterKey).
                        appLogger.usage(LOG, "Sessão já conhecida — pulando pedido de masterKey")
                        val pb = charPrivateBonded
                        if (pb != null) enableNotification(g, pb)
                    } else {
                        askMasterKey(g)
                    }
                }
                MedLevensohnGatt.CHAR_PRIVATE_BONDED -> {
                    listener?.onConnectionChanged(true)
                    g.readRemoteRssi()
                }
            }
        }

        @SuppressLint("MissingPermission")
        private fun askMasterKey(g: BluetoothGatt) {
            val pu = charPrivateUnbonded ?: return
            val payload = MedLevensohnCrypto.deriveAskKeyPayload(serial)
            writeChar(g, pu, payload)
            appLogger.usage(LOG, "Pedindo masterKey (f001)", mapOf("serial" to serial))
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                appLogger.error(LOG, "onCharacteristicWrite falhou", mapOf("uuid" to characteristic.uuid, "status" to status))
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicRead(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            handleRead(g, characteristic, status, value)
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicRead(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            @Suppress("DEPRECATION") val value = characteristic.value ?: ByteArray(0)
            handleRead(g, characteristic, status, value)
        }

        @SuppressLint("MissingPermission")
        private fun handleRead(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int, value: ByteArray) {
            if (status != BluetoothGatt.GATT_SUCCESS) { appLogger.error(LOG, "onCharacteristicRead falhou", mapOf("status" to status)); return }
            if (characteristic.uuid != MedLevensohnGatt.CHAR_MAIN) return
            val master = masterKey ?: run { appLogger.error(LOG, "Leitura f002 sem masterKey salva"); return }
            if (value.size != 17) { appLogger.error(LOG, "Bootstrap f002 tamanho inesperado", mapOf("size" to value.size)); disconnect(); return }
            val decrypted = runCatching { MedLevensohnCrypto.aesCfb128Decrypt(value, master, iv) }.getOrNull()
            if (decrypted == null || decrypted.size != 17) { appLogger.error(LOG, "Falha ao decifrar bootstrap f002"); disconnect(); return }
            val candidateKey = decrypted.copyOfRange(0, 16)
            val crc8 = MedLevensohnCrc.crc8Maxim(candidateKey)
            val expectedCrc8 = decrypted[16].toInt() and 0xFF
            if (crc8 != expectedCrc8) {
                appLogger.error(LOG, "CRC8 da sessionKey não bateu — bootstrap rejeitado",
                    mapOf("crc8" to crc8, "esperado" to expectedCrc8))
                disconnect(); return
            }
            sessionKey = candidateKey
            hasStartTime = false
            appLogger.usage(LOG, "sessionKey derivada e validada (CRC8 ok)")
            val cmd = MedLevensohnCrypto.aesCfb128Encrypt(CMD_GREETING, candidateKey, iv)
            writeChar(g, characteristic, cmd)
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            dispatchNotification(g, characteristic.uuid, value)
        }

        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                val value = characteristic.value ?: return
                dispatchNotification(g, characteristic.uuid, value)
            }
        }

        @SuppressLint("MissingPermission")
        private fun dispatchNotification(g: BluetoothGatt, uuid: java.util.UUID, value: ByteArray) {
            listener?.onRawPacket(uuid, value)
            when (uuid) {
                MedLevensohnGatt.CHAR_PRIVATE_UNBONDED -> {
                    if (value.size != 16) { appLogger.error(LOG, "masterKey com tamanho inesperado", mapOf("size" to value.size)); return }
                    masterKey = value
                    appLogger.usage(LOG, "masterKey recebida (f001) — lendo f002 uma vez")
                    val main = charMain ?: return
                    g.readCharacteristic(main)
                }
                MedLevensohnGatt.CHAR_MAIN -> handleMainNotify(g, value)
                MedLevensohnGatt.CHAR_PRIVATE_BONDED -> handleCurrentNotify(value)
            }
        }

        private fun handleMainNotify(g: BluetoothGatt, value: ByteArray) {
            val key = sessionKey ?: run { appLogger.error(LOG, "Notify f002 sem sessionKey"); return }
            val decrypted = runCatching { MedLevensohnCrypto.aesCfb128Decrypt(value, key, iv) }.getOrNull()
            if (decrypted == null || decrypted.size < 4) { appLogger.error(LOG, "Falha ao decifrar f002 ou payload curto demais"); return }
            val payloadLen = decrypted.size - 2
            val expectedCrc = MedLevensohnCrc.crc16CcittFalse(decrypted, 0, payloadLen)
            val actualCrc = ((decrypted[payloadLen + 1].toInt() and 0xFF) shl 8) or (decrypted[payloadLen].toInt() and 0xFF)
            if (expectedCrc != actualCrc) { appLogger.error(LOG, "CRC16 do payload f002 não bateu — descartado"); return }

            val opcode = ((decrypted[1].toInt() and 0xFF) shl 8) or (decrypted[0].toInt() and 0xFF)
            appLogger.usage(LOG, "f002 opcode", mapOf("opcode" to "0x%03x".format(opcode), "bytes" to payloadLen))
            when (opcode) {
                0x110 -> respond(g, if (hasStartTime) CMD_RECONNECT else CMD_ASK_START_TIME)
                0x111 -> {
                    val body = decrypted.copyOfRange(2, payloadLen)
                    LastPastGlucose.parse(body)?.let { listener?.onHistoricalReading(it.minFromStart, it.glucoseMgDl) }
                    respond(g, CMD_ASK_START_TIME)
                }
                0x121 -> { hasStartTime = true; respond(g, CMD_RECONNECT) }
                0x123 -> {
                    // getPastValues: uint16 startID + N× HistoryGlucose(2B) — só o essencial, sem paginação completa.
                    if (payloadLen >= 6) {
                        val startId = ((decrypted[3].toInt() and 0xFF) shl 8) or (decrypted[2].toInt() and 0xFF)
                        val numHist = (payloadLen - 4) / 2
                        for (i in 0 until numHist) {
                            val off = 4 + i * 2
                            val word = ((decrypted[off + 1].toInt() and 0xFF) shl 8) or (decrypted[off].toInt() and 0xFF)
                            val valid = (word shr 15) and 0x01 != 0
                            if (valid) listener?.onHistoricalReading(startId + i, word and 0x03FF)
                        }
                    }
                }
                else -> appLogger.usage(LOG, "f002 opcode não tratado (ok, ignorado)", mapOf("opcode" to "0x%03x".format(opcode)))
            }
        }

        @SuppressLint("MissingPermission")
        private fun respond(g: BluetoothGatt, plaintextCmd: ByteArray) {
            val key = sessionKey ?: return
            val main = charMain ?: return
            writeChar(g, main, MedLevensohnCrypto.aesCfb128Encrypt(plaintextCmd, key, iv))
        }

        private fun handleCurrentNotify(value: ByteArray) {
            val key = sessionKey ?: run { appLogger.error(LOG, "Notify f003 sem sessionKey"); return }
            val decrypted = runCatching { MedLevensohnCrypto.aesCfb128Decrypt(value, key, iv) }.getOrNull() ?: run {
                appLogger.error(LOG, "Falha ao decifrar f003"); return
            }
            val current = CurrentGlucose.parse(decrypted)
            if (current == null) { appLogger.error(LOG, "f003 payload não é um CurrentGlucose válido", mapOf("size" to decrypted.size)); return }
            listener?.onCurrentReading(current)
        }

        override fun onReadRemoteRssi(g: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) listener?.onRssi(rssi)
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableNotification(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        g.setCharacteristicNotification(characteristic, true)
        val cccd = characteristic.getDescriptor(MedLevensohnGatt.CCCD) ?: run {
            appLogger.error(LOG, "CCCD ausente", mapOf("uuid" to characteristic.uuid)); return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        } else {
            @Suppress("DEPRECATION") run {
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                g.writeDescriptor(cccd)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeChar(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, bytes: ByteArray): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(characteristic, bytes, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION") run {
                characteristic.value = bytes
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                g.writeCharacteristic(characteristic)
            }
        }
    }
}

private fun hexToBytes(hex: String): ByteArray =
    ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
