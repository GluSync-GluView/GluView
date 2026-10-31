package com.gluview.app.ui.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.gluview.app.R
import com.gluview.app.ui.components.kit.Fact
import com.gluview.app.ui.components.kit.FactList
import com.gluview.app.ui.components.kit.GluCallout
import com.gluview.app.ui.components.kit.GluCard
import com.gluview.app.ui.components.kit.GluSection
import com.gluview.app.ui.components.kit.NavRow
import com.gluview.app.ui.components.kit.Page
import com.gluview.app.ui.components.kit.RowGroup
import com.gluview.app.ui.components.kit.SecretTextField
import com.gluview.app.ui.components.kit.SwitchRow
import com.gluview.app.ui.components.kit.Tone
import com.gluview.app.ui.theme.Spacing
import com.gluview.app.util.breakableEmail

/** Estado da Conta (para o harness desenhar sem ViewModel). */
internal data class AccountUi(
    val email: String,
    val baseUrl: String,
    val displayName: String,
    val displayNameSaving: Boolean,
    val displayNameSaved: Boolean,
    val passwordCodeSent: Boolean,
    val passwordCode: String,
    val newPassword: String,
    val passwordLoading: Boolean,
    val editableBaseUrl: String?,
    val passkeysEnabled: Boolean,
    val displayNameLoadFailed: Boolean = false,
    /** ADR-0030 — liberação da nuvem/acompanhamento: approved|pending|waitlisted|rejected|revoked|none|"" */
    val cloudSyncState: String = "",
    val cloudSyncRequesting: Boolean = false
)

internal data class AccountActions(
    val onDisplayName: (String) -> Unit,
    val onSaveDisplayName: () -> Unit,
    val onRequestCode: () -> Unit,
    val onCode: (String) -> Unit,
    val onNewPassword: (String) -> Unit,
    val onConfirmPassword: () -> Unit,
    val onCancelPassword: () -> Unit,
    val onBaseUrl: (String) -> Unit,
    val onSaveBaseUrl: () -> Unit,
    val onPasskeysEnabled: (Boolean) -> Unit,
    val onPasskeys: () -> Unit,
    val onMyAccessRequests: () -> Unit,
    val onRequestCloudSync: () -> Unit = {}
)

/** Conta: perfil, senha, chaves de acesso, candidaturas e o servidor. */
@Composable
fun AccountSettingsScreen(
    onBack: () -> Unit,
    onPasskeys: () -> Unit,
    onMyAccessRequests: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val displayName        by viewModel.displayName.collectAsState()
    val displayNameSaving  by viewModel.displayNameSaving.collectAsState()
    val displayNameSaved   by viewModel.displayNameSaved.collectAsState()
    val displayNameMessage by viewModel.displayNameMessage.collectAsState()
    val displayNameLoadFailed by viewModel.displayNameLoadFailed.collectAsState()
    val passwordCodeSent      by viewModel.passwordCodeSent.collectAsState()
    val passwordCode          by viewModel.passwordCode.collectAsState()
    val newAccountPassword    by viewModel.newAccountPassword.collectAsState()
    val passwordChangeLoading by viewModel.passwordChangeLoading.collectAsState()
    val editableBaseUrl by viewModel.editableBaseUrl.collectAsState()
    val accountMessage  by viewModel.accountMessage.collectAsState()
    val passkeysEnabled by viewModel.passkeysEnabled.collectAsState()
    val cloudSyncState      by viewModel.cloudSyncState.collectAsState()
    val cloudSyncRequesting by viewModel.cloudSyncRequesting.collectAsState()
    LaunchedEffect(Unit) { viewModel.refreshCloudSync() }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(accountMessage) {
        accountMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearAccountMessage()
        }
    }
    LaunchedEffect(displayNameMessage) {
        displayNameMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearDisplayNameMessage()
        }
    }

    AccountContent(
        ui = AccountUi(state.email, state.baseUrl, displayName, displayNameSaving, displayNameSaved, passwordCodeSent,
            passwordCode, newAccountPassword, passwordChangeLoading, editableBaseUrl, passkeysEnabled, displayNameLoadFailed,
            cloudSyncState, cloudSyncRequesting),
        actions = AccountActions(viewModel::onDisplayNameChange, viewModel::saveDisplayName,
            viewModel::requestPasswordChangeCode, viewModel::onPasswordCodeChange, viewModel::onNewAccountPasswordChange,
            viewModel::confirmPasswordChange, viewModel::cancelPasswordChange, viewModel::onBaseUrlChange,
            viewModel::saveBaseUrl, viewModel::onPasskeysEnabledChange, onPasskeys, onMyAccessRequests,
            viewModel::requestCloudSync),
        onBack = onBack,
        snackbarHostState = snackbarHostState,
        onRetryDisplayName = viewModel::loadDisplayName
    )
}

/**
 * Conta (redesign visual, Fase 6): "Perfil" com o e-mail em rótulo → valor e o nome com a ajuda
 * DENTRO do campo; troca de senha com os requisitos visíveis (código 0/6, mínimo 8) e senha com
 * mostrar/ocultar; chaves de acesso e candidaturas vieram de Geral (decisão 5.5 #4); "Servidor"
 * mostra a URL uma vez e só abre o aviso + campo em "Alterar (avançado)".
 */
@Composable
internal fun AccountContent(
    ui: AccountUi,
    actions: AccountActions,
    onBack: () -> Unit,
    snackbarHostState: SnackbarHostState? = null,
    onRetryDisplayName: () -> Unit = {}
) {
    var editUrl by rememberSaveable { mutableStateOf(ui.editableBaseUrl != null) }

    Page(title = stringResource(R.string.settings_account_title), onBack = onBack, snackbarHostState = snackbarHostState) {
        GluSection(stringResource(R.string.settings_profile_section)) {
            GluCard {
                FactList(listOf(Fact(stringResource(R.string.settings_email_label), breakableEmail(ui.email), wide = true)))
                OutlinedTextField(
                    value         = ui.displayName,
                    onValueChange = actions.onDisplayName,
                    label         = { Text(stringResource(R.string.settings_display_name_label)) },
                    singleLine    = true,
                    supportingText = {
                        Text(stringResource(if (ui.displayNameSaved) R.string.settings_display_name_saved else R.string.settings_display_name_helper))
                    },
                    modifier      = Modifier.fillMaxWidth()
                )
                if (ui.displayNameLoadFailed) {
                    // L6: sem o nome atual, salvar apagaria o nome no servidor — bloqueia e oferece recarregar.
                    GluCallout(stringResource(R.string.settings_display_name_load_failed), tone = Tone.Warning,
                        actionLabel = stringResource(R.string.common_retry), onAction = onRetryDisplayName)
                }
                OutlinedButton(
                    onClick  = actions.onSaveDisplayName,
                    enabled  = !ui.displayNameSaving && !ui.displayNameSaved && !ui.displayNameLoadFailed,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.settings_display_name_save)) }
            }
        }

        CloudSyncSection(ui, actions)

        // Código por e-mail, mesmo par forgot/reset do login.
        GluSection(stringResource(R.string.settings_change_password), description = stringResource(R.string.settings_change_password_helper)) {
            GluCard {
                if (!ui.passwordCodeSent) {
                    OutlinedButton(
                        onClick  = actions.onRequestCode,
                        enabled  = !ui.passwordLoading,
                        modifier = Modifier.fillMaxWidth()
                    ) { ProgressLabel(stringResource(R.string.settings_send_password_code), ui.passwordLoading) }
                } else {
                    OutlinedTextField(
                        value           = ui.passwordCode,
                        onValueChange   = actions.onCode,
                        label           = { Text(stringResource(R.string.login_code_label)) },
                        singleLine      = true,
                        supportingText  = { Text("${ui.passwordCode.length}/6") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Next),
                        modifier        = Modifier.fillMaxWidth()
                    )
                    SecretTextField(
                        value = ui.newPassword,
                        onValueChange = actions.onNewPassword,
                        label = stringResource(R.string.login_new_password_label),
                        supportingText = stringResource(R.string.settings_password_min)
                    )
                    Button(
                        onClick  = actions.onConfirmPassword,
                        enabled  = !ui.passwordLoading && ui.passwordCode.length == 6 && ui.newPassword.length >= 8,
                        modifier = Modifier.fillMaxWidth()
                    ) { ProgressLabel(stringResource(R.string.settings_confirm_password_change), ui.passwordLoading) }
                    TextButton(
                        onClick  = actions.onCancelPassword,
                        enabled  = !ui.passwordLoading,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.settings_cancel)) }
                }
            }
        }

        GluSection(stringResource(R.string.settings_access_section)) {
            GluCard {
                RowGroup(buildList {
                    add {
                        SwitchRow(stringResource(R.string.passkeys_beta_switch_title), ui.passkeysEnabled, actions.onPasskeysEnabled,
                            description = stringResource(R.string.passkeys_beta_switch_subtitle))
                    }
                    if (ui.passkeysEnabled) add { NavRow(stringResource(R.string.passkeys_nav_subtitle), actions.onPasskeys, indent = true) }
                    // Achado 2026-09-26 — histórico de candidaturas (bomba/sensor beta)
                    add {
                        NavRow(stringResource(R.string.access_requests_title), actions.onMyAccessRequests, icon = Icons.Default.History,
                            subtitle = stringResource(R.string.access_requests_nav_subtitle))
                    }
                })
            }
        }

        GluSection(stringResource(R.string.settings_server)) {
            GluCard {
                FactList(listOf(Fact(stringResource(R.string.settings_server_url_label), ui.baseUrl, wide = true)))
                if (!editUrl) {
                    TextButton(onClick = { editUrl = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.settings_base_url_section))
                    }
                } else {
                    GluCallout(stringResource(R.string.settings_base_url_warning), tone = Tone.Warning)
                    OutlinedTextField(
                        value           = ui.editableBaseUrl ?: ui.baseUrl,
                        onValueChange   = actions.onBaseUrl,
                        label           = { Text(stringResource(R.string.settings_server_url_label)) },
                        singleLine      = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                        modifier        = Modifier.fillMaxWidth()
                    )
                    OutlinedButton(
                        onClick  = actions.onSaveBaseUrl,
                        enabled  = ui.editableBaseUrl != null,
                        colors   = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.settings_base_url_save)) }
                }
            }
        }
    }
}

/** Rótulo de botão com indicador de progresso à frente enquanto a operação roda. */
/**
 * ADR-0030 — a conta é grátis e o app funciona inteiro no aparelho; guardar a glicemia na nuvem e
 * ser acompanhado dependem de liberação (um pedido só). O servidor é quem bloqueia de verdade.
 */
@Composable
private fun CloudSyncSection(ui: AccountUi, actions: AccountActions) {
    GluSection(stringResource(R.string.settings_cloud_section), description = stringResource(R.string.settings_cloud_desc)) {
        GluCard {
            val (text, tone) = when (ui.cloudSyncState) {
                "approved"              -> R.string.settings_cloud_state_approved to Tone.Success
                "pending", "waitlisted" -> R.string.settings_cloud_state_pending to Tone.Info
                "rejected"              -> R.string.settings_cloud_state_rejected to Tone.Warning
                else                    -> R.string.settings_cloud_state_none to Tone.Info
            }
            GluCallout(stringResource(text), tone = tone)
            if (ui.cloudSyncState !in setOf("approved", "pending", "waitlisted")) {
                Button(
                    onClick  = actions.onRequestCloudSync,
                    enabled  = !ui.cloudSyncRequesting,
                    modifier = Modifier.fillMaxWidth()
                ) { ProgressLabel(stringResource(R.string.settings_cloud_request), ui.cloudSyncRequesting) }
            }
        }
    }
}

@Composable
internal fun ProgressLabel(text: String, busy: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
        if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        Text(text)
    }
}
