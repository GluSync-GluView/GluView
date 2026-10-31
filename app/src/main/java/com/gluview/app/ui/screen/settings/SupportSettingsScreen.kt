package com.gluview.app.ui.screen.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.EventNote
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.SupportAgent
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import android.net.Uri
import com.gluview.app.R
import com.gluview.app.ui.components.kit.ConfirmDialog
import com.gluview.app.ui.components.kit.GluCard
import com.gluview.app.ui.components.kit.GluSection
import com.gluview.app.ui.components.kit.NavRow
import com.gluview.app.ui.components.kit.Page
import com.gluview.app.ui.components.kit.RowGroup
import com.gluview.app.util.AppLogger

/**
 * Submenu de Ajustes — logs, relatório médico, diagnóstico do serviço e backup de
 * configurações. Antes eram 4 seções + 3 botões soltos direto em Ajustes.
 */
@Composable
fun SupportSettingsScreen(
    onBack: () -> Unit,
    onDiagnostic: () -> Unit,
    onEventLog: () -> Unit,
    onReport: () -> Unit,
    onAppLogs: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
    supportReportViewModel: SupportReportViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val supportReportState by supportReportViewModel.uiState.collectAsState()
    var showSupportReportDialog by remember { mutableStateOf(false) }
    var supportReportMessage by remember { mutableStateOf("") }

    // Importar sobrescreve todas as configurações: o arquivo escolhido espera a confirmação
    // (decisão 5.5 #3 — antes aplicava direto).
    var pendingImport by remember { mutableStateOf<Uri?>(null) }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> pendingImport = uri }

    LaunchedEffect(state.backupMsg) {
        state.backupMsg?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearBackupMsg()
        }
    }

    SupportContent(
        supportLogEnabled = supportReportState.enabled,
        onSupportLog = { showSupportReportDialog = true },
        onEventLog = onEventLog,
        onAppLogs = onAppLogs,
        onReport = onReport,
        onDiagnostic = onDiagnostic,
        onExport = viewModel::exportBackup,
        onImport = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
        onBack = onBack,
        snackbarHostState = snackbarHostState
    )

    pendingImport?.let { uri ->
        ConfirmDialog(
            title = stringResource(R.string.settings_import_confirm_title),
            message = stringResource(R.string.settings_import_confirm_body),
            confirmLabel = stringResource(R.string.settings_import_confirm_button),
            onConfirm = { viewModel.importBackup(uri); pendingImport = null },
            onDismiss = { pendingImport = null }
        )
    }

    LaunchedEffect(supportReportState.sent) {
        if (supportReportState.sent) {
            snackbarHostState.showSnackbar(context.getString(R.string.settings_support_log_sent))
            showSupportReportDialog = false
            supportReportMessage = ""
            supportReportViewModel.dismiss()
        }
    }

    if (showSupportReportDialog) {
        AlertDialog(
            onDismissRequest = { if (!supportReportState.sending) showSupportReportDialog = false },
            title = { Text(stringResource(R.string.settings_send_support_log)) },
            text = {
                Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.settings_support_log_confirm_body))
                    OutlinedTextField(
                        value         = supportReportMessage,
                        onValueChange = { supportReportMessage = it },
                        label         = { Text(stringResource(R.string.settings_support_log_message_label)) },
                        modifier      = Modifier.fillMaxWidth()
                    )
                    supportReportState.error?.let { error ->
                        Text(text = error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { supportReportViewModel.send(supportReportMessage) },
                    enabled = !supportReportState.sending
                ) { ProgressLabel(stringResource(R.string.settings_send_support_log), supportReportState.sending) }
            },
            dismissButton = {
                TextButton(
                    onClick = { showSupportReportDialog = false },
                    enabled = !supportReportState.sending
                ) { Text(stringResource(R.string.settings_cancel)) }
            }
        )
    }
}

/**
 * Diagnóstico e suporte (redesign visual, Fase 6): navegação em linhas agrupadas — Suporte,
 * Registros, Relatórios, Diagnóstico e Backup — no lugar de 4 botões soltos e de uma seção
 * "Diagnóstico" que não tinha o diagnóstico.
 */
@Composable
internal fun SupportContent(
    supportLogEnabled: Boolean,
    onSupportLog: () -> Unit,
    onEventLog: () -> Unit,
    onAppLogs: () -> Unit,
    onReport: () -> Unit,
    onDiagnostic: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onBack: () -> Unit,
    snackbarHostState: SnackbarHostState? = null
) {
    Page(title = stringResource(R.string.settings_support_section_title), onBack = onBack, snackbarHostState = snackbarHostState) {
        // ADR-0029 — "Enviar log para o suporte" (gated por Features:SupportLogEnabled).
        if (supportLogEnabled) {
            GluSection(stringResource(R.string.settings_support_group)) {
                GluCard { NavRow(stringResource(R.string.settings_send_support_log), onSupportLog, icon = Icons.Default.SupportAgent,
                    subtitle = stringResource(R.string.settings_send_support_log_subtitle)) }
            }
        }
        GluSection(stringResource(R.string.settings_logs_group)) {
            GluCard {
                RowGroup(listOf(
                    { NavRow(stringResource(R.string.settings_event_log), onEventLog, icon = Icons.Default.EventNote) },
                    { NavRow(stringResource(R.string.settings_view_export_logs), onAppLogs, icon = Icons.Default.Description,
                        subtitle = stringResource(R.string.settings_logs_retention_desc, AppLogger.RETENTION_DAYS)) }
                ))
            }
        }
        GluSection(stringResource(R.string.settings_reports_group)) {
            GluCard { NavRow(stringResource(R.string.settings_medical_report), onReport, icon = Icons.Default.Assessment) }
        }
        GluSection(stringResource(R.string.settings_diagnostic_section)) {
            GluCard { NavRow(stringResource(R.string.settings_service_diagnostic), onDiagnostic, icon = Icons.Default.MonitorHeart) }
        }
        GluSection(stringResource(R.string.settings_backup_section), description = stringResource(R.string.settings_backup_desc)) {
            GluCard {
                RowGroup(listOf(
                    { NavRow(stringResource(R.string.settings_export), onExport, icon = Icons.Default.Upload) },
                    { NavRow(stringResource(R.string.settings_import), onImport, icon = Icons.Default.Download,
                        subtitle = stringResource(R.string.settings_import_subtitle)) }
                ))
            }
        }
    }
}
