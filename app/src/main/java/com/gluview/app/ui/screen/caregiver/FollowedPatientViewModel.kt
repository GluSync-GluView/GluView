package com.gluview.app.ui.screen.caregiver

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gluview.app.data.datastore.AppPreferences
import com.gluview.app.data.repository.CaregiverRepository
import com.gluview.app.domain.model.GlucoseThresholds
import com.gluview.app.util.GraphReading
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Mínima, média e máxima das últimas 24 h (mg/dL). */
data class FollowedStats(val min: Double, val avg: Double, val max: Double)

data class FollowedPatientUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val sgv: Int? = null,
    val arrow: String = "",
    val readings: List<GraphReading> = emptyList(),
    // L10: o detalhe passa a ter o que a Home do seguidor tem.
    val delta: Int = 0,
    val measuredAtMs: Long = 0L,
    val thresholds: GlucoseThresholds = GlucoseThresholds.DEFAULT,
    val stats: FollowedStats? = null
)

/**
 * Detalhe do paciente acompanhado: leitura em tempo real pelo [FollowedPatientFeed] (antes
 * consultava a cada 30 s, sem delta, "há X min" nem estatísticas — L10), limites do paciente (L1).
 */
@HiltViewModel
class FollowedPatientViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    preferences: AppPreferences,
    repo: CaregiverRepository
) : ViewModel() {

    private val patientId: String = savedStateHandle.get<String>("patientId").orEmpty()
    private val feed = FollowedPatientFeed(viewModelScope, preferences, repo)

    val ui: StateFlow<FollowedPatientUiState> = feed.state.map { f ->
        FollowedPatientUiState(
            loading = f.loading,
            error = if (patientId.isBlank()) "Paciente inválido" else f.error,
            sgv = f.sgv, arrow = f.arrow, readings = f.readings,
            delta = f.delta, measuredAtMs = f.measuredAtMs, thresholds = f.thresholds,
            stats = f.readings.takeIf { it.isNotEmpty() }?.let { r ->
                FollowedStats(r.minOf { it.sgvMgDl }.toDouble(), r.map { it.sgvMgDl }.average(), r.maxOf { it.sgvMgDl }.toDouble())
            }
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, FollowedPatientUiState())

    init { feed.watch(patientId) }

    fun refresh() = feed.refresh()

    override fun onCleared() { feed.stop() }
}
