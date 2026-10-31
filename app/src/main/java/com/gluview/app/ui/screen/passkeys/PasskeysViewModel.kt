package com.gluview.app.ui.screen.passkeys

import com.gluview.app.R
import com.gluview.app.util.UiText
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gluview.app.data.remote.dto.PasskeyInfoDto
import com.gluview.app.data.repository.AuthRepository
import com.gluview.app.util.PasskeyManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PasskeysUiState(
    val passkeys: List<PasskeyInfoDto> = emptyList(),
    val loading: Boolean = true,
    val registering: Boolean = false,
    val error: UiText? = null,
    val errorDetail: String? = null,
    val message: UiText? = null
)

@HiltViewModel
class PasskeysViewModel @Inject constructor(
    private val authRepo: AuthRepository,
    private val passkeyManager: PasskeyManager
) : ViewModel() {

    private val _ui = MutableStateFlow(PasskeysUiState())
    val ui: StateFlow<PasskeysUiState> = _ui.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _ui.update { it.copy(loading = true, error = null, errorDetail = null) }
            authRepo.listPasskeys()
                .onSuccess { list -> _ui.update { it.copy(passkeys = list, loading = false) } }
                .onFailure { e -> _ui.update { it.copy(loading = false, error = UiText.Res(R.string.history_load_error_body), errorDetail = e.message) } }
        }
    }

    /**
     * Orquestra o cadastro: opções do servidor -> Credential Manager (precisa do Context da
     * Activity, por isso vem do Composable) -> verifica no servidor. Mesma divisão de
     * responsabilidade do BiometricPrompt em SettingsScreen (a UI do sistema é acionada perto
     * de quem tem o Context de Activity, não guardada dentro do ViewModel).
     */
    fun registerPasskey(context: Context, label: String) {
        if (label.isBlank()) {
            _ui.update { it.copy(message = UiText.Res(R.string.passkeys_label_required)) }
            return
        }
        viewModelScope.launch {
            _ui.update { it.copy(registering = true, message = null) }
            val result = runCatching {
                val optionsJson = authRepo.beginPasskeyRegistration().getOrThrow()
                val credentialJson = passkeyManager.createPasskey(context, optionsJson)
                    ?: return@runCatching false // usuário cancelou o prompt — não é erro
                authRepo.finishPasskeyRegistration(credentialJson, label.trim()).getOrThrow()
                true
            }
            result.onSuccess { created ->
                _ui.update { it.copy(registering = false, message = if (created) UiText.Res(R.string.passkeys_registered) else null) }
                if (created) refresh()
            }.onFailure {
                _ui.update { it.copy(registering = false, message = UiText.Res(R.string.passkeys_register_failed)) }
            }
        }
    }

    fun revoke(id: String) {
        viewModelScope.launch {
            authRepo.revokePasskey(id)
                .onSuccess { _ui.update { it.copy(message = UiText.Res(R.string.passkeys_removed)) }; refresh() }
                .onFailure { _ui.update { it.copy(message = UiText.Res(R.string.passkeys_remove_failed)) } }
        }
    }

    fun clearMessage() = _ui.update { it.copy(message = null) }
}
