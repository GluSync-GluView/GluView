package com.gluview.app.ui.screen.agp

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gluview.app.R
import com.gluview.app.data.datastore.AppPreferences
import com.gluview.app.data.repository.GlucoseRepository
import com.gluview.app.domain.agp.AgpCalculator
import com.gluview.app.domain.agp.AgpRanges
import com.gluview.app.domain.agp.AgpReport
import com.gluview.app.util.ExportHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId
import javax.inject.Inject

/** Períodos do AGP. 14 dias é o mínimo recomendado pelo consenso; 7 sai com aviso. */
enum class AgpPeriod(val days: Int) { D7(7), D14(14), D30(30) }

data class AgpUiState(
    val period: AgpPeriod = AgpPeriod.D14,
    val report: AgpReport? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
    /** Seguidor: o AGP usa as leituras do aparelho, que são da própria pessoa. */
    val followerOnly: Boolean = false,
    val exporting: Boolean = false,
    @StringRes val exportMsg: Int? = null
)

/**
 * PT-4 — AGP com as leituras do Room (o sensor grava tudo no aparelho, com ou sem nuvem). O
 * período são as últimas [AgpPeriod.days] × 24 h até agora (assim o "sensor ativo" não conta as
 * horas de hoje que ainda não aconteceram).
 */
@HiltViewModel
class AgpViewModel @Inject constructor(
    private val glucose: GlucoseRepository,
    private val preferences: AppPreferences,
    private val exportHelper: ExportHelper
) : ViewModel() {

    private val _ui = MutableStateFlow(AgpUiState())
    val ui: StateFlow<AgpUiState> = _ui.asStateFlow()

    private var loadJob: Job? = null

    init { load() }

    fun onPeriod(period: AgpPeriod) {
        if (period == _ui.value.period && _ui.value.report != null) return
        _ui.value = _ui.value.copy(period = period)
        load()
    }

    fun retry() = load()

    private fun load() {
        loadJob?.cancel()
        val period = _ui.value.period
        loadJob = viewModelScope.launch {
            _ui.value = _ui.value.copy(isLoading = true, error = null)
            runCatching {
                val follower = preferences.isFollowerOnly.first()
                val ranges = AgpRanges.withTarget(
                    preferences.lowThreshold.first().toDouble(),
                    preferences.highThreshold.first().toDouble()
                ).let { if (it.targetLow == 70.0 && it.targetHigh == 180.0) AgpRanges.CONSENSUS else it }
                val report = withContext(Dispatchers.Default) {
                    val zone = ZoneId.systemDefault()
                    val (from, to) = periodBounds(period.days, System.currentTimeMillis())
                    val samples = glucose.getReadingsBetween(from, to - 1)
                        .filter { !it.isStale && it.sgvMgDl > 0 }
                        .map { AgpCalculator.Sample(it.dateMs, it.sgvMgDl) }
                    AgpCalculator.compute(samples, from, to, zone, ranges)
                }
                follower to report
            }.onSuccess { (follower, report) ->
                _ui.value = _ui.value.copy(report = report, followerOnly = follower, isLoading = false)
            }.onFailure {
                _ui.value = _ui.value.copy(error = it.message ?: it.javaClass.simpleName, isLoading = false)
            }
        }
    }

    fun exportPdf() {
        val report = _ui.value.report ?: return
        if (_ui.value.exporting) return
        viewModelScope.launch {
            _ui.value = _ui.value.copy(exporting = true)
            val ok = runCatching { exportHelper.exportAgpPdf(report) }.isSuccess
            _ui.value = _ui.value.copy(exporting = false,
                exportMsg = if (ok) R.string.report_export_pdf_ok else R.string.report_export_pdf_fail)
        }
    }

    fun clearExportMsg() { _ui.value = _ui.value.copy(exportMsg = null) }

    companion object {
        /** Últimos [days] × 24 h terminando em [nowMs]: [início, fim). */
        fun periodBounds(days: Int, nowMs: Long): Pair<Long, Long> = (nowMs - days * 86_400_000L) to (nowMs + 1)
    }
}
