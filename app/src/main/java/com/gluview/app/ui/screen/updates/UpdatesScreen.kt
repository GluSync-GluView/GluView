package com.gluview.app.ui.screen.updates

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import com.gluview.app.R
import com.gluview.app.data.update.UpdateInstaller
import com.gluview.app.data.update.UpdateInstaller.FailReason
import com.gluview.app.data.update.UpdateSafetyRules.Reason
import com.gluview.app.util.TimeFormat
import com.gluview.app.ui.components.chart.rememberChartAppearance
import com.gluview.app.ui.components.kit.AppDialog
import com.gluview.app.ui.components.kit.ConfirmDialog
import com.gluview.app.ui.components.kit.GluCallout
import com.gluview.app.ui.components.kit.GluCard
import com.gluview.app.ui.components.kit.GluSection
import com.gluview.app.ui.components.kit.Page
import com.gluview.app.ui.components.kit.SwitchRow
import com.gluview.app.ui.components.kit.Tone
import com.gluview.app.ui.theme.Spacing
import com.gluview.app.util.formatDateTime
import java.util.Locale

/** Ações da tela — separadas do estado para as capturas do harness visual. */
data class UpdatesActions(
    val onCheckNow: () -> Unit = {},
    val onUpdate: () -> Unit = {},
    val onOpenInstallPermission: () -> Unit = {},
    val onOpenAppSettings: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onAutoCheck: (Boolean) -> Unit = {},
    val onDismissLostPermissions: () -> Unit = {}
)

@Composable
fun UpdatesScreen(onBack: () -> Unit, viewModel: UpdatesViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsState()
    val context = LocalContext.current

    // Volta da tela "Instalar apps desconhecidos": segue se a permissão foi dada.
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        viewModel.continueAfterPermission()
    }
    // Confirmação do sistema (sempre no Android 9): aberta pela tela, que está em primeiro plano.
    val confirmLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {}
    val waiting = ui.install as? UpdateInstaller.State.WaitingUserAction
    LaunchedEffect(waiting) {
        if (waiting != null) {
            viewModel.onUserActionLaunched()
            runCatching { confirmLauncher.launch(waiting.intent) }
        }
    }

    var confirmInstall by remember { mutableStateOf(false) }

    UpdatesContent(
        ui = ui,
        timeFormat = rememberChartAppearance().timeFormat,
        actions = UpdatesActions(
            onCheckNow = viewModel::checkNow,
            onUpdate = { confirmInstall = true },
            onOpenInstallPermission = {
                permissionLauncher.launch(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                )
            },
            onOpenAppSettings = {
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                    )
                }
            },
            onRetry = { viewModel.resetInstall() },
            onAutoCheck = viewModel::setAutoCheck,
            onDismissLostPermissions = viewModel::dismissLostPermissions
        ),
        onBack = onBack
    )

    if (confirmInstall) {
        val target = ui.available
        ConfirmDialog(
            title = stringResource(R.string.updates_confirm_title, target?.versionName ?: ""),
            message = stringResource(R.string.updates_downtime_warning),
            confirmLabel = stringResource(R.string.updates_confirm_button),
            onConfirm = { confirmInstall = false; viewModel.update() },
            onDismiss = { confirmInstall = false },
            destructive = false
        )
    }
    // Porteiro (decisão de 24/10/2026): nunca bloqueia — mostra os motivos e deixa escolher.
    // "Mais tarde" (ou fechar o diálogo) não tem consequência: a versão continua disponível.
    val needsConfirmation = ui.install as? UpdateInstaller.State.NeedsConfirmation
    if (needsConfirmation != null) {
        AppDialog(
            title = stringResource(R.string.updates_warn_title),
            body = needsConfirmation.reasons.map { "• " + reasonText(it) }.joinToString("\n") +
                "\n\n" + stringResource(R.string.updates_warn_body),
            confirmLabel = stringResource(R.string.updates_warn_continue),
            onConfirm = viewModel::confirmNow,
            dismissLabel = stringResource(R.string.updates_warn_later),
            onDismiss = viewModel::postpone
        )
    }
}

@Composable
fun UpdatesContent(ui: UpdatesUi, timeFormat: TimeFormat, actions: UpdatesActions, onBack: () -> Unit) {
    Page(title = stringResource(R.string.updates_title), onBack = onBack) {
        GluCard {
            Text(stringResource(R.string.updates_installed, ui.installedName, ui.installedCode),
                style = MaterialTheme.typography.bodyLarge)
            Text(
                if (ui.lastCheckMs > 0) stringResource(R.string.updates_last_check, formatDateTime(ui.lastCheckMs, timeFormat))
                else stringResource(R.string.updates_last_check_never),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(onClick = actions.onCheckNow, enabled = !ui.checking && !ui.localOnly,
                modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(if (ui.checking) R.string.updates_checking else R.string.updates_check_now))
            }
        }

        if (ui.localOnly || ui.checkMessage == CheckMessage.NO_SERVER) {
            GluCallout(stringResource(R.string.updates_no_server), tone = Tone.Info)
        }
        when (ui.checkMessage) {
            CheckMessage.UP_TO_DATE -> if (ui.available == null) GluCallout(stringResource(R.string.updates_up_to_date), tone = Tone.Success)
            CheckMessage.FAILED -> GluCallout(stringResource(R.string.updates_check_failed), tone = Tone.Warning)
            CheckMessage.UNSUPPORTED_ANDROID -> GluCallout(stringResource(R.string.updates_unsupported_android), tone = Tone.Warning)
            else -> Unit
        }

        if (ui.lostPermissions.isNotEmpty()) {
            GluCallout(
                title = stringResource(R.string.updates_lost_permissions_title),
                message = stringResource(R.string.updates_lost_permissions_body,
                    ui.lostPermissions.sorted().map { permissionName(it) }.joinToString(", ")),
                tone = Tone.Danger,
                actionLabel = stringResource(R.string.updates_open_app_settings),
                onAction = actions.onOpenAppSettings
            )
            TextButton(onClick = actions.onDismissLostPermissions) { Text(stringResource(R.string.updates_dismiss)) }
        }

        ui.available?.let { latest -> AvailableCard(ui, latest, actions) }

        GluSection(stringResource(R.string.updates_auto_section)) {
            GluCard {
                SwitchRow(
                    title = stringResource(R.string.updates_auto_check),
                    checked = ui.autoCheck,
                    onCheckedChange = actions.onAutoCheck,
                    description = stringResource(R.string.updates_auto_check_desc),
                    enabled = !ui.localOnly
                )
            }
        }
    }
}

@Composable
private fun AvailableCard(ui: UpdatesUi, latest: com.gluview.app.data.update.UpdatePreferences.KnownLatest, actions: UpdatesActions) {
    GluCard {
        Text(stringResource(R.string.updates_available_title, latest.versionName), style = MaterialTheme.typography.titleMedium)
        if (latest.sizeBytes > 0) {
            Text(stringResource(R.string.updates_available_size, String.format(Locale.ROOT, "%.1f MB", latest.sizeBytes / 1_048_576.0)),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (latest.security) GluCallout(stringResource(R.string.updates_security), tone = Tone.Warning)
        Text(stringResource(R.string.updates_downtime_warning), style = MaterialTheme.typography.bodySmall)

        when (val s = ui.install) {
            is UpdateInstaller.State.Downloading -> {
                val fraction = if (s.totalBytes > 0) (s.readBytes.toFloat() / s.totalBytes).coerceIn(0f, 1f) else 0f
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.updates_downloading, (fraction * 100).toInt()))
            }
            UpdateInstaller.State.Verifying -> { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(stringResource(R.string.updates_verifying)) }
            UpdateInstaller.State.Installing -> { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(stringResource(R.string.updates_installing)) }
            is UpdateInstaller.State.WaitingUserAction -> Text(stringResource(R.string.updates_waiting_user))
            UpdateInstaller.State.Installed -> GluCallout(stringResource(R.string.updates_installed_ok), tone = Tone.Success)
            UpdateInstaller.State.NeedsInstallPermission -> GluCallout(
                title = stringResource(R.string.updates_permission_title),
                message = stringResource(R.string.updates_permission_body),
                tone = Tone.Info,
                actionLabel = stringResource(R.string.updates_permission_button),
                onAction = actions.onOpenInstallPermission
            )
            is UpdateInstaller.State.Failed -> {
                GluCallout(failText(s.reason), tone = Tone.Danger)
                OutlinedButton(onClick = actions.onRetry, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.updates_retry)) }
            }
            is UpdateInstaller.State.NeedsConfirmation, UpdateInstaller.State.Idle -> Row(
                Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                Button(onClick = actions.onUpdate, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.updates_install_button)) }
            }
        }
    }
}

@Composable
private fun reasonText(reason: Reason): String = stringResource(when (reason) {
    Reason.LOW_GLUCOSE -> R.string.updates_reason_low
    Reason.RECENT_LOW -> R.string.updates_reason_recent_low
    Reason.FALLING -> R.string.updates_reason_falling
    Reason.ACTIVE_ALERT -> R.string.updates_reason_active_alert
    Reason.SNOOZED -> R.string.updates_reason_snoozed
    Reason.SENSOR_SETUP -> R.string.updates_reason_sensor_setup
    Reason.NIGHT -> R.string.updates_reason_night
    Reason.NO_RECENT_READING -> R.string.updates_reason_no_reading
    Reason.HIGH_GLUCOSE -> R.string.updates_reason_high
    Reason.STALE -> R.string.updates_reason_stale
})

@Composable
private fun failText(reason: FailReason): String = stringResource(when (reason) {
    FailReason.NO_SPACE -> R.string.updates_fail_no_space
    FailReason.LINK_DENIED -> R.string.updates_fail_denied
    FailReason.LINK_NOT_FOUND -> R.string.updates_fail_not_found
    FailReason.LINK_RATE_LIMITED -> R.string.updates_fail_rate_limited
    FailReason.LINK_UNAVAILABLE -> R.string.updates_fail_unavailable
    FailReason.NETWORK -> R.string.updates_fail_network
    FailReason.BAD_LINK, FailReason.HASH_MISMATCH, FailReason.BAD_PACKAGE,
    FailReason.NOT_NEWER, FailReason.UNTRUSTED_CERT -> R.string.updates_fail_verification
    FailReason.UNSUPPORTED_ANDROID -> R.string.updates_unsupported_android
    FailReason.INSTALL_BLOCKED, FailReason.INSTALL_INCOMPATIBLE -> R.string.updates_fail_blocked
    FailReason.INSTALL_CONFLICT -> R.string.updates_fail_conflict
    FailReason.INSTALL_STORAGE -> R.string.updates_fail_storage
    FailReason.INSTALL_ABORTED -> R.string.updates_fail_aborted
    FailReason.INSTALL_FAILED -> R.string.updates_fail_generic
})

@Composable
private fun permissionName(key: String): String = when (key) {
    android.Manifest.permission.SEND_SMS -> stringResource(R.string.updates_perm_sms)
    android.Manifest.permission.CALL_PHONE -> stringResource(R.string.updates_perm_call)
    android.Manifest.permission.ACCESS_FINE_LOCATION -> stringResource(R.string.updates_perm_location)
    android.Manifest.permission.BLUETOOTH_CONNECT, android.Manifest.permission.BLUETOOTH_SCAN -> stringResource(R.string.updates_perm_bluetooth)
    android.Manifest.permission.POST_NOTIFICATIONS -> stringResource(R.string.updates_perm_notifications)
    UpdateInstaller.BATTERY_EXEMPTION -> stringResource(R.string.updates_perm_battery)
    else -> key.substringAfterLast('.')
}
