package com.gluview.app.ui.screen.accessrequests

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gluview.app.data.remote.dto.MyAccessRequestDto
import com.gluview.app.data.repository.AccessRequestRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MyAccessRequestsUiState(
    val requests: List<MyAccessRequestDto> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null
)

/** Achado 2026-09-26 — histórico completo das próprias candidaturas (bomba/sensor beta, e o
 *  próprio pedido de acesso ao app). Mesmo padrão de ExternalIntegrationViewModel: lista pequena,
 *  sem cache local (Room), busca de novo toda vez que a tela abre. */
@HiltViewModel
class MyAccessRequestsViewModel @Inject constructor(
    private val repo: AccessRequestRepository
) : ViewModel() {

    private val _ui = MutableStateFlow(MyAccessRequestsUiState())
    val ui: StateFlow<MyAccessRequestsUiState> = _ui.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _ui.update { it.copy(loading = true, error = null) }
            repo.getMyRequests()
                .onSuccess { list -> _ui.update { it.copy(requests = list, loading = false) } }
                // Só o detalhe técnico: a tela põe a frase traduzida.
                .onFailure { e -> _ui.update { it.copy(loading = false, error = e.message ?: e.javaClass.simpleName) } }
        }
    }
}
