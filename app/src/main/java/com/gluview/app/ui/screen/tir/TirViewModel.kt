package com.gluview.app.ui.screen.tir

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gluview.app.data.datastore.AppPreferences
import com.gluview.app.data.remote.dto.TirReportDto
import com.gluview.app.data.repository.StatsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlinx.coroutines.Job

enum class TirPeriod(val days: Int, val label: String) {
    D7(7, "7 dias"), D14(14, "14 dias"), D30(30, "30 dias")
}

data class TirUiState(
    val report:    TirReportDto? = null,
    val period:    TirPeriod    = TirPeriod.D30,
    val isLoading: Boolean      = false,
    val error:     String?      = null
)

@HiltViewModel
class TirViewModel @Inject constructor(
    private val repository: StatsRepository,
    private val preferences: AppPreferences
) : ViewModel() {

    private val _uiState = MutableStateFlow(TirUiState())
    val uiState: StateFlow<TirUiState> = _uiState.asStateFlow()

    // Modo seguidor: TIR do paciente ativo (via GluSync), não dados locais.
    private suspend fun activePatientId(): String? =
        if (preferences.isFollowerOnly.first()) preferences.activeFollowedPatientId.first().ifBlank { null }
        else null

    init { load(TirPeriod.D30) }

    fun onPeriodSelected(p: TirPeriod) {
        if (_uiState.value.period == p) return
        _uiState.value = _uiState.value.copy(period = p)
        load(p)
    }

    fun retry() = load(_uiState.value.period)

    private var loadJob: Job? = null

    private fun load(p: TirPeriod) {
        // L18: trocar de período cancela a carga anterior (a resposta velha chegava depois e
        // sobrescrevia a nova).
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            runCatching { repository.getTirReport(p.days, activePatientId()) }
                .onSuccess { report -> _uiState.value = _uiState.value.copy(report = report, isLoading = false) }
                // error = detalhe técnico (a tela mostra a mensagem traduzida e isto como "Detalhe técnico").
                .onFailure { _uiState.value = _uiState.value.copy(error = it.message ?: it.javaClass.simpleName, isLoading = false) }
        }
    }
}
