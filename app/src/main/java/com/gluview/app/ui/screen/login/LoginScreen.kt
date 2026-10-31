package com.gluview.app.ui.screen.login

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.gluview.app.R
import com.gluview.app.ui.components.kit.CheckboxRow
import com.gluview.app.ui.components.kit.GluCallout
import com.gluview.app.ui.components.kit.GluCard
import com.gluview.app.ui.components.kit.SecretTextField
import com.gluview.app.ui.components.kit.Tone
import com.gluview.app.ui.theme.Spacing

/** Ações do formulário (tudo do [LoginViewModel]); separadas para a tela poder ser capturada sem ViewModel. */
internal class LoginActions(
    val onEmail: (String) -> Unit = {},
    val onPassword: (String) -> Unit = {},
    val onDisplayName: (String) -> Unit = {},
    val onNote: (String) -> Unit = {},
    val onCode: (String) -> Unit = {},
    val onNewPassword: (String) -> Unit = {},
    val onTogglePatient: () -> Unit = {},
    val onToggleFollower: () -> Unit = {},
    val onToggleMode: () -> Unit = {},
    val onSubmit: () -> Unit = {},
    val onConfirmCode: () -> Unit = {},
    val onResendCode: () -> Unit = {},
    val onCancelVerification: () -> Unit = {},
    val onOpenForgot: () -> Unit = {},
    val onSubmitForgot: () -> Unit = {},
    val onConfirmReset: () -> Unit = {},
    val onCancelForgot: () -> Unit = {},
    val onPasskey: () -> Unit = {},
    val onLocalOnly: () -> Unit = {},
    val onCheckStatus: () -> Unit = {}
)

@Composable
fun LoginScreen(
    onLoginSuccess: () -> Unit,
    onCheckAccessRequestStatus: () -> Unit,
    viewModel: LoginViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(uiState.success) {
        if (uiState.success) onLoginSuccess()
    }

    LoginContent(uiState, LoginActions(
        onEmail = viewModel::onEmailChange,
        onPassword = viewModel::onPasswordChange,
        onDisplayName = viewModel::onDisplayNameChange,
        onNote = viewModel::onNoteChange,
        onCode = { if (it.length <= 6) viewModel.onCodeChange(it) },
        onNewPassword = viewModel::onNewPasswordChange,
        onTogglePatient = viewModel::togglePatient,
        onToggleFollower = viewModel::toggleFollower,
        onToggleMode = viewModel::toggleMode,
        onSubmit = viewModel::submit,
        onConfirmCode = viewModel::confirmCode,
        onResendCode = viewModel::resendCode,
        onCancelVerification = viewModel::cancelVerification,
        onOpenForgot = viewModel::openForgotPassword,
        onSubmitForgot = viewModel::submitForgotPassword,
        onConfirmReset = viewModel::confirmPasswordReset,
        onCancelForgot = viewModel::cancelForgotPassword,
        onPasskey = { viewModel.loginWithPasskey(context) },
        onLocalOnly = viewModel::useLocalOnly,
        onCheckStatus = onCheckAccessRequestStatus
    ))
}

/**
 * Entrar, criar conta, pedir acesso, confirmar código e redefinir senha (redesign visual, Fase 8).
 * O que a pessoa está fazendo é o título do card (antes um subtítulo cinza sob um "GluView"
 * enorme); "Passo N de 2" no esqueci a senha; avisos e erros no topo do card em TODOS os ramos
 * (antes "Pedido enviado!" e "Novo código enviado" nunca apareciam), com anúncio ao TalkBack;
 * erro de validação no próprio campo; olho para mostrar a senha; perfis como caixas de marcar
 * (os chips quebravam com fonte grande); ações secundárias em largura total e o modo local num
 * card próprio, não um link depois do divisor.
 */
@Composable
internal fun LoginContent(state: LoginUiState, actions: LoginActions) {
    val verifying = state.pendingVerification || state.pendingAccessRequestVerification
    val main = !verifying && !state.isForgotPasswordMode && !state.pendingPasswordReset
    // Surface própria: fundo e cor de texto do tema mesmo fora do Scaffold (o "GluView" saía preto no escuro).
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
            // Rolagem + imePadding: no modo cadastro o formulário cresce e, com o teclado
            // aberto, os campos de baixo ficariam cortados sem isso.
            modifier = Modifier
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = Spacing.gutter, vertical = Spacing.lg)
        ) {
            BrandHeader()
            GluCard {
                Column(Modifier.padding(Spacing.xxs), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    when {
                        verifying -> VerifyForm(state, actions)
                        state.pendingPasswordReset -> ResetForm(state, actions)
                        state.isForgotPasswordMode -> ForgotForm(state, actions)
                        else -> MainForm(state, actions)
                    }
                }
            }
            if (main) {
                OutlinedButton(onClick = actions.onToggleMode, enabled = !state.isLoading, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(if (state.isRegisterMode) R.string.login_already_have_account else R.string.login_no_account),
                        textAlign = TextAlign.Center)
                }
                // Achado 2026-09-26 — quem pediu acesso ao app (modo approval) não tinha como
                // consultar o status depois de fechar a tela de verificação do código.
                // Também quando já existe um pedido guardado: a consulta da flag pode falhar (rede)
                // ou o modo ter mudado depois do pedido.
                if (state.registrationMode == "approval" || state.hasPendingAccessRequest) {
                    TextButton(onClick = actions.onCheckStatus, enabled = !state.isLoading, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.login_check_access_request_status), textAlign = TextAlign.Center)
                    }
                }
                // ADR-0030 — a conta é sempre o começo (grátis, uso local); a nuvem e o
                // acompanhamento são pedidos depois. "Agora não" só para quem já usava sem conta
                // antes desta versão: o app volta a perguntar depois de alguns dias.
                GluCard {
                    Text(stringResource(R.string.login_free_account_title), style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.semantics { heading() })
                    Text(stringResource(R.string.login_free_account_body), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (state.legacyLocalOnly) {
                        Text(stringResource(R.string.login_legacy_local_body), style = MaterialTheme.typography.bodyMedium)
                        OutlinedButton(onClick = actions.onLocalOnly, enabled = !state.isLoading, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.login_use_local_only), textAlign = TextAlign.Center)
                        }
                    }
                }
            }
        }
    }
    }
}

@Composable
private fun BrandHeader() {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Box(
            Modifier.size(56.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.WaterDrop, contentDescription = null, modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.onPrimary)
        }
        Text("GluView", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }
}

/** Título do card (+ passo e explicação) e os avisos do ramo. */
@Composable
private fun FormHeader(state: LoginUiState, title: String, subtitle: String?, step: Int? = null, onCheckStatus: () -> Unit = {}) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
        if (step != null) {
            Text(stringResource(R.string.login_step_of, step, 2), style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary)
        }
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        if (subtitle != null) {
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    state.infoMessage?.let {
        GluCallout(it.asString(), tone = Tone.Success, announce = true,
            actionLabel = if (state.accessRequestSent) stringResource(R.string.login_check_access_request_status) else null,
            onAction = if (state.accessRequestSent) onCheckStatus else null)
    }
    // Erro de validação aparece no campo; os demais (servidor, rede) aqui.
    if (state.errorField == null) state.error?.let { GluCallout(it.asString(), tone = Tone.Danger, announce = true) }
}

private fun LoginUiState.fieldError(field: LoginField) = if (errorField == field) error else null

@Composable
private fun EmailField(state: LoginUiState, onChange: (String) -> Unit, imeAction: ImeAction) {
    val err = state.fieldError(LoginField.EMAIL)
    OutlinedTextField(
        value = state.email,
        onValueChange = onChange,
        label = { Text(stringResource(R.string.login_email_label)) },
        singleLine = true,
        isError = err != null,
        supportingText = err?.let { { Text(it.asString()) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = imeAction),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun CodeField(state: LoginUiState, onChange: (String) -> Unit, imeAction: ImeAction) {
    val err = state.fieldError(LoginField.CODE)
    OutlinedTextField(
        value = state.verificationCode,
        onValueChange = onChange,
        label = { Text(stringResource(R.string.login_code_label)) },
        singleLine = true,
        isError = err != null,
        supportingText = err?.let { { Text(it.asString()) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = imeAction),
        modifier = Modifier.fillMaxWidth()
    )
}

/** Botão principal com o spinner no lugar do texto enquanto carrega. */
@Composable
private fun PrimaryButton(label: String, loading: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = !loading, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
        else Text(label, textAlign = TextAlign.Center)
    }
}

@Composable
private fun WideTextButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text(label, textAlign = TextAlign.Center) }
}

@Composable
private fun ColumnScope.VerifyForm(state: LoginUiState, actions: LoginActions) {
    FormHeader(state, stringResource(R.string.login_verify_title), stringResource(R.string.login_verify_subtitle, state.email))
    CodeField(state, actions.onCode, ImeAction.Done)
    PrimaryButton(stringResource(R.string.login_confirm_code), state.isLoading, actions.onConfirmCode)
    // ADR-0029 — reenviar código só existe pro fluxo de conta (open); a
    // candidatura de acesso (approval) ainda não tem esse endpoint.
    if (!state.pendingAccessRequestVerification) {
        WideTextButton(stringResource(R.string.login_resend_code), !state.isLoading, actions.onResendCode)
    }
    WideTextButton(stringResource(R.string.login_back), !state.isLoading, actions.onCancelVerification)
}

@Composable
private fun ColumnScope.ResetForm(state: LoginUiState, actions: LoginActions) {
    FormHeader(state, stringResource(R.string.login_reset_password_title), stringResource(R.string.login_verify_subtitle, state.email), step = 2)
    CodeField(state, actions.onCode, ImeAction.Next)
    val err = state.fieldError(LoginField.NEW_PASSWORD)
    SecretTextField(state.newPassword, actions.onNewPassword, stringResource(R.string.login_new_password_label),
        supportingText = err?.asString() ?: stringResource(R.string.login_password_rule), isError = err != null)
    PrimaryButton(stringResource(R.string.login_reset_password_button), state.isLoading, actions.onConfirmReset)
    WideTextButton(stringResource(R.string.login_resend_code), !state.isLoading, actions.onResendCode)
    WideTextButton(stringResource(R.string.login_back), !state.isLoading, actions.onCancelForgot)
}

@Composable
private fun ColumnScope.ForgotForm(state: LoginUiState, actions: LoginActions) {
    FormHeader(state, stringResource(R.string.login_forgot_password), stringResource(R.string.login_forgot_password_subtitle), step = 1)
    EmailField(state, actions.onEmail, ImeAction.Done)
    PrimaryButton(stringResource(R.string.login_send_code), state.isLoading, actions.onSubmitForgot)
    WideTextButton(stringResource(R.string.login_back), !state.isLoading, actions.onCancelForgot)
}

@Composable
private fun ColumnScope.MainForm(state: LoginUiState, actions: LoginActions) {
    // ADR-0029 — modo approval não pede senha aqui: a conta só existe depois de
    // aprovada + convite resgatado no portal (a pessoa define a senha lá).
    val isAccessRequestSignup = state.isRegisterMode && state.registrationMode == "approval"
    FormHeader(
        state,
        title = stringResource(when {
            isAccessRequestSignup -> R.string.login_access_request_title
            state.isRegisterMode -> R.string.login_create_account
            else -> R.string.login_enter
        }),
        subtitle = stringResource(when {
            isAccessRequestSignup -> R.string.login_access_request_help
            state.isRegisterMode -> R.string.login_register_subtitle
            else -> R.string.login_enter_help
        }),
        onCheckStatus = actions.onCheckStatus
    )
    EmailField(state, actions.onEmail, ImeAction.Next)

    // O modo do cadastro chega da rede depois da tela: o campo entra/sai animado em vez de pular.
    AnimatedVisibility(visible = !isAccessRequestSignup) {
        val err = state.fieldError(LoginField.PASSWORD)
        SecretTextField(state.password, actions.onPassword, stringResource(R.string.login_password_label),
            supportingText = err?.asString() ?: if (state.isRegisterMode) stringResource(R.string.login_password_rule) else null,
            isError = err != null)
    }

    // Só faz sentido pra quem já tem conta — some no modo cadastro.
    if (!state.isRegisterMode) {
        TextButton(onClick = actions.onOpenForgot, enabled = !state.isLoading, modifier = Modifier.align(Alignment.End)) {
            Text(stringResource(R.string.login_forgot_password))
        }
    }

    // F4 — campos extras no modo cadastro: nome + perfis combináveis
    if (state.isRegisterMode) {
        OutlinedTextField(
            value = state.displayName,
            onValueChange = actions.onDisplayName,
            label = { Text(stringResource(R.string.login_name_optional_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Column {
            Text(stringResource(R.string.login_you_are), style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.semantics { heading() })
            CheckboxRow(stringResource(R.string.role_patient), state.isPatient, { actions.onTogglePatient() },
                description = stringResource(R.string.login_role_patient_desc))
            CheckboxRow(stringResource(R.string.role_follower), state.isFollower, { actions.onToggleFollower() },
                description = stringResource(R.string.login_role_follower_desc))
            val err = state.fieldError(LoginField.ROLES)
            Text(err?.asString() ?: stringResource(R.string.login_roles_both), style = MaterialTheme.typography.bodySmall,
                color = if (err != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        AnimatedVisibility(visible = isAccessRequestSignup) {
            OutlinedTextField(
                value = state.note,
                onValueChange = actions.onNote,
                label = { Text(stringResource(R.string.login_access_request_note_label)) },
                singleLine = false,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    PrimaryButton(
        stringResource(when {
            isAccessRequestSignup -> R.string.login_access_request_submit
            state.isRegisterMode -> R.string.login_create_account
            else -> R.string.login_enter
        }),
        state.isLoading, actions.onSubmit
    )

    // T85 — Passkeys (beta): só aparece se a flag estiver ligada neste
    // aparelho (Configurações > Segurança) e no modo "Entrar" (não faz
    // sentido cadastrar via passkey antes de a conta existir).
    if (!state.isRegisterMode && state.passkeysAvailable) {
        OutlinedButton(
            onClick = actions.onPasskey,
            enabled = !state.isLoading && !state.passkeyLoading,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
        ) {
            if (state.passkeyLoading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Text(stringResource(R.string.login_with_passkey), textAlign = TextAlign.Center)
        }
    }
}
