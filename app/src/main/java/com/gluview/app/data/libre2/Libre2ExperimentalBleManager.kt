package com.gluview.app.data.libre2

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
import android.content.Context
import android.os.Build
import com.gluview.app.util.AppLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * BLE observacional do Libre 2 Plus.
 *
 * Este transporte nao usa Libre2Native nem nenhuma biblioteca Abbott. Ele abre o GATT,
 * habilita os canais de notificacao e registra os bytes recebidos. A ausencia da resposta
 * criptografica de auth e intencional nesta etapa: o objetivo e capturar o comportamento
 * real do sensor sem colocar o pipeline ativo em risco.
 */
@Singleton
class Libre2ExperimentalBleManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appLogger: AppLogger
) {
    interface Listener {
        fun onConnectionChanged(connected: Boolean)
        fun onPacket(uuid: UUID, value: ByteArray)
        fun onRssi(rssi: Int)
        fun onReady()
    }

    companion object {
        private val SERVICE_UUID = Libre2BleManager.SERVICE_UUID
        private val LOGIN_UUID = Libre2BleManager.LOGIN_UUID
        private val DATA_UUID = Libre2BleManager.DATA_UUID
        private val CCCD_UUID = Libre2BleManager.CCCD_UUID
        private const val LOG = "LIBRE2_EXP_BLE"
        private val CHALLENGE_REQUEST = byteArrayOf(0x20)
    }

    private val adapter: BluetoothAdapter? by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }

    private var gatt: BluetoothGatt? = null
    private var loginChar: BluetoothGattCharacteristic? = null
    private var dataChar: BluetoothGattCharacteristic? = null
    private var listener: Listener? = null
    private var scanCallback: ScanCallback? = null
    @Volatile private var targetMac: String? = null
    @Volatile private var phase = "idle"

    fun setListener(value: Listener?) { listener = value }

    @SuppressLint("MissingPermission")
    fun startScan(onFound: (String, String, Int) -> Unit) {
        val scanner = adapter?.bluetoothLeScanner ?: run {
            appLogger.error(LOG, "BLE scanner indisponivel")
            return
        }
        stopScan()
        val seen = HashSet<String>()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.device.name ?: result.scanRecord?.deviceName ?: return
                if (!name.startsWith("ABBOTT", true) &&
                    !name.startsWith("FSL", true) &&
                    !name.startsWith("Libre", true)) return
                if (!seen.add(result.device.address)) return
                appLogger.usage(LOG, "Sensor encontrado no scan experimental",
                    mapOf("name" to name, "mac" to result.device.address, "rssi" to result.rssi))
                onFound(name, result.device.address, result.rssi)
            }

            override fun onScanFailed(errorCode: Int) {
                appLogger.error(LOG, "Scan experimental falhou", mapOf("errorCode" to errorCode))
            }
        }
        scanCallback = callback
        runCatching { scanner.startScan(null, settings, callback) }
            .onSuccess { appLogger.usage(LOG, "Scan experimental iniciado") }
            .onFailure { error ->
                appLogger.error(LOG, "Nao foi possivel iniciar scan experimental",
                    mapOf("erro" to (error.message ?: error::class.simpleName.orEmpty())))
            }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        scanCallback?.let { callback ->
            runCatching { adapter?.bluetoothLeScanner?.stopScan(callback) }
        }
        scanCallback = null
    }

    @SuppressLint("MissingPermission")
    fun connect(mac: String) {
        val bluetooth = adapter ?: run {
            appLogger.error(LOG, "BluetoothAdapter indisponivel")
            return
        }
        gatt?.let { runCatching { it.close() } }
        resetCharacteristics()
        targetMac = mac
        val device = runCatching { bluetooth.getRemoteDevice(mac) }.getOrNull() ?: run {
            appLogger.error(LOG, "MAC experimental invalido", mapOf("mac" to mac))
            return
        }
        phase = "connecting"
        val autoConnect = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        appLogger.usage(LOG, "Conectando coletor experimental",
            mapOf("mac" to mac, "autoConnect" to autoConnect))
        gatt = runCatching {
            device.connectGatt(context, autoConnect, gattCallback, BluetoothDevice.TRANSPORT_LE)
        }.getOrElse { error ->
            appLogger.error(LOG, "connectGatt experimental falhou",
                mapOf("erro" to (error.message ?: error::class.simpleName.orEmpty())))
            null
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        appLogger.usage(LOG, "Desconectando coletor experimental", mapOf("fase" to phase))
        gatt?.let { runCatching { it.disconnect(); it.close() } }
        gatt = null
        resetCharacteristics()
        phase = "idle"
        safeConnectionChanged(false)
    }

    private fun resetCharacteristics() {
        loginChar = null
        dataChar = null
    }

    private fun safeConnectionChanged(connected: Boolean) {
        runCatching { listener?.onConnectionChanged(connected) }
            .onFailure { error -> logCallbackError("onConnectionChanged", error) }
    }

    private fun safePacket(uuid: UUID, value: ByteArray) {
        runCatching { listener?.onPacket(uuid, value.copyOf()) }
            .onFailure { error -> logCallbackError("onPacket", error) }
    }

    private fun safeReady() {
        runCatching { listener?.onReady() }
            .onFailure { error -> logCallbackError("onReady", error) }
    }

    private fun logCallbackError(where: String, error: Throwable) {
        appLogger.error(LOG, "Callback experimental isolado",
            mapOf("where" to where, "erro" to (error.message ?: error::class.simpleName.orEmpty())))
    }

    @SuppressLint("MissingPermission")
    private fun writeValue(characteristic: BluetoothGattCharacteristic, value: ByteArray): Boolean {
        val connection = gatt ?: return false
        return runCatching {
            @Suppress("DEPRECATION")
            characteristic.value = value
            @Suppress("DEPRECATION")
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            connection.writeCharacteristic(characteristic)
        }.getOrElse { error ->
            appLogger.error(LOG, "Escrita experimental lancou excecao",
                mapOf("uuid" to characteristic.uuid, "erro" to (error.message ?: error::class.simpleName.orEmpty())))
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableNotify(connection: BluetoothGatt, characteristic: BluetoothGattCharacteristic): Boolean {
        val local = runCatching { connection.setCharacteristicNotification(characteristic, true) }
            .getOrDefault(false)
        val descriptor = characteristic.getDescriptor(CCCD_UUID) ?: run {
            appLogger.error(LOG, "CCCD ausente no canal experimental", mapOf("uuid" to characteristic.uuid))
            return false
        }
        val remote = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                connection.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ==
                    BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                connection.writeDescriptor(descriptor)
            }
        }.getOrDefault(false)
        return local && remote
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(connection: BluetoothGatt, status: Int, newState: Int) {
            runCatching {
                val connected = newState == BluetoothProfile.STATE_CONNECTED
                appLogger.usage(LOG, "Estado GATT experimental",
                    mapOf("mac" to (targetMac ?: "?"), "connected" to connected,
                          "status" to status, "phase" to phase))
                if (connected) {
                    phase = "services"
                    val priority = connection.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    appLogger.usage(LOG, "Prioridade GATT experimental solicitada", mapOf("ok" to priority))
                    val discovered = connection.discoverServices()
                    appLogger.usage(LOG, "discoverServices experimental", mapOf("ok" to discovered))
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    phase = "disconnected"
                    runCatching { connection.close() }
                    if (gatt === connection) gatt = null
                    resetCharacteristics()
                    safeConnectionChanged(false)
                }
            }.onFailure { error ->
                logCallbackError("onConnectionStateChange", error)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(connection: BluetoothGatt, status: Int) {
            runCatching {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    appLogger.error(LOG, "Descoberta experimental falhou", mapOf("status" to status))
                    disconnect()
                    return
                }
                val services = connection.services.joinToString { service ->
                    "${service.uuid}(chars=${service.characteristics.size})"
                }
                appLogger.usage(LOG, "Servicos GATT experimentais", mapOf("services" to services))
                val service = connection.getService(SERVICE_UUID)
                loginChar = service?.getCharacteristic(LOGIN_UUID)
                dataChar = service?.getCharacteristic(DATA_UUID)
                if (loginChar == null || dataChar == null) {
                    appLogger.error(LOG, "fde3/f001/f002 ausentes no modo experimental",
                        mapOf("service" to (service != null), "f001" to (loginChar != null),
                              "f002" to (dataChar != null)))
                    disconnect()
                    return
                }
                phase = "f001_notify"
                safeConnectionChanged(true)
                connection.readRemoteRssi()
                val accepted = enableNotify(connection, loginChar!!)
                appLogger.usage(LOG, "Notify f001 experimental solicitado", mapOf("ok" to accepted))
                if (!accepted) disconnect()
            }.onFailure { error ->
                logCallbackError("onServicesDiscovered", error)
                disconnect()
            }
        }

        override fun onDescriptorWrite(
            connection: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            runCatching {
                val uuid = descriptor.characteristic.uuid
                appLogger.usage(LOG, "CCCD experimental escrito",
                    mapOf("uuid" to uuid, "status" to status, "phase" to phase))
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    appLogger.error(LOG, "CCCD experimental recusado", mapOf("uuid" to uuid, "status" to status))
                    disconnect()
                    return
                }
                when (uuid) {
                    LOGIN_UUID -> {
                        phase = "challenge_request"
                        val accepted = writeValue(loginChar!!, CHALLENGE_REQUEST)
                        appLogger.usage(LOG, "Challenge experimental [0x20] enviado",
                            mapOf("ok" to accepted, "hex" to CHALLENGE_REQUEST.toHex()))
                        if (!accepted) disconnect()
                    }
                    DATA_UUID -> {
                        phase = "capture"
                        appLogger.usage(LOG, "Modo experimental pronto: aguardando f002")
                        safeReady()
                    }
                }
            }.onFailure { error ->
                logCallbackError("onDescriptorWrite", error)
                disconnect()
            }
        }

        override fun onCharacteristicWrite(
            connection: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            appLogger.usage(LOG, "Characteristic experimental escrita",
                mapOf("uuid" to characteristic.uuid, "status" to status, "phase" to phase))
        }

        override fun onCharacteristicChanged(
            connection: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            receive(characteristic.uuid, value)
        }

        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            connection: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                characteristic.value?.let { receive(characteristic.uuid, it) }
            }
        }

        override fun onReadRemoteRssi(connection: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                appLogger.usage(LOG, "RSSI experimental", mapOf("rssi" to rssi))
                runCatching { listener?.onRssi(rssi) }
            }
        }
    }

    private fun receive(uuid: UUID, value: ByteArray) {
        runCatching {
            appLogger.usage(LOG, "Pacote BLE experimental recebido",
                mapOf("uuid" to uuid, "bytes" to value.size, "phase" to phase, "hex" to value.toHex()))
            safePacket(uuid, value)
            if (uuid == LOGIN_UUID && value.size == 14) {
                val connection = gatt ?: return@runCatching
                val characteristic = dataChar ?: return@runCatching
                phase = "f002_notify"
                val accepted = enableNotify(connection, characteristic)
                appLogger.usage(LOG, "Notify f002 experimental solicitado apos challenge",
                    mapOf("ok" to accepted))
                if (!accepted) disconnect()
            }
        }.onFailure { error ->
            logCallbackError("receive", error)
        }
    }
}
