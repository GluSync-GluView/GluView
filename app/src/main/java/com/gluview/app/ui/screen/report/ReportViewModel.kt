package com.gluview.app.ui.screen.report

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gluview.app.R
import com.gluview.app.data.datastore.AppPreferences
import com.gluview.app.data.remote.dto.SummaryReportDto
import com.gluview.app.data.repository.StatsRepository
import com.gluview.app.util.ExportHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Calendar
import javax.inject.Inject
import kotlinx.coroutines.Job

data class ReportUiState(
    val report:    SummaryReportDto? = null,
    val mes:       Int               = Calendar.getInstance().get(Calendar.MONTH) + 1,
    val ano:       Int               = Calendar.getInstance().get(Calendar.YEAR),
    val isLoading: Boolean           = false,
    /** Detalhe técnico do erro (a tela mostra a mensagem traduzida). */
    val error:     String?           = null,
    /** Resultado da exportação (string traduzida), mostrado num snackbar. */
    @StringRes val exportMsg: Int?   = null,
    /** Exportação em curso: o botão mostra progresso e os dois ficam desligados. */
    val exporting: ExportKind?       = null
)

enum class ExportKind { PDF, CSV }

@HiltViewModel
class ReportViewModel @Inject constructor(
    private val repository: StatsRepository,
    private val exportHelper: ExportHelper,
    private val preferences: AppPreferences
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReportUiState())
    val uiState: StateFlow<ReportUiState> = _uiState.asStateFlow()

    // Modo seguidor: relatório do paciente ativo (via GluSync), não dados locais.
    private suspend fun activePatientId(): String? =
        if (preferences.isFollowerOnly.first()) preferences.activeFollowedPatientId.first().ifBlank { null }
        else null

    init { load() }

    fun onMonthChanged(mes: Int, ano: Int) {
        _uiState.value = _uiState.value.copy(mes = mes, ano = ano)
        load()
    }

    fun exportPdf() = export(ExportKind.PDF, R.string.report_export_pdf_ok, R.string.report_export_pdf_fail) { exportHelper.exportPdf(it) }

    fun exportCsv() = export(ExportKind.CSV, R.string.report_export_csv_ok, R.string.report_export_csv_fail) { exportHelper.exportCsv(it) }

    private fun export(kind: ExportKind, @StringRes ok: Int, @StringRes fail: Int, block: suspend (SummaryReportDto) -> Unit) {
        val report = _uiState.value.report ?: return
        if (_uiState.value.exporting != null) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(exporting = kind)
            val msg = if (runCatching { block(report) }.isSuccess) ok else fail
            _uiState.value = _uiState.value.copy(exporting = null, exportMsg = msg)
        }
    }

    fun retry() = load()

    fun clearExportMsg() { _uiState.value = _uiState.value.copy(exportMsg = null) }

    private var loadJob: Job? = null

    private fun load() {
        val s = _uiState.value
        // L18: trocar de mês cancela a carga anterior (a resposta velha sobrescrevia a nova).
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            runCatching { repository.getSummaryReport(mes = s.mes, ano = s.ano, patientId = activePatientId()) }
                .onSuccess { r -> _uiState.value = _uiState.value.copy(report = r, isLoading = false) }
                .onFailure { _uiState.value = _uiState.value.copy(error = it.message ?: it.javaClass.simpleName, isLoading = false) }
        }
    }
}
