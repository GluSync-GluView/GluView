package com.gluview.app.data.librebridge

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
 * GATT BLE para leitores acoplados ao Libre (MiaoMiao/Tomato, Bubble, Atom).
 *
 * Porte do essencial de xDrip DexCollectionService (GPLv3): descoberta por nome, serviços/características
 * HM-10 (ffe0/ffe1) e Nordic UART (6e400001/..002/..003), habilitação de notify e escrita de comandos.
 * - DexCollectionService.java:2051-2068 encaminha pacotes aos decoders por tipo; 2083-2103 valida
 *   serviços/características de dados/login; 547-597 inicializa Tomato/Bubble/Atom (fila de comandos).
 * - HM10Attributes.java: HM_RX_TX/NRF_UART_* UUIDs.
 *
 * O protocolo exato de framing varia por firmware/modelo; esta implementação suporta os perfis HM-10 e NUS
 * mais comuns (Tomato/MiaoMiao/Bubble/Atom) conforme referência, com maturidade "Sem teste".
 */
@Singleton
class LibreBridgeBleManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appLogger: AppLogger
) {
    enum class BridgeKind { MIAOMIAO, BUBBLE, ATOM }

    interface Listener {
        fun onConnectionChanged(connected: Boolean)
        fun onPacket(data: ByteArray)
        fun onRssi(rssi: Int)
        fun onReady(kind: BridgeKind)
    }

    companion object {
        private const val LOG = "BRIDGE_BLE"
        private val HM10_SERVICE: UUID = UUID.fromString(LibreBridgeConstants.HM10_SERVICE)
        private val HM10_RX_TX: UUID = UUID.fromString(LibreBridgeConstants.HM10_RX_TX)
        private val NUS_SERVICE: UUID = UUID.fromString(LibreBridgeConstants.NUS_SERVICE)
        private val NUS_RX: UUID = UUID.fromString(LibreBridgeConstants.NUS_RX)
        private val NUS_TX: UUID = UUID.fromString(LibreBridgeConstants.NUS_TX)
        private val CCCD: UUID = UUID.fromString(LibreBridgeConstants.CCCD)

        fun inferKind(name: String?): BridgeKind? {
            val n = name?.lowercase() ?: return null
            return when {
                n.contains("bubble") -> BridgeKind.BUBBLE
                n.contains("atom") -> BridgeKind.ATOM
                n.startsWith("miaomiao") || n.startsWith("watlaa") || n.contains("miaomiao") -> BridgeKind.MIAOMIAO
                n.startsWith("tomato") -> BridgeKind.MIAOMIAO
                else -> null
            }
        }
    }

    private val adapter: BluetoothAdapter? by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var notifyChar: BluetoothGattCharacteristic? = null
    private var listener: Listener? = null
    private var scanCallback: ScanCallback? = null
    @Volatile private var targetMac: String? = null
    @Volatile private var detectedKind: BridgeKind? = null

    fun setListener(l: Listener?) { listener = l }

    @SuppressLint("MissingPermission")
    fun startScan(expected: BridgeKind? = null, onFound: (name: String?, mac: String, rssi: Int, kind: BridgeKind) -> Unit): Boolean {
        val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner ?: run {
            appLogger.error(LOG, "bluetoothLeScanner indisponível")
            return false
        }
        stopScan()
        val seen = HashSet<String>()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.device.name ?: result.scanRecord?.deviceName
                val kind = inferKind(name) ?: return
                if (expected != null && kind != expected) return
                val mac = result.device.address
                if (!seen.add(mac)) return
                appLogger.usage(LOG, "Leitor encontrado", mapOf("name" to (name ?: "?"), "mac" to mac, "kind" to kind.name, "rssi" to result.rssi))
                onFound(name, mac, result.rssi, kind)
            }
            override fun onScanFailed(errorCode: Int) {
                appLogger.error(LOG, "Scan falhou", mapOf("errorCode" to errorCode))
            }
        }
        scanCallback = cb
        scanner.startScan(null, settings, cb)
        return true
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        scanCallback?.let { runCatching { adapter?.bluetoothLeScanner?.stopScan(it) } }
        scanCallback = null
    }

    @SuppressLint("MissingPermission")
    fun connect(mac: String, kind: BridgeKind? = null) {
        val a = adapter ?: return
        gatt?.let { runCatching { it.close() } }
        gatt = null; writeChar = null; notifyChar = null
        clearWrites()
        targetMac = mac; detectedKind = kind
        val device = runCatching { a.getRemoteDevice(mac) }.getOrNull() ?: return
        appLogger.usage(LOG, "Conectando", mapOf("mac" to mac, "kind" to (kind?.name ?: "?")))
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        gatt?.let { runCatching { it.disconnect(); it.close() } }
        gatt = null; writeChar = null; notifyChar = null
        listener?.onConnectionChanged(false)
    }

    /**
     * Fila de escrita (achado com o simulador, 21/11/2026): o Android só aceita UMA operação GATT
     * pendente. A fonte mandava 2–3 comandos seguidos ([D1 05] [F0]; [D3 01] [D1 05] [F0]) e todos
     * depois do primeiro falhavam — o MiaoMiao nunca começava a ler. Agora cada escrita espera o
     * onCharacteristicWrite da anterior (como a fila do xDrip/Juggluco).
     */
    private val writeQueue = ArrayDeque<ByteArray>()
    private var writing = false

    fun write(bytes: ByteArray): Boolean {
        synchronized(writeQueue) { writeQueue.addLast(bytes.copyOf()) }
        pumpWrites()
        return true
    }

    @SuppressLint("MissingPermission")
    private fun pumpWrites() {
        val next: ByteArray
        synchronized(writeQueue) {
            if (writing) return
            next = writeQueue.firstOrNull() ?: return
            writing = true
        }
        val g = gatt
        val ch = writeChar
        val ok = if (g == null || ch == null) false
        else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(ch, next, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run { ch.value = next; ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT; g.writeCharacteristic(ch) }
        }
        if (!ok) {
            appLogger.error(LOG, "Falha write BLE", mapOf("len" to next.size))
            synchronized(writeQueue) { writeQueue.clear(); writing = false }
        }
    }

    private fun clearWrites() = synchronized(writeQueue) { writeQueue.clear(); writing = false }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                appLogger.usage(LOG, "GATT conectado", mapOf("mac" to (targetMac ?: "?")))
                runCatching { g.discoverServices() }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                runCatching { g.close() }
                gatt = null; writeChar = null; notifyChar = null
                clearWrites()
                listener?.onConnectionChanged(false)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                appLogger.error(LOG, "Descoberta de serviços falhou", mapOf("status" to status))
                return
            }
            // Preferir HM-10; fallback NUS (como bridges xDrip/nRF)
            val hm = g.getService(HM10_SERVICE)
            val nus = g.getService(NUS_SERVICE)
            val (w, n) = when {
                hm != null -> hm.getCharacteristic(HM10_RX_TX) to hm.getCharacteristic(HM10_RX_TX)
                nus != null -> nus.getCharacteristic(NUS_RX) to nus.getCharacteristic(NUS_TX)
                else -> {
                    appLogger.error(LOG, "Nenhum serviço de bridge encontrado (HM10/NUS)")
                    return
                }
            }
            if (n == null || w == null) {
                appLogger.error(LOG, "Características de bridge ausentes")
                return
            }
            writeChar = w; notifyChar = n
            runCatching { g.setCharacteristicNotification(n, true) }
            val desc = n.getDescriptor(CCCD)
            if (desc != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeDescriptor(desc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    run { desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; g.writeDescriptor(desc) }
                }
            } else {
                listener?.onReady(detectedKind ?: BridgeKind.MIAOMIAO)
            }
            listener?.onConnectionChanged(true)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            appLogger.usage(LOG, "CCCD escrito", mapOf("status" to status))
            listener?.onReady(detectedKind ?: BridgeKind.MIAOMIAO)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            synchronized(writeQueue) {
                if (writeQueue.isNotEmpty()) writeQueue.removeFirst()
                writing = false
            }
            if (status != BluetoothGatt.GATT_SUCCESS) appLogger.error(LOG, "Escrita BLE recusada pelo leitor", mapOf("status" to status))
            pumpWrites()
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val v = characteristic.value ?: return
            listener?.onPacket(v.copyOf())
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            listener?.onPacket(value.copyOf())
        }

        override fun onReadRemoteRssi(g: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) listener?.onRssi(rssi)
        }
    }
}
