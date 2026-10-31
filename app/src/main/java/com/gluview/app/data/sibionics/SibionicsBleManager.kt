package com.gluview.app.data.sibionics

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
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import com.gluview.app.util.AppLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * GATT BLE do sensor Sibionics (Frente B / B1), usando a API nativa do Android.
 * UUIDs confirmados nos APKs e no Juggluco:
 *   service FF30 · notify FF31 (sensor→app) · write FF32 (app→sensor) · CCCD 2902
 *
 * Pacotes chegam CRUS (cifrados) e são entregues ao listener para decodificação.
 * Reconexão gerenciada externamente pelo SibionicsBleSource (tiered backoff).
 *
 * Permissões (Android 12+): BLUETOOTH_SCAN + BLUETOOTH_CONNECT — verificadas antes de chamar.
 */
@Singleton
class SibionicsBleManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appLogger: AppLogger
) {
    interface Listener {
        fun onConnectionChanged(connected: Boolean)
        fun onPacket(raw: ByteArray)
        fun onRssi(rssi: Int)
        /** CCCD escrito (ou sensor sem CCCD) — enviar comando inicial se o protocolo exigir. */
        fun onReady()
        /**
         * O rádio Bluetooth do SISTEMA acabou de ser ligado pelo usuário (Configurações/painel
         * rápido). Diferente de [onConnectionChanged], que depende do callback GATT do sensor
         * (não confiável quando o rádio inteiro cai — muitos aparelhos nunca disparam
         * `onConnectionStateChange` nesse caso), este evento vem do próprio adapter do sistema.
         * A fonte deve tentar reconectar imediatamente em vez de esperar o próximo passo do
         * cronograma de backoff.
         */
        fun onBluetoothTurnedOn() {}
    }

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("0000ff30-0000-1000-8000-00805f9b34fb")
        val NOTIFY_UUID:  UUID = UUID.fromString("0000ff31-0000-1000-8000-00805f9b34fb")
        val WRITE_UUID:   UUID = UUID.fromString("0000ff32-0000-1000-8000-00805f9b34fb")
        val CCCD_UUID:    UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val LOG = "SIBIONICS_BLE"
    }

    private val adapter: BluetoothAdapter? by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }

    // ── Rádio do sistema ligado/desligado ───────────────────────────────────────
    // Registrado uma vez (singleton de app, nunca desregistrado — vive com o processo).
    // Cobre o caso em que o usuário desliga o Bluetooth pelas Configurações com uma conexão GATT
    // ativa: em vários aparelhos (incl. Samsung/One UI) o callback `onConnectionStateChange` do
    // GATT NUNCA dispara quando é o rádio inteiro que cai — sem isso, o app fica "achando" que
    // ainda está conectado e nunca entra no cronograma de reconexão.
    init {
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                    BluetoothAdapter.STATE_OFF, BluetoothAdapter.STATE_TURNING_OFF -> {
                        if (gatt != null) {
                            appLogger.error(LOG, "Bluetooth do sistema desligado — forçando estado desconectado")
                            disconnect()
                        }
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
        }.onFailure { e ->
            appLogger.error(LOG, "Falha ao registrar receiver de estado do Bluetooth",
                mapOf("erro" to (e::class.simpleName ?: "?")))
        }
    }

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var listener: Listener? = null
    @Volatile private var targetMac: String? = null

    /**
     * Nome anunciado pelo dispositivo atualmente conectado, quando o sistema o conhece.
     * É a identidade verificável do rádio (ver [com.gluview.app.data.sensor.SensorIdentityMatcher]):
     * o endereço sozinho não diz de qual sensor ele é, o nome diz.
     */
    @Volatile var connectedDeviceName: String? = null
        private set

    fun setListener(l: Listener?) { listener = l }

    // ── Scan (tela de seleção do sensor) ──────────────────────────────────────
    private var scanCallback: ScanCallback? = null

    /**
     * Devolve false quando nem dá para começar (Bluetooth desligado) — L20: antes voltava em
     * silêncio e a tela ficava "procurando" para sempre. [onFailed] = o sistema recusou o scan.
     */
    @SuppressLint("MissingPermission")
    fun startScan(
        onFailed: (errorCode: Int) -> Unit = {},
        onFound: (name: String?, mac: String, rssi: Int) -> Unit
    ): Boolean {
        val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner ?: run {
            appLogger.error(LOG, "bluetoothLeScanner indisponível (BT desligado?)")
            return false
        }
        stopScan()
        // SEM filtro de serviço: sensores Sibionics conectados a outro app param de anunciar
        // o serviço ff30, e alguns só colocam ff30 no scan response. Scaneando tudo e
        // identificando pelo nome (família "LT…") garantimos enxergar o sensor real.
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.device.name
                    ?: result.scanRecord?.deviceName  // às vezes só vem no scan record
                // Só loga/reporta sensores Sibionics (nome "LT…"): reduz ruído e flood de log.
                if (name != null && name.startsWith("LT", ignoreCase = true)) {
                    android.util.Log.d(LOG, "Scan: name='$name' " +
                        "mac=${result.device.address} rssi=${result.rssi}")
                    onFound(name, result.device.address, result.rssi)
                }
            }
            override fun onScanFailed(errorCode: Int) {
                appLogger.error(LOG, "Scan BLE falhou", mapOf("errorCode" to errorCode))
                onFailed(errorCode)
            }
        }
        scanCallback = cb
        scanner.startScan(null, settings, cb)   // null = sem filtro, mostra todos
        appLogger.usage(LOG, "Scan iniciado (sem filtro)")
        return true
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        scanCallback?.let { adapter?.bluetoothLeScanner?.stopScan(it) }
        scanCallback = null
    }

    // ── Conexão ────────────────────────────────────────────────────────────────
    @SuppressLint("MissingPermission")
    fun connect(mac: String) {
        val a = adapter ?: run { appLogger.error(LOG, "BluetoothAdapter==null"); return }
        // Fecha GATT anterior antes de abrir novo (reconexão após queda)
        gatt?.let { runCatching { it.close() } }
        gatt = null
        writeChar = null
        targetMac = mac
        val device = runCatching { a.getRemoteDevice(mac) }.getOrNull() ?: run {
            appLogger.error(LOG, "MAC inválido", mapOf("mac" to mac))
            return
        }
        android.util.Log.d(LOG, "Conectando ao MAC=$mac")
        appLogger.usage(LOG, "Conectando", mapOf("mac" to mac))
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        appLogger.usage(LOG, "Desconectando (solicitação interna)")
        gatt?.let { runCatching { it.disconnect(); it.close() } }
        gatt = null
        writeChar = null
        listener?.onConnectionChanged(false)
    }

    /**
     * Escreve na FF32 (comando de auth/ativação). Loga o resultado e os bytes enviados.
     * @return true se o envio foi aceito pela stack BLE (não confirma recebimento pelo sensor)
     */
    @SuppressLint("MissingPermission")
    fun writeCommand(bytes: ByteArray): Boolean {
        val g = gatt ?: run {
            appLogger.error(LOG, "writeCommand: gatt==null", mapOf("hex" to bytes.toHex(16)))
            return false
        }
        val ch = writeChar ?: run {
            appLogger.error(LOG, "writeCommand: writeChar==null (FF32 não encontrada)", mapOf("hex" to bytes.toHex(16)))
            return false
        }
        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(ch, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                ch.value = bytes
                ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                g.writeCharacteristic(ch)
            }
        }
        if (ok) {
            appLogger.usage(LOG, "FF32 enviado", mapOf("bytes" to bytes.size, "hex" to bytes.toHex()))
        } else {
            appLogger.error(LOG, "FF32 falhou", mapOf("bytes" to bytes.size, "hex" to bytes.toHex()))
        }
        return ok
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            val stateName = when (newState) {
                BluetoothProfile.STATE_CONNECTED    -> "CONNECTED"
                BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED"
                BluetoothProfile.STATE_CONNECTING   -> "CONNECTING"
                else                                -> "UNKNOWN($newState)"
            }
            android.util.Log.d(LOG, "GATT state=$stateName status=$status mac=${targetMac ?: "?"}")
            appLogger.usage(LOG, "Estado GATT mudou",
                mapOf("mac" to (targetMac ?: "?"), "state" to stateName, "gattStatus" to status))

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connectedDeviceName = runCatching { g.device?.name }.getOrNull()
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                writeChar = null
                connectedDeviceName = null
                runCatching { g.close() }
                gatt = null
                // Reconexão é responsabilidade do SibionicsBleSource (tiered backoff).
                listener?.onConnectionChanged(false)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                appLogger.error(LOG, "Descoberta de serviços falhou",
                    mapOf("status" to status, "mac" to (targetMac ?: "?")))
                disconnect()
                return
            }

            // Loga todos os serviços encontrados — útil para confirmar UUIDs no SB01/SB02
            val allServices = g.services.joinToString { svc ->
                "${svc.uuid}(chars=${svc.characteristics.size})"
            }
            android.util.Log.d(LOG, "Serviços: $allServices")
            appLogger.usage(LOG, "Serviços GATT descobertos", mapOf("services" to allServices))

            val service = g.getService(SERVICE_UUID)
            val notify  = service?.getCharacteristic(NOTIFY_UUID)
            writeChar   = service?.getCharacteristic(WRITE_UUID)

            if (notify == null || writeChar == null) {
                appLogger.error(LOG, "Characteristics FF31/FF32 ausentes",
                    mapOf(
                        "servicoEncontrado" to (service != null),
                        "ff31" to (notify != null),
                        "ff32" to (writeChar != null)
                    ))
                disconnect()
                return
            }

            appLogger.usage(LOG, "FF31 e FF32 encontradas — habilitando notificações")
            g.setCharacteristicNotification(notify, true)

            val cccd = notify.getDescriptor(CCCD_UUID)
            if (cccd != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    run {
                        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        g.writeDescriptor(cccd)
                    }
                }
                // onDescriptorWrite chamará onReady() após a conclusão
            } else {
                // Sensor sem CCCD: notificações podem chegar mesmo assim (comportamento comum
                // em sensores Sibionics mais antigos). Chama onReady() diretamente.
                appLogger.usage(LOG, "CCCD ausente — chamando onReady() diretamente")
                listener?.onConnectionChanged(true)
                g.readRemoteRssi()
                listener?.onReady()
                return
            }

            listener?.onConnectionChanged(true)
            g.readRemoteRssi()
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int
        ) {
            android.util.Log.d(LOG, "CCCD write status=$status (0=OK) — FF31 notify habilitado")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                // Alguns sensores continuam streamando mesmo com CCCD com falha de escrita.
                // Registra o erro mas chama onReady() mesmo assim — SB01 confirmará se funciona.
                appLogger.error(LOG, "Escrita CCCD retornou erro (sensor pode funcionar mesmo assim)",
                    mapOf("status" to status))
            } else {
                appLogger.usage(LOG, "CCCD escrito — notificações FF31 habilitadas")
            }
            listener?.onReady()
        }

        // API 33+: valor entregue diretamente no callback
        override fun onCharacteristicChanged(
            g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray
        ) {
            if (ch.uuid == NOTIFY_UUID) {
                android.util.Log.d(LOG, "FF31 RX len=${value.size} hex=${value.toHex(24)}")
                appLogger.usage(LOG, "Pacote FF31", mapOf("bytes" to value.size, "hex" to value.toHex(24)))
                listener?.onPacket(value)
            }
        }

        // API < 33
        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && ch.uuid == NOTIFY_UUID) {
                val value = ch.value ?: return
                appLogger.usage(LOG, "Pacote FF31 (legado)", mapOf("bytes" to value.size, "hex" to value.toHex(24)))
                listener?.onPacket(value)
            }
        }

        override fun onReadRemoteRssi(g: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val qualidade = when {
                    rssi >= -60 -> "excelente"
                    rssi >= -70 -> "bom"
                    rssi >= -80 -> "razoável"
                    else        -> "fraco"
                }
                appLogger.usage(LOG, "RSSI", mapOf("rssi" to rssi, "qualidade" to qualidade))
                listener?.onRssi(rssi)
            }
        }
    }

}

/** Hex formatado dos primeiros [maxBytes] bytes. File-private — só para diagnóstico BLE. */
private fun ByteArray.toHex(maxBytes: Int = size): String =
    take(maxBytes).joinToString(" ") { "%02X".format(it) } +
    if (size > maxBytes) " …+${size - maxBytes}" else ""
