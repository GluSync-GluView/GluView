package com.gluview.app.ui.screen.sensorscan

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gluview.app.R
import com.gluview.app.data.datastore.AppPreferences
import com.gluview.app.data.local.entity.SensorHistoryEntity
import com.gluview.app.data.repository.SensorHistoryRepository
import com.gluview.app.data.sibionics.SibionicsBleManager
import com.gluview.app.data.sibionics.SibionicsQrParser
import com.gluview.app.data.sibionics.SibionicsStatusHolder
import com.gluview.app.data.source.DataSourceType
import com.gluview.app.service.GluSyncService
import com.gluview.app.service.ServiceWatchdogWorker
import com.gluview.app.util.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class FoundDevice(val name: String?, val mac: String, val rssi: Int)

data class SensorScanUiState(
    val scanning: Boolean          = false,
    val devices: List<FoundDevice> = emptyList(),
    val serial: String             = "",
    val customerId: String         = "",
    val savedMac: String           = "",
    val savedSerial: String        = "",
    val savedCustomerId: String    = "",
    val saved: Boolean             = false,
    val forgotten: Boolean         = false,
    val reconnected: Boolean       = false,
    val scanError: UiText?         = null,
    // Calibração
    val lastRawMmol: Double        = 0.0,   // glicemia bruta do algoritmo (sem calibração)
    val calFactor: Double          = 0.0,   // fator atual (0 = não calibrado)
    val calibrated: Boolean        = false, // pulso de UI após calibrar
    // Histórico local (3 meses) — mesmo modelo do histórico de glicemia
    val history: List<SensorHistoryEntity> = emptyList(),
    val showHelp: Boolean          = false
)

@HiltViewModel
class SensorScanViewModel @Inject constructor(
    private val bleManager: SibionicsBleManager,
    private val preferences: AppPreferences,
    private val statusHolder: SibionicsStatusHolder,
    private val sensorHistory: SensorHistoryRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _state = MutableStateFlow(SensorScanUiState())
    val uiState: StateFlow<SensorScanUiState> = _state.asStateFlow()

    init {
        // Pre-popula campos com valores salvos
        viewModelScope.launch {
            val savedS  = preferences.sibionicsSerial.first()
            val savedCid = preferences.sibionicsCustomerId.first()
            _state.update { it.copy(serial = savedS, customerId = savedCid) }
        }
        // Observa MAC/serial/customerId salvos para exibir o "sensor configurado" atual
        viewModelScope.launch {
            combine(
                preferences.sibionicsMac,
                preferences.sibionicsSerial,
                preferences.sibionicsCustomerId
            ) { mac, serial, cid -> Triple(mac, serial, cid) }
            .collect { (mac, serial, cid) ->
                _state.update { it.copy(savedMac = mac, savedSerial = serial, savedCustomerId = cid) }
            }
        }
        // Observa a glicemia bruta (para calibrar) e o fator de calibração atual
        viewModelScope.launch {
            statusHolder.status.collect { st ->
                _state.update { it.copy(lastRawMmol = st.lastRawMmol) }
            }
        }
        viewModelScope.launch {
            preferences.sibionicsCalFactor.collect { f ->
                _state.update { it.copy(calFactor = f) }
            }
        }
        viewModelScope.launch {
            sensorHistory.observeAll().collect { list ->
                _state.update { it.copy(history = list) }
            }
        }
        viewModelScope.launch { sensorHistory.flushPending() }
        // Reenvia períodos que existem só no aparelho — cobre o histórico perdido pelo bug antigo
        // que marcava como sincronizado sem o servidor ter aceitado (ver reconcileWithServer).
        viewModelScope.launch { sensorHistory.reconcileWithServer() }
        viewModelScope.launch { sensorHistory.pruneOld() }
    }

    fun onShowHelp() { _state.update { it.copy(showHelp = true) } }
    fun onDismissHelp() { _state.update { it.copy(showHelp = false) } }

    fun startScan() {
        _state.update { it.copy(scanning = true, devices = emptyList()) }
        val started = bleManager.startScan(
            onFailed = { _state.update { it.copy(scanning = false, scanError = UiText.Res(R.string.sensorscan_scan_failed)) } }
        ) { name, mac, rssi ->
            val found = FoundDevice(name, mac, rssi)
            _state.update { s ->
                val list = s.devices.toMutableList()
                val idx  = list.indexOfFirst { it.mac == mac }
                if (idx >= 0) list[idx] = found else list.add(found)
                s.copy(devices = list.sortedByDescending { it.rssi })
            }
        }
        // L20: Bluetooth desligado — avisa em vez de ficar "procurando" sem procurar nada.
        if (!started) _state.update { it.copy(scanning = false, scanError = UiText.Res(R.string.sensorscan_bluetooth_off)) }
    }

    fun stopScan() {
        bleManager.stopScan()
        _state.update { it.copy(scanning = false) }
    }

    fun onSerialChange(value: String)     { _state.update { it.copy(serial     = value, scanError = null) } }
    fun onCustomerIdChange(value: String) { _state.update { it.copy(customerId = value, scanError = null) } }
    fun clearScanError()                  { _state.update { it.copy(scanError  = null) } }

    fun onScanResult(rawValue: String) {
        val parsed = SibionicsQrParser.parse(rawValue)
        if (parsed != null) {
            _state.update {
                it.copy(
                    customerId = parsed.customerId,
                    serial     = if (parsed.serial.isNotBlank()) parsed.serial else it.serial,
                    scanError  = null
                )
            }
        } else {
            _state.update { it.copy(scanError = UiText.Res(R.string.sensorscan_code_not_recognized)) }
        }
    }

    /** A câmera/leitor do Google nem abriu (antes caía em "código não reconhecido"). */
    fun onScannerUnavailable() { _state.update { it.copy(scanError = UiText.Res(R.string.sensorscan_camera_failed)) } }

    fun saveSensor(mac: String) {
        viewModelScope.launch {
            val serial     = _state.value.serial.trim()
            val customerId = _state.value.customerId.trim()
            val newCode    = customerId.ifBlank { SibionicsQrParser.connectionCodeFrom(serial) }

            // Detecta sensor DIFERENTE (código mudou) ANTES de sobrescrever.
            val oldSerial = preferences.sibionicsSerial.first()
            val oldCid    = preferences.sibionicsCustomerId.first()
            val oldCode   = oldCid.ifBlank { SibionicsQrParser.connectionCodeFrom(oldSerial) }

            android.util.Log.d("SIBIONICS_SCAN",
                "saveSensor: mac=$mac serial='$serial' customerId='$customerId' code=$newCode (antigo=$oldCode)")
            preferences.saveSibionicsSensor(mac = mac, serial = serial, customerId = customerId)

            if (newCode.isNotBlank() && newCode != oldCode) {
                // oldCode vazio pode ser sensor novo de verdade OU dado local perdido (limpeza de
                // dados/reinstalação) — nesse caso o GluSync ainda tem o sensor "aberto" com o
                // fator de calibração. Reconciliamos antes de decidir se é reset ou reconexão.
                val remoteActive = if (oldCode.isBlank()) sensorHistory.fetchActiveFromServer() else null
                val remoteCode = remoteActive?.customerId?.ifBlank { null }
                    ?: remoteActive?.serial?.let { SibionicsQrParser.connectionCodeFrom(it) }

                if (remoteActive != null && remoteCode == newCode) {
                    // MESMO sensor do servidor — restaura o fator de calibração em vez de zerar.
                    val restoredFactor = remoteActive.calibrationFactor ?: 1.0
                    preferences.saveSibionicsCalFactor(restoredFactor)
                    sensorHistory.adoptFromServer(remoteActive)
                    android.util.Log.d("SIBIONICS_SCAN",
                        "saveSensor: reconciliado com GluSync (code=$newCode) → fator restaurado=$restoredFactor")
                } else {
                    // Sensor NOVO de verdade → zera o estado do algoritmo e a âncora, para rastrear
                    // a deriva desde a ativação (índice ~1) e bater no Sugar Joy nativamente.
                    preferences.clearAlgoContext()
                    preferences.clearSibionicsSensorStart()
                    preferences.saveSibionicsCalFactor(1.0)
                    android.util.Log.d("SIBIONICS_SCAN",
                        "saveSensor: sensor novo ($newCode≠$oldCode) → contexto e âncora zerados (rastreio do zero)")
                    sensorHistory.startNew(mac, serial, customerId, preferences.dataSource.first())
                }
            }

            _state.update { it.copy(saved = true) }
            // Reinicia o serviço para aplicar a nova configuração do sensor imediatamente
            val dataSource = preferences.dataSource.first()
            if (DataSourceType.fromId(dataSource).isLocalSensor) {
                ServiceWatchdogWorker.cancel(context)
                context.stopService(Intent(context, GluSyncService::class.java))
                delay(800)
                context.startForegroundService(Intent(context, GluSyncService::class.java))
                ServiceWatchdogWorker.schedule(context)
            }
        }
    }

    fun clearSaved() { _state.update { it.copy(saved = false) } }

    /**
     * Salva o serial/ID digitados (ou escaneados) MANTENDO o MAC já configurado.
     * Resolve o caso em que o sensor já está conectado e some da lista de scan
     * (sensor conectado não faz advertising), impedindo tocar nele de novo.
     */
    fun saveConfig() {
        val mac = _state.value.savedMac
        if (mac.isBlank()) {
            _state.update { it.copy(scanError = UiText.Res(R.string.sensorscan_no_saved_sensor)) }
            return
        }
        saveSensor(mac)
    }

    /** Reconecta ao sensor reiniciando o serviço (sem apagar a configuração). */
    fun reconnectSensor() {
        viewModelScope.launch {
            android.util.Log.d("SIBIONICS_SCAN", "reconnectSensor: reiniciando serviço")
            ServiceWatchdogWorker.cancel(context)
            context.stopService(Intent(context, GluSyncService::class.java))
            delay(800)
            context.startForegroundService(Intent(context, GluSyncService::class.java))
            ServiceWatchdogWorker.schedule(context)
            _state.update { it.copy(reconnected = true) }
        }
    }

    fun clearReconnected() { _state.update { it.copy(reconnected = false) } }

    /** Esquece todo o sensor configurado e para o serviço — evita ter de limpar o app. */
    fun forgetSensor() {
        viewModelScope.launch {
            android.util.Log.d("SIBIONICS_SCAN", "forgetSensor: limpando MAC/serial/customerId")
            sensorHistory.endActive()
            preferences.clearSibionicsSensor()
            ServiceWatchdogWorker.cancel(context)
            context.stopService(Intent(context, GluSyncService::class.java))
            _state.update {
                it.copy(serial = "", customerId = "", savedMac = "", savedSerial = "",
                        savedCustomerId = "", devices = emptyList(), forgotten = true)
            }
        }
    }

    fun clearForgotten() { _state.update { it.copy(forgotten = false) } }

    /**
     * Calibração OPCIONAL: ajusta o valor de fábrica. fator = glicemia_real / glicemia_de_fábrica.
     * A glicemia exibida passa a ser glicemia_de_fábrica × fator. Sem calibrar, o fator é 1.0
     * (valor de fábrica, como o Sugar Joy). Recalibrável ao longo da vida do sensor.
     * @param refMgdl glicemia de referência (ponta de dedo / medidor), em mg/dL.
     */
    fun calibrate(refMgdl: Int) {
        val rawMmol = _state.value.lastRawMmol   // glicemia de fábrica (mmol/L), sem fator
        if (rawMmol <= 0.0) {
            _state.update { it.copy(scanError = UiText.Res(R.string.calibration_needs_reading)) }
            return
        }
        if (refMgdl !in 20..600) {
            _state.update { it.copy(scanError = UiText.Res(R.string.calibration_out_of_range)) }
            return
        }
        val refMmol = refMgdl / 18.0182
        val factor  = refMmol / rawMmol
        viewModelScope.launch {
            preferences.saveSibionicsCalFactor(factor)
            // Espelha no GluSync — cópia de segurança do fator, sobrevive a limpeza de dados
            // local (ver auditoria 2026-07-05: perda do fator zerou a calibração no app).
            sensorHistory.updateCalibration(factor)
            android.util.Log.d("SIBIONICS_SCAN",
                "calibrate: refMgdl=$refMgdl mmolFabrica=%.2f → fator=%.4f".format(rawMmol, factor))
            _state.update { it.copy(calibrated = true) }
        }
    }

    fun clearCalibrated() { _state.update { it.copy(calibrated = false) } }

    /** Restaura o valor de fábrica (fator 1.0). */
    fun resetCalibration() {
        viewModelScope.launch {
            preferences.saveSibionicsCalFactor(1.0)
            _state.update { it.copy(calibrated = true) }
        }
    }

    override fun onCleared() {
        super.onCleared()
        bleManager.stopScan()
    }
}
