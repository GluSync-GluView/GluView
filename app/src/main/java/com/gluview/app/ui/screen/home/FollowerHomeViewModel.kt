package com.gluview.app.ui.screen.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gluview.app.data.datastore.AppPreferences
import com.gluview.app.data.repository.CaregiverRepository
import com.gluview.app.domain.model.GlucoseThresholds
import com.gluview.app.ui.screen.caregiver.CaregiverItem
import com.gluview.app.ui.screen.caregiver.FollowedPatientFeed
import com.gluview.app.util.GraphReading
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class FollowerHomeUiState(
    val loading: Boolean            = true,
    val error: String?              = null,
    val patientId: String           = "",
    val patientName: String         = "",
    val sgv: Int?                   = null,
    val arrow: String               = "",
    val delta: Int                  = 0,
    /** Hora da MEDIÇÃO da leitura atual (L7; antes a hora da consulta). */
    val lastUpdatedMs: Long          = 0L,
    val readings: List<GraphReading> = emptyList(),
    val following: List<CaregiverItem> = emptyList(),
    val showPicker: Boolean          = false,
    /** Limites do paciente acompanhado (L1). */
    val thresholds: GlucoseThresholds = GlucoseThresholds.DEFAULT
)

private data class PickerState(
    val patientId: String = "",
    val patientName: String = "",
    val following: List<CaregiverItem> = emptyList(),
    val showPicker: Boolean = false
)

/**
 * Home para conta seguidor-apenas: seletor de paciente + leitura em tempo real do paciente
 * ativo. A leitura vem do [FollowedPatientFeed] (SignalR + consulta de 30 s, limites do paciente,
 * hora da medição), o mesmo do detalhe do acompanhado.
 */
@HiltViewModel
class FollowerHomeViewModel @Inject constructor(
    private val preferences: AppPreferences,
    private val repo: CaregiverRepository
) : ViewModel() {

    private val feed = FollowedPatientFeed(viewModelScope, preferences, repo)
    private val picker = MutableStateFlow(PickerState())

    val ui: StateFlow<FollowerHomeUiState> = combine(picker, feed.state) { p, f ->
        FollowerHomeUiState(
            loading = f.loading, error = f.error,
            patientId = p.patientId, patientName = p.patientName,
            sgv = f.sgv, arrow = f.arrow, delta = f.delta, lastUpdatedMs = f.measuredAtMs,
            readings = f.readings, following = p.following, showPicker = p.showPicker,
            thresholds = f.thresholds
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, FollowerHomeUiState())

    init {
        viewModelScope.launch { loadFollowing() }
        viewModelScope.launch {
            preferences.activeFollowedPatientId.collect { id ->
                val name = preferences.activeFollowedPatientName.first()
                picker.update { it.copy(patientId = id, patientName = name) }
                feed.watch(id)
            }
        }
    }

    fun refresh() {
        viewModelScope.launch { loadFollowing() }
        feed.refresh()
    }

    fun openPicker()  { picker.update { it.copy(showPicker = true) } }
    fun closePicker() { picker.update { it.copy(showPicker = false) } }

    fun selectPatient(item: CaregiverItem) {
        viewModelScope.launch {
            preferences.saveActiveFollowedPatient(item.patientId, item.otherName)
            picker.update { it.copy(showPicker = false) }
        }
    }

    fun clearActivePatient() {
        viewModelScope.launch { preferences.clearActiveFollowedPatient() }
    }

    private suspend fun loadFollowing() {
        runCatching {
            val myId = repo.me().id
            repo.links()
                .filter { it.status == "accepted" && it.iAmFollower(myId) }
                .map { CaregiverItem(it, it.otherPartyName(myId), it.patientId, iAmFollower = true) }
        }.onSuccess { list -> picker.update { it.copy(following = list) } }
    }

    override fun onCleared() {
        feed.stop()
    }
}
