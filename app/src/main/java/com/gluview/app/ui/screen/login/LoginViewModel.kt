package com.gluview.app.ui.screen.login

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gluview.app.R
import com.gluview.app.data.datastore.AppPreferences
import com.gluview.app.data.repository.AccessRequestRepository
import com.gluview.app.data.repository.AppConfigRepository
import com.gluview.app.data.repository.AuthRepository
import com.gluview.app.util.GluSyncConstants
import com.gluview.app.util.PasskeyManager
import com.gluview.app.util.UiText
import com.gluview.app.util.toUiText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Campo que recebe o erro de validação (borda vermelha + texto embaixo dele). */
enum class LoginField { EMAIL, PASSWORD, NEW_PASSWORD, CODE, ROLES }

data class LoginUiState(
    val baseUrl: String    = GluSyncConstants.BASE_URL,
    val email: String      = "",
    val password: String   = "",
    val isLoading: Boolean = false,
    val error: UiText?     = null,
    /** Erro de validação de um campo: aparece no campo, não no aviso do card. */
    val errorField: LoginField? = null,
    val success: Boolean   = false,
    // F4 — cadastro
    val isRegisterMode: Boolean = false,
    val displayName: String     = "",
    val isPatient: Boolean      = true,
    val isFollower: Boolean     = false,
    // Verificação de e-mail (anti-robô) — cadastro só loga depois de confirmar o código.
    val pendingVerification: Boolean = false,
    val verificationCode: String     = "",
    val infoMessage: UiText?         = null,
    /** Candidatura confirmada: o aviso de sucesso oferece "Ver status do pedido". */
    val accessRequestSent: Boolean   = false,
    /** Há um pedido de acesso guardado no aparelho — "ver status" aparece mesmo sem a flag carregar. */
    val hasPendingAccessRequest: Boolean = false,
    // Esqueci minha senha — mesmo mecanismo de código de 6 dígitos.
    val isForgotPasswordMode: Boolean = false,
    val pendingPasswordReset: Boolean = false,
    val newPassword: String           = "",
    // T85 — Passkeys (beta): lido do DataStore local, funciona mesmo deslogado.
    val passkeysAvailable: Boolean = false,
    val passkeyLoading: Boolean    = false,
    // ADR-0029 — "open" preserva 100% o comportamento de sempre (cadastro cria conta direto).
    // "approval" faz o cadastro virar uma candidatura (POST /access-requests) em vez de conta.
    val registrationMode: String = "open",
    val note: String = "",
    // Confirmação de e-mail de uma CANDIDATURA (não conta — ver AccessRequestRepository) — usa a
    // MESMA tela de código que pendingVerification, só o destino da confirmação muda.
    val pendingAccessRequestVerification: Boolean = false,
    val accessRequestId: String? = null,
    /** Instalação antiga que usava o app sem conta (modo local): pode adiar a criação da conta. */
    val legacyLocalOnly: Boolean = false
)

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val accessRequestRepository: AccessRequestRepository,
    private val appConfigRepository: AppConfigRepository,
    private val preferences: AppPreferences,
    private val passkeyManager: PasskeyManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(passkeysAvailable = preferences.passkeysEnabled.first())
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(legacyLocalOnly = preferences.localOnlyMode.first())
        }
        viewModelScope.launch {
            preferences.pendingAppAccessRequestId.collect { id ->
                _uiState.value = _uiState.value.copy(hasPendingAccessRequest = id.isNotBlank())
            }
        }
        // ADR-0029 — decide qual formulário de cadastro mostrar (com senha vs. candidatura).
        // Falha de rede aqui não impede nada: fica em "open" (comportamento de sempre) até
        // conseguir consultar; o pior caso é mostrar o formulário de sempre por mais tempo.
        viewModelScope.launch {
            appConfigRepository.getFeatureFlags(_uiState.value.baseUrl).onSuccess { flags ->
                _uiState.value = _uiState.value.copy(registrationMode = flags.registrationMode)
            }
        }
    }

    fun onEmailChange(value: String)    { _uiState.value = _uiState.value.copy(email    = value, error = null, errorField = null) }
    fun onPasswordChange(value: String) { _uiState.value = _uiState.value.copy(password = value, error = null, errorField = null) }
    fun onDisplayNameChange(v: String)  { _uiState.value = _uiState.value.copy(displayName = v, error = null, errorField = null) }
    fun togglePatient()                 { _uiState.value = _uiState.value.copy(isPatient = !_uiState.value.isPatient, error = null, errorField = null) }
    fun toggleFollower()                { _uiState.value = _uiState.value.copy(isFollower = !_uiState.value.isFollower, error = null, errorField = null) }
    fun toggleMode()                    { _uiState.value = _uiState.value.copy(isRegisterMode = !_uiState.value.isRegisterMode, error = null, errorField = null) }
    fun onCodeChange(value: String)     { _uiState.value = _uiState.value.copy(verificationCode = value, error = null, errorField = null, infoMessage = null) }
    fun onNewPasswordChange(value: String) { _uiState.value = _uiState.value.copy(newPassword = value, error = null, errorField = null) }
    fun onNoteChange(value: String)     { _uiState.value = _uiState.value.copy(note = value, error = null, errorField = null) }

    private fun invalid(state: LoginUiState, field: LoginField, msg: Int) =
        state.copy(error = UiText.Res(msg), errorField = field)

    /** Sai da tela de código de volta pro formulário de entrar/cadastrar. */
    fun cancelVerification() {
        _uiState.value = _uiState.value.copy(
            pendingVerification = false, pendingAccessRequestVerification = false, accessRequestId = null,
            verificationCode = "", error = null, errorField = null, infoMessage = null
        )
    }

    fun openForgotPassword() {
        _uiState.value = _uiState.value.copy(isForgotPasswordMode = true, error = null, errorField = null,
            infoMessage = null, accessRequestSent = false)
    }

    /** Sai do fluxo de "esqueci minha senha" (de qualquer um dos 2 passos) de volta ao login. */
    fun cancelForgotPassword() {
        _uiState.value = _uiState.value.copy(
            isForgotPasswordMode = false, pendingPasswordReset = false,
            verificationCode = "", newPassword = "", error = null, errorField = null, infoMessage = null
        )
    }

    private fun rolesFromState(state: LoginUiState) = buildList {
        if (state.isPatient) add("patient")
        if (state.isFollower) add("follower")
    }.ifEmpty { listOf("patient") }

    fun submit() {
        val state = _uiState.value
        // ADR-0029 — modo approval não pede senha (a conta só existe depois de aprovada +
        // convite resgatado no portal) — só e-mail.
        if (state.isRegisterMode && state.registrationMode == "approval") {
            if (state.email.isBlank()) {
                _uiState.value = invalid(state, LoginField.EMAIL, R.string.login_error_fill_email)
                return
            }
            submitAccessRequest(state)
            return
        }
        if (state.email.isBlank()) {
            _uiState.value = invalid(state, LoginField.EMAIL, R.string.login_error_fill_email)
            return
        }
        if (state.password.isBlank()) {
            _uiState.value = invalid(state, LoginField.PASSWORD, R.string.login_error_fill_password)
            return
        }
        if (state.isRegisterMode) register(state) else login(state)
    }

    private fun login(state: LoginUiState) {
        viewModelScope.launch {
            _uiState.value = state.copy(isLoading = true, error = null, errorField = null, infoMessage = null, accessRequestSent = false)
            val result = authRepository.login(state.baseUrl, state.email, state.password)
            val failure = result.exceptionOrNull()
            // Bloqueado por e-mail não verificado (mensagem vem do servidor) — manda direto
            // pra tela de código em vez de só mostrar um erro sem saída.
            if (failure != null && failure.message?.contains("verificado", ignoreCase = true) == true) {
                _uiState.value = _uiState.value.copy(isLoading = false, error = failure.toUiText(), pendingVerification = true)
            } else {
                finish(result)
            }
        }
    }

    private fun register(state: LoginUiState) {
        if (state.password.length < 8) {
            _uiState.value = invalid(state, LoginField.PASSWORD, R.string.login_error_password_short)
            return
        }
        val roles = rolesFromState(state)
        if (!state.isPatient && !state.isFollower) {
            _uiState.value = invalid(state, LoginField.ROLES, R.string.login_error_pick_role)
            return
        }
        viewModelScope.launch {
            _uiState.value = state.copy(isLoading = true, error = null, errorField = null)
            val result = authRepository.register(
                state.baseUrl, state.email, state.password, state.displayName, roles
            )
            _uiState.value = if (result.isSuccess) {
                _uiState.value.copy(isLoading = false, pendingVerification = true, error = null)
            } else {
                _uiState.value.copy(
                    isLoading = false,
                    error     = result.exceptionOrNull()?.toUiText() ?: UiText.Res(R.string.error_generic)
                )
            }
        }
    }

    // ADR-0029 — candidatura de acesso (feature_key=APP). Diferente de register(): não cria
    // conta, cria um pedido que só vira conta depois de aprovado + convite resgatado no portal.
    private fun submitAccessRequest(state: LoginUiState) {
        val roles = rolesFromState(state)
        if (!state.isPatient && !state.isFollower) {
            _uiState.value = invalid(state, LoginField.ROLES, R.string.login_error_pick_role)
            return
        }
        viewModelScope.launch {
            _uiState.value = state.copy(isLoading = true, error = null, errorField = null)
            val result = accessRequestRepository.createAccessRequest(
                state.baseUrl, state.email, state.displayName.ifBlank { null }, roles, state.note.ifBlank { null }
            )
            _uiState.value = result.fold(
                onSuccess = { response ->
                    // Persiste além do LoginUiState (perdido ao fechar o app) — é o que permite
                    // abrir AccessRequestStatusScreen depois, mesmo em outra sessão do app.
                    viewModelScope.launch { preferences.savePendingAppAccessRequestId(response.id) }
                    _uiState.value.copy(
                        isLoading = false, error = null,
                        pendingAccessRequestVerification = true, accessRequestId = response.id
                    )
                },
                onFailure = { e ->
                    _uiState.value.copy(isLoading = false, error = e.toUiText())
                }
            )
        }
    }

    /** Confirma o código de 6 dígitos — só aqui a conta realmente autentica (modo "open") ou só
     *  aqui a candidatura de acesso prova posse do e-mail (modo "approval" — não loga, a conta
     *  ainda não existe; ver ActivationInviteService/ActivateAccountPage no portal). */
    fun confirmCode() {
        val state = _uiState.value
        if (state.verificationCode.length != 6 || state.verificationCode.any { !it.isDigit() }) {
            _uiState.value = invalid(state, LoginField.CODE, R.string.login_error_code)
            return
        }
        if (state.pendingAccessRequestVerification) {
            confirmAccessRequestCode(state)
            return
        }
        viewModelScope.launch {
            _uiState.value = state.copy(isLoading = true, error = null)
            val result = authRepository.verifyEmail(state.email, state.verificationCode, rolesFromState(state))
            finish(result)
        }
    }

    private fun confirmAccessRequestCode(state: LoginUiState) {
        val accessRequestId = state.accessRequestId
        if (accessRequestId == null) {
            _uiState.value = state.copy(error = UiText.Res(R.string.error_generic))
            return
        }
        viewModelScope.launch {
            _uiState.value = state.copy(isLoading = true, error = null)
            val result = accessRequestRepository.verifyAccessRequest(accessRequestId, state.verificationCode)
            _uiState.value = result.fold(
                onSuccess = {
                    // Sucesso aqui NÃO é login — o pedido só vira conta depois do admin aprovar e
                    // o convite ser resgatado (portal). Volta pro formulário de entrar avisando.
                    _uiState.value.copy(
                        isLoading = false, error = null,
                        pendingAccessRequestVerification = false, accessRequestId = null,
                        isRegisterMode = false, verificationCode = "",
                        infoMessage = UiText.Res(R.string.login_access_request_sent), accessRequestSent = true
                    )
                },
                onFailure = { e ->
                    _uiState.value.copy(isLoading = false, error = e.toUiText())
                }
            )
        }
    }

    fun resendCode() {
        val state = _uiState.value
        viewModelScope.launch {
            _uiState.value = state.copy(isLoading = true, error = null, infoMessage = null)
            val result = authRepository.resendVerification(state.email)
            _uiState.value = _uiState.value.copy(
                isLoading   = false,
                error       = result.exceptionOrNull()?.toUiText(),
                infoMessage = if (result.isSuccess) UiText.Res(R.string.login_code_resent) else null
            )
        }
    }

    /** Passo 1 do "esqueci minha senha": pede o e-mail e envia o código. */
    fun submitForgotPassword() {
        val state = _uiState.value
        if (state.email.isBlank()) {
            _uiState.value = invalid(state, LoginField.EMAIL, R.string.login_error_fill_email)
            return
        }
        viewModelScope.launch {
            _uiState.value = state.copy(isLoading = true, error = null)
            val result = authRepository.forgotPassword(state.email)
            _uiState.value = if (result.isSuccess) {
                _uiState.value.copy(isLoading = false, pendingPasswordReset = true)
            } else {
                _uiState.value.copy(
                    isLoading = false,
                    error     = result.exceptionOrNull()?.toUiText() ?: UiText.Res(R.string.error_generic)
                )
            }
        }
    }

    /** Passo 2: confirma o código + nova senha — já loga em seguida. */
    fun confirmPasswordReset() {
        val state = _uiState.value
        if (state.verificationCode.length != 6 || state.verificationCode.any { !it.isDigit() }) {
            _uiState.value = invalid(state, LoginField.CODE, R.string.login_error_code)
            return
        }
        if (state.newPassword.length < 8) {
            _uiState.value = invalid(state, LoginField.NEW_PASSWORD, R.string.login_error_password_short)
            return
        }
        viewModelScope.launch {
            _uiState.value = state.copy(isLoading = true, error = null)
            val result = authRepository.resetPassword(state.email, state.verificationCode, state.newPassword)
            finish(result)
        }
    }

    /** Login sem senha: opções do servidor -> Credential Manager -> verifica no servidor.
     *  Não pede e-mail (fluxo "discoverable credential" — o Android mostra as passkeys
     *  salvas pro domínio, e o servidor identifica o usuário pelo credential_id). */
    fun loginWithPasskey(context: Context) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(passkeyLoading = true, error = null)
            val result = runCatching {
                val optionsJson = authRepository.beginPasskeyLogin().getOrThrow()
                val credentialJson = passkeyManager.getPasskey(context, optionsJson)
                    ?: return@runCatching false // usuário cancelou o prompt — não é erro
                authRepository.finishPasskeyLogin(_uiState.value.baseUrl, credentialJson).getOrThrow()
                true
            }
            result.onSuccess { loggedIn ->
                _uiState.value = _uiState.value.copy(passkeyLoading = false, success = loggedIn)
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(
                    passkeyLoading = false,
                    error = e.toUiText()
                )
            }
        }
    }

    /**
     * "Agora não" — só para instalações antigas que já usavam o app sem conta (ADR-0030: conta nova
     * é sempre obrigatória). Continua no modo local e adia a pergunta (ver MainActivity).
     */
    fun useLocalOnly() {
        if (!_uiState.value.legacyLocalOnly) return
        viewModelScope.launch {
            preferences.saveAccountPromptPostponedAtMs(System.currentTimeMillis())
            _uiState.value = _uiState.value.copy(success = true)
        }
    }

    private fun finish(result: Result<Unit>) {
        val state = _uiState.value
        _uiState.value = if (result.isSuccess) {
            state.copy(isLoading = false, success = true)
        } else {
            state.copy(
                isLoading = false,
                error     = result.exceptionOrNull()?.toUiText() ?: UiText.Res(R.string.error_generic)
            )
        }
    }
}
