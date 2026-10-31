package com.gluview.app.ui.screen.dailypattern

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gluview.app.data.datastore.AppPreferences
import com.gluview.app.data.repository.DailyPatternPoint
import com.gluview.app.data.repository.StatsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlinx.coroutines.Job

enum class PatternPeriod(val days: Int, val label: String) {
    D7(7, "7 dias"), D14(14, "14 dias"), D30(30, "30 dias")
}

@HiltViewModel
class DailyPatternViewModel @Inject constructor(
    private val repository: StatsRepository,
    private val preferences: AppPreferences
) : ViewModel() {

    // Modo seguidor: padrão diário do paciente ativo (via GluSync), não dados locais.
    private suspend fun activePatientId(): String? =
        if (preferences.isFollowerOnly.first()) preferences.activeFollowedPatientId.first().ifBlank { null }
        else null

    private val _points    = MutableStateFlow<List<DailyPatternPoint>>(emptyList())
    val points: StateFlow<List<DailyPatternPoint>> = _points.asStateFlow()

    private val _period    = MutableStateFlow(PatternPeriod.D7)
    val period: StateFlow<PatternPeriod> = _period.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error     = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init { load(PatternPeriod.D7) }

    fun onPeriodSelected(p: PatternPeriod) {
        if (_period.value == p) return
        _period.value = p
        load(p)
    }

    /** "Tentar novamente" do estado de erro. */
    fun retry() = load(_period.value)

    private var loadJob: Job? = null

    private fun load(p: PatternPeriod) {
        // L18: trocar de período cancela a carga anterior (a resposta velha sobrescrevia a nova).
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            val result = runCatching { repository.getDailyPattern(p.days, activePatientId()) }
            result.onFailure { _error.value = it.message ?: it.javaClass.simpleName } // detalhe técnico
            result.getOrNull()?.let { _points.value = it }
            _isLoading.value = false
        }
    }
}
