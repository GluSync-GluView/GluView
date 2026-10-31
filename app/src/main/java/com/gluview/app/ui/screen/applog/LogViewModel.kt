package com.gluview.app.ui.screen.applog

import com.gluview.app.R
import com.gluview.app.data.datastore.AppPreferences
import com.gluview.app.util.withAppLanguage
import kotlinx.coroutines.flow.first
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gluview.app.data.local.dao.AppLogDao
import com.gluview.app.data.local.entity.AppLogEntity
import com.gluview.app.util.AppLogger
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/**
 * F1/T08 — lista os logs do app (do Room, filtrados por dia/nível) e exporta os arquivos de
 * texto via compartilhamento.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LogViewModel @Inject constructor(
    private val appLogDao: AppLogDao,
    private val preferences: AppPreferences,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val zoneId = ZoneId.of("America/Sao_Paulo")

    fun today(): LocalDate = LocalDate.now(zoneId)

    // Fora da janela de retenção do AppLogger não existe dado (apagado) — nem deixa escolher.
    fun oldestSelectableDate(): LocalDate = today().minusDays((AppLogger.RETENTION_DAYS - 1).toLong())

    private val _selectedDate = MutableStateFlow(today())
    val selectedDate: StateFlow<LocalDate> = _selectedDate.asStateFlow()

    private val _errorsOnly = MutableStateFlow(false)
    val errorsOnly: StateFlow<Boolean> = _errorsOnly.asStateFlow()

    /** null = ainda carregando (antes nascia vazio e "Nenhum log" piscava antes do Room). */
    val logs: StateFlow<List<AppLogEntity>?> =
        combine(_selectedDate, _errorsOnly) { date, errorsOnly -> date to errorsOnly }
            .flatMapLatest { (date, errorsOnly) ->
                val startMs = date.atStartOfDay(zoneId).toInstant().toEpochMilli()
                val endMs = date.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
                appLogDao.observeByRange(startMs, endMs, errorsOnly)
            }
            .stateIn<List<AppLogEntity>?>(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // Tamanho total ocupado pelos arquivos de log em disco (mesma pasta lida por [export]).
    private val _logSizeBytes = MutableStateFlow(0L)
    val logSizeBytes: StateFlow<Long> = _logSizeBytes.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val logDir = context.getExternalFilesDir("logs") ?: File(context.filesDir, "logs")
            _logSizeBytes.value = logDir.listFiles()?.sumOf { it.length() } ?: 0L
        }
    }

    fun onDateSelected(date: LocalDate) {
        if (date.isAfter(today()) || date.isBefore(oldestSelectableDate())) return
        _selectedDate.value = date
    }

    fun onShiftDay(deltaDays: Long) {
        onDateSelected(_selectedDate.value.plusDays(deltaDays))
    }

    /** Falha ao exportar (antes engolida em silêncio): a tela mostra uma snackbar e limpa. */
    private val _exportFailed = MutableStateFlow(false)
    val exportFailed: StateFlow<Boolean> = _exportFailed.asStateFlow()
    fun clearExportFailed() { _exportFailed.value = false }

    fun onToggleErrorsOnly() {
        _errorsOnly.value = !_errorsOnly.value
    }

    /** Junta todos os arquivos de log de texto num único e abre o compartilhamento. */
    fun export() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                // Textos no idioma do app (no Android < 13 o contexto da aplicação fica no do sistema).
                val text = context.withAppLanguage(preferences.appLanguage.first())
                val logDir = context.getExternalFilesDir("logs") ?: File(context.filesDir, "logs")
                val dir = File(context.cacheDir, "exports").also { it.mkdirs() }
                val out = File(dir, "gluview_logs.txt")
                out.bufferedWriter().use { w ->
                    logDir.listFiles()
                        ?.filter { it.isFile }
                        ?.sortedBy { it.name }
                        ?.forEach { f ->
                            w.appendLine("===== ${f.name} =====")
                            f.forEachLine { w.appendLine(it) }
                        }
                }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", out)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, text.getString(R.string.applog_export_subject))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(
                    Intent.createChooser(intent, text.getString(R.string.applog_export)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            }.onFailure { _exportFailed.value = true }
        }
    }
}
