package com.gluview.app.ui.screen.integrations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gluview.app.R
import com.gluview.app.data.remote.dto.IntegrationStatusDto
import com.gluview.app.util.UiText
import com.gluview.app.data.repository.IntegrationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ExternalIntegrationUiState(
    val integrations: List<IntegrationStatusDto> = emptyList(),
    val loading: Boolean = true,
    /** Erro da lista (mensagem traduzida + detalhe técnico). */
    val error: UiText? = null,
    val errorDetail: String? = null,
    val message: UiText? = null,
    val connecting: Boolean = false
)

@HiltViewModel
class ExternalIntegrationViewModel @Inject constructor(
    private val repo: IntegrationRepository
) : ViewModel() {

    private val _ui = MutableStateFlow(ExternalIntegrationUiState())
    val ui: StateFlow<ExternalIntegrationUiState> = _ui.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _ui.update { it.copy(loading = true, error = null, errorDetail = null) }
            runCatching { repo.list() }
                .onSuccess { list -> _ui.update { it.copy(integrations = list, loading = false) } }
                .onFailure { e  -> _ui.update { it.copy(loading = false, error = friendly(e), errorDetail = e.message) } }
        }
    }

    fun connect(provider: String, username: String, password: String, region: String?) {
        viewModelScope.launch {
            _ui.update { it.copy(connecting = true) }
            runCatching { repo.connect(provider, username.trim(), password, region) }
                .onSuccess { ok ->
                    _ui.update { it.copy(connecting = false, message = UiText.Res(if (ok) R.string.integrations_connected else R.string.integrations_rejected)) }
                    if (ok) refresh()
                }
                .onFailure { e ->
                    _ui.update { it.copy(connecting = false, message = friendly(e)) }
                }
        }
    }

    fun disconnect(provider: String) {
        viewModelScope.launch {
            runCatching { repo.disconnect(provider) }
                .onSuccess { ok ->
                    _ui.update { it.copy(message = UiText.Res(if (ok) R.string.integrations_disconnected else R.string.integrations_disconnect_failed)) }
                    refresh()
                }
                .onFailure { e -> _ui.update { it.copy(message = friendly(e)) } }
        }
    }

    fun clearMessage() = _ui.update { it.copy(message = null) }

    private fun friendly(e: Throwable): UiText = when {
        e.message?.contains("401") == true || e.message?.contains("403") == true -> UiText.Res(R.string.integrations_need_login)
        else -> UiText.Res(R.string.history_load_error_body)
    }
}
