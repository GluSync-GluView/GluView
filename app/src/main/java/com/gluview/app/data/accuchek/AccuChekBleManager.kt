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
import androidx.core.content.ContextCompat
import com.gluview.app.util.AppLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * GATT BLE do Accu-Chek SmartGuide — porta o fluxo real do Juggluco (`GlucoseMeterGatt.java` +
 * `MeterScanner.java`, GPLv3) para o perfil Bluetooth SIG "Glucose Service" (0x1808). Ver
 * [AccuChekGatt]/[AccuChekMeasurementParser]/[AccuChekRacp] para os UUIDs e o parsing de bytes.
 *
 * Fluxo (idêntico ao Juggluco, `discover()`/`handleManufactory()`/`startGlucoseService()`):
 *  1. connectGatt (autoConnect) → aguarda bond (o pareamento com PIN de 6 dígitos impresso na tampa
 *     azul do aplicador é feito pelo SISTEMA Android — sem código de PIN próprio aqui, igual ao
 *     Juggluco, que nunca chama `createBond()` para este perfil).
 *  2. discoverServices → lê Manufacturer Name (0x2A29); "Roche" confirma o SmartGuide.
 *  3. Lê Date/Time (0x2A08) se existir — usado só para log/diagnóstico de deriva de relógio.
 *  4. Habilita notificação em Glucose Measurement (0x2A18) [+ Context 0x2A34 antes, se existir].
 *  5. Habilita indicação no Record Access Control Point (0x2A52).
 *  6. Lê o Serial Number (0x2A25) se existir.
 *  7. Escreve no RACP "Report Stored Records ≥ sequence" (ver [AccuChekRacp]) — o sensor então
 *     notifica cada registro em 0x2A18 e finaliza com uma Response Code em 0x2A52.
 *
 * IMPORTANTE (sem hardware para validar ainda): esta classe replica fielmente a MÁQUINA DE ESTADOS
 * do Juggluco, mas os bytes reais nunca foram capturados de um SmartGuide de verdade. Quando o
 * sensor chegar, ativar `AppLogger` em modo verboso e comparar o hex recebido em
 * [Listener.onRawGlucosePacket] com [AccuChekMeasurementParser] antes de confiar na glicemia.
 */
@Singleton
class AccuChekBleManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appLogger: AppLogger
) {
    interface Listener {
        fun onConnectionChanged(connected: Boolean)
        fun onReading(reading: AccuChekReading)
        /** Pacote cru da 0x2A18 antes do parse — para diagnóstico/oráculo quando o sensor chegar. */
        fun onRawGlucosePacket(bytes: ByteArray) {}
        fun onSerialNumber(serial: String) {}
        /** RACP terminou o ciclo de busca (com ou sem registros novos) — o chamador decide se avança o cursor. */
        fun onFetchComplete() {}
        fun onRssi(rssi: Int) {}
        fun onBluetoothTurnedOn() {}
    }

    companion object {
        private const val LOG = "ACCUCHEK_BLE"
    }

    private val adapter: BluetoothAdapter? by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }

    private var gatt: BluetoothGatt? = null
    private var glucoseChar: BluetoothGattCharacteristic? = null
    private var contextChar: BluetoothGattCharacteristic? = null
    private var racpChar: BluetoothGattCharacteristic? = null
    private var dateTimeChar: BluetoothGattCharacteristic? = null
    private var serialChar: BluetoothGattCharacteristic? = null
    private var manufacturerChar: BluetoothGattCharacteristic? = null
    private var listener: Listener? = null
    private var isRocheDevice = false
    @Volatile private var targetMac: String? = null
    @Volatile private var fetchFromSequence: Int = 0

    fun setListener(l: Listener?) { listener = l }

    /** Próxima busca de RACP começará em >= [sequence] — chamado pela [AccuChekSource] com o cursor persistido. */
    fun setFetchFromSequence(sequence: Int) { fetchFromSequence = sequence }

    // ── Rádio do sistema ligado/desligado (mesmo padrão do SibionicsBleManager) ──────────────────
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

    // ── Scan (filtra por nome "ACCU-CHEK…", igual MeterScanner.isRocheAccuChekName) ───────────────
    private var scanCallback: ScanCallback? = null

    @SuppressLint("MissingPermission")
    fun startScan(onFound: (name: String?, mac: String, rssi: Int) -> Unit) {
        val scanner = adapter?.bluetoothLeScanner ?: run {
            appLogger.error(LOG, "bluetoothLeScanner indisponível (BT desligado?)")
            return
        }
        stopScan()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.device.name ?: result.scanRecord?.deviceName
                if (AccuChekGatt.isAccuChekName(name)) {
                    appLogger.usage(LOG, "Scan encontrou possível SmartGuide",
                        mapOf("name" to (name ?: "?"), "mac" to result.device.address, "rssi" to result.rssi))
                    onFound(name, result.device.address, result.rssi)
                }
            }
            override fun onScanFailed(errorCode: Int) {
                appLogger.error(LOG, "Scan BLE falhou", mapOf("errorCode" to errorCode))
            }
        }
        scanCallback = cb
        // Sem filtro de serviço no scanner do SO: o AccuChek pode não anunciar 0x1808 no advertisement
        // principal (comum em meters — só aparece após conectar); filtramos por NOME no callback.
        scanner.startScan(null, settings, cb)
        appLogger.usage(LOG, "Scan iniciado (filtro por nome ACCU-CHEK)")
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        scanCallback?.let { adapter?.bluetoothLeScanner?.stopScan(it) }
        scanCallback = null
    }

    // ── Conexão ──────────────────────────────────────────────────────────────────────────────────
    @SuppressLint("MissingPermission")
    fun connect(mac: String) {
        val a = adapter ?: run { appLogger.error(LOG, "BluetoothAdapter==null"); return }
        gatt?.let { runCatching { it.close() } }
        resetCharacteristics()
        targetMac = mac
        val device = runCatching { a.getRemoteDevice(mac) }.getOrNull() ?: run {
            appLogger.error(LOG, "MAC inválido", mapOf("mac" to mac))
            return
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
        glucoseChar = null; contextChar = null; racpChar = null
        dateTimeChar = null; serialChar = null; manufacturerChar = null
        isRocheDevice = false
    }

    // ── GATT callback ────────────────────────────────────────────────────────────────────────────
    private val gattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            val bondState = g.device.bondState
            appLogger.usage(LOG, "Estado GATT mudou", mapOf(
                "mac" to (targetMac ?: "?"), "newState" to newState,
                "gattStatus" to status, "bondState" to bondState
            ))
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                when (bondState) {
                    BluetoothDevice.BOND_NONE, BluetoothDevice.BOND_BONDED -> {
                        if (!g.discoverServices()) {
                            appLogger.error(LOG, "discoverServices() falhou ao chamar")
                            disconnect()
                        }
                    }
                    BluetoothDevice.BOND_BONDING -> {
                        // Aguarda o ACTION_BOND_STATE_CHANGED (registrado abaixo) chamar discoverServices().
                        appLogger.usage(LOG, "Aguardando pareamento (PIN da tampa azul do aplicador)")
                    }
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                resetCharacteristics()
                runCatching { g.close() }
                gatt = null
                listener?.onConnectionChanged(false)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                appLogger.error(LOG, "Descoberta de serviços falhou", mapOf("status" to status))
                disconnect()
                return
            }
            val allServices = g.services.joinToString { s -> "${s.uuid}(chars=${s.characteristics.size})" }
            appLogger.usage(LOG, "Serviços GATT descobertos", mapOf("services" to allServices))

            val glucoseService = g.getService(AccuChekGatt.GLUCOSE_SERVICE)
            glucoseChar = glucoseService?.getCharacteristic(AccuChekGatt.GLUCOSE_MEASUREMENT)
            contextChar = glucoseService?.getCharacteristic(AccuChekGatt.GLUCOSE_MEASUREMENT_CONTEXT)
            racpChar    = glucoseService?.getCharacteristic(AccuChekGatt.RECORD_ACCESS_CONTROL_POINT)
            dateTimeChar = glucoseService?.getCharacteristic(AccuChekGatt.DATE_TIME)
            serialChar   = glucoseService?.getCharacteristic(AccuChekGatt.SERIAL_NUMBER)

            val infoService = g.getService(AccuChekGatt.DEVICE_INFO_SERVICE)
            manufacturerChar = infoService?.getCharacteristic(AccuChekGatt.MANUFACTURER_NAME)

            if (glucoseChar == null || racpChar == null) {
                appLogger.error(LOG, "Serviço Glucose (0x1808) ou characteristics essenciais ausentes",
                    mapOf("glucoseService" to (glucoseService != null),
                          "glucoseChar" to (glucoseChar != null), "racpChar" to (racpChar != null)))
                disconnect()
                return
            }

            val mChar = manufacturerChar
            if (mChar != null) {
                g.readCharacteristic(mChar)
            } else {
                appLogger.usage(LOG, "Sem Manufacturer Name — assumindo AccuChek pelo filtro de nome do scan")
                isRocheDevice = true
                proceedAfterManufacturer(g)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicRead(
            g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int
        ) {
            @Suppress("DEPRECATION")
            val value = characteristic.value ?: ByteArray(0)
            handleCharacteristicRead(g, characteristic, status, value)
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int
        ) {
            handleCharacteristicRead(g, characteristic, status, value)
        }

        @SuppressLint("MissingPermission")
        private fun handleCharacteristicRead(
            g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int, value: ByteArray
        ) {
            if (status == BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION) {
                // Android deve disparar o diálogo de pareamento do sistema automaticamente; só aguardar.
                appLogger.usage(LOG, "Leitura pediu autenticação — aguardando bond do sistema")
                return
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                appLogger.error(LOG, "onCharacteristicRead falhou", mapOf("uuid" to characteristic.uuid, "status" to status))
                disconnect()
                return
            }
            when (characteristic.uuid) {
                AccuChekGatt.MANUFACTURER_NAME -> {
                    val manufacturer = runCatching { String(value) }.getOrDefault("")
                    isRocheDevice = AccuChekGatt.isRocheManufacturer(manufacturer)
                    appLogger.usage(LOG, "Manufacturer Name lido",
                        mapOf("manufacturer" to manufacturer, "isRoche" to isRocheDevice))
                    proceedAfterManufacturer(g)
                }
                AccuChekGatt.DATE_TIME -> {
                    logSensorClockOffset(value)
                    enableGlucoseNotifications(g)
                }
                AccuChekGatt.SERIAL_NUMBER -> {
                    val serial = runCatching { String(value) }.getOrDefault("")
                    appLogger.usage(LOG, "Serial number lido", mapOf("serial" to serial))
                    listener?.onSerialNumber(serial)
                    requestRecords(g)
                }
                else -> appLogger.usage(LOG, "onCharacteristicRead UUID desconhecido", mapOf("uuid" to characteristic.uuid))
            }
        }

        @SuppressLint("MissingPermission")
        private fun proceedAfterManufacturer(g: BluetoothGatt) {
            val dt = dateTimeChar
            if (isRocheDevice && dt != null) {
                g.readCharacteristic(dt)
            } else {
                enableGlucoseNotifications(g)
            }
        }

        private fun logSensorClockOffset(value: ByteArray) {
            if (value.size < 7) return
            val year   = ((value[1].toInt() and 0xFF) shl 8) or (value[0].toInt() and 0xFF)
            val month  = value[2].toInt() and 0xFF
            val day    = value[3].toInt() and 0xFF
            val hour   = value[4].toInt() and 0xFF
            val minute = value[5].toInt() and 0xFF
            val second = value[6].toInt() and 0xFF
            val meterTime = runCatching { LocalDateTime.of(year, month, day, hour, minute, second) }.getOrNull()
            if (meterTime != null) {
                val offsetSec = java.time.Duration.between(meterTime, LocalDateTime.now()).seconds
                appLogger.usage(LOG, "Hora do sensor lida", mapOf("meterTime" to meterTime.toString(), "offsetSec" to offsetSec))
            }
        }

        @SuppressLint("MissingPermission")
        private fun enableGlucoseNotifications(g: BluetoothGatt) {
            val ctx = contextChar
            if (ctx != null) {
                enableNotification(g, ctx)
            } else {
                val glu = glucoseChar ?: return
                enableNotification(g, glu)
            }
        }

        @SuppressLint("MissingPermission")
        private fun requestRecords(g: BluetoothGatt) {
            val racp = racpChar ?: return
            val cmd = AccuChekRacp.buildFetchFromSequence(fetchFromSequence)
            writeChar(g, racp, cmd)
            appLogger.usage(LOG, "RACP: pedindo registros", mapOf("fromSequence" to fetchFromSequence, "hex" to cmd.toHexLog()))
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            val uuid = descriptor.characteristic.uuid
            if (status != BluetoothGatt.GATT_SUCCESS) {
                appLogger.error(LOG, "onDescriptorWrite falhou", mapOf("uuid" to uuid, "status" to status))
                return
            }
            when (uuid) {
                AccuChekGatt.GLUCOSE_MEASUREMENT_CONTEXT -> enableNotification(g, glucoseChar ?: return)
                AccuChekGatt.GLUCOSE_MEASUREMENT -> {
                    val racp = racpChar
                    if (racp != null) enableIndication(g, racp)
                    listener?.onConnectionChanged(true)
                    g.readRemoteRssi()
                }
                AccuChekGatt.RECORD_ACCESS_CONTROL_POINT -> {
                    val serial = serialChar
                    if (isRocheDevice && serial != null) g.readCharacteristic(serial) else requestRecords(g)
                }
                else -> {}
            }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            dispatchNotification(characteristic.uuid, value)
        }

        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                val value = characteristic.value ?: return
                dispatchNotification(characteristic.uuid, value)
            }
        }

        private fun dispatchNotification(uuid: java.util.UUID, value: ByteArray) {
            when (uuid) {
                AccuChekGatt.GLUCOSE_MEASUREMENT -> {
                    appLogger.usage(LOG, "Pacote Glucose Measurement", mapOf("bytes" to value.size, "hex" to value.toHexLog()))
                    listener?.onRawGlucosePacket(value)
                    when (val result = AccuChekMeasurementParser.parse(value)) {
                        is AccuChekParseResult.Reading -> listener?.onReading(result.value)
                        is AccuChekParseResult.NoGlucose -> appLogger.usage(LOG, "Registro sem glicemia", mapOf("seq" to result.sequenceNumber))
                        is AccuChekParseResult.Invalid -> appLogger.error(LOG, "Registro inválido", mapOf("motivo" to result.reason, "hex" to value.toHexLog()))
                    }
                }
                AccuChekGatt.RECORD_ACCESS_CONTROL_POINT -> {
                    appLogger.usage(LOG, "RACP resposta", mapOf("hex" to value.toHexLog()))
                    when {
                        AccuChekRacp.isSuccessResponse(value) -> listener?.onFetchComplete()
                        AccuChekRacp.isNoRecordsFound(value) -> {
                            appLogger.usage(LOG, "RACP: nenhum registro novo")
                            listener?.onFetchComplete()
                        }
                        else -> appLogger.usage(LOG, "RACP resposta não reconhecida")
                    }
                }
                AccuChekGatt.GLUCOSE_MEASUREMENT_CONTEXT -> {
                    // Log-only — contexto (refeição/insulina/etc.) não é usado para o valor de mg/dL.
                    appLogger.usage(LOG, "Glucose Measurement Context (ignorado)", mapOf("bytes" to value.size))
                }
            }
        }

        override fun onReadRemoteRssi(g: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) listener?.onRssi(rssi)
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableNotification(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        g.setCharacteristicNotification(characteristic, true)
        writeCccd(g, characteristic, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
    }

    @SuppressLint("MissingPermission")
    private fun enableIndication(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        g.setCharacteristicNotification(characteristic, true)
        writeCccd(g, characteristic, BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)
    }

    @SuppressLint("MissingPermission")
    private fun writeCccd(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, enableValue: ByteArray) {
        val cccd = characteristic.getDescriptor(AccuChekGatt.CCCD) ?: run {
            appLogger.error(LOG, "CCCD ausente", mapOf("uuid" to characteristic.uuid))
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(cccd, enableValue)
        } else {
            @Suppress("DEPRECATION")
            run {
                cccd.value = enableValue
                g.writeDescriptor(cccd)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeChar(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, bytes: ByteArray): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(characteristic, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                characteristic.value = bytes
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                g.writeCharacteristic(characteristic)
            }
        }
    }
}

private fun ByteArray.toHexLog(maxBytes: Int = size): String =
    take(maxBytes).joinToString(" ") { "%02X".format(it) } +
        if (size > maxBytes) " …+${size - maxBytes}" else ""
