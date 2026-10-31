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
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import com.gluview.app.util.AppLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * GATT BLE do Dexcom G6 — porta o driver nativo do xDrip (`com.eveningoutpost.dexdrip.g5model`,
 * GPLv3). Ver [DexcomGatt]/[DexcomG6Crypto]/[DexcomG6Tx]/[DexcomG6Rx] para UUIDs, cripto e pacotes.
 *
 * Fluxo (idêntico ao `Ob1G5StateMachine` do xDrip):
 *  1. Scan filtrado pelo serviço de anúncio 0xFEBC → connectGatt.
 *  2. discoverServices → habilita notify em Authentication/Control/Backfill/Communication.
 *  3. Escreve AuthRequest (opcode 0x01, token aleatório de 8 bytes) em Authentication.
 *  4. Transmissor responde AuthChallenge (0x03: tokenHash 8B + challenge 8B) — confere que o
 *     tokenHash bate com `challengeHash(meuToken, chave)` (prova que o transmissor conhece a
 *     MESMA chave, derivada do Transmitter ID — ver [DexcomG6Crypto.deriveKey]).
 *  5. Responde com `challengeHash(challenge, chave)` (0x04) — prova que O APP conhece a chave.
 *  6. Transmissor confirma com AuthStatus (0x05: authenticated + bonded).
 *  7. Se autenticado mas não pareado (`bonded=false`), pede bond ao sistema Android.
 *  8. Pronto: pede glicemia (0x4E) periodicamente; TransmitterTime (0x25) dá o início da sessão.
 *
 * IMPORTANTE (sem hardware para validar ainda, 27/07/2026): a derivação de chave e o handshake
 * desafio-resposta são publicamente documentados (não é engenharia reversa de binário) e foram
 * portados fielmente do xDrip — alta confiança na CORREÇÃO da lógica. O que falta validar com o
 * transmissor real: o valor exato do `slot` no AuthRequest, e se o Android bonda automaticamente
 * ou precisa de `createBond()` explícito (o xDrip chama explicitamente — replicado aqui).
 */
@Singleton
class DexcomG6BleManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appLogger: AppLogger
) {
    interface Listener {
        fun onConnectionChanged(connected: Boolean)
        fun onAuthenticated()
        fun onGlucose(reading: DexcomGlucose)
        fun onTransmitterTime(time: TransmitterTime)
        fun onRawPacket(uuid: java.util.UUID, value: ByteArray) {}
        fun onRssi(rssi: Int) {}
        fun onBluetoothTurnedOn() {}
    }

    companion object {
        private const val LOG = "DEXCOM_G6_BLE"
    }

    private val adapter: BluetoothAdapter? by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }

    private var gatt: BluetoothGatt? = null
    private var charComm: BluetoothGattCharacteristic? = null
    private var charControl: BluetoothGattCharacteristic? = null
    private var charAuth: BluetoothGattCharacteristic? = null
    private var charBackfill: BluetoothGattCharacteristic? = null
    private var listener: Listener? = null

    private var transmitterId: String = ""
    private var key: ByteArray = ByteArray(0)
    private var myToken: ByteArray = ByteArray(0)
    @Volatile private var targetMac: String? = null
    @Volatile private var authenticated = false

    fun setListener(l: Listener?) { listener = l }

    /** Deve ser chamado ANTES de [connect] — o Transmitter ID (6 caracteres, impresso no transmissor) deriva a chave AES. */
    fun configure(transmitterId: String) {
        this.transmitterId = transmitterId
        this.key = DexcomG6Crypto.deriveKey(transmitterId)
    }

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

    // ── Scan (filtra pelo serviço de anúncio 0xFEBC) ───────────────────────────────
    private var scanCallback: ScanCallback? = null

    @SuppressLint("MissingPermission")
    fun startScan(onFound: (name: String?, mac: String, rssi: Int) -> Unit) {
        val scanner = adapter?.bluetoothLeScanner ?: run { appLogger.error(LOG, "bluetoothLeScanner indisponível"); return }
        stopScan()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(DexcomGatt.ADVERTISEMENT_SERVICE)).build())
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.device.name ?: result.scanRecord?.deviceName
                appLogger.usage(LOG, "Scan encontrou possível transmissor Dexcom", mapOf("name" to (name ?: "?"), "mac" to result.device.address))
                onFound(name, result.device.address, result.rssi)
            }
            override fun onScanFailed(errorCode: Int) { appLogger.error(LOG, "Scan BLE falhou", mapOf("errorCode" to errorCode)) }
        }
        scanCallback = cb
        scanner.startScan(filters, settings, cb)
        appLogger.usage(LOG, "Scan iniciado (filtro por serviço 0xFEBC)")
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        scanCallback?.let { adapter?.bluetoothLeScanner?.stopScan(it) }
        scanCallback = null
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
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
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
        charComm = null; charControl = null; charAuth = null; charBackfill = null
        authenticated = false
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
            val service = g.getService(DexcomGatt.CGM_SERVICE)
            charComm = service?.getCharacteristic(DexcomGatt.COMMUNICATION)
            charControl = service?.getCharacteristic(DexcomGatt.CONTROL)
            charAuth = service?.getCharacteristic(DexcomGatt.AUTHENTICATION)
            charBackfill = service?.getCharacteristic(DexcomGatt.BACKFILL)

            if (charAuth == null || charControl == null) {
                appLogger.error(LOG, "Serviço CGM ou characteristics essenciais ausentes")
                disconnect(); return
            }
            appLogger.usage(LOG, "Serviços GATT descobertos — habilitando notificações")
            enableNotification(g, charAuth!!)
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            val uuid = descriptor.characteristic.uuid
            if (status != BluetoothGatt.GATT_SUCCESS) { appLogger.error(LOG, "onDescriptorWrite falhou", mapOf("uuid" to uuid, "status" to status)); return }
            when (uuid) {
                DexcomGatt.AUTHENTICATION -> {
                    val control = charControl
                    if (control != null) enableNotification(g, control) else startAuth(g)
                }
                DexcomGatt.CONTROL -> startAuth(g)
                DexcomGatt.COMMUNICATION -> { listener?.onConnectionChanged(true); g.readRemoteRssi() }
            }
        }

        @SuppressLint("MissingPermission")
        private fun startAuth(g: BluetoothGatt) {
            val auth = charAuth ?: return
            myToken = ByteArray(8).also { SecureRandom().nextBytes(it) }
            val request = DexcomG6Tx.authRequest(myToken)
            writeChar(g, auth, request)
            appLogger.usage(LOG, "AuthRequest enviado", mapOf("token" to myToken.joinToString("") { "%02x".format(it) }))
        }

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
                DexcomGatt.AUTHENTICATION -> handleAuthNotify(g, value)
                DexcomGatt.COMMUNICATION -> handleCommNotify(value)
            }
        }

        @SuppressLint("MissingPermission")
        private fun handleAuthNotify(g: BluetoothGatt, value: ByteArray) {
            val opcode = value.getOrNull(0)?.toInt()?.and(0xFF) ?: return
            when (opcode) {
                0x03 -> { // AuthChallenge
                    when (val result = DexcomG6Rx.parseAuthChallenge(value)) {
                        is AuthChallengeResult.Parsed -> {
                            val expectedTokenHash = DexcomG6Crypto.challengeHash(myToken, key)
                            if (!expectedTokenHash.contentEquals(result.tokenHash)) {
                                appLogger.error(LOG, "tokenHash não bateu — Transmitter ID errado ou chave incorreta")
                                disconnect(); return
                            }
                            appLogger.usage(LOG, "tokenHash confirmado — respondendo ao challenge")
                            val response = DexcomG6Tx.authChallengeResponse(DexcomG6Crypto.challengeHash(result.challenge, key))
                            writeChar(g, charAuth ?: return, response)
                        }
                        AuthChallengeResult.Invalid -> appLogger.error(LOG, "AuthChallenge inválido", mapOf("hex" to value.toHexLog()))
                    }
                }
                0x05 -> { // AuthStatus
                    val authStatus = DexcomG6Rx.parseAuthStatus(value)
                    if (authStatus == null) { appLogger.error(LOG, "AuthStatus inválido"); return }
                    appLogger.usage(LOG, "AuthStatus recebido", mapOf("authenticated" to authStatus.authenticated, "bonded" to authStatus.bonded))
                    if (!authStatus.authenticated) { appLogger.error(LOG, "Transmissor recusou autenticação"); disconnect(); return }
                    authenticated = true
                    if (!authStatus.bonded) {
                        appLogger.usage(LOG, "Autenticado mas não pareado — solicitando bond ao sistema")
                        runCatching { g.device.createBond() }
                    }
                    listener?.onAuthenticated()
                    val control = charControl
                    if (control != null) enableNotification(g, control) else enableNotification(g, charComm ?: return)
                }
            }
        }

        private fun handleCommNotify(value: ByteArray) {
            val opcode = value.getOrNull(0)?.toInt()?.and(0xFF) ?: return
            when (opcode) {
                0x25 -> DexcomG6Rx.parseTransmitterTime(value)?.let { listener?.onTransmitterTime(it) }
                    ?: appLogger.error(LOG, "TransmitterTime inválido")
                0x31, 0x4F -> DexcomG6Rx.parseGlucose(value)?.let { listener?.onGlucose(it) }
                    ?: appLogger.error(LOG, "Pacote de glicemia inválido (CRC?)", mapOf("hex" to value.toHexLog()))
                else -> appLogger.usage(LOG, "Opcode Communication não tratado", mapOf("opcode" to "0x%02x".format(opcode)))
            }
        }

        override fun onReadRemoteRssi(g: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) listener?.onRssi(rssi)
        }
    }

    /** Pede uma leitura de glicemia — só funciona após [Listener.onAuthenticated]. */
    @SuppressLint("MissingPermission")
    fun requestGlucose() {
        val g = gatt ?: return
        val comm = charComm ?: return
        if (!authenticated) { appLogger.error(LOG, "requestGlucose chamado antes de autenticar"); return }
        writeChar(g, comm, DexcomG6Tx.askGlucose())
    }

    @SuppressLint("MissingPermission")
    private fun enableNotification(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        g.setCharacteristicNotification(characteristic, true)
        val cccd = characteristic.getDescriptor(DexcomGatt.CCCD) ?: run {
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
            g.writeCharacteristic(characteristic, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION") run {
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
