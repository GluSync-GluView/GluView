package com.gluview.app.ui.screen.settings

import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.EventNote
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material.icons.filled.SystemUpdate
import com.gluview.app.ui.screen.updates.UpdatesViewModel
import com.gluview.app.ui.screen.donation.DonationEntryViewModel
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.gluview.app.BuildConfig
import com.gluview.app.R
import androidx.compose.material.icons.filled.Cloud
import com.gluview.app.ui.components.kit.ConfirmDialog
import com.gluview.app.ui.components.kit.GluCard
import com.gluview.app.ui.components.kit.GluSection
import com.gluview.app.ui.components.kit.NavRow
import com.gluview.app.ui.components.kit.Page
import com.gluview.app.ui.components.kit.RowGroup
import com.gluview.app.ui.components.kit.SwitchRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: (() -> Unit)? = null,
    onGeneralSettings: () -> Unit,
    onAlertSettings: () -> Unit,
    onSyncSettings: () -> Unit,
    onSupportSettings: () -> Unit,
    onAccountSettings: () -> Unit,
    onCaregiver: () -> Unit,
    // ADR-0029 — Wearables saiu da barra inferior (deu lugar à Bomba); a tela em si não muda,
    // só ganha esta porta de entrada nova (mesmo padrão de quando o Mi Band saiu de Ajustes).
    onWatchFaces: () -> Unit,
    onIntegrations: () -> Unit,
    onLogout: () -> Unit,
    onUpdates: () -> Unit = {},
    onDonate: () -> Unit = {},
    onDiary: () -> Unit = {},
    onReminders: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
    updatesViewModel: UpdatesViewModel = hiltViewModel(),
    donationEntryViewModel: DonationEntryViewModel = hiltViewModel()
) {
    val updatesUi by updatesViewModel.ui.collectAsState()
    val donationsEnabled by donationEntryViewModel.enabled.collectAsState()
    val isAdmin           by viewModel.isAdmin.collectAsState()
    val isFollowerOnly    by viewModel.isFollowerOnly.collectAsState()
    val isLocalOnly       by viewModel.isLocalOnly.collectAsState()
    val debugSeedEnabled  by viewModel.debugSeedEnabled.collectAsState()
    val debugBusy         by viewModel.debugBusy.collectAsState()
    val debugMessage      by viewModel.debugMessage.collectAsState()
    val confirmLogoutTitle = stringResource(R.string.settings_confirm_logout_title)
    val confirmLogoutSubtitle = stringResource(R.string.settings_confirm_logout_subtitle)
    val cancelLabel = stringResource(R.string.settings_cancel)
    val context = LocalContext.current
    val activity = context as AppCompatActivity
    var showLogoutDialog by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    // Modo Debug/Admin — busca o estado real (ligado/desligado) assim que a conta admin abre a tela.
    LaunchedEffect(isAdmin) {
        if (isAdmin) viewModel.refreshDebugStatus()
    }
    LaunchedEffect(debugMessage) {
        debugMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearDebugMessage()
        }
    }
    // S33 — tenta autenticação biométrica antes de mostrar o diálogo de logout.
    // API 30+: combina biometria fraca + PIN/padrão via DEVICE_CREDENTIAL.
    // API 26–29: usa apenas BIOMETRIC_WEAK (DEVICE_CREDENTIAL não pode ser combinado) e
    //            exige setNegativeButtonText para o botão de cancelamento.
    fun tryLogout() {
        val biometricManager = BiometricManager.from(context)
        val authenticators = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            BIOMETRIC_WEAK or DEVICE_CREDENTIAL
        } else {
            BIOMETRIC_WEAK
        }
        if (biometricManager.canAuthenticate(authenticators) == BiometricManager.BIOMETRIC_SUCCESS) {
            val promptInfo = BiometricPrompt.PromptInfo.Builder()
                .setTitle(confirmLogoutTitle)
                .setSubtitle(confirmLogoutSubtitle)
                .setAllowedAuthenticators(authenticators)
                .apply {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                        setNegativeButtonText(cancelLabel)
                    }
                }
                .build()
            val executor = ContextCompat.getMainExecutor(context)
            BiometricPrompt(activity, executor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    showLogoutDialog = true
                }
            }).authenticate(promptInfo)
        } else {
            // Dispositivo sem biometria/PIN configurado — mostra diálogo diretamente
            showLogoutDialog = true
        }
    }

    SettingsContent(
        isAdmin = isAdmin, isFollowerOnly = isFollowerOnly, isLocalOnly = isLocalOnly,
        debugSeedEnabled = debugSeedEnabled, debugBusy = debugBusy,
        nav = SettingsNav(onAccountSettings, onGeneralSettings, onSyncSettings, onAlertSettings, onWatchFaces,
            onCaregiver, onIntegrations, onSupportSettings, onUpdates, onDonate, onDiary, onReminders),
        updateAvailableName = updatesUi.available?.versionName,
        showDonate = donationsEnabled,
        onDebugSeedToggle = viewModel::onDebugSeedToggle,
        onDebugBackfill = viewModel::onDebugBackfillClick,
        onLogout = ::tryLogout,
        onBack = onBack,
        snackbarHostState = snackbarHostState
    )

    if (showLogoutDialog) {
        ConfirmDialog(
            title = stringResource(R.string.settings_logout),
            message = stringResource(R.string.settings_logout_confirm_body),
            confirmLabel = stringResource(R.string.settings_logout_confirm_button),
            onConfirm = { viewModel.logout(onLogout) },
            onDismiss = { showLogoutDialog = false }
        )
    }
}

/** Destinos do hub de Ajustes. */
internal data class SettingsNav(
    val account: () -> Unit,
    val general: () -> Unit,
    val sync: () -> Unit,
    val alerts: () -> Unit,
    val wearables: () -> Unit,
    val caregiver: () -> Unit,
    val integrations: () -> Unit,
    val support: () -> Unit,
    // 9º campo com padrão: não quebra a construção posicional no SettingsCaptureTest (plano OTA, 5.4).
    val updates: () -> Unit = {},
    // 10º, com padrão pelo mesmo motivo (doação via Pix).
    val donate: () -> Unit = {},
    // Plano 17 — diário e lembretes (com padrão, mesmo motivo).
    val diary: () -> Unit = {},
    val reminders: () -> Unit = {}
)

/**
 * Hub de Ajustes (redesign visual, Fase 6): Conta no topo e grupos com cabeçalho — App, Conexões, Ajuda —
 * no lugar de um bloco único de 7 linhas; subtítulos curtos em até 2 linhas (antes cortados com
 * reticências já em fonte normal); "Integrações" como entrada própria (antes dentro de Geral >
 * Acesso, decisão 5.5 #4); "Sair da conta" com confirmação em vermelho.
 */
@Composable
internal fun SettingsContent(
    isAdmin: Boolean,
    isFollowerOnly: Boolean,
    isLocalOnly: Boolean,
    debugSeedEnabled: Boolean,
    debugBusy: Boolean,
    nav: SettingsNav,
    onDebugSeedToggle: (Boolean) -> Unit,
    onDebugBackfill: () -> Unit,
    onLogout: () -> Unit,
    onBack: (() -> Unit)? = null,
    snackbarHostState: SnackbarHostState? = null,
    /** Nome da versão nova conhecida, se houver (subtítulo de "Atualizações"). */
    updateAvailableName: String? = null,
    /** "Apoiar o GluView": só com a doação ligada no GluSync. */
    showDonate: Boolean = false
) {
    // Aba principal da barra inferior: sem seta (o voltar do sistema continua).
    Page(title = stringResource(R.string.settings_title), onBack = onBack, snackbarHostState = snackbarHostState) {
        // Conta: um item só, sem cabeçalho de grupo (seria "Conta" duas vezes).
        if (!isLocalOnly) {
            GluCard {
                NavRow(stringResource(R.string.settings_account_section), nav.account, icon = Icons.Default.AccountCircle,
                    subtitle = stringResource(R.string.settings_account_section_subtitle))
            }
        }
        GluSection(stringResource(R.string.settings_group_app)) {
            GluCard {
                RowGroup(buildList {
                    add { NavRow(stringResource(R.string.settings_general_section), nav.general, icon = Icons.Default.Tune,
                        subtitle = stringResource(R.string.settings_general_section_subtitle)) }
                    add { NavRow(stringResource(R.string.settings_configure_alerts), nav.alerts, icon = Icons.Default.NotificationsActive,
                        subtitle = stringResource(R.string.settings_alerts_section_subtitle)) }
                    if (!isFollowerOnly) add { NavRow(stringResource(R.string.diary_title), nav.diary, icon = Icons.Default.EventNote,
                        subtitle = stringResource(R.string.settings_diary_subtitle)) }
                    if (!isFollowerOnly) add { NavRow(stringResource(R.string.reminders_title), nav.reminders, icon = Icons.Default.Alarm,
                        subtitle = stringResource(R.string.settings_reminders_subtitle)) }
                    if (!isFollowerOnly) add { NavRow(stringResource(R.string.settings_sync_section_title), nav.sync, icon = Icons.Default.Devices,
                        subtitle = stringResource(R.string.settings_sync_section_subtitle)) }
                    // ADR-0029 — Wearables saiu da barra inferior (deu lugar à Bomba).
                    add { NavRow(stringResource(R.string.nav_wearables), nav.wearables, icon = Icons.Default.Watch,
                        subtitle = stringResource(R.string.settings_wearables_subtitle)) }
                    // Plano OTA (E6) — o estado vai no subtítulo (a NavRow não tem badge).
                    add { NavRow(stringResource(R.string.settings_updates_title), nav.updates, icon = Icons.Default.SystemUpdate,
                        subtitle = if (updateAvailableName != null)
                            stringResource(R.string.settings_updates_subtitle_available, updateAvailableName)
                        else stringResource(R.string.settings_updates_subtitle, BuildConfig.VERSION_NAME)) }
                })
            }
        }
        if (!isLocalOnly) {
            GluSection(stringResource(R.string.settings_group_connections)) {
                GluCard {
                    RowGroup(listOf(
                        { NavRow(stringResource(R.string.settings_caregiver_section), nav.caregiver, icon = Icons.Default.Groups,
                            subtitle = stringResource(R.string.settings_caregiver_subtitle)) },
                        { NavRow(stringResource(R.string.settings_integrations_title), nav.integrations, icon = Icons.Default.Cloud,
                            subtitle = stringResource(R.string.settings_integrations_subtitle)) }
                    ))
                }
            }
        }
        GluSection(stringResource(R.string.settings_group_help)) {
            GluCard {
                RowGroup(buildList {
                    add { NavRow(stringResource(R.string.settings_support_section_title), nav.support, icon = Icons.Default.MonitorHeart,
                        subtitle = stringResource(R.string.settings_support_section_subtitle)) }
                    // Doação via Pix: último da lista, nunca disputa atenção com o que é cuidado.
                    if (showDonate) add { NavRow(stringResource(R.string.settings_donate_title), nav.donate, icon = Icons.Default.Favorite,
                        subtitle = stringResource(R.string.settings_donate_subtitle)) }
                })
            }
        }

        // Modo Debug/Admin — só visível pra conta administradora (gate também no servidor).
        if (isAdmin) {
            GluSection(stringResource(R.string.settings_debug_admin), description = stringResource(R.string.settings_debug_desc)) {
                GluCard {
                    SwitchRow(stringResource(R.string.settings_debug_generate_continuously), debugSeedEnabled, onDebugSeedToggle,
                        enabled = !debugBusy)
                    OutlinedButton(onClick = onDebugBackfill, enabled = !debugBusy, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.settings_debug_generate_today))
                    }
                }
            }
        }

        OutlinedButton(
            onClick  = onLogout,
            modifier = Modifier.fillMaxWidth(),
            colors   = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
        ) {
            Text(stringResource(R.string.settings_logout))
        }

        // Versão instalada — referência para suporte e para conferir se a atualização chegou (E1, plano OTA).
        Text(
            text     = stringResource(R.string.settings_app_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
            style    = MaterialTheme.typography.bodySmall,
            color    = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally)
        )
    }
}
