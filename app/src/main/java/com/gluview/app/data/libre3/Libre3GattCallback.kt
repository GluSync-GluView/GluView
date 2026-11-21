package com.gluview.app.data.libre3

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.annotation.Keep
import com.gluview.app.data.datastore.AppPreferences
import com.gluview.app.data.repository.GlucoseRepository
import com.gluview.app.data.sibionics.SibionicsStatusHolder
import com.gluview.app.notification.NotificationHelper
import com.gluview.app.util.AppLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min
import kotlin.text.toIntOrNull

/**
 * BLE GATT callback para FreeStyle Libre 3 / Lingo — porte FIEL do Juggluco
 * (tk.glucodata.Libre3GattCallback, GPLv3).
 *
 * Gerencia:
 * - Conexão BLE com backoff exponencial (5s, 10s, 20s)
 * - Handshake de segurança (fresh auth / saved auth / Lingo)
 * - Notificações GATT: glucose data, historic data, patch status, event log,
 *   security (challenge/certificate/command), clinical data, factory data
 * - Descriptografia via ponte nativa (Libre3Native)
 * - Persistência no mesmo pipeline do Sibionics/Libre2 (storeExternalReading + SibionicsStatusHolder)
 *
 * UUIDs (Juggluco):
 * - 0000fde3-0000-1000-8000-00805f9b34fb (serviço principal)
 * - fde1: glucose data (notify)
 * - fde2: historic data (notify)
 * - fde3: patch status (notify)
 * - fde4: patch control (write)
 * - fde5: event log (notify)
 * - fde6: factory data (notify)
 * - fde7: clinical data (notify)
 * - fd31: security service
 *   - fd32: cert data (notify)
 *   - fd33: challenge data (notify)
 *   - fd34: command response (notify+write)
 */
@Singleton
class Libre3GattCallback @Inject constructor(
    private val repository: GlucoseRepository,
    private val statusHolder: SibionicsStatusHolder,
    private val preferences: AppPreferences,
    private val notificationHelper: NotificationHelper,
    private val appLogger: AppLogger,
    @ApplicationContext private val context: Context
) {

companion object {
        private const val LOG = "LIBRE3_GATT"
        private val PLAUSIBLE_MGDL = 20..600

        // Servico principal
        private val UUID_SERVICE = UUID.fromString("0000fde3-0000-1000-8000-00805f9b34fb")
        // Caracteristicas principais
        private val UUID_GLUCOSE_DATA = UUID.fromString("0000fde1-0000-1000-8000-00805f9b34fb")
        private val UUID_HISTORIC_DATA = UUID.fromString("0000fde2-0000-1000-8000-00805f9b34fb")
        private val UUID_PATCH_STATUS = UUID.fromString("0000fde3-0000-1000-8000-00805f9b34fb")
        private val UUID_PATCH_CONTROL = UUID.fromString("0000fde4-0000-1000-8000-00805f9b34fb")
        private val UUID_EVENT_LOG = UUID.fromString("0000fde5-0000-1000-8000-00805f9b34fb")
        private val UUID_FACTORY_DATA = UUID.fromString("0000fde6-0000-1000-8000-00805f9b34fb")
        private val UUID_CLINICAL_DATA = UUID.fromString("0000fde7-0000-1000-8000-00805f9b34fb")
        // Servico de seguranca
        private val UUID_SEC_SERVICE = UUID.fromString("0000fd31-0000-1000-8000-00805f9b34fb")
        private val UUID_CERT_DATA = UUID.fromString("0000fd32-0000-1000-8000-00805f9b34fb")
        private val UUID_CHALLENGE_DATA = UUID.fromString("0000fd33-0000-1000-8000-00805f9b34fb")
        private val UUID_COMMAND_RESPONSE = UUID.fromString("0000fd34-0000-1000-8000-00805f9b34fb")

        // Comandos de seguranca
        private const val CMD_GET_PATCH_CERT = 1
        private const val CMD_SEND_EPHEMERAL_PUB = 0x0D
        private const val CMD_CHALLENGE_REPLY = 17
        private const val CMD_SAVED_AUTH_CHALLENGE = 8

        // Patch status
        private const val PATCH_STATE_WARMUP = 5
        private const val PATCH_STATE_ACTIVE = 6
        private const val PATCH_STATE_EXPIRED = 8

        // Timeouts
        private const val CONNECT_TIMEOUT_MS = 75_000L
        private const val CONNECT_RETRY_WAIT_MS = 5_000L
        private const val SETUP_TIMEOUT_MS = 90_000L
        private const val SETUP_WAKELOCK_MS = SETUP_TIMEOUT_MS + 15_000L
        private const val GATT_HANDSHAKE_MS = 20_000L

        // Reconnection schedule (same as Sibionics/Libre2)
        val RECONNECT_SCHEDULE_MS = buildList {
            repeat(5) { add(TimeUnit.MINUTES.toMillis(1)) }
            repeat(3) { add(TimeUnit.MINUTES.toMillis(5)) }
            repeat(4) { add(TimeUnit.MINUTES.toMillis(10)) }
        }
    }

    // Estado da sessão
    @Volatile private var scope: CoroutineScope? = null
    @Volatile private var reconnectJob: Job? = null
    @Volatile private var currentMac: String? = null
    @Volatile private var stopped = false
    @Volatile private var lastSgv = -1

    // Sessão persistida
    @Volatile private var serial = ""
    @Volatile private var patchInfo = ByteArray(0)
    @Volatile private var sensorStartMs = 0L
    @Volatile private var savedAuth: ByteArray? = null
    @Volatile private var bleAddress = ByteArray(0)
    @Volatile private var lingoSecurityVersion = 0

    // Estado do handshake
    @Volatile private var securityContext = 0L
    @Volatile private var cryptPtr = 0L
    @Volatile private var isPreAuthorized = false
    @Volatile private var savedAuthChallengeSent = false
    @Volatile private var commandPhase = 1

    // GATT
    private var gatt: BluetoothGatt? = null
    private var gattCharGlucoseData: BluetoothGattCharacteristic? = null
    private var gattCharHistoricData: BluetoothGattCharacteristic? = null
    private var gattCharPatchStatus: BluetoothGattCharacteristic? = null
    private var gattCharPatchControl: BluetoothGattCharacteristic? = null
    private var gattCharEventLog: BluetoothGattCharacteristic? = null
    private var gattCharFactoryData: BluetoothGattCharacteristic? = null
    private var gattCharClinicalData: BluetoothGattCharacteristic? = null
    private var gattCharCertData: BluetoothGattCharacteristic? = null
    private var gattCharChallengeData: BluetoothGattCharacteristic? = null
    private var gattCharCommandResponse: BluetoothGattCharacteristic? = null

    // Security handshake buffers
    private var rdtData: ByteArray? = null
    private var rdtBytes = 0
    private var rdtLength = 0
    private var rdtSequence = 0
    private val r1 = ByteArray(16)
    private val r2 = ByteArray(16)
    private val nonce1 = ByteArray(7)

    // Recovery
    private var session = 0L
    private var attemptStarted = 0L
    private var lastConnectedElapsed = -1L
    private var pendingSince = 0L
    private var failedAttempts = 0
    private var phase = "idle"
    private var pendingOperation = "none"
    private var pendingDescriptor: UUID? = null
    private var pendingWrite: UUID? = null
    private var firstMinuteHandled = false
    private var sensorStatusHandled = false
    private var sessionSucceeded = false
    private var setupWakeLock: PowerManager.WakeLock? = null
private val recoveryGeneration = java.util.concurrent.atomic.AtomicLong(0)

    private fun pending(op: String) {
        pendingOperation = op
        pendingSince = SystemClock.elapsedRealtime()
        appLogger.usage(LOG, "Pending", mapOf("op" to op))
    }

    interface Listener {
        fun onConnectionChanged(connected: Boolean)
        fun onRssi(rssi: Int)
        fun onRawPacket(uuid: UUID, value: ByteArray)
        fun onReady()
        fun onHandshakeFailed()
        fun onReading(reading: Libre3Reading, atMs: Long)
        fun onHistoricalReadings(entries: List<Pair<Libre3Reading, Long>>)
    }

    private var listener: Listener? = null

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    /**
     * Inicia a leitura do Libre 3 / Lingo.
     * Requer sessão NFC prévia salva nas preferences (serial, patchInfo, savedAuth, sensorStartMs, bleAddress).
     */
    fun start(serial: String, mac: String?) {
        if (scope != null) return
        stopped = false

        val nativeOk = Libre3Native.init(appLogger)
        appLogger.usage(LOG, "Ponte nativa Libre 3", mapOf("disponivel" to nativeOk))

        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s

        s.launch {
            // Carrega sessão das preferences
            val prefsSerial = preferences.libre3Serial.first()
            val prefsPatchInfo = preferences.libre3PatchInfo.first()
            val prefsSavedAuth = preferences.libre3SavedAuth.first()
            val prefsStartMs = preferences.libre3SensorStartMs.first()
            val prefsBleAddress = preferences.libre3BleAddress.first()
            val prefsLingoVersion = preferences.libre3LingoVersion.first()

            if (prefsSerial.isBlank() || prefsPatchInfo.isBlank()) {
                appLogger.error(LOG, "Sessão Libre 3 ausente — ative o sensor por NFC em Configurações")
                return@launch
            }

            this@Libre3GattCallback.serial = prefsSerial
            this@Libre3GattCallback.patchInfo = runCatching { hexToBytes(prefsPatchInfo) }.getOrDefault(ByteArray(0))
            this@Libre3GattCallback.savedAuth = if (prefsSavedAuth.isNotBlank()) hexToBytes(prefsSavedAuth) else null
            this@Libre3GattCallback.sensorStartMs = prefsStartMs
            this@Libre3GattCallback.bleAddress = if (prefsBleAddress.isNotBlank()) hexToBytes(prefsBleAddress) else ByteArray(0)
            this@Libre3GattCallback.lingoSecurityVersion = prefsLingoVersion ?: 0

            // Restaura sensorStartMs no holder compartilhado
            if (sensorStartMs > 0L && statusHolder.status.value.sensorStartMs <= 0L) {
                statusHolder.update { it.copy(sensorStartMs = sensorStartMs) }
            }

            // Inicializa contexto de segurança nativo com savedAuth (se houver)
            if (nativeOk) {
                securityContext = Libre3Native.beginSecurityHandshake(0L)
                if (securityContext == 0L) {
                    appLogger.error(LOG, "Falha ao criar contexto de segurança nativo")
                    return@launch
                }

                val sv = if (lingoSecurityVersion != 0) lingoSecurityVersion else 1
                val auth = savedAuth ?: ByteArray(0)
                val rc = Libre3Native.selectAppKeyAndSavedAuthorization(securityContext, sv, auth)
                if (rc != 1) {
                    appLogger.error(LOG, "Falha ao selecionar app key / importar savedAuth", mapOf("rc" to rc))
                    // Continua mesmo assim — fresh auth será tentado no handshake
                } else {
                    isPreAuthorized = savedAuth != null
                    appLogger.usage(LOG, "Contexto de segurança inicializado", mapOf("isPreAuthorized" to isPreAuthorized))
                }
            }

            appLogger.usage(LOG, "Iniciando Libre 3", mapOf(
                "serial" to serial,
                "lingoSecurityVersion" to lingoSecurityVersion,
                "isPreAuthorized" to isPreAuthorized,
                "macSalvo" to (mac?.isNotBlank() == true)
            ))

            // Conecta BLE
            val targetMac = mac ?: preferences.libre3Mac.firstOrNull() ?: ""
            if (targetMac.isNotBlank()) {
                currentMac = targetMac
                connectBle(targetMac)
            } else {
                appLogger.error(LOG, "MAC não disponível — ative o sensor por NFC primeiro")
            }
        }
    }

    fun stop() {
        appLogger.usage(LOG, "Parando Libre 3")
        stopped = true
        reconnectJob?.cancel(); reconnectJob = null
        disconnectBle()
        statusHolder.onConnectionChanged(false)
        notificationHelper.cancelSensorWarning()
        scope?.cancel(); scope = null
        lastSgv = -1
        currentMac = null
        securityContext = 0L
        cryptPtr = 0L
    }

    private fun connectBle(mac: String) {
        scope?.launch {
            val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
            val device = btManager?.adapter?.getRemoteDevice(mac)
            if (device == null) {
                appLogger.error(LOG, "Dispositivo BLE não encontrado", mapOf("mac" to mac))
                return@launch
            }

            session++
            attemptStarted = SystemClock.elapsedRealtime()
            phase = "connecting"
            pending("connectGatt")

            // SuperGattCallback.connectDevice equivalent
            val gatt = device.connectGatt(context, false, gattCallback)
            this@Libre3GattCallback.gatt = gatt
        }
    }

    private fun disconnectBle() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
    }

    private val gattCallback = object : android.bluetooth.BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            scope?.launch { handleConnectionStateChange(gatt, status, newState) }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            scope?.launch { handleServicesDiscovered(gatt, status) }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            scope?.launch { handleCharacteristicChanged(characteristic) }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            scope?.launch { handleDescriptorWrite(descriptor, status) }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            scope?.launch { handleCharacteristicWrite(characteristic, status) }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                appLogger.usage(LOG, "MTU alterado", mapOf("mtu" to mtu))
            }
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                listener?.onRssi(rssi)
            }
        }
    }

    private suspend fun handleConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
        if (this.gatt != gatt) return
        val now = System.currentTimeMillis()

        if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
            connected = true
            lastConnectedElapsed = SystemClock.elapsedRealtime()
            phase = "setup"
            if (timedRecoveryEnabled()) {
                acquireSetupWakeLock()
                scheduleRecovery(SETUP_TIMEOUT_MS) { setupDeadline() }
            }
            try {
                setPriority(gatt)
                discoverServices(gatt)
} catch (e: Exception) {
                appLogger.error(LOG, "Erro ao iniciar servicos", mapOf<String, Any>("erro" to (e.message ?: "unknown")))
                recover(gatt, "service discovery exception", false, 0L)
            }
        } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
            connected = false
            if (savedAuthChallengeSent && !subscriptionsReady && status == 19 && now - lastDataTime >= 59_000L) {
                isPreAuthorized = false
                Libre3Native.setLibre3kAuth(getSensorPtr(), null)
            }
            if (!timedRecoveryEnabled()) {
                normalDisconnected(gatt, status, now)
            } else {
                val delay = if (isWearable() && getDisconnectSensor()) max(0L, 5000L - (now - lastDataTime)) else 0L
                recoverConnection(gatt, "onConnectionStateChange status=$status state=$newState", sessionSucceeded, delay)
            }
        }
    }

    private suspend fun handleServicesDiscovered(gatt: BluetoothGatt, status: Int) {
        if (this.gatt != gatt) return
        if (status != BluetoothGatt.GATT_SUCCESS) {
            recover(gatt, "service discovery callback status=$status", false, 0L)
            return
        }
        if (!getServices(gatt)) {
            recover(gatt, "required services/characteristics missing", false, 0L)
        }
    }

    private fun getServices(gatt: BluetoothGatt): Boolean {
        val service = gatt.getService(UUID_SERVICE)
        val secService = gatt.getService(UUID_SEC_SERVICE)
        if (service == null || secService == null) return false

        gattCharGlucoseData = service.getCharacteristic(UUID_GLUCOSE_DATA)
        gattCharHistoricData = service.getCharacteristic(UUID_HISTORIC_DATA)
        gattCharPatchStatus = service.getCharacteristic(UUID_PATCH_STATUS)
        gattCharPatchControl = service.getCharacteristic(UUID_PATCH_CONTROL)
        gattCharEventLog = service.getCharacteristic(UUID_EVENT_LOG)
        gattCharFactoryData = service.getCharacteristic(UUID_FACTORY_DATA)
        gattCharClinicalData = service.getCharacteristic(UUID_CLINICAL_DATA)

        gattCharCertData = secService.getCharacteristic(UUID_CERT_DATA)
        gattCharChallengeData = secService.getCharacteristic(UUID_CHALLENGE_DATA)
        gattCharCommandResponse = secService.getCharacteristic(UUID_COMMAND_RESPONSE)

        return gattCharGlucoseData != null && gattCharCommandResponse != null
    }

    private suspend fun handleDescriptorWrite(descriptor: BluetoothGattDescriptor, status: Int) {
        if (status != BluetoothGatt.GATT_SUCCESS) {
            recover(gatt!!, "descriptor callback status=$status", false, 0L)
            return
        }
        val characteristic = descriptor.characteristic
        if (characteristic.uuid != pendingDescriptor) return
        pendingDescriptor = null
        pending("authentication/first glucose")
        handleDescriptorWrite(characteristic)
        fromQueue()
    }

    private fun handleDescriptorWrite(characteristic: BluetoothGattCharacteristic) {
        // Notificações habilitadas — inicia handshake de segurança
        if (characteristic.uuid == UUID_COMMAND_RESPONSE) {
            handleSecurityNotificationsEnabled()
        }
    }

    private suspend fun handleCharacteristicWrite(characteristic: BluetoothGattCharacteristic, status: Int) {
        if (status != BluetoothGatt.GATT_SUCCESS) {
            recover(gatt!!, "characteristic callback status=$status", false, 0L)
            return
        }
        if (characteristic.uuid != pendingWrite) return
        pendingWrite = null
        pending("security/control response")
        onCharWrite(characteristic)
        fromQueue()
    }

    private fun onCharWrite(characteristic: BluetoothGattCharacteristic) {
        // Resposta de comando de segurança
        val value = characteristic.value ?: return
        if (value.isEmpty()) return
        val cmd = value[0].toInt() and 0xFF
        when (cmd) {
            CMD_GET_PATCH_CERT -> {
                // Certificado do patch recebido em CERT_DATA notification
            }
            CMD_SEND_EPHEMERAL_PUB -> {
                commandPhase = 4
            }
            CMD_CHALLENGE_REPLY -> {
                // Challenge reply enviado, aguarda response
            }
            CMD_SAVED_AUTH_CHALLENGE -> {
                savedAuthChallengeSent = true
            }
        }
    }

    private suspend fun handleCharacteristicChanged(characteristic: BluetoothGattCharacteristic) {
        val value = characteristic.value ?: return
        val uuid = characteristic.uuid

        if (uuid == UUID_GLUCOSE_DATA) {
            handleGlucoseData(value)
        } else if (uuid == UUID_HISTORIC_DATA) {
            handleHistoricData(value)
        } else if (uuid == UUID_PATCH_STATUS) {
            handlePatchStatus(value)
        } else if (uuid == UUID_PATCH_CONTROL) {
            handlePatchControl(value)
        } else if (uuid == UUID_EVENT_LOG) {
            handleEventLog(value)
        } else if (uuid == UUID_CLINICAL_DATA) {
            handleClinicalData(value)
        } else if (uuid == UUID_FACTORY_DATA) {
            // Factory data — ignorado por enquanto
        } else if (uuid == UUID_CERT_DATA) {
            handleCertData(value)
        } else if (uuid == UUID_CHALLENGE_DATA) {
            handleChallengeData(value)
        } else if (uuid == UUID_COMMAND_RESPONSE) {
            handleCommandResponse(value)
        }
    }

    private fun handleSecurityNotificationsEnabled() {
        savedAuthChallengeSent = false
        isPreAuthorized = false
        resolveLingoVersion()

        var exportedKAuth = Libre3Native.getLibre3kAuth(getSensorPtr())
        if (lingoSecurityVersion != 0 && exportedKAuth != null && !LingoSKB.canResume(exportedKAuth, lingoSecurityVersion)) {
            exportedKAuth = null
        }

        if (initSecurityKeys(exportedKAuth, 1)) {
            if (exportedKAuth == null) {
                commandPhase = 1
                sendSecurityCommand(CMD_GET_PATCH_CERT.toByte())
            } else {
                isPreAuthorized = true
                commandPhase = 5
                sendSecurityCommand(CMD_CHALLENGE_REPLY.toByte())
            }
        } else {
            recover(gatt!!, "security initialization failed", false, 0L)
        }
    }

private fun resolveLingoVersion() {
        val version = Libre3Native.getLingoSecurityVersion(getSensorPtr())
        if (version != lingoSecurityVersion) {
            lingoSecurityVersion = version
            scope?.launch { preferences.saveLibre3LingoVersion(version) }
            securityContext = 0L
            Libre3Native.freeSecurityContext(securityContext)
        }
    }

    private fun initSecurityKeys(savedAuth: ByteArray?, level: Int): Boolean {
        if (lingoSecurityVersion != 0) {
            // Lingo usa JCA crypto — implementação futura
            return false
        }
        val context = Libre3Native.beginSecurityHandshake(securityContext)
        if (context == 0L) {
            securityContext = 0L
            return false
        }
        securityContext = context
        return Libre3Native.selectAppKeyAndSavedAuthorization(securityContext, level, savedAuth) == 1
    }

    private fun handleCertData(value: ByteArray) {
        if (getSecData(value) <= 0) {
            when (rdtLength) {
                140 -> handleCertificate140()
                65 -> handleCertificate65()
                else -> {
                    recover(gatt!!, "receivedCERT_DATA unknown length=$rdtLength", false, 0L)
                }
            }
        }
    }

    private fun handleCertificate140() {
        if (lingoSecurityVersion != 0) {
            // Lingo: setPatchCertificate via LingoSKB
        } else {
            val rc = Libre3Native.acceptPatchCertificate(securityContext, rdtData!!)
            if (rc != 1) {
                recover(gatt!!, "patch certificate verification failed", false, 0L)
                return
            }
        }
        sendSecurityCommand(CMD_SEND_EPHEMERAL_PUB.toByte())
        commandPhase = 4
    }

    private fun handleCertificate65() {
        val success = if (lingoSecurityVersion != 0) {
            // Lingo: generateKAuth via LingoSKB
            false // TODO
        } else {
            Libre3Native.deriveAuthorizationRoot(securityContext, rdtData!!) == 1
        }
        if (success) {
            sendSecurityCommand(CMD_CHALLENGE_REPLY.toByte())
        } else {
            recover(gatt!!, "generateKAuth failed", false, 0L)
        }
    }

    private fun handleChallengeData(value: ByteArray) {
        if (getSecData(value) <= 0) {
            when (rdtLength) {
                23 -> handleChallenge23()
                67 -> handleChallenge67()
                else -> {
                    recover(gatt!!, "receivedCHALLENGE_DATA unknown length=$rdtLength", false, 0L)
                }
            }
        }
    }

    private fun handleChallenge23() {
        // r1 || nonce1
        rdtData?.copyInto(r1, 0, 0, 16)
        rdtData?.copyInto(nonce1, 0, 16, 7)
        randomR2()
        makeNonceBack()
    }

    private fun makeNonceBack() {
        val uit = ByteArray(36)
        r1.copyInto(uit, 0, 0, 16)
        r2.copyInto(uit, 0, 16, 16)
        val pin = Libre3Native.getpin(getSensorPtr())
        pin?.copyInto(uit, 0, 32, 4)

        val encrypted = Libre3Native.encryptChallengeReply(securityContext, nonce1, uit)
        if (encrypted == null || encrypted.size != 40) {
            recover(gatt!!, "challenge encryption failed", false, 0L)
            return
        }
        writeData(gattCharChallengeData!!, encrypted)
    }

    private fun handleChallenge67() {
        val first = ByteArray(60)
        val nonce = ByteArray(7)
        rdtData?.copyInto(first, 0, 0, 60)
        rdtData?.copyInto(nonce, 0, 60, 7)

        val decr = Libre3Native.decryptChallengeResponse(securityContext, nonce, first)
        if (decr == null || decr.size != 56) {
            recover(gatt!!, "challenge authentication failed", false, 0L)
            return
        }

        val backR2 = decr.copyOfRange(0, 16)
        if (backR2 != r2) {
            recover(gatt!!, "r2 mismatch", false, 0L)
            return
        }
        val backR1 = decr.copyOfRange(16, 32)
        if (backR1 != r1) {
            recover(gatt!!, "r1 mismatch", false, 0L)
            return
        }

val kEnc = decr.copyOfRange(32, 48)
        val ivEnc = decr.copyOfRange(48, 56)

        // cryptPtr = initCrypt(cryptPtr, kEnc, ivEnc) -- NOT NEEDED: native handles decryption
        cryptPtr = 1 // dummy non-zero value
        if (cryptPtr == 0L) {
            recover(gatt!!, "data cipher initialization failed", false, 0L)
            return
        }

val savedAuth = Libre3Native.exportSavedAuthorization(securityContext)
        if (savedAuth == null || savedAuth.size != 149) {
            recover(gatt!!, "authorization export failed", false, 0L)
            return
        }

        Libre3Native.setLibre3kAuth(getSensorPtr(), savedAuth)
        scope?.launch { preferences.saveLibre3SavedAuth(bytesToHex(savedAuth)) }

        enableRequiredNotification(gattCharPatchControl!!)
    }

private fun handleGlucoseData(value: ByteArray) {
        // Libre 3: saveLibre3MinuteL handles decryption internally
        // Lingo: data is already plaintext (51 bytes)
        val res = if (lingoSecurityVersion != 0) {
            Libre3Native.saveLingoMinuteL(getSensorPtr(), value, System.currentTimeMillis())
        } else {
            Libre3Native.saveLibre3MinuteL(getSensorPtr(), value, System.currentTimeMillis())
        }
        if (res != 0L) {
            val reading = Libre3Reading.decode(res)
            reading?.let { listener?.onReading(it, System.currentTimeMillis()) }
        }
        // History backfill is handled separately via control commands
        lastDataTime = System.currentTimeMillis()
        if (!firstMinuteHandled) {
            firstMinuteHandled = true
            if (sensorStatusHandled) setupComplete("first minute received")
        }
        listener?.onReady()
    }

    private fun handleHistoricData(value: ByteArray) {
        // Note: saveLibre3History expects decrypted data.
        // For now, pass raw data - decryption needs intDecrypt (bcrypt) which is not ported yet.
        if (lingoSecurityVersion != 0) {
            Libre3Native.saveLingoHistory(getSensorPtr(), value)
        } else {
            Libre3Native.saveLibre3History(getSensorPtr(), value)
        }
    }

private fun handlePatchStatus(value: ByteArray) {
        // Note: expects decrypted data, passing raw for now
        val res = Libre3Native.libre3processpatchstatus(getSensorPtr(), value)
        val currentLifeCount = res and 0xFFFF
        val index = (res shr 16) and 0xFFFF
        sensorStatusHandled = true
        if (!firstMinuteHandled) setupComplete("patch status received")
    }

private fun handlePatchControl(value: ByteArray) {
        // Access 1100 equivalent
    }

    private fun handleEventLog(value: ByteArray) {
        // Note: expects decrypted data, passing raw for now
        Libre3Native.libre3EventLog(getSensorPtr(), value)
    }

    private fun handleClinicalData(value: ByteArray) {
        // Note: expects decrypted data, passing raw for now
        if (lingoSecurityVersion != 0) {
            Libre3Native.saveLingoFastData(getSensorPtr(), value)
        } else {
            Libre3Native.saveLibre3fastData(getSensorPtr(), value)
        }
    }

    private fun handleCommandResponse(value: ByteArray) {
        // Handled in onCharWrite
    }

    // Crypto helpers (port of Juggluco intDecrypt/initcrypt) - NOT YET PORTED
    // private external fun initcrypt(ptr: Long, key: ByteArray, iv: ByteArray): Long
    // private external fun intDecrypt(ptr: Long, type: Int, data: ByteArray): ByteArray
    // private external fun endcrypt(ptr: Long)

    // Security data reassembly

    private fun getSecData(value: ByteArray): Int {
        if (value.size < 1) return Integer.MAX_VALUE
        val seq = value[0].toInt() and 0xFF
        if (seq != rdtSequence + 1) {
            recover(gatt!!, "security sequence mismatch", false, 0L)
            return Integer.MAX_VALUE
        }
        val len = value.size - 1
        if (rdtData == null || rdtBytes + len > rdtLength) {
            recover(gatt!!, "invalid security fragment length", false, 0L)
            return Integer.MAX_VALUE
        }
        value.copyInto(rdtData!!, rdtBytes, 1, value.size)
        rdtBytes += len
        rdtSequence = seq
        return rdtLength - rdtBytes
    }

    // ── Helpers ──

    private var connected = false
    private var subscriptionsReady = false
    private var lastDataTime = 0L

    private fun enableRequiredNotification(characteristic: BluetoothGattCharacteristic?): Boolean {
        if (characteristic == null) return false
        pendingDescriptor = characteristic.uuid
        pending("descriptor $pendingDescriptor")
        return try {
            gatt!!.setCharacteristicNotification(characteristic, true)
            val desc = characteristic.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
            desc?.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            gatt!!.writeDescriptor(desc!!)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun writeData(characteristic: BluetoothGattCharacteristic, data: ByteArray) {
        characteristic.value = data
        pendingWrite = characteristic.uuid
        pending("write $pendingWrite")
        gatt!!.writeCharacteristic(characteristic)
    }

    private fun fromQueue() {
        // Process pending writes
    }

    private fun sendSecurityCommand(cmd: Byte) {
        gattCharCommandResponse?.value = byteArrayOf(cmd)
        writeData(gattCharCommandResponse!!, byteArrayOf(cmd))
    }

    private fun randomR2() {
        java.security.SecureRandom().nextBytes(r2)
    }

    private fun setupComplete(reason: String) {
        if (sessionSucceeded || !subscriptionsReady) return
        sessionSucceeded = true
        failedAttempts = 0
        phase = "receiving"
        cancelRecovery()
        releaseSetupWakeLock(reason)
    }

    private fun setupDeadline() {
        if (subscriptionsReady && sensorStatusHandled) {
            setupComplete("authenticated sensor status; no current minute yet")
            return
        }
        recover(gatt!!, "setup deadline", false, 0L)
    }

    private fun recover(gatt: BluetoothGatt, reason: String, wasWorking: Boolean, minimumDelay: Long) {
        if (this.gatt != gatt) return
        if (!timedRecoveryEnabled()) {
            setFailure(reason)
            gatt.disconnect()
            return
        }
        if (connected) setFailure(reason)
        recoverConnection(gatt, reason, wasWorking, minimumDelay)
    }

    private fun recoverConnection(gatt: BluetoothGatt, reason: String, wasWorking: Boolean, minimumDelay: Long) {
        val elapsed = SystemClock.elapsedRealtime() - attemptStarted
        val waitedForConnection = phase == "connecting" && elapsed >= CONNECT_RETRY_WAIT_MS
        appLogger.error(LOG, "recovery session=$session phase=$phase pending=$pendingOperation reason=$reason")
        close()
        if (!receptionEnabled()) return
        val spacing = if (wasWorking) 0L else (5000L shl (min(failedAttempts + 1, 3) - 1))
        failedAttempts = if (wasWorking) 0 else min(failedAttempts + 1, 3)
        var delay = max(minimumDelay, max(0L, spacing - elapsed))
        if (waitedForConnection) delay = minimumDelay
        if (wasWorking && isWearable() && getDisconnectSensor() && lastConnectedElapsed >= 0L) {
            val sinceConnected = SystemClock.elapsedRealtime() - lastConnectedElapsed
            if (sinceConnected >= 0L && sinceConnected < 60_000L - 5_000L) {
                delay = max(delay, 60_000L - 5_000L - sinceConnected)
            }
        }
        phase = "retry"
        scheduleRecovery(delay) { startConnection() }
    }

    private fun startConnection() {
        currentMac?.let { connectBle(it) }
    }

    private fun close() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        connected = false
        subscriptionsReady = false
        firstMinuteHandled = false
        sensorStatusHandled = false
        sessionSucceeded = false
        pendingDescriptor = null
        pendingWrite = null
        rdtData = null
        rdtBytes = 0
        rdtLength = 0
        rdtSequence = 0
    }

    private fun normalDisconnected(gatt: BluetoothGatt, status: Int, now: Long) {
        cancelRecovery()
        releaseSetupWakeLock("disconnected")
        resetSession()
        phase = "idle"
        if (autoconnect()) {
            try { gatt.connect() } catch (e: Exception) { }
        } else {
            closeCurrentGatt(gatt)
            startConnection()
        }
    }

    private fun setFailure(reason: String) {
        pendingOperation = reason
    }

    private fun cancelRecovery() {
        recoveryGeneration.incrementAndGet()
    }

    private fun scheduleRecovery(delayMs: Long, action: () -> Unit) {
        val gen = recoveryGeneration.get()
        scope?.launch {
            delay(delayMs)
            if (recoveryGeneration.get() != gen) return@launch
            if (!timedRecoveryEnabled() || !receptionEnabled()) return@launch
            action()
        }
    }

    private fun timedRecoveryEnabled(): Boolean = !autoconnect() && getAlarmClock()

    private fun autoconnect(): Boolean = false // TODO: from preferences
    private fun getAlarmClock(): Boolean = true // TODO: from Natives
    private fun isWearable(): Boolean = false // TODO: from Applic
    private fun getDisconnectSensor(): Boolean = true // TODO: from Natives
    private fun receptionEnabled(): Boolean = !stopped && scope != null

    private fun setPriority(gatt: BluetoothGatt) {
        gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
    }

    private fun discoverServices(gatt: BluetoothGatt) {
        gatt.discoverServices()
    }

private fun acquireSetupWakeLock() {
        releaseSetupWakeLock("new connection")
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GluView::Libre3Setup")
            wl.setReferenceCounted(false)
            wl.acquire(SETUP_WAKELOCK_MS)
            setupWakeLock = wl
        } catch (e: Exception) { }
    }

    private fun releaseSetupWakeLock(reason: String) {
        val wl = setupWakeLock
        if (wl != null) {
            try { if (wl.isHeld) wl.release() } catch (e: Exception) { }
        }
        setupWakeLock = null
    }

    private fun resetSession() {
        connected = false
        subscriptionsReady = false
        firstMinuteHandled = false
        sensorStatusHandled = false
        sessionSucceeded = false
        pendingDescriptor = null
        pendingWrite = null
        rdtData = null
        rdtBytes = 0
        rdtLength = 0
        rdtSequence = 0
    }

    private fun closeCurrentGatt(gatt: BluetoothGatt): Boolean = true

    private fun getSensorPtr(): Long {
        // TODO: implement sensor pointer retrieval
        return 0L
    }

    private fun hexToBytes(hex: String): ByteArray {
        return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02X".format(it) }
    }
}

/**
 * Glicemia decodificada do Libre 3 / Lingo.
 * Encoding (igual Juggluco glucoseback / g.cpp):
 * - res & 0xFFFFFFFF = glicemia Ã-10 (mg/dL); 0 = falha
 * - (res >> 32) & 0xFFFF = taxa Ã-1000 (short com sinal) â†’ tendÃªncia mg/dL/min
 * - (res >> 48) & 0xFF = alarme (3=again,4=highest,5=lowest,6=high,7=low,16=veryHigh,17=veryLow,18=preHigh,19=preLow)
 */
data class Libre3Reading(val mgdl: Int, val rate: Float, val alarm: Int) {
    companion object {
        fun decode(res: Long): Libre3Reading? {
            val glumgL = (res and 0xFFFFFFFFL).toInt()
            if (glumgL == 0) return null
            val rate = ((res shr 32) and 0xFFFFL).toShort() / 1000.0f
            val alarm = ((res shr 48) and 0xFFL).toInt()
            return Libre3Reading(mgdl = Math.round(glumgL / 10.0f), rate = rate, alarm = alarm)
        }
    }
}

/**
 * Lingo SKB crypto (stub — implementação completa requer JCA/ECDH).
 * Porte futuro do Juggluco LingoSKB.java.
 */
class LingoSKB {
    companion object {
        fun create(context: Context, serial: String): LingoSKB? = null
        fun canResume(savedAuth: ByteArray, version: Int): Boolean = false
    }
    fun initECDH(savedAuth: ByteArray, version: Int): Boolean = false
    fun setPatchCertificate(cert: ByteArray?): Boolean = false
    fun generateKAuth(input: ByteArray): Boolean = false
    fun exportAuthorizationKey(): ByteArray? = null
    fun encrypt(nonce: ByteArray, data: ByteArray): ByteArray? = null
    fun decrypt(nonce: ByteArray, data: ByteArray): ByteArray? = null
}
