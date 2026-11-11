package com.gluview.app.ui.screen.reminders

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gluview.app.data.datastore.AppPreferences
import com.gluview.app.data.diary.DiaryRepository
import com.gluview.app.data.local.entity.InsulinEntity
import com.gluview.app.data.local.entity.MedicationEntity
import com.gluview.app.data.local.entity.ReminderEntity
import com.gluview.app.data.local.entity.ReminderLogEntity
import com.gluview.app.data.reminder.ReminderRepository
import com.gluview.app.data.reminder.ReminderScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

@HiltViewModel
class RemindersViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: ReminderRepository,
    private val scheduler: ReminderScheduler,
    private val diary: DiaryRepository,
    private val preferences: AppPreferences
) : ViewModel() {

    /** null = carregando. */
    val reminders: StateFlow<List<ReminderEntity>?> = repo.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val insulins: StateFlow<List<InsulinEntity>> = diary.observeInsulins()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val medications: StateFlow<List<MedicationEntity>> = diary.observeMedications()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** O que aconteceu hoje (tocou, feito, adiado, pulado). */
    val todayLog: StateFlow<List<ReminderLogEntity>> = repo.observeLogFrom(
        LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val smartMealPrompt: StateFlow<Boolean> = preferences.smartMealPrompt
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _exactAllowed = MutableStateFlow(true)
    val exactAllowed: StateFlow<Boolean> = _exactAllowed.asStateFlow()
    private val _notificationsAllowed = MutableStateFlow(true)
    val notificationsAllowed: StateFlow<Boolean> = _notificationsAllowed.asStateFlow()

    init { refreshPermissions() }

    /** Volta das telas do sistema: confere de novo as permissões e remarca tudo. */
    fun refreshPermissions() {
        _exactAllowed.value = scheduler.canScheduleExact()
        _notificationsAllowed.value = NotificationManagerCompat.from(context).areNotificationsEnabled()
        viewModelScope.launch { scheduler.rescheduleAll() }
    }

    fun save(r: ReminderEntity) { viewModelScope.launch { repo.save(r) } }
    fun setEnabled(r: ReminderEntity, enabled: Boolean) { viewModelScope.launch { repo.setEnabled(r, enabled) } }
    fun delete(r: ReminderEntity) { viewModelScope.launch { repo.delete(r) } }
    fun setSmartMealPrompt(enabled: Boolean) { viewModelScope.launch { preferences.saveSmartMealPrompt(enabled) } }
}
