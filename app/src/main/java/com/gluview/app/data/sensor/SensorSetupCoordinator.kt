package com.gluview.app.data.sensor

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.gluview.app.data.accuchek.AccuChekBleManager
import com.gluview.app.data.accuchek.cgm.SmartGuidePackageParser
import com.gluview.app.data.datastore.AppPreferences
import com.gluview.app.data.medlevensohn.MedLevensohnBleManager
import com.gluview.app.data.medlevensohn.MedLevensohnGatt
import com.gluview.app.data.repository.SensorHistoryRepository
import com.gluview.app.data.sibionics.SibionicsQrParser
import com.gluview.app.data.sibionics.SibionicsStatusHolder
import com.gluview.app.data.source.DataSourceType
import com.gluview.app.service.GluSyncService
import com.gluview.app.service.ServiceWatchdogWorker
import com.gluview.app.util.AppLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import kotlin.coroutines.resume
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Estados do fluxo de configuração/troca de sensor.
 *
 * Um único estado por vez, em vez das flags independentes que a tela antiga carregava
 * (`scanning`/`saved`/`forgotten`/`reconnected`/`calibrated`), onde a maioria das combinações não
 * significava nada e nada impedia dois fluxos simultâneos.
 */
sealed interface SetupState {
    /** Nada em andamento. */
    data object Idle : SetupState

    /** Escolhendo o tipo de sensor (o atual aparece primeiro). */
    data object ChoosingSensor : SetupState

    /** Explicando o que o usuário precisa ter em mãos. [notice] = por que voltou para cá (L19). */
    data class Instructions(val type: DataSourceType, val notice: ScanNotice? = null) : SetupState

    /** Câmera aberta, esperando o código da embalagem. */
    data class ReadingPackage(val type: DataSourceType) : SetupState

    /**
     * Digitando o número de série (embalagem ilegível ou câmera sem foco) — só para sensores que se
     * identificam pelo serial (Accu-Chek SmartGuide). [invalid] = a última tentativa não valeu.
     */
    data class EnteringSerial(val type: DataSourceType, val invalid: Boolean = false) : SetupState

    /**
     * Digitando o PIN de pareamento (SmartGuide: 6 dígitos da tampa do aplicador), com o aviso de
     * que o sensor só aceita um celular por vez. [expiry] = validade lida da embalagem.
     */
    data class EnteringPin(
        val type: DataSourceType,
        val code: SensorCode,
        val expiry: LocalDate? = null,
        val invalid: Boolean = false
    ) : SetupState

    /** Procurando no ar o sensor que corresponde à embalagem. */
    data class Searching(val type: DataSourceType, val code: SensorCode) : SetupState

    /** Sensor encontrado e configurado; esperando o pareamento com o PIN terminar. */
    data class Pairing(val type: DataSourceType, val code: SensorCode) : SetupState

    /** Sensor encontrado; conectando e salvando a configuração. */
    data class Connecting(val type: DataSourceType, val code: SensorCode) : SetupState

    /** Pronto — serviço reiniciado com o sensor novo. */
    data class Ready(val type: DataSourceType, val warmupMinutes: Int) : SetupState

    /** Falhas, cada uma com sua saída. [code] = a caixa já lida (tentar de novo não pede outra leitura, L19). */
    data class Failed(val reason: FailureReason, val type: DataSourceType?, val code: SensorCode? = null) : SetupState
}

/** Por que a leitura da caixa não aconteceu e o assistente voltou ao passo da caixa (L19). */
enum class ScanNotice { PERMISSION_DENIED, SCAN_CANCELLED, CAMERA_FAILED }

/** Motivos de falha — a UI traduz cada um em linguagem de gente. */
enum class FailureReason {
    /** O código lido não é de um sensor compatível. */
    INVALID_CODE,

    /** A janela de busca fechou sem achar o sensor da embalagem. */
    SENSOR_NOT_FOUND,

    /** Bluetooth desligado. */
    BLUETOOTH_UNAVAILABLE,

    /** O pareamento foi recusado (PIN errado, cancelado ou sensor preso a outro celular). */
    PAIRING_FAILED,

    /** O pareamento não terminou a tempo (janela do sistema sem resposta, sensor longe). */
    PAIRING_TIMEOUT,

    /** Sem a permissão de procurar dispositivos por perto (L19: antes caía em "Bluetooth desligado"). */
    BLUETOOTH_PERMISSION,

    /** Qualquer outra falha inesperada. */
    UNKNOWN
}

/**
 * Conduz a configuração de um sensor do começo ao fim, sem pedir decisões técnicas ao usuário.
 *
 * O fluxo é sempre o mesmo, para primeiro sensor e para troca:
 * escolher o tipo → escanear a embalagem → **encontrar o sensor automaticamente** → conectar.
 *
 * A etapa que antes era manual (escolher um endereço numa lista) virou consequência do código
 * escaneado: a embalagem é a identidade do sensor, e só o rádio que corresponde a ela é adotado.
 */
@Singleton
class SensorSetupCoordinator @Inject constructor(
    private val preferences: AppPreferences,
    private val discovery: SensorDiscovery,
    private val sensorHistory: SensorHistoryRepository,
    private val accuChekBle: AccuChekBleManager,
    private val medLevBle: MedLevensohnBleManager,
    private val statusHolder: SibionicsStatusHolder,
    private val appLogger: AppLogger,
    @ApplicationContext private val context: Context
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<SetupState>(SetupState.Idle)
    val state: StateFlow<SetupState> = _state.asStateFlow()

    /** Job da etapa em andamento — garante uma sessão por vez (clique duplo não duplica nada). */
    private var runningJob: Job? = null

    /** PIN digitado para o SmartGuide — guardado para "tentar de novo" sem digitar outra vez. */
    @Volatile private var pendingPin: String? = null

    // ── Transições dirigidas pela UI ───────────────────────────────────────────

    fun startSensorChange() {
        if (_state.value !is SetupState.Idle) return   // já em andamento — ignora repetição
        appLogger.usage(LOG, "Fluxo de troca de sensor iniciado")
        _state.value = SetupState.ChoosingSensor
    }

    fun chooseSensor(type: DataSourceType) {
        _state.value = SetupState.Instructions(type)
    }

    fun readyToScan() {
        val type = currentType() ?: return
        _state.value = SetupState.ReadingPackage(type)
    }

    /**
     * A câmera não chegou a ler: permissão negada, leitura cancelada ou o leitor não abriu. Volta
     * ao passo da caixa dizendo o porquê (L19: antes ficava preso em "Lendo o código", sem câmera).
     */
    fun scanAborted(notice: ScanNotice) {
        val s = _state.value as? SetupState.ReadingPackage ?: return
        appLogger.usage(LOG, "Leitura da caixa não aconteceu", mapOf("motivo" to notice.name))
        _state.value = SetupState.Instructions(s.type, notice)
    }

    /** Cancela o que estiver em andamento e volta ao repouso. */
    fun cancel() {
        runningJob?.cancel()
        runningJob = null
        pendingPin = null
        _state.value = SetupState.Idle
    }

    /** SmartGuide: digitar o número de série em vez de ler a embalagem. */
    fun enterSerialManually() {
        val type = currentType() ?: return
        _state.value = SetupState.EnteringSerial(type)
    }

    fun onSerialEntered(text: String) {
        val type = currentType() ?: return
        if (type == DataSourceType.MEDLEVENSOHN_SMART2) {
            val serial = text.trim().uppercase().filter { it.isLetterOrDigit() }
            if (serial.length !in 6..MedLevensohnGatt.SERIAL_LENGTH) {
                _state.value = SetupState.EnteringSerial(type, invalid = true)
                return
            }
            search(type, SensorCode(connectionCode = serial, serial = serial))
            return
        }
        val serial = SmartGuidePackageParser.normalizeSerial(text)
        if (serial == null) {
            _state.value = SetupState.EnteringSerial(type, invalid = true)
            return
        }
        appLogger.usage(LOG, "Serial digitado à mão")
        _state.value = SetupState.EnteringPin(type, SensorCode(connectionCode = serial, serial = serial))
    }

    /**
     * Smart 2.0: não há código de embalagem conhecido — procura o sensor da família mais perto
     * (`AiDEX X-`/`LinX-`/`Lumi-`/`Smart-`).
     */
    fun searchNearby() {
        val type = currentType() ?: return
        search(type, SensorCode(connectionCode = ANY_SERIAL, serial = ""))
    }

    /** PIN de 6 dígitos digitado: guarda e procura o sensor. */
    fun onPinEntered(pin: String) {
        val s = _state.value as? SetupState.EnteringPin ?: return
        val clean = pin.trim()
        if (clean.length != SMARTGUIDE_PIN_LENGTH || !clean.all(Char::isDigit)) {
            _state.value = s.copy(invalid = true)
            return
        }
        pendingPin = clean
        search(s.type, s.code)
    }

    /**
     * Tentar de novo. Com a caixa já lida (falha de Bluetooth ou sensor não encontrado), repete só
     * a busca (L19: antes obrigava a escanear a caixa outra vez). Devolve true quando a tela precisa
     * abrir a câmera.
     */
    fun retry(): Boolean {
        val s = _state.value as? SetupState.Failed ?: return false
        val type = s.type
        val code = s.code
        return when {
            type == null -> { _state.value = SetupState.ChoosingSensor; false }
            code != null && type == DataSourceType.ACCU_CHEK_SMARTGUIDE && pendingPin == null ->
                { _state.value = SetupState.EnteringPin(type, code); false }
            code != null -> { search(type, code); false }
            else -> { _state.value = SetupState.ReadingPackage(type); true }
        }
    }

    /**
     * Recebe o conteúdo bruto do código lido na embalagem e conduz o resto sozinho:
     * valida → procura o sensor correspondente → salva → reinicia o serviço.
     */
    fun onPackageScanned(rawValue: String) {
        val type = currentType() ?: return
        if (runningJob?.isActive == true) return

        runningJob = scope.launch {
            if (type == DataSourceType.ACCU_CHEK_SMARTGUIDE) {
                val pkg = SmartGuidePackageParser.parse(rawValue)
                if (pkg == null) {
                    appLogger.error(LOG, "Código da embalagem não é de um SmartGuide")
                    _state.value = SetupState.Failed(FailureReason.INVALID_CODE, type)
                    return@launch
                }
                appLogger.usage(LOG, "Embalagem do SmartGuide lida", mapOf("validade" to (pkg.expiryDate?.toString() ?: "?")))
                _state.value = SetupState.EnteringPin(type, pkg.toSensorCode(), pkg.expiryDate)
                return@launch
            }
            val parsed = SibionicsQrParser.parse(rawValue)
            val code = parsed?.let {
                SensorCode(
                    connectionCode = it.customerId.ifBlank { SibionicsQrParser.connectionCodeFrom(it.serial) },
                    serial         = it.serial
                )
            }
            if (code == null || !code.isValid) {
                appLogger.error(LOG, "Código da embalagem não reconhecido")
                _state.value = SetupState.Failed(FailureReason.INVALID_CODE, type)
                return@launch
            }

            appLogger.usage(LOG, "Embalagem lida — identidade do sensor derivada",
                mapOf("temSerial" to code.serial.isNotBlank()))
            runSearch(type, code)
        }
    }

    /** Repete a busca com uma caixa já lida. */
    private fun search(type: DataSourceType, code: SensorCode) {
        if (runningJob?.isActive == true) return
        runningJob = scope.launch { runSearch(type, code) }
    }

    private suspend fun runSearch(type: DataSourceType, code: SensorCode) {
        // Sem rádio ligado não há o que procurar. Checar aqui (e não deixar a busca falhar por
        // timeout) é o que diferencia "ligue o Bluetooth" de "não encontramos seu sensor" —
        // duas telas com ações completamente diferentes para quem está com a caixa na mão.
        bluetoothProblem()?.let { reason ->
            appLogger.error(LOG, "Busca abortada — Bluetooth indisponível", mapOf("motivo" to reason.name))
            _state.value = SetupState.Failed(reason, type, code)
            return
        }

        if (type == DataSourceType.ACCU_CHEK_SMARTGUIDE) {
            runSmartGuideSearch(type, code)
            return
        }
        if (type == DataSourceType.MEDLEVENSOHN_SMART2) {
            runMedLevSearch(type, code)
            return
        }

        _state.value = SetupState.Searching(type, code)

        val found = runCatching { discovery.findSensor(code, SibionicsIdentityMatcher()) }
            .onFailure { e ->
                appLogger.error(LOG, "Busca do sensor falhou",
                    mapOf("erro" to (e.message ?: e::class.simpleName.orEmpty())))
            }
            .getOrNull()

        if (found == null) {
            _state.value = SetupState.Failed(FailureReason.SENSOR_NOT_FOUND, type, code)
            return
        }

        _state.value = SetupState.Connecting(type, code)
        applySensor(type, code, found)
        _state.value = SetupState.Ready(type, WARMUP_MINUTES)
    }

    // ── Accu-Chek SmartGuide ───────────────────────────────────────────────────

    /**
     * SmartGuide: acha o sensor (já pareado neste celular — pelo GluView ou pelo app oficial — ou
     * anunciando `AC-<serial>`), grava serial + PIN, reinicia o serviço e espera o pareamento.
     * O pareamento em si acontece no serviço ([AccuChekBleManager]), que responde ao Android com o PIN.
     */
    private suspend fun runSmartGuideSearch(type: DataSourceType, code: SensorCode) {
        val pin = pendingPin ?: run { _state.value = SetupState.EnteringPin(type, code); return }
        _state.value = SetupState.Searching(type, code)

        val bonded = accuChekBle.findBondedSensor(code.serial)
        val address = bonded?.address ?: scanSmartGuide(code)
        if (address == null) {
            _state.value = SetupState.Failed(FailureReason.SENSOR_NOT_FOUND, type, code)
            return
        }
        appLogger.usage(LOG, "SmartGuide encontrado", mapOf("jaPareado" to (bonded != null)))

        _state.value = SetupState.Connecting(type, code)
        val sameSensor = preferences.accuChekSerial.first() == code.serial
        if (sameSensor) preferences.saveAccuChekPin(pin) else preferences.setAccuChekSensor(code.serial, pin)
        preferences.saveAccuChekMac(address)
        if (preferences.dataSource.first() != type.id) preferences.saveDataSource(type.id)

        _state.value = SetupState.Pairing(type, code)
        // Assina os eventos ANTES de reiniciar o serviço, para não perder um pareamento rápido.
        when (val outcome = awaitPairing { restartService() }) {
            AccuChekBleManager.LinkEvent.Ready -> {
                delay(READY_SETTLE_MS) // a fonte grava os minutos do sensor logo depois do "pronto"
                val minute = statusHolder.status.value.lastIndex
                val warmupLeft = if (minute < 0) WARMUP_MINUTES else (WARMUP_MINUTES - minute).coerceIn(0, WARMUP_MINUTES)
                appLogger.usage(LOG, "SmartGuide pareado e lendo", mapOf("minutoDoSensor" to minute))
                _state.value = SetupState.Ready(type, warmupLeft)
            }
            null -> {
                appLogger.error(LOG, "Pareamento do SmartGuide não terminou a tempo")
                _state.value = SetupState.Failed(FailureReason.PAIRING_TIMEOUT, type, code)
            }
            else -> {
                appLogger.error(LOG, "Pareamento do SmartGuide falhou", mapOf("evento" to outcome.toString()))
                pendingPin = null // provável PIN errado: pede de novo no "tentar de novo"
                _state.value = SetupState.Failed(FailureReason.PAIRING_FAILED, type, code)
            }
        }
    }

    /** Roda [start] e espera o primeiro evento decisivo do pareamento (pronto ou falha), ou null no prazo. */
    private suspend fun awaitPairing(start: suspend () -> Unit): AccuChekBleManager.LinkEvent? = coroutineScope {
        val waiter = async {
            withTimeoutOrNull(PAIRING_WINDOW_MS) {
                accuChekBle.events.first { e ->
                    e == AccuChekBleManager.LinkEvent.Ready ||
                        (e is AccuChekBleManager.LinkEvent.Pairing &&
                            (e.state == AccuChekBleManager.PairingState.FAILED || e.state == AccuChekBleManager.PairingState.LOST))
                }
            }
        }
        start()
        waiter.await()
    }

    /** Busca `AC-<serial>` pela janela do gerenciador (30 s); null se não achar. */
    private suspend fun scanSmartGuide(code: SensorCode): String? = suspendCancellableCoroutine { cont ->
        accuChekBle.startScan(code.serial, onTimeout = { if (cont.isActive) cont.resume(null) }) { _, address, _ ->
            accuChekBle.stopScan()
            if (cont.isActive) cont.resume(address)
        }
        cont.invokeOnCancellation { accuChekBle.stopScan() }
    }

    // ── Smart 2.0 (MedLevensohn / AiDEX X) ─────────────────────────────────────

    /**
     * Procura o sensor (com [code].serial, só o que termina nele), guarda serial + endereço, reinicia o
     * serviço e espera a sessão abrir. O pareamento do Android, quando houver, é feito pelo próprio
     * sensor (o Juggluco não chama createBond).
     */
    private suspend fun runMedLevSearch(type: DataSourceType, code: SensorCode) {
        _state.value = SetupState.Searching(type, code)
        val wanted = code.serial.ifBlank { null }
        val found = scanMedLev(wanted)
        if (found == null) {
            _state.value = SetupState.Failed(FailureReason.SENSOR_NOT_FOUND, type, code)
            return
        }
        val (name, address) = found
        val serial = MedLevensohnGatt.extractSerial(name)
        val foundCode = SensorCode(connectionCode = serial, serial = serial)
        appLogger.usage(LOG, "Smart 2.0 encontrado", mapOf("nome" to name))
        _state.value = SetupState.Connecting(type, foundCode)
        preferences.saveMedLevensohnSensor(serial, address)
        if (preferences.dataSource.first() != type.id) preferences.saveDataSource(type.id)
        _state.value = SetupState.Pairing(type, foundCode)
        restartService()
        val opened = withTimeoutOrNull(PAIRING_WINDOW_MS) {
            while (!medLevBle.isConnected) delay(500)
            true
        } == true
        if (opened) {
            appLogger.usage(LOG, "Smart 2.0 conectado e com sessão aberta")
            _state.value = SetupState.Ready(type, 0)
        } else {
            appLogger.error(LOG, "Smart 2.0 não abriu a sessão a tempo")
            _state.value = SetupState.Failed(FailureReason.PAIRING_TIMEOUT, type, foundCode)
        }
    }

    /**
     * Busca pelo gerenciador do Smart 2.0 (30 s). Depois do primeiro achado espera um pouco para
     * comparar a força do sinal e fica com o mais forte (o que está no corpo).
     */
    private suspend fun scanMedLev(serial: String?): Pair<String, String>? {
        val candidates = LinkedHashMap<String, Triple<String, String, Int>>()
        return try {
            withTimeoutOrNull(MEDLEV_SCAN_WINDOW_MS) {
                medLevBle.startScan(serial) { name, mac, rssi -> synchronized(candidates) { candidates[mac] = Triple(name, mac, rssi) } }
                while (synchronized(candidates) { candidates.isEmpty() }) delay(250)
                delay(MEDLEV_SETTLE_MS)
                synchronized(candidates) { candidates.values.maxByOrNull { it.third } }?.let { it.first to it.second }
            }
        } finally {
            medLevBle.stopScan()
        }
    }

    // ── Efeitos ────────────────────────────────────────────────────────────────

    /**
     * Grava a configuração do sensor encontrado e reinicia o serviço para adotá-lo.
     *
     * Espelha o que a tela antiga fazia ao salvar, com uma diferença: o endereço não vem de uma
     * escolha do usuário, vem da identidade da embalagem — e o nome anunciado é memorizado junto,
     * para que reconexões futuras possam confirmar que o rádio continua sendo o sensor certo.
     */
    private suspend fun applySensor(type: DataSourceType, code: SensorCode, found: SensorDiscovery.Found) {
        val oldSerial = preferences.sibionicsSerial.first()
        val oldCid    = preferences.sibionicsCustomerId.first()
        val oldCode   = oldCid.ifBlank { SibionicsQrParser.connectionCodeFrom(oldSerial) }
        val isNewSensor = code.connectionCode.isNotBlank() && code.connectionCode != oldCode

        preferences.saveSibionicsSensor(
            mac        = found.mac,
            serial     = code.serial,
            customerId = code.connectionCode
        )
        preferences.saveSibionicsBleName(found.name)

        if (isNewSensor) {
            // Sensor físico diferente: o estado do algoritmo e a âncora de idade são do sensor
            // ANTERIOR e não podem ser herdados — reaproveitá-los data as leituras novas com a
            // idade do sensor velho e polui a calibração.
            preferences.clearAlgoContext()
            preferences.clearSibionicsSensorStart()
            preferences.saveSibionicsCalFactor(1.0)
            sensorHistory.startNew(found.mac, code.serial, code.connectionCode, type.id)
            appLogger.usage(LOG, "Sensor novo configurado",
                mapOf("mac" to found.mac, "nome" to found.name, "fonte" to type.id))
        } else {
            appLogger.usage(LOG, "Mesmo sensor reconfigurado — estado preservado",
                mapOf("mac" to found.mac, "nome" to found.name))
        }

        if (preferences.dataSource.first() != type.id) {
            preferences.saveDataSource(type.id)
        }

        restartService()
    }

    private suspend fun restartService() {
        runCatching {
            ServiceWatchdogWorker.cancel(context)
            context.stopService(Intent(context, GluSyncService::class.java))
            delay(SERVICE_RESTART_DELAY_MS)
            context.startForegroundService(Intent(context, GluSyncService::class.java))
            ServiceWatchdogWorker.schedule(context)
        }.onFailure { e ->
            appLogger.error(LOG, "Falha ao reiniciar o serviço após configurar o sensor",
                mapOf("erro" to (e.message ?: e::class.simpleName.orEmpty())))
        }
    }

    /**
     * O que impede a busca, separado (L19): permissão de Bluetooth negada (a partir do Android 12 são
     * permissões próprias, antes disso o scan dependia de localização) ou adaptador desligado.
     */
    private fun bluetoothProblem(): FailureReason? {
        val needed = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            arrayOf(android.Manifest.permission.BLUETOOTH_SCAN, android.Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (!needed.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
            return FailureReason.BLUETOOTH_PERMISSION
        }
        val adapter: BluetoothAdapter? =
            (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (adapter == null || !adapter.isEnabled) return FailureReason.BLUETOOTH_UNAVAILABLE
        return null
    }

    private fun currentType(): DataSourceType? = when (val s = _state.value) {
        is SetupState.Instructions  -> s.type
        is SetupState.ReadingPackage -> s.type
        is SetupState.EnteringSerial -> s.type
        is SetupState.EnteringPin   -> s.type
        is SetupState.Searching     -> s.type
        is SetupState.Connecting    -> s.type
        is SetupState.Pairing       -> s.type
        is SetupState.Failed        -> s.type
        else                        -> null
    }

    private companion object {
        const val LOG = "SENSOR_SETUP"
        const val SERVICE_RESTART_DELAY_MS = 800L
        /** Aquecimento do Sibionics e do SmartGuide, exibido na tela final (ver SibionicsSensorStatus.WARMUP_MINUTES). */
        const val WARMUP_MINUTES = 60
        const val SMARTGUIDE_PIN_LENGTH = 6
        /** Prazo do pareamento: reiniciar o serviço, conectar e o usuário responder à janela do sistema. */
        const val PAIRING_WINDOW_MS = 150_000L
        const val READY_SETTLE_MS = 800L
        const val MEDLEV_SCAN_WINDOW_MS = 30_000L
        const val MEDLEV_SETTLE_MS = 1_500L
        /** Código de "qualquer sensor da família" (busca sem serial). */
        const val ANY_SERIAL = "****"
    }
}
