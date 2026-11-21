package com.gluview.app.ui.screen.libre3

import android.content.Context
import android.content.Intent
import android.nfc.Tag
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gluview.app.data.datastore.AppPreferences
import com.gluview.app.data.libre2.Libre2NfcController
import com.gluview.app.data.libre3.Libre3NfcActivator
import com.gluview.app.data.source.DataSourceType
import com.gluview.app.service.GluSyncService
import com.gluview.app.service.ServiceWatchdogWorker
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

/** Resultado mostrado na tela (sem nome de exceção). */
sealed interface Libre3ActivationResult {
    data class Activated(val warmupMin: Int) : Libre3ActivationResult
    data object TakenOver : Libre3ActivationResult
    data object WrongAccount : Libre3ActivationResult
    data object Ended : Libre3ActivationResult
    data object NotLibre3 : Libre3ActivationResult
    data object Lingo : Libre3ActivationResult
    data class Failed(val detail: String) : Libre3ActivationResult
}

data class Libre3ActivationUiState(
    val waitingForTag: Boolean = false,
    val activating: Boolean = false,
    val result: Libre3ActivationResult? = null,
    val savedSerial: String = "",
    val accountId: String = ""
)

/**
 * Ativação / assunção do FreeStyle Libre 3 por NFC (plano 21, F6.1). Mesmo fluxo do Libre 2 Plus:
 * liga o modo leitor via [Libre2NfcController] (genérico de NFC), lê o sensor com
 * [Libre3NfcActivator], grava a fonte LIBRE3 e reinicia o serviço.
 */
@HiltViewModel
class Libre3ActivationViewModel @Inject constructor(
    private val controller: Libre2NfcController,
    private val activator: Libre3NfcActivator,
    private val preferences: AppPreferences,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _state = MutableStateFlow(Libre3ActivationUiState())
    val uiState: StateFlow<Libre3ActivationUiState> = _state.asStateFlow()
    private var nfcJob: Job? = null

    init {
        viewModelScope.launch { preferences.libre3Serial.collect { s -> _state.update { it.copy(savedSerial = s) } } }
        viewModelScope.launch {
            val id = preferences.libre3AccountIdOrCreate()
            _state.update { it.copy(accountId = id.toString()) }
        }
    }

    fun autoStartNfcIfNeeded() {
        viewModelScope.launch { if (preferences.libre3Serial.first().isBlank()) startNfc() }
    }

    fun startNfc() {
        if (!DataSourceType.LIBRE3.isUsableOnThisDevice) return
        _state.update { it.copy(waitingForTag = true, result = null) }
        controller.setReaderMode(true)
        nfcJob?.cancel()
        nfcJob = viewModelScope.launch { onTag(controller.tags.first()) }
    }

    fun stopNfc() {
        nfcJob?.cancel(); nfcJob = null
        controller.setReaderMode(false)
        _state.update { it.copy(waitingForTag = false) }
    }

    fun onAccountIdChange(text: String) {
        _state.update { it.copy(accountId = text.filter(Char::isDigit).take(10)) }
    }

    private suspend fun onTag(tag: Tag) {
        _state.update { it.copy(waitingForTag = false, activating = true) }
        val override = _state.value.accountId.toLongOrNull()?.takeIf { it in 1L..0xFFFFFFFFL }
        // O modo leitor fica ligado durante a leitura (desligar antes mata a sessão NFC).
        val outcome = withContext(Dispatchers.IO) { activator.activate(tag, override) }
        controller.setReaderMode(false)
        val result = when (outcome) {
            is Libre3NfcActivator.Outcome.Ok -> {
                preferences.saveDataSource(DataSourceType.LIBRE3.id)
                restartService()
                if (outcome.session.wasNew) Libre3ActivationResult.Activated(outcome.session.warmupMin)
                else Libre3ActivationResult.TakenOver
            }
            Libre3NfcActivator.Outcome.WrongAccount -> Libre3ActivationResult.WrongAccount
            Libre3NfcActivator.Outcome.Ended -> Libre3ActivationResult.Ended
            Libre3NfcActivator.Outcome.NotLibre3 -> Libre3ActivationResult.NotLibre3
            Libre3NfcActivator.Outcome.Lingo -> Libre3ActivationResult.Lingo
            is Libre3NfcActivator.Outcome.Failed -> Libre3ActivationResult.Failed(outcome.reason)
        }
        _state.update { it.copy(activating = false, result = result) }
    }

    fun forget() {
        viewModelScope.launch {
            preferences.clearLibre3Sensor()
            ServiceWatchdogWorker.cancel(context)
            context.stopService(Intent(context, GluSyncService::class.java))
            _state.update { it.copy(result = null, savedSerial = "") }
        }
    }

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
