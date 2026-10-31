package com.gluview.app.ui.screen.libre2

import android.content.Context
import android.content.Intent
import android.nfc.Tag
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gluview.app.R
import com.gluview.app.data.datastore.AppPreferences
import com.gluview.app.data.libre2.Libre2NfcActivator
import com.gluview.app.data.libre2.Libre2NfcController
import com.gluview.app.data.repository.GlucoseRepository
import com.gluview.app.data.sibionics.SibionicsStatusHolder
import com.gluview.app.data.source.DataSourceType
import com.gluview.app.service.GluSyncService
import com.gluview.app.service.ServiceWatchdogWorker
import com.gluview.app.util.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class Libre2ActivationUiState(
    val waitingForTag: Boolean = false,
    val activating: Boolean    = false,
    val activated: Boolean     = false,
    val serial: String         = "",
    val streamingSupported: Boolean = true,
    val savedSerial: String    = "",
    val lastRawMmol: Double     = 0.0,   // glicemia de fábrica (mmol/L), base da calibração
    val calFactor: Double       = 0.0,
    val calibrated: Boolean     = false,
    /** Aviso passageiro (calibração). */
    val error: UiText?          = null,
    /** Última leitura NFC falhou: fica na tela até tentar de novo (antes um snackbar que sumia).
     *  O texto é o detalhe técnico; a frase traduzida fica na tela. */
    val readFailure: String?    = null
)

/**
 * Ativação por NFC do FreeStyle Libre 2 Plus + calibração por ponta de dedo (mesmo modelo do
 * Sibionics). Liga o reader-mode via [Libre2NfcController]; ao encostar o sensor, ativa, salva a
 * sessão, seleciona a fonte LIBRE2_PLUS e (re)inicia o serviço.
 */
@HiltViewModel
class Libre2ActivationViewModel @Inject constructor(
    private val controller: Libre2NfcController,
    private val activator: Libre2NfcActivator,
    private val preferences: AppPreferences,
    private val repository: GlucoseRepository,
    private val statusHolder: SibionicsStatusHolder,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private companion object { const val MMOL_TO_MGDL = 18.0182 }

    private val _state = MutableStateFlow(Libre2ActivationUiState())
    val uiState: StateFlow<Libre2ActivationUiState> = _state.asStateFlow()

    private var nfcJob: Job? = null

    init {
        viewModelScope.launch {
            preferences.libre2Serial.collect { s -> _state.update { it.copy(savedSerial = s) } }
        }
        viewModelScope.launch {
            preferences.libre2CalFactor.collect { f -> _state.update { it.copy(calFactor = f) } }
        }
        viewModelScope.launch {
            statusHolder.status.collect { st -> _state.update { it.copy(lastRawMmol = st.lastRawMmol) } }
        }
    }

    /** Começa a aguardar o encostar do sensor (liga o reader-mode). */
    fun startNfc() {
        // Aparelho 32 bits: a leitura do Libre 2 usa a biblioteca da Abbott, que só existe em
        // arm64. A tela já mostra o aviso no lugar do botão; esta trava só evita armar o NFC à toa
        // (o activate() falharia com o erro técnico da ponte).
        if (!DataSourceType.LIBRE2_PLUS.isUsableOnThisDevice) return
        _state.update { it.copy(waitingForTag = true, error = null, readFailure = null, activated = false) }
        controller.setReaderMode(true)
        nfcJob?.cancel()
        nfcJob = viewModelScope.launch {
            val tag = controller.tags.first()
            onTagScanned(tag)
        }
    }

    /** Cancela a espera pelo sensor (desliga o reader-mode). */
    /** Arms NFC automatically only when no Libre sensor is saved yet. */
    fun autoStartNfcIfNeeded() {
        viewModelScope.launch {
            if (preferences.libre2Uid.first().isBlank()) startNfc()
        }
    }

    fun stopNfc() {
        nfcJob?.cancel(); nfcJob = null
        controller.setReaderMode(false)
        _state.update { it.copy(waitingForTag = false) }
    }

    private suspend fun onTagScanned(tag: Tag) {
        _state.update { it.copy(waitingForTag = false, activating = true) }
        // Mantém o reader mode ativo durante toda a leitura — desabilitar antes do activate()
        // chama disableReaderMode() na MainActivity e mata a sessão NFC imediatamente.
        val result = withContext(Dispatchers.IO) { activator.activate(tag) }
        controller.setReaderMode(false)
        result.onSuccess { session ->
            val mac = session.bleMac.ifBlank { preferences.libre2Mac.first() }
            preferences.saveLibre2Session(
                // MAC autoritativo vem do enable-streaming (bleAddress); se ausente, preserva o já descoberto.
                mac = mac,
                serial = session.serial,
                uidHex = session.uidHex,
                patchInfoHex = session.patchInfoHex,
                sessionKeyHex = session.streamingAuthHex,
                calibrationJson = session.calibration.toJson(),
                framHex = session.framHex,
                sensorStartMs = session.sensorStartMs
            )
            session.scanReading?.let { reading ->
                if (reading.mgdl in 20..600) {
                    val nowMs = System.currentTimeMillis()
                    val startMs = session.sensorStartMs.takeIf { it > 0L } ?: nowMs
                    val ageMin = ((nowMs - startMs) / 60_000L).toInt().coerceAtLeast(0)
                    val mmol = reading.mgdl / MMOL_TO_MGDL
                    statusHolder.onRawMmol(mmol)
                    repository.storeExternalReading(
                        dateMs = nowMs,
                        sgvMgDl = reading.mgdl.toDouble(),
                        arrow = "→",
                        delta = 0,
                        makeCurrent = true,
                        rawMmol = mmol,
                        calibFactor = 1.0,
                        sensorMac = mac,
                        sensorAgeMin = ageMin,
                        confirmed = null
                    )
                    statusHolder.onReading(ageMin, startMs, nowMs)
                }
            }
            preferences.saveDataSource(DataSourceType.LIBRE2_PLUS.id)
            _state.update {
                it.copy(
                    activating = false, activated = true,
                    serial = session.serial,
                    // Sem streaming: aviso fixo na tela (streamingSupported), não mais snackbar.
                    streamingSupported = session.streamingEnabled
                )
            }
            restartService()
        }.onFailure { e ->
            _state.update {
                it.copy(activating = false, readFailure = e.message ?: e::class.simpleName.orEmpty())
            }
        }
    }

    /** Calibração por ponta de dedo: fator = glicemia_real / glicemia_de_fábrica. */
    fun calibrate(refMgdl: Int) {
        val rawMmol = _state.value.lastRawMmol
        if (rawMmol <= 0.0) {
            _state.update { it.copy(error = UiText.Res(R.string.calibration_needs_reading)) }
            return
        }
        if (refMgdl !in 20..600) {
            _state.update { it.copy(error = UiText.Res(R.string.calibration_out_of_range)) }
            return
        }
        val factor = (refMgdl / MMOL_TO_MGDL) / rawMmol
        viewModelScope.launch {
            preferences.saveLibre2CalFactor(factor)
            _state.update { it.copy(calibrated = true) }
        }
    }

    fun resetCalibration() {
        viewModelScope.launch {
            preferences.saveLibre2CalFactor(1.0)
            _state.update { it.copy(calibrated = true) }
        }
    }

    fun forget() {
        viewModelScope.launch {
            preferences.clearLibre2Sensor()
            ServiceWatchdogWorker.cancel(context)
            context.stopService(Intent(context, GluSyncService::class.java))
            _state.update { Libre2ActivationUiState() }
        }
    }

    fun clearError()      { _state.update { it.copy(error = null) } }
    fun clearCalibrated() { _state.update { it.copy(calibrated = false) } }

    private suspend fun restartService() {
        ServiceWatchdogWorker.cancel(context)
        context.stopService(Intent(context, GluSyncService::class.java))
        delay(800)
        context.startForegroundService(Intent(context, GluSyncService::class.java))
        ServiceWatchdogWorker.schedule(context)
    }

    override fun onCleared() {
        super.onCleared()
        nfcJob?.cancel()
        controller.setReaderMode(false)
    }
}
