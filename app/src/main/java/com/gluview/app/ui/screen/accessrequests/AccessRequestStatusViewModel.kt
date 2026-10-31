package com.gluview.app.ui.screen.accessrequests

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gluview.app.data.datastore.AppPreferences
import com.gluview.app.data.remote.dto.MyAccessRequestDto
import com.gluview.app.data.repository.AccessRequestRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AccessRequestStatusUiState(
    val loading: Boolean = true,
    val request: MyAccessRequestDto? = null,
    // true = sem ID salvo neste aparelho (nunca pediu, ou já limpou); false = ID existe mas a
    // consulta falhou (rede/servidor) — mensagens diferentes pra cada caso.
    val notFound: Boolean = false,
    val error: String? = null
)

/** Achado 2026-09-26 — quem pediu acesso ao app (ainda sem conta) não tinha NENHUMA forma de
 *  consultar o status depois de fechar a tela de verificação. O ID (AppPreferences.
 *  pendingAppAccessRequestId, gravado por LoginViewModel) é a única chave — pública, mas de alta
 *  entropia, mesmo modelo de confiança do /access-requests/verify. */
@HiltViewModel
class AccessRequestStatusViewModel @Inject constructor(
    private val repo: AccessRequestRepository,
    private val preferences: AppPreferences
) : ViewModel() {

    private val _ui = MutableStateFlow(AccessRequestStatusUiState())
    val ui: StateFlow<AccessRequestStatusUiState> = _ui.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _ui.update { it.copy(loading = true, error = null, notFound = false) }
            val id = preferences.pendingAppAccessRequestId.first()
            if (id.isBlank()) {
                _ui.update { it.copy(loading = false, notFound = true) }
                return@launch
            }
            repo.getPublicStatus(id)
                .onSuccess { req -> _ui.update { it.copy(loading = false, request = req) } }
                .onFailure { e ->
                    val is404 = e.message?.contains("404") == true
                    _ui.update { it.copy(loading = false, notFound = is404, error = if (is404) null else e.message) }
                }
        }
    }
}
