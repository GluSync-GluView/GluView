package com.gluview.app.ui.screen.apikeys

import com.gluview.app.R
import com.gluview.app.util.UiText
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gluview.app.data.remote.dto.ApiKeyInfoDto
import com.gluview.app.data.remote.dto.CreatedApiKeyDto
import com.gluview.app.data.repository.ApiKeyRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ApiKeysUiState(
    val keys: List<ApiKeyInfoDto> = emptyList(),
    val loading: Boolean = true,
    val error: UiText? = null,
    val errorDetail: String? = null,
    val createdKey: CreatedApiKeyDto? = null,
    val message: UiText? = null
)

@HiltViewModel
class ApiKeysViewModel @Inject constructor(
    private val repo: ApiKeyRepository
) : ViewModel() {

    private val _ui = MutableStateFlow(ApiKeysUiState())
    val ui: StateFlow<ApiKeysUiState> = _ui.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _ui.update { it.copy(loading = true, error = null, errorDetail = null) }
            runCatching { repo.list() }
                .onSuccess { list -> _ui.update { it.copy(keys = list, loading = false) } }
                .onFailure { e -> _ui.update { it.copy(loading = false, error = friendly(e), errorDetail = e.message) } }
        }
    }

    fun create(name: String, read: Boolean, write: Boolean) {
        viewModelScope.launch {
            val scopes = buildList {
                if (read) add("glucose:read")
                if (write) add("glucose:write")
            }
            runCatching { repo.create(name.trim().ifBlank { "Integracao" }, scopes) }
                .onSuccess { created ->
                    _ui.update { it.copy(createdKey = created) }
                    refresh()
                }
                .onFailure { _ui.update { it.copy(message = UiText.Res(R.string.apikeys_create_failed)) } }
        }
    }

    fun revoke(id: String) {
        viewModelScope.launch {
            runCatching { repo.revoke(id) }
                .onSuccess { ok ->
                    _ui.update { it.copy(message = UiText.Res(if (ok) R.string.apikeys_revoked_msg else R.string.apikeys_revoke_failed)) }
                    refresh()
                }
                .onFailure { e -> _ui.update { it.copy(message = friendly(e)) } }
        }
    }

    fun dismissCreated() = _ui.update { it.copy(createdKey = null) }
    fun clearMessage() = _ui.update { it.copy(message = null) }

    private fun friendly(e: Throwable): UiText = when {
        e.message?.contains("401") == true || e.message?.contains("403") == true -> UiText.Res(R.string.apikeys_need_login)
        else -> UiText.Res(R.string.history_load_error_body)
    }
}
