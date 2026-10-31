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
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.gluview.app.util.AppLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * GATT BLE do FreeStyle Libre 2 Plus — porte FIEL do Juggluco `Libre2GattCallback` (caminho **Gen2**).
 *
 * IMPORTANTE (corrigido 11/07/2026): o Libre 2 Plus usa o serviço **`fde3`** (ADC customizado), NÃO o
 * `089810cc` (que é o serviço de dados do Libre 3 real). O discriminador no Juggluco é o intervalo de
 * amostragem: 5 min → Libre 3; 1 min → Libre 2/2 Plus (Gen2). Ver `SensorGlucoseData::isLibre3()`.
 *
 * Máquina de estados (conphase), espelhando `Libre2GattCallback`:
 * ```
 *   0  idle
 *   1  notify em f001 (BLELogin) habilitado          (onServicesDiscovered → m2831x)
 *   2  [0x20] escrito em f001; aguarda challenge 14B  (onDescriptorWrite → writeBLELogin)
 *   3  resposta de auth (19B) escrita; aguarda sessão (phase2 → authenticateStream)
 *   4  notify em f002 (CompositeRawData); streaming    (phase3 → v1check OK)
 * ```
 * A cripto (auth V1/V2 e decrypt do pacote via V2(773)) é delegada à [Libre2Native] (lib da Abbott).
 * Enquanto a ponte nativa não existir ([Libre2Native.available]==false), o handshake falha de forma
 * limpa e logada — sem placeholders inventados.
 */
@Singleton
class Libre2BleManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appLogger: AppLogger
) {
    interface Listener {
        fun onConnectionChanged(connected: Boolean)
        /** Cópia diagnóstica do pacote bruto; nunca deve alterar o fluxo nativo principal. */
        fun onRawPacket(uuid: UUID, value: ByteArray) {}
        /** Glicemia decodificada de um pacote de streaming (processTooth já aplicou o algoritmo Abbott). */
        fun onReading(reading: Libre2Reading, atMs: Long)
        /**
         * Pontos de trend/histórico embutidos no MESMO pacote da leitura atual (21/07/2026) — o
         * sensor guarda glicemias de quando o app esteve desconectado; cada par já vem com o
         * timestamp real calculado (não "agora"). Nunca inclui o ponto atual (já é `onReading`).
         */
        fun onHistoricalReadings(entries: List<Pair<Libre2Reading, Long>>) {}
        fun onRssi(rssi: Int)
        /** GATT pronto — notify em f002 habilitado, streaming iniciado. */
        fun onReady()
        /** A sessão persistida deixou de ser válida durante auth/phase3/CCCD f002. */
        fun onHandshakeFailed() {}
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
        // Serviço ADC customizado do Libre 2 / 2 Plus (Gen2). Juggluco: mADCCustomServiceUUID.
        val SERVICE_UUID: UUID = UUID.fromString("0000fde3-0000-1000-8000-00805f9b34fb")
        // f001 = BLELogincharacteristic (write passcode/resposta de auth; recebe challenge por notify)
        val LOGIN_UUID:   UUID = UUID.fromString("0000f001-0000-1000-8000-00805f9b34fb")
        // f002 = CompositeRawDatacharacteristic (notify — pacotes de streaming cifrados)
        val DATA_UUID:    UUID = UUID.fromString("0000f002-0000-1000-8000-00805f9b34fb")
        val CCCD_UUID:    UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        const val NAME_PREFIX = "ABBOTT"          // device advertise "ABBOTT"+serial (matchDeviceName)
        const val PACKET_LEN = 46                  // 20 + 18 + 8

        private const val LOG = "LIBRE2_BLE"
        private const val MAX_HANDSHAKE_RETRIES = 5
        private const val HANDSHAKE_RETRY_DELAY_MS = 100L

        // Seletores de operação V1/V2 (Juggluco Gen2.java / Libre2GattCallback.java).
        private const val OP_CONTACT_RESET = 37400 // V1: libera o contexto de sessão anterior
        private const val OP_AUTH_P1 = 28960       // V1: 1ª etapa da resposta de auth (authlastv1v2)
        private const val OP_AUTH_P2 = 6505        // V2: 2ª etapa da resposta de auth (→ 19 bytes)
        private const val OP_V1CHECK = 29465       // V1: valida o contexto de sessão (phase3); 0 = OK
        private const val OP_DECRYPT_PACKET = 773  // V2: descriptografa o pacote de 46B com a sessão

        // Juggluco: writeBLELogin envia [32] em f001 para pedir o challenge de segurança.
        private val CHALLENGE_REQUEST = byteArrayOf(0x20)

        /**
         * Decide se um resultado de scan é o sensor Libre 2 / 2 Plus alvo. Cobre três formas de
         * anúncio observadas na prática:
         *  - Libre 2 / 2 Plus clássico: nome `ABBOTT…` / `FSL…` / `Libre…`;
         *  - qualquer Gen2 que exponha o serviço `fde3` no scanRecord (nem todo firmware o faz — mas
         *    quando faz, é um casamento sem falso-positivo, pois `fde3` é específico da Abbott);
         *  - família "7F" (serial 301+, patchInfo `7F …`): NÃO anuncia nome estilo Abbott, usa o
         *    próprio MAC como nome → só dá para reconhecer casando o MAC contra o que a ativação NFC
         *    persistiu ([savedMac]).
         *
         * Função pura (sem dependência de Android além de tipos de dados) para poder ser testada.
         */
        fun matchesLibre2Sensor(
            name: String?,
            mac: String,
            serviceUuids: List<String>,
            savedMac: String?,
        ): Boolean {
            if (name != null && (
                    name.startsWith(NAME_PREFIX, ignoreCase = true) ||
                    name.startsWith("FSL", ignoreCase = true) ||
                    name.startsWith("Libre", ignoreCase = true)
                )
            ) return true
            val fde3 = SERVICE_UUID.toString()
            if (serviceUuids.any { it.equals(fde3, ignoreCase = true) }) return true
            if (!savedMac.isNullOrBlank() && savedMac.equals(mac, ignoreCase = true)) return true
            return false
        }
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
    private var loginChar: BluetoothGattCharacteristic? = null
    private var dataChar:  BluetoothGattCharacteristic? = null
    private var listener: Listener? = null
    @Volatile private var targetMac: String? = null

    // Estado do handshake / sessão (na thread da GATT callback).
    private var conphase = 0
    private var contactint = 0                     // handle de sessão (P1res da auth); usado no v1check
    private var tovalue = 0                         // chave de sessão validada; usada no decrypt (V2 773)
    private val phase3Buf = ByteArray(25)          // contexto de sessão: 7 + 18 bytes
    private var phase3Bytes = 0
    // Remontagem do pacote de streaming: 20 + 18 + 8 = 46 bytes.
    private val packet = ByteArray(PACKET_LEN)
    private var pack1 = false
    private var pack2 = false

    // ── Watchdog de handshake ──────────────────────────────────────────────────
    private val watchdog = Handler(Looper.getMainLooper())
    private var watchdogRunnable: Runnable? = null
    @Volatile private var connectStartMs = 0L

    // Libre2GattCallback do Juggluco repete as operações assíncronas até cinco vezes,
    // com 100 ms entre tentativas. O stack BLE da Samsung pode retornar 133 mesmo
    // depois de writeCharacteristic() ter aceitado a operação localmente.
    private var pendingAuthResponse: ByteArray? = null
    private var authWriteAttempt = 0
    private var authRetryRunnable: Runnable? = null
    private var dataNotifyAttempt = 0
    private var dataNotifyPending = false
    private var dataNotifyRetryRunnable: Runnable? = null

    private fun armWatchdog(stageLabel: String, afterMs: Long) {
        val phaseAtArm = conphase
        watchdogRunnable?.let { watchdog.removeCallbacks(it) }
        val r = Runnable {
            if (gatt != null && conphase == phaseAtArm) {
                appLogger.error(LOG, "TIMEOUT — handshake travou aguardando: $stageLabel",
                    mapOf("faseAtual" to conphase, "prazoMs" to afterMs,
                          "desdeConexaoMs" to (SystemClock.elapsedRealtime() - connectStartMs)))
            }
        }
        watchdogRunnable = r
        watchdog.postDelayed(r, afterMs)
    }

    private fun cancelWatchdog() {
        watchdogRunnable?.let { watchdog.removeCallbacks(it) }
        watchdogRunnable = null
    }

    fun setListener(l: Listener?) { listener = l }

    // ── Scan (descoberta por nome "ABBOTT…"; MAC salvo evita o scan) ────────────
    private var scanCallback: ScanCallback? = null
    private val seenDevices = HashSet<String>()

    /**
     * Scan de descoberta: loga todos os devices próximos e chama [onFound] quando um device casa com
     * [matchesLibre2Sensor]. O casamento primário é por nome (`ABBOTT…`); muitos Libre 2 Plus não
     * colocam o serviço `fde3` no advertising (só no GATT), e a família "7F" nem usa nome estilo
     * Abbott — por isso [savedMac] (o MAC que a ativação NFC persistiu, derivado de
     * [Libre2Native.deviceAddress]) entra como casamento exato de fallback.
     */
    @SuppressLint("MissingPermission")
    fun startScan(savedMac: String? = null, onFound: (name: String?, mac: String, rssi: Int) -> Unit) {
        val scanner = adapter?.bluetoothLeScanner ?: run {
            appLogger.error(LOG, "bluetoothLeScanner indisponível (BT desligado?)")
            return
        }
        stopScan()
        seenDevices.clear()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val mac = result.device.address
                if (!seenDevices.add(mac)) return
                if (result.rssi < -85) return
                if (seenDevices.size > 60) return

                val name = result.device.name ?: result.scanRecord?.deviceName
                val rec  = result.scanRecord
                val svcUuidList = rec?.serviceUuids?.map { it.uuid.toString() } ?: emptyList()
                val svcUuids = svcUuidList.joinToString(",").ifEmpty { "-" }

                appLogger.usage(LOG, "SCAN device",
                    mapOf("name" to (name ?: "null"), "mac" to mac, "rssi" to result.rssi, "svcUuids" to svcUuids))

                if (matchesLibre2Sensor(name, mac, svcUuidList, savedMac)) {
                    appLogger.usage(LOG, "SENSOR Libre 2 Plus identificado",
                        mapOf("name" to (name ?: "null"), "mac" to mac, "rssi" to result.rssi,
                              "via" to when {
                                  name?.startsWith(NAME_PREFIX, true) == true -> "nome"
                                  svcUuidList.any { it.equals(SERVICE_UUID.toString(), true) } -> "fde3"
                                  else -> "mac-salvo"
                              }))
                    onFound(name, mac, result.rssi)
                }
            }
            override fun onScanFailed(errorCode: Int) {
                appLogger.error(LOG, "Scan BLE falhou", mapOf("errorCode" to errorCode))
            }
        }
        scanCallback = cb
        runCatching { scanner.startScan(null, settings, cb) }
            .onSuccess { appLogger.usage(LOG, "Scan de descoberta iniciado (por nome ABBOTT…)") }
            .onFailure { e ->
                appLogger.error(LOG, "Falha ao iniciar scan BLE",
                    mapOf("erro" to (e::class.simpleName ?: "?"), "msg" to (e.message ?: "")))
            }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        scanCallback?.let { adapter?.bluetoothLeScanner?.stopScan(it) }
        scanCallback = null
    }

    // ── Conexão ──────────────────────────────────────────────────────────────
    @SuppressLint("MissingPermission")
    fun connect(mac: String) {
        val a = adapter ?: run { appLogger.error(LOG, "BluetoothAdapter==null (Bluetooth desligado?)"); return }
        if (!a.isEnabled) appLogger.error(LOG, "Bluetooth DESLIGADO — não é possível conectar")
        cancelWatchdog()
        gatt?.let { runCatching { it.close() } }
        resetState()
        targetMac = mac
        val device = runCatching { a.getRemoteDevice(mac) }.getOrNull() ?: run {
            appLogger.error(LOG, "MAC inválido", mapOf("mac" to mac)); return
        }
        // autoConnect=true no Android 13+ (fiel ao Juggluco SuperGattCallback: autoconnect=getAndroid13()).
        // O Libre 2 Plus anuncia de forma intermitente; um connect one-shot (autoConnect=false) expira com
        // status 147 se o sensor não estiver anunciando naquele instante. Com autoConnect o stack conecta
        // em segundo plano quando o sensor aparece — essencial logo após a ativação (warm-up).
        val autoConnect = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        appLogger.usage(LOG, "Conectando",
            mapOf("mac" to mac, "bondState" to device.bondState, "autoConnect" to autoConnect))
        connectStartMs = SystemClock.elapsedRealtime()
        gatt = runCatching {
            device.connectGatt(context, autoConnect, gattCallback, BluetoothDevice.TRANSPORT_LE)
        }.getOrElse { e ->
            appLogger.error(LOG, "connectGatt lançou exceção",
                mapOf("erro" to (e::class.simpleName ?: "?"), "msg" to (e.message ?: "")))
            null
        }
        if (gatt == null) {
            appLogger.error(LOG, "connectGatt retornou null — conexão não iniciada", mapOf("mac" to mac))
        } else {
            armWatchdog("CONNECTED + descoberta de serviços", 20_000L)
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        appLogger.usage(LOG, "Desconectando", mapOf("faseAoDesconectar" to conphase))
        cancelWatchdog()
        gatt?.let { runCatching { it.disconnect(); it.close() } }
        gatt = null
        resetState()
        listener?.onConnectionChanged(false)
    }

    private fun resetState() {
        authRetryRunnable?.let { watchdog.removeCallbacks(it) }
        dataNotifyRetryRunnable?.let { watchdog.removeCallbacks(it) }
        authRetryRunnable = null
        dataNotifyRetryRunnable = null
        pendingAuthResponse = null
        authWriteAttempt = 0
        dataNotifyAttempt = 0
        dataNotifyPending = false
        loginChar = null; dataChar = null
        conphase = 0; contactint = 0; tovalue = 0
        phase3Bytes = 0; pack1 = false; pack2 = false
    }

    // ── Escrita / notificação ──────────────────────────────────────────────────
    @SuppressLint("MissingPermission")
    private fun writeChar(ch: BluetoothGattCharacteristic, data: ByteArray): Boolean {
        val g = gatt ?: run { appLogger.error(LOG, "writeChar: gatt==null", mapOf("char" to ch.uuid)); return false }
        val ok = runCatching {
            // Este é o caminho usado pelo Juggluco: valor na characteristic e
            // writeCharacteristic(characteristic). O overload com ByteArray,
            // embora válido no Android 13+, retornou status 133 neste aparelho.
            @Suppress("DEPRECATION")
            run { ch.value = data; ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT; g.writeCharacteristic(ch) }
        }.getOrElse { e ->
            appLogger.error(LOG, "writeChar lançou exceção",
                mapOf("char" to ch.uuid, "erro" to (e::class.simpleName ?: "?"))); false
        }
        if (!ok) appLogger.error(LOG, "writeChar retornou false",
            mapOf("char" to ch.uuid, "props" to ch.properties, "bytes" to data.size))
        return ok
    }

    @SuppressLint("MissingPermission")
    private fun enableNotify(g: BluetoothGatt, ch: BluetoothGattCharacteristic): Boolean {
        val setOk = runCatching { g.setCharacteristicNotification(ch, true) }.getOrElse { e ->
            appLogger.error(LOG, "setCharacteristicNotification lançou exceção",
                mapOf("char" to ch.uuid, "erro" to (e::class.simpleName ?: "?"))); false
        }
        val cccd = ch.getDescriptor(CCCD_UUID) ?: run {
            appLogger.error(LOG, "CCCD (0x2902) ausente no characteristic", mapOf("char" to ch.uuid))
            return setOk
        }
        val writeOk = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run { cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; g.writeDescriptor(cccd) }
            }
        }.getOrElse { e ->
            appLogger.error(LOG, "writeDescriptor(CCCD) lançou exceção",
                mapOf("char" to ch.uuid, "erro" to (e::class.simpleName ?: "?"))); false
        }
        if (!writeOk) appLogger.error(LOG, "writeDescriptor(CCCD) retornou false", mapOf("char" to ch.uuid))
        return writeOk
    }

    // ── GATT Callback ─────────────────────────────────────────────────────────
    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            val stateName = when (newState) {
                BluetoothProfile.STATE_CONNECTED    -> "CONNECTED"
                BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED"
                else                                -> "STATE($newState)"
            }
            appLogger.usage(LOG, "Estado GATT",
                mapOf("mac" to (targetMac ?: "?"), "state" to stateName, "gattStatus" to status,
                      "desdeConexaoMs" to (SystemClock.elapsedRealtime() - connectStartMs)))
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                if (status != BluetoothGatt.GATT_SUCCESS)
                    appLogger.error(LOG, "CONNECTED com status de erro", mapOf("gattStatus" to status))
                val priorityOk = runCatching {
                    g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                }.getOrElse { e ->
                    appLogger.error(LOG, "requestConnectionPriority lançou exceção",
                        mapOf("erro" to (e::class.simpleName ?: "?")))
                    false
                }
                appLogger.usage(LOG, "Prioridade GATT HIGH solicitada", mapOf("ok" to priorityOk))
                val discoverOk = runCatching { g.discoverServices() }.getOrElse { e ->
                    appLogger.error(LOG, "discoverServices lançou exceção",
                        mapOf("erro" to (e::class.simpleName ?: "?"))); false
                }
                appLogger.usage(LOG, "discoverServices chamado", mapOf("ok" to discoverOk))
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val failedHandshake = conphase in 2..3 || (conphase == 4 && dataNotifyPending)
                appLogger.error(LOG, "DESCONECTADO",
                    mapOf("gattStatus" to status, "faseAoDesconectar" to conphase,
                          "duracaoMs" to (SystemClock.elapsedRealtime() - connectStartMs),
                          "handshakeFalhou" to failedHandshake))
                cancelWatchdog()
                if (failedHandshake) listener?.onHandshakeFailed()
                resetState()
                runCatching { g.close() }
                gatt = null
                listener?.onConnectionChanged(false)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                appLogger.error(LOG, "Descoberta de serviços falhou", mapOf("status" to status)); disconnect(); return
            }
            g.services.forEach { svc ->
                val chars = svc.characteristics.joinToString(", ") { "${it.uuid}(props=${it.properties})" }
                appLogger.usage(LOG, "GATT Service", mapOf("uuid" to svc.uuid, "chars" to chars))
            }
            val service = g.getService(SERVICE_UUID) ?: run {
                appLogger.error(LOG, "Serviço fde3 NÃO encontrado. Serviços: ${g.services.map { it.uuid }}")
                disconnect(); return
            }
            loginChar = service.getCharacteristic(LOGIN_UUID)
            dataChar  = service.getCharacteristic(DATA_UUID)
            if (loginChar == null || dataChar == null) {
                appLogger.error(LOG, "Características f001/f002 ausentes",
                    mapOf("f001" to (loginChar != null), "f002" to (dataChar != null)))
                disconnect(); return
            }
            appLogger.usage(LOG, "Características f001/f002 encontradas",
                mapOf("f001props" to loginChar!!.properties, "f002props" to dataChar!!.properties))
            listener?.onConnectionChanged(true)
            g.readRemoteRssi()

            // m2831x (Gen2): conphase=1, habilita notify em f001 → onDescriptorWrite dispara o [0x20].
            conphase = 1
            val ok = enableNotify(g, loginChar!!)
            appLogger.usage(LOG, "Notify f001 habilitado (aguardando onDescriptorWrite)", mapOf("ok" to ok))
            armWatchdog("confirmação do CCCD f001 → challenge request", 12_000L)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            appLogger.usage(LOG, "CCCD escrito",
                mapOf("char" to descriptor.characteristic.uuid, "status" to status, "fase" to conphase))
            if (descriptor.characteristic.uuid == DATA_UUID && conphase == 4 && dataNotifyPending) {
                dataNotifyPending = false
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    dataNotifyAttempt = 0
                    dataNotifyRetryRunnable = null
                    appLogger.usage(LOG, "BLE pronto — streaming iniciado")
                    listener?.onReady()
                } else {
                    appLogger.error(LOG, "CCCD f002 falhou; repetindo habilitação",
                        mapOf("status" to status, "tentativa" to (dataNotifyAttempt + 1)))
                    scheduleDataNotifyRetry()
                }
                return
            }
            if (status != BluetoothGatt.GATT_SUCCESS) { disconnect(); return }
            when (conphase) {
                1 -> writeBLELogin()                 // notify f001 OK → pede o challenge
            }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            handleCharChanged(ch.uuid, value)
        }

        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) ch.value?.let { handleCharChanged(ch.uuid, it) }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            appLogger.usage(LOG, "onCharacteristicWrite", mapOf("uuid" to ch.uuid, "status" to status, "fase" to conphase))
            if (ch.uuid == LOGIN_UUID && pendingAuthResponse != null) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    pendingAuthResponse = null
                    authWriteAttempt = 0
                    authRetryRunnable?.let { watchdog.removeCallbacks(it) }
                    authRetryRunnable = null
                } else if (conphase == 3) {
                    appLogger.error(LOG, "Resposta de auth rejeitada pelo transporte; repetindo",
                        mapOf("status" to status, "tentativa" to (authWriteAttempt + 1)))
                    scheduleAuthResponseRetry()
                }
            }
        }

        override fun onReadRemoteRssi(g: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) listener?.onRssi(rssi)
        }
    }

    /** Juggluco writeBLELogin: conphase=2, escreve [0x20] em f001 para pedir o challenge de segurança. */
    private fun writeBLELogin() {
        conphase = 2
        val ok = loginChar?.let { writeChar(it, CHALLENGE_REQUEST) } ?: false
        appLogger.usage(LOG, "Challenge request [0x20] enviado", mapOf("ok" to ok))
        if (ok) armWatchdog("challenge de 14 bytes do sensor (notify f001)", 12_000L) else disconnect()
    }

    private fun handleCharChanged(uuid: UUID, value: ByteArray) {
        appLogger.usage(LOG, "onCharacteristicChanged",
            mapOf("uuid" to uuid, "len" to value.size, "hex" to value.hexDump(20), "fase" to conphase))
        runCatching { listener?.onRawPacket(uuid, value.copyOf()) }
            .onFailure { error ->
                appLogger.error(LOG, "Falha isolada no tap experimental bruto",
                    mapOf("uuid" to uuid, "erro" to (error.message ?: error::class.simpleName.orEmpty())))
            }
        when (uuid) {
            LOGIN_UUID -> handleLoginPhase(value)
            DATA_UUID  -> handleDataChunk(value)
        }
    }

    // ── Autenticação (f001): phase2 = challenge→resposta; phase3 = contexto de sessão ──
    @SuppressLint("MissingPermission")
    private fun handleLoginPhase(value: ByteArray) {
        when (conphase) {
            2 -> {   // phase2: challenge de 14 bytes
                if (value.size != 14) {
                    appLogger.error(LOG, "phase2: challenge de tamanho inesperado",
                        mapOf("esperado" to 14, "recebido" to value.size, "hex" to value.hexDump()))
                    disconnect(); return
                }
                val response = authenticateStream(value) ?: run { disconnect(); return }
                pendingAuthResponse = response.copyOf()
                authWriteAttempt = 0
                writeAuthResponse()
            }
            3 -> phase3(value)
            else -> appLogger.usage(LOG, "f001 em fase inesperada", mapOf("fase" to conphase, "hex" to value.hexDump()))
        }
    }

    /**
     * phase3: contexto de sessão em duas partes (7 bytes, depois 18 = 25 total). Na 2ª parte,
     * valida com v1check (0 = OK) e, se válido, habilita o streaming em f002. Fiel ao Juggluco.
     */
    @SuppressLint("MissingPermission")
    private fun phase3(value: ByteArray) {
        when (value.size) {
            7 -> {   // primeira parte
                System.arraycopy(value, 0, phase3Buf, 0, 7); phase3Bytes = 7
                appLogger.usage(LOG, "phase3: parte 1/2 (7B)")
            }
            18 -> {  // segunda parte → valida a sessão
                System.arraycopy(value, 0, phase3Buf, 7, 18); phase3Bytes = 25
                val i = if (v1check(contactint, 1, phase3Buf) != 0) { contactintZero(contactint); 0 } else contactint
                tovalue = i
                if (i <= 0) {
                    appLogger.error(LOG, "phase3: falha ao criar contexto de sessão", mapOf("contactint" to i))
                    disconnect(); return
                }
                appLogger.usage(LOG, "Sessão Gen2 estabelecida", mapOf("sessao" to i))
                val g = gatt ?: return
                val ch = dataChar ?: return
                conphase = 4
                dataNotifyAttempt = 0
                requestDataNotify(g, ch)
            }
            else -> appLogger.error(LOG, "phase3: parte de tamanho inesperado", mapOf("len" to value.size))
        }
    }

    /**
     * Equivalente ao Runnable de phase2 do Juggluco. Mantém a resposta pendente
     * até o callback confirmar a escrita, permitindo recuperar o status 133.
     */
    @SuppressLint("MissingPermission")
    private fun writeAuthResponse() {
        val response = pendingAuthResponse ?: return
        val ch = loginChar ?: run { disconnect(); return }
        val attempt = authWriteAttempt
        val ok = writeChar(ch, response)
        appLogger.usage(LOG, "phase2: resposta de auth (19B) enviada",
            mapOf("ok" to ok, "tentativa" to (attempt + 1), "hex" to response.hexDump()))
        if (ok) {
            conphase = 3
            armWatchdog("contexto de sessão do sensor (phase3)", 12_000L)
        } else {
            scheduleAuthResponseRetry()
        }
    }

    private fun scheduleAuthResponseRetry() {
        authRetryRunnable?.let { watchdog.removeCallbacks(it) }
        if (authWriteAttempt + 1 >= MAX_HANDSHAKE_RETRIES) {
            appLogger.error(LOG, "Resposta de auth falhou após tentativas",
                mapOf("tentativas" to MAX_HANDSHAKE_RETRIES))
            disconnect()
            return
        }
        authWriteAttempt++
        val r = Runnable {
            authRetryRunnable = null
            if (gatt != null && conphase == 3 && pendingAuthResponse != null) writeAuthResponse()
        }
        authRetryRunnable = r
        watchdog.postDelayed(r, HANDSHAKE_RETRY_DELAY_MS)
    }

    /** Equivalente ao Runnable de asknotification(f002) do Juggluco. */
    @SuppressLint("MissingPermission")
    private fun requestDataNotify(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
        dataNotifyPending = true
        val attempt = dataNotifyAttempt
        val ok = enableNotify(g, ch)
        appLogger.usage(LOG, "Notify f002 habilitado (aguardando confirmação)",
            mapOf("ok" to ok, "tentativa" to (attempt + 1)))
        if (!ok) {
            dataNotifyPending = false
            scheduleDataNotifyRetry()
        } else {
            armWatchdog("confirmação do CCCD f002 / primeiro pacote de glicemia", 90_000L)
        }
    }

    private fun scheduleDataNotifyRetry() {
        dataNotifyRetryRunnable?.let { watchdog.removeCallbacks(it) }
        if (dataNotifyAttempt + 1 >= MAX_HANDSHAKE_RETRIES) {
            appLogger.error(LOG, "CCCD f002 falhou após tentativas",
                mapOf("tentativas" to MAX_HANDSHAKE_RETRIES))
            disconnect()
            return
        }
        dataNotifyAttempt++
        val r = Runnable {
            dataNotifyRetryRunnable = null
            val g = gatt
            val ch = dataChar
            if (g != null && ch != null && conphase == 4) requestDataNotify(g, ch)
        }
        dataNotifyRetryRunnable = r
        watchdog.postDelayed(r, HANDSHAKE_RETRY_DELAY_MS)
    }

    /**
     * Remonta o pacote de streaming (20+18+8=46), descriptografa com a chave de sessão (V2 773) e
     * processa via processTooth. Fiel ao Juggluco `oldonCharacteristicChanged`.
     */
    private fun handleDataChunk(value: ByteArray) {
        cancelWatchdog()
        when (value.size) {
            20 -> { System.arraycopy(value, 0, packet, 0, 20); pack1 = true; pack2 = false }
            18 -> if (pack1) { System.arraycopy(value, 0, packet, 20, 18); pack2 = true }
            8  -> if (pack1 && pack2) {
                pack1 = false; pack2 = false
                System.arraycopy(value, 0, packet, 38, 8)
                val atMs = System.currentTimeMillis()
                val packetCopy = packet.copyOf()
                // Descriptografa o pacote de 46B com a chave de sessão via V2(773) — lib da Abbott.
                val decrypted = Libre2Native.v2(OP_DECRYPT_PACKET, tovalue, packetCopy, null)
                if (decrypted == null) {
                    appLogger.error(LOG, "Decrypt do pacote retornou null — sessão inválida?"); return
                }
                val res = Libre2Native.processTooth(decrypted)
                notifyHistoricalReadings(atMs)  // trend embutido no pacote — independente do resultado da leitura atual
                if (res == 1L) {
                    appLogger.usage(LOG, "processTooth: pacote duplicado")
                    return
                }
                val reading = Libre2Reading.decode(res) ?: run {
                    appLogger.error(LOG, "processTooth: glicemia inválida (res=0)")
                    return
                }
                appLogger.usage(LOG, "Glicemia",
                    mapOf("mgdl" to reading.mgdl, "rate" to "%.2f".format(reading.rate), "alarm" to reading.alarm))
                listener?.onReading(reading, atMs)
            }
            else -> appLogger.usage(LOG, "Chunk de tamanho inesperado", mapOf("len" to value.size))
        }
    }

    /**
     * Trend/histórico embutido no pacote que acabou de ser processado (21/07/2026) — glicemias
     * de quando o app esteve desconectado, com o timestamp real já calculado nativamente (ver
     * `libre2_stream.cpp::packHistory`, mesma fórmula do Juggluco `addStreamHistory`). Chamado
     * SEMPRE após `processTooth` (consome o buffer nativo mesmo se a leitura atual for descartada).
     */
    private fun notifyHistoricalReadings(nowMs: Long) {
        val raw = Libre2Native.consumeHistory()
        if (raw == null || raw.isEmpty()) return
        val entries = ArrayList<Pair<Libre2Reading, Long>>(raw.size)
        for (packed: Long in raw) {
            val epochSec: Long = packed ushr 32
            val mgLx10: Int = (packed and 0xFFFFFFFFL).toInt()
            val mgdl: Int = ((mgLx10 / 10.0f) + 0.5f).toInt()
            if (mgdl <= 0) continue
            entries.add(Libre2Reading(mgdl = mgdl, rate = 0f, alarm = 0) to epochSec * 1000L)
        }
        if (entries.isEmpty()) return
        appLogger.usage(LOG, "Histórico do sensor", mapOf("pontos" to entries.size, "nowMs" to nowMs))
        listener?.onHistoricalReadings(entries)
    }

    // ── Cripto de auth (Kotlin, fiel a Libre2GattCallback.authenticateStream + Gen2.authlastv1v2/v1check) ──

    /** Deriva a resposta de 19 bytes ao challenge, usando ident+authData da sessão e V1/V2. */
    private fun authenticateStream(challenge: ByteArray): ByteArray? {
        if (contactint > 0) { Libre2Native.v1(OP_CONTACT_RESET, contactint, null, null); contactint = 0 }
        val ident = Libre2Native.sensorIdent() ?: run { appLogger.error(LOG, "authenticateStream: sensorIdent==null (sessão NFC ausente / nativo pendente)"); return null }
        val auth = Libre2Native.streamingAuthData() ?: run { appLogger.error(LOG, "authenticateStream: streamingAuthData==null"); return null }
        val numb = when {
            auth.size == 12 -> ((auth[11].toInt() shl 8) or auth[10].toInt()) and 0xFFFF
            auth.size < 10  -> { appLogger.error(LOG, "authenticateStream: authData curto (${auth.size})"); return null }
            else            -> -1
        }
        val bArr2 = ByteArray(challenge.size + 10)
        System.arraycopy(auth, 0, bArr2, 0, 10)
        System.arraycopy(challenge, 0, bArr2, 10, challenge.size)
        val out19 = ByteArray(19)
        contactint = authlastv1v2(31, ident, bArr2, out19, numb)
        return if (contactint > 0) out19 else { appLogger.error(LOG, "authenticateStream: contactint=$contactint"); null }
    }

    /** Gen2.authlastv1v2: V1(28960) → V2(6505) produzindo os 19 bytes em [out19]. Retorna o handle P1. */
    private fun authlastv1v2(i: Int, ident: ByteArray, bArr2: ByteArray, out19: ByteArray, numb: Int): Int {
        val p1 = Libre2Native.v1(OP_AUTH_P1, numb, ident, null)
        if (p1 < 0) return p1
        val opArr = byteArrayOf(1, i.toByte())
        val p2 = Libre2Native.v2(OP_AUTH_P2, p1, opArr, bArr2)
        if (p2 == null) {
            Libre2Native.v1(OP_CONTACT_RESET, p1, null, null); return -99
        }
        System.arraycopy(p2, 0, out19, 0, minOf(p2.size, 19))
        return p1
    }

    /** Gen2.v1check: V1(29465). Retorna 0 quando o contexto de sessão é válido. */
    private fun v1check(i: Int, i2: Int, bArr: ByteArray): Int {
        val i2Arr = byteArrayOf(i2.toByte())
        val result = Libre2Native.v1(OP_V1CHECK, i, i2Arr, bArr)
        return result
    }

    private fun contactintZero(i: Int) { Libre2Native.v1(OP_CONTACT_RESET, i, null, null); contactint = 0 }
}

private fun ByteArray.hexDump(maxBytes: Int = size): String =
    take(maxBytes).joinToString(" ") { "%02X".format(it) } +
    if (size > maxBytes) " …+${size - maxBytes}" else ""
