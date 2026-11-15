package com.gluview.app.ui.screen.diary

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gluview.app.R
import com.gluview.app.data.diary.DaySummary
import com.gluview.app.data.diary.DiaryCloudSync
import com.gluview.app.data.diary.DiaryRepository
import com.gluview.app.data.local.entity.EventLogEntity
import com.gluview.app.data.local.entity.InsulinEntity
import com.gluview.app.data.local.entity.MedicationEntity
import com.gluview.app.util.ExportHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DiaryViewModel @Inject constructor(
    private val repo: DiaryRepository,
    private val exportHelper: ExportHelper,
    private val cloud: DiaryCloudSync,
    private val healthRecords: com.gluview.app.data.local.dao.HealthRecordDao,
    private val patterns: com.gluview.app.data.health.HealthPatterns
) : ViewModel() {

    private val _day = MutableStateFlow(LocalDate.now())
    val day: StateFlow<LocalDate> = _day.asStateFlow()

    /** null = carregando. */
    val entries: StateFlow<List<EventLogEntity>?> = _day.flatMapLatest { repo.observeDay(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val summary: StateFlow<DaySummary> = entries.map { DaySummary.of(it.orEmpty()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DaySummary())

    /** F3.5 — refeições de outros apps (Health Connect) neste dia que ainda não estão no diário. */
    val suggestions: StateFlow<List<com.gluview.app.data.local.entity.HealthRecordEntity>> = _day.flatMapLatest { d ->
        val (from, to) = DiaryRepository.dayBounds(d, ZoneId.systemDefault())
        kotlinx.coroutines.flow.combine(
            healthRecords.observeStartingBetween(com.gluview.app.data.health.HealthConnectMapper.KIND_NUTRITION, from, to),
            repo.observeDay(d)
        ) { recs, entries ->
            val have = entries.map { it.uuid }.toSet()
            recs.filter { "hc-" + it.hcId !in have && (it.value > 0 || it.value2 > 0) }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun addSuggestion(r: com.gluview.app.data.local.entity.HealthRecordEntity) {
        viewModelScope.launch {
            repo.save(EventLogEntity(
                occurredAt = r.startMs, type = com.gluview.app.data.diary.DiaryType.MEAL.key, value = r.value, kcal = r.value2,
                notes = r.title, uuid = "hc-" + r.hcId, source = com.gluview.app.data.health.HealthConnectSync.SOURCE_HC
            ))
        }
    }

    val insulins: StateFlow<List<InsulinEntity>> = repo.observeInsulins()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val medications: StateFlow<List<MedicationEntity>> = repo.observeMedications()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** O diário está indo também para a nuvem (só com a liberação). */
    private val _cloudOn = MutableStateFlow(false)
    val cloudOn: StateFlow<Boolean> = _cloudOn.asStateFlow()

    private val _message = MutableStateFlow<Int?>(null)
    val message: StateFlow<Int?> = _message.asStateFlow()

    init {
        viewModelScope.launch { _cloudOn.value = cloud.isAllowed() }
    }

    fun previousDay() { _day.value = _day.value.minusDays(1) }
    fun nextDay() { if (_day.value < LocalDate.now()) _day.value = _day.value.plusDays(1) }
    fun goToday() { _day.value = LocalDate.now() }
    fun setDay(day: LocalDate) { _day.value = minOf(day, LocalDate.now()) }

    fun save(entry: EventLogEntity) {
        viewModelScope.launch {
            repo.save(entry)
            val day = DiaryRepository.dayOf(entry.occurredAt)
            if (day != _day.value) _day.value = day
        }
    }

    fun delete(entry: EventLogEntity) { viewModelScope.launch { repo.delete(entry) } }

    /** PDF do diário dos últimos [days] dias até o dia aberto. */
    fun exportPdf(days: Int, includePatterns: Boolean = false) {
        viewModelScope.launch {
            val zone = ZoneId.systemDefault()
            val last = _day.value
            val first = last.minusDays(days.toLong() - 1)
            val from = DiaryRepository.dayBounds(first, zone).first
            val to = DiaryRepository.dayBounds(last, zone).second
            val list = repo.getBetween(from, to)
            _message.value = if (list.isEmpty()) R.string.diary_export_empty
            else runCatching {
                val pat = if (includePatterns) patterns.computeBetween(from, minOf(to, System.currentTimeMillis())) else null
                exportHelper.exportDiaryPdf(list, first, last, pat)
            }
                .fold({ null }, { R.string.diary_export_fail })
        }
    }

    fun clearMessage() { _message.value = null }

    companion object {
        val EXPORT_DAYS = listOf(7, 14, 30)
    }
}
