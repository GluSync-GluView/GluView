package com.gluview.app.ui.screen.eventlog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gluview.app.data.local.dao.EventLogDao
import com.gluview.app.data.local.entity.EventLogEntity
import com.gluview.app.data.repository.GlucoseRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Tipos de evento; rótulo, unidade e exemplo saem de string resource na tela. */
enum class EventType { MEAL, INSULIN, EXERCISE, NOTE }

@HiltViewModel
class EventLogViewModel @Inject constructor(
    private val dao:        EventLogDao,
    private val glucoseRepo: GlucoseRepository
) : ViewModel() {

    /** null = ainda carregando (antes nascia vazio e "Nenhum evento" piscava antes do Room). */
    val events: StateFlow<List<EventLogEntity>?> = dao.observeRecent()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun addEvent(type: EventType, value: Double, notes: String) {
        viewModelScope.launch {
            dao.insert(
                EventLogEntity(
                    type       = type.name.lowercase(),
                    value      = value,
                    notes      = notes,
                    sgvAtEvent = glucoseRepo.currentReading.value?.sgvMgDl ?: 0.0
                )
            )
        }
    }

    fun delete(event: EventLogEntity) {
        viewModelScope.launch { dao.delete(event) }
    }
}
