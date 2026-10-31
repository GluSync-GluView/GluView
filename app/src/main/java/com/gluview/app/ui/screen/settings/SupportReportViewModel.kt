package com.gluview.app.ui.screen.settings

import android.content.Context
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gluview.app.BuildConfig
import com.gluview.app.data.local.dao.AppLogDao
import com.gluview.app.data.repository.AppConfigRepository
import com.gluview.app.data.repository.SupportReportRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

data class SupportReportUiState(
    val enabled: Boolean = false,
    val sending: Boolean = false,
    val sent: Boolean = false,
    val error: String? = null
)

/** Botão "Enviar log para o suporte" (Ajustes) — grava no banco + notifica por e-mail (sem o
 *  log completo — ver SupportReportService/EmailService no GluSync). Reaproveita o mesmo Room
 *  (AppLogDao) já usado pela tela de Logs (LogViewModel), sem duplicar a captura de log. */
@HiltViewModel
class SupportReportViewModel @Inject constructor(
    private val appLogDao: AppLogDao,
    private val supportReportRepository: SupportReportRepository,
    private val appConfigRepository: AppConfigRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(SupportReportUiState())
    val uiState: StateFlow<SupportReportUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            appConfigRepository.getFeatureFlags().onSuccess { flags ->
                _uiState.value = _uiState.value.copy(enabled = flags.supportLogEnabled)
            }
        }
    }

    fun send(userMessage: String?) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(sending = true, error = null)
            val logText = buildLogText()
            val result = supportReportRepository.send(
                logText     = logText,
                userMessage = userMessage?.takeIf { it.isNotBlank() },
                appVersion  = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                deviceInfo  = "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
            )
            _uiState.value = result.fold(
                onSuccess = { _uiState.value.copy(sending = false, sent = true) },
                onFailure = { e -> _uiState.value.copy(sending = false, error = e.message ?: "Erro ao conectar ao servidor") }
            )
        }
    }

    fun dismiss() {
        _uiState.value = _uiState.value.copy(sending = false, sent = false, error = null)
    }

    private suspend fun buildLogText(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
        return appLogDao.observeRecent(limit = 500).first().reversed().joinToString("\n") { entry ->
            "${fmt.format(Date(entry.ts))} [${entry.level}] ${entry.tag} ${entry.origin} — ${entry.msg}"
        }
    }
}
