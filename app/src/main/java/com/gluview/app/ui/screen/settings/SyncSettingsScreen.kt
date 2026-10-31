package com.gluview.app.ui.screen.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.navigation.compose.hiltViewModel
import com.gluview.app.R
import com.gluview.app.data.source.DataSourceType
import com.gluview.app.ui.components.kit.AppDialog
import com.gluview.app.ui.components.kit.GluCallout
import com.gluview.app.ui.components.kit.GluCard
import com.gluview.app.ui.components.kit.GluSection
import com.gluview.app.ui.components.kit.NavRow
import com.gluview.app.ui.components.kit.Page
import com.gluview.app.ui.components.kit.SliderRow
import com.gluview.app.ui.components.kit.SwitchRow
import com.gluview.app.ui.components.kit.Tone
import com.gluview.app.util.formatDate
import java.util.concurrent.TimeUnit

/** Estado de Sincronização (para o harness desenhar sem ViewModel). */
internal data class SyncUi(
    val isLocalOnly: Boolean,
    val dataSource: String,
    val uploadOverMobileData: Boolean,
    val uploadMobileIntervalMin: Int,
    val pollingInterval: Int,
    val retentionDays: Int,
    val pendingRetentionDays: Int,
    val pendingRetentionAtMs: Long
)

internal data class SyncActions(
    val onUploadOverMobileData: (Boolean) -> Unit,
    val onUploadMobileInterval: (Int) -> Unit,
    val onPollingInterval: (Int) -> Unit,
    val onRetention: (Int) -> Unit,
    val onCancelPendingRetention: () -> Unit
)

/**
 * Submenu de Ajustes — como os dados do sensor circulam: envio ao GluSync, intervalo de
 * polling na nuvem e retenção local. Separado de Sensor (fonte de dados/BLE em si, aba própria
 * na barra inferior); as janelas dos gráficos ficam em Geral.
 */
@Composable
fun SyncSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val dataSource              by viewModel.dataSource.collectAsState()
    val uploadOverMobileData    by viewModel.uploadOverMobileData.collectAsState()
    val uploadMobileIntervalMin by viewModel.uploadMobileIntervalMin.collectAsState()
    val isLocalOnly             by viewModel.isLocalOnly.collectAsState()
    val retentionPending        by viewModel.pendingRetentionDays.collectAsState()
    val pendingAtMs             by viewModel.pendingRetentionChangeAtMs.collectAsState()

    SyncContent(
        ui = SyncUi(isLocalOnly, dataSource, uploadOverMobileData, uploadMobileIntervalMin, state.pollingInterval,
            state.dataRetentionDays, retentionPending, pendingAtMs),
        actions = SyncActions(viewModel::onUploadOverMobileDataChange, viewModel::onUploadMobileIntervalMinChange,
            viewModel::onPollingIntervalChange, viewModel::onDataRetentionChange, viewModel::onCancelPendingRetention),
        onBack = onBack
    )
}

/** Retenção mínima que o app aceita (abaixo disto o botão Aplicar fica desligado, com o erro no campo). */
private const val MIN_RETENTION_DAYS = 30

/**
 * Sincronização (redesign visual, Fase 6): switches e sliders do kit (linha inteira tocável,
 * trilho liso, valor com unidade à direita); a descrição dos dados móveis diz o intervalo real
 * (antes "a cada X min"); retenção com o requisito visível (sufixo "dias", "Mínimo 30 dias", erro
 * abaixo de 30), sem o botão desabilitado "Salvo ✓"; avisos e a redução pendente em callouts com
 * a data no idioma; "Por que sincronizar" como linha que abre a ajuda.
 */
@Composable
internal fun SyncContent(ui: SyncUi, actions: SyncActions, onBack: () -> Unit) {
    var showGluSyncInfo by remember { mutableStateOf(false) }

    Page(title = stringResource(R.string.settings_sync_section_title), onBack = onBack) {
        // Envio ao GluSync — poupar dados móveis (sensores BLE: Sibionics e Libre 2 Plus)
        if (!ui.isLocalOnly && DataSourceType.fromId(ui.dataSource).isLocalSensor) {
            GluSection(stringResource(R.string.settings_glusync_upload), description = stringResource(R.string.settings_wifi_reconnect_flush)) {
                GluCard {
                    SwitchRow(
                        stringResource(R.string.settings_upload_over_mobile_data), ui.uploadOverMobileData, actions.onUploadOverMobileData,
                        description = if (ui.uploadOverMobileData)
                            stringResource(R.string.settings_upload_mobile_data_desc_on, ui.uploadMobileIntervalMin)
                        else stringResource(R.string.settings_upload_mobile_data_desc_off)
                    )
                    if (ui.uploadOverMobileData) {
                        val minutes = stringResource(R.string.settings_minutes_value, ui.uploadMobileIntervalMin)
                        SliderRow(stringResource(R.string.settings_mobile_data_interval), ui.uploadMobileIntervalMin.toFloat(),
                            1f..60f, 1f, { minutes }, { actions.onUploadMobileInterval(it.toInt()) })
                    }
                }
            }
        }

        // Intervalo de polling — só se aplica à leitura via nuvem (GluSync)
        if (!ui.isLocalOnly) {
            GluSection(stringResource(R.string.settings_update_interval)) {
                GluCard {
                    val every = pluralStringResource(R.plurals.settings_minutes_full, ui.pollingInterval, ui.pollingInterval)
                    SliderRow(stringResource(R.string.settings_update_interval_row), ui.pollingInterval.toFloat(),
                        1f..15f, 1f, { every }, { actions.onPollingInterval(it.toInt()) })
                }
            }
        }

        RetentionSection(ui, actions, onInfo = { showGluSyncInfo = true })
    }

    if (showGluSyncInfo) {
        AppDialog(
            title = stringResource(R.string.settings_glusync_promo_title),
            body = stringResource(R.string.settings_glusync_promo_body),
            onDismiss = { showGluSyncInfo = false },
            confirmLabel = stringResource(R.string.settings_glusync_promo_dismiss)
        )
    }
}

@Composable
private fun RetentionSection(ui: SyncUi, actions: SyncActions, onInfo: () -> Unit) {
    val hasPending = ui.pendingRetentionAtMs > 0
    val displayed = if (hasPending) ui.pendingRetentionDays else ui.retentionDays

    GluSection(
        stringResource(R.string.settings_local_retention),
        description = stringResource(if (ui.isLocalOnly) R.string.settings_local_retention_desc_local
                                     else R.string.settings_local_retention_desc_synced)
    ) {
        GluCard {
            var unlimited by remember(displayed) { mutableStateOf(displayed == -1) }
            var daysDraft by remember(displayed) { mutableStateOf(if (displayed == -1) "" else displayed.toString()) }
            // L25: abaixo do mínimo não aplica (antes virava 30 em silêncio ao tocar em Aplicar).
            val typed = daysDraft.toIntOrNull()
            val valid = unlimited || (typed != null && typed >= MIN_RETENTION_DAYS)
            val draftDays = if (unlimited) -1 else typed ?: 0
            val isDirty = draftDays != displayed

            SwitchRow(stringResource(R.string.settings_retention_unlimited), unlimited, { unlimited = it })
            if (!unlimited) {
                OutlinedTextField(
                    value = daysDraft,
                    onValueChange = { daysDraft = it.filter(Char::isDigit) },
                    label = { Text(stringResource(R.string.settings_retention_days_label)) },
                    suffix = { Text(stringResource(R.string.settings_days_suffix)) },
                    supportingText = { Text(stringResource(R.string.settings_retention_min_hint, MIN_RETENTION_DAYS)) },
                    isError = typed != null && typed < MIN_RETENTION_DAYS,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Button(onClick = { actions.onRetention(draftDays) }, enabled = isDirty && valid, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_retention_apply))
            }
        }

        if (displayed == -1) GluCallout(stringResource(R.string.settings_retention_unlimited_warning), tone = Tone.Warning)

        if (hasPending) {
            val target = formatDate(ui.pendingRetentionAtMs + TimeUnit.DAYS.toMillis(7))
            val pendingLabel = if (ui.pendingRetentionDays == -1) stringResource(R.string.settings_retention_unlimited)
                else pluralStringResource(R.plurals.settings_days_value, ui.pendingRetentionDays, ui.pendingRetentionDays)
            GluCallout(
                message = stringResource(R.string.settings_retention_pending_warning, pendingLabel, target),
                tone = Tone.Warning,
                actionLabel = stringResource(R.string.settings_retention_pending_cancel),
                onAction = actions.onCancelPendingRetention
            )
        }

        if (ui.isLocalOnly) {
            GluCard { NavRow(stringResource(R.string.settings_glusync_promo_button), onInfo, icon = Icons.Outlined.Info) }
        }
    }
}
