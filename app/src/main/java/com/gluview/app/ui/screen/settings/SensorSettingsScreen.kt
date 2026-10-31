package com.gluview.app.ui.screen.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import com.gluview.app.R
import com.gluview.app.data.remote.dto.AccessRequestFeatures
import com.gluview.app.data.sibionics.GlucoseDisplayMode
import com.gluview.app.data.source.DataSourceType
import com.gluview.app.data.source.SensorVariant
import com.gluview.app.data.source.SensorVariantCatalog
import com.gluview.app.ui.components.label
import com.gluview.app.ui.components.kit.GluCard
import com.gluview.app.ui.components.kit.GluSection
import com.gluview.app.ui.components.kit.Page
import com.gluview.app.ui.components.kit.RadioRow
import com.gluview.app.ui.components.kit.SliderRow
import com.gluview.app.ui.components.kit.SwitchRow

/** Estado do Sensor (para o harness desenhar sem ViewModel). */
internal data class SensorUi(
    val dataSource: String,
    val warnEnabled: Boolean,
    val agingDays: Int,
    val noSignalMin: Int,
    val displayMode: String,
    /** Estado da candidatura por feature (pending/waitlisted/approved/rejected). */
    val betaStatus: Map<String, String>,
    val betaEnabled: Boolean,
    /** Variante escolhida/detectada (modelo + região); "" = padrão da fonte. */
    val sensorVariantId: String = ""
)

internal data class SensorActions(
    val onDataSource: (String) -> Unit,
    val onBeta: (DataSourceType) -> Unit,
    val onWarnEnabled: (Boolean) -> Unit,
    val onAgingDays: (Int) -> Unit,
    val onNoSignalMin: (Int) -> Unit,
    val onDisplayMode: (String) -> Unit,
    val onSensorSetup: () -> Unit,
    val onSensorScanner: () -> Unit,
    val onLibre2Activation: () -> Unit,
    val onVariant: (SensorVariant) -> Unit = {}
)

/**
 * Aba principal "Sensor" da barra inferior — fonte de dados e configuração do sensor em si
 * (BLE/NFC, aviso de saúde). Separado de Sincronização (envio ao GluSync, intervalo de
 * polling, retenção), que é outra tela em Ajustes.
 * Só existe pra quem tem sensor próprio (não aparece pra conta seguidor-apenas, checado no
 * chamador via isFollowerOnly).
 */
@Composable
fun SensorSettingsScreen(
    onBack: (() -> Unit)? = null,
    onSensorSetup: () -> Unit,
    onSensorScanner: () -> Unit,
    onLibre2Activation: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val dataSource         by viewModel.dataSource.collectAsState()
    val sensorVariantId    by viewModel.sensorVariantId.collectAsState()
    val sensorWarnEnabled  by viewModel.sensorWarnEnabled.collectAsState()
    val sensorAgingDays    by viewModel.sensorAgingDays.collectAsState()
    val sensorNoSignalMin  by viewModel.sensorNoSignalMin.collectAsState()
    val glucoseDisplayMode by viewModel.glucoseDisplayMode.collectAsState()
    // ADR-0029 — candidatura de beta por sensor "coming soon".
    val sensorBetaStatus     by viewModel.sensorBetaStatus.collectAsState()
    val sensorBetaSubmitting by viewModel.sensorBetaSubmitting.collectAsState()
    val sensorBetaEnabled    by viewModel.sensorBetaEnabled.collectAsState()
    var betaDialogSource by remember { mutableStateOf<DataSourceType?>(null) }
    val sensorBetaMessage by viewModel.sensorBetaMessage.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(Unit) { viewModel.refreshSensorBetaStatus() }
    LaunchedEffect(sensorBetaMessage) {
        sensorBetaMessage?.let { snackbarHostState.showSnackbar(context.getString(it)); viewModel.clearSensorBetaMessage() }
    }

    SensorContent(
        ui = SensorUi(dataSource, sensorWarnEnabled, sensorAgingDays, sensorNoSignalMin, glucoseDisplayMode,
            sensorBetaStatus, sensorBetaEnabled, sensorVariantId),
        actions = SensorActions(viewModel::onDataSourceChange, { betaDialogSource = it }, viewModel::onSensorWarnEnabledChange,
            viewModel::onSensorAgingDaysChange, viewModel::onSensorNoSignalMinChange, viewModel::onGlucoseDisplayModeChange,
            onSensorSetup, onSensorScanner, onLibre2Activation, viewModel::onSensorVariantChange),
        onBack = onBack,
        snackbarHostState = snackbarHostState
    )

    // ADR-0029 — candidatura de beta de um sensor comingSoon. betaState é recarregado a partir
    // do MESMO GET /access-requests/me/status já usado pelos selos da lista.
    betaDialogSource?.let { source ->
        val betaState = sensorBetaStatus[AccessRequestFeatures.sensorBeta(source.id)]
        AlertDialog(
            onDismissRequest = { betaDialogSource = null },
            title = { Text(stringResource(R.string.settings_apply_beta_title, stringResource(source.labelRes))) },
            text = {
                Text(
                    when (betaState) {
                        "pending", "waitlisted" -> stringResource(R.string.settings_beta_already_applied)
                        "approved" -> stringResource(R.string.settings_beta_approved)
                        // Achado 2026-09-26 — antes caía no "else" genérico (parecia nunca ter
                        // candidatado), deixando recandidatar sem avisar que já foi recusado.
                        "rejected" -> stringResource(R.string.settings_beta_rejected)
                        else -> stringResource(R.string.settings_apply_beta_body)
                    },
                    modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())
                )
            },
            confirmButton = {
                if (betaState !in setOf("pending", "waitlisted", "approved")) {
                    TextButton(
                        onClick = {
                            viewModel.applySensorBeta(source.id)
                            betaDialogSource = null
                        },
                        enabled = !sensorBetaSubmitting
                    ) {
                        Text(
                            if (betaState == "rejected") stringResource(R.string.settings_beta_rejected_reapply)
                            else stringResource(R.string.insulin_pump_apply_beta)
                        )
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { betaDialogSource = null }) { Text(stringResource(R.string.settings_cancel)) }
            }
        )
    }
}

/** Rótulo traduzido do modo de leitura (o enum guarda só o id e um rótulo em pt). */
@Composable
private fun displayModeLabel(mode: GlucoseDisplayMode): String = stringResource(when (mode) {
    GlucoseDisplayMode.ALL              -> R.string.settings_display_mode_all
    GlucoseDisplayMode.CONFIRMED_ONLY   -> R.string.settings_display_mode_confirmed
    GlucoseDisplayMode.ALL_WITH_MARKERS -> R.string.settings_display_mode_markers
})

/**
 * Sensor (redesign visual, Fase 6): seções próprias — Fonte de dados, Sensor atual (ação
 * principal em Button para todos os sensores, antes Outlined no Libre e preenchido no Sibionics),
 * Aviso de saúde (SwitchRow + sliders sem trilho pontilhado) e Modo de leitura (opções em linhas,
 * rótulos traduzidos — antes chips com texto fixo em pt). Na lista de fontes, "em breve" com a
 * candidatura desligada aparece desabilitado (antes o toque fechava o menu e nada acontecia) e
 * cada estado da candidatura tem selo.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SensorContent(ui: SensorUi, actions: SensorActions, onBack: (() -> Unit)?, snackbarHostState: SnackbarHostState? = null) {
    val currentSource = DataSourceType.fromId(ui.dataSource)
    val currentVariant = SensorVariantCatalog.resolve(ui.sensorVariantId, ui.dataSource)

    // Aba principal da barra inferior: sem seta (o voltar do sistema continua).
    Page(title = stringResource(R.string.settings_sensor_section_title), onBack = onBack, snackbarHostState = snackbarHostState) {
        GluSection(stringResource(R.string.settings_data_source)) {
            // T12 — seleção por dropdown (antes eram chips/botões).
            var expanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                val label = currentVariant.label()
                OutlinedTextField(
                    value = if (currentSource.isUsableOnThisDevice) label
                            else stringResource(R.string.settings_source_label_unavailable, label),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.settings_data_source)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth()
                )
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    // Uma linha por variante (modelo + região), agrupadas por modelo; variantes com o
                    // mesmo protocolo usam a mesma implementação (SensorVariant.source).
                    SensorVariantCatalog.all.forEach { variant ->
                        val source = variant.source
                        val betaState = ui.betaStatus[AccessRequestFeatures.sensorBeta(source.id)]
                        val name = variant.label()
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(when {
                                        !source.isUsableOnThisDevice -> stringResource(R.string.settings_source_label_unavailable, name)
                                        source.comingSoon || !variant.implemented -> stringResource(R.string.settings_source_label_coming_soon, name)
                                        else -> name
                                    })
                                    variant.region?.let {
                                        Text(stringResource(it.nameRes), style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            },
                            // "Em breve" sem a candidatura ligada: desabilitado (o toque não fazia nada).
                            // Sensor de biblioteca nativa num aparelho 32 bits: idem (nem a beta faz sentido).
                            // Variante sem protocolo implementado (Libre europeu): sempre desabilitada.
                            enabled = variant.implemented && source.isUsableOnThisDevice && (!source.comingSoon || ui.betaEnabled),
                            onClick = {
                                expanded = false
                                if (source.comingSoon) actions.onBeta(source) else actions.onVariant(variant)
                            },
                            trailingIcon = {
                                when {
                                    variant.id == currentVariant.id && source.id == ui.dataSource ->
                                        Icon(Icons.Default.Check, stringResource(R.string.settings_selected))
                                    !source.comingSoon -> Unit
                                    betaState in setOf("pending", "waitlisted") ->
                                        Icon(Icons.Default.HourglassEmpty, stringResource(R.string.settings_beta_pending))
                                    betaState == "approved" ->
                                        Icon(Icons.Default.Verified, stringResource(R.string.access_requests_status_approved),
                                            tint = MaterialTheme.colorScheme.primary)
                                    betaState == "rejected" ->
                                        Icon(Icons.Default.Block, stringResource(R.string.access_requests_status_rejected),
                                            tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        )
                    }
                }
            }
        }

        if (currentSource.isUsableOnThisDevice && (currentSource == DataSourceType.LIBRE2_PLUS || currentSource.isSibionics)) {
            GluSection(stringResource(R.string.settings_current_sensor_section)) {
                if (currentSource == DataSourceType.LIBRE2_PLUS) {
                    Button(onClick = actions.onLibre2Activation, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.settings_activate_sensor_nfc))
                    }
                } else {
                    // Caminho principal: escanear a caixa e deixar o app achar o sensor.
                    Button(onClick = actions.onSensorSetup, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.settings_change_sensor))
                    }
                    // Saída de emergência: lista de dispositivos, código/serial manuais, calibração.
                    TextButton(onClick = actions.onSensorScanner, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.settings_sensor_advanced))
                    }
                }
            }
        }

        if (currentSource.isSibionics) {
            GluSection(stringResource(R.string.settings_sensor_health_warning)) {
                GluCard {
                    SwitchRow(stringResource(R.string.settings_sensor_health_warning_toggle), ui.warnEnabled, actions.onWarnEnabled,
                        description = stringResource(R.string.settings_sensor_health_warning_desc))
                    if (ui.warnEnabled) {
                        // O texto do valor acompanha o estado (o slider é controlado: value = estado).
                        val daysText = pluralStringResource(R.plurals.settings_days_value, ui.agingDays, ui.agingDays)
                        val minutesText = stringResource(R.string.settings_minutes_value, ui.noSignalMin)
                        SliderRow(
                            title = stringResource(R.string.settings_sensor_aging_after),
                            value = ui.agingDays.toFloat(), valueRange = 7f..60f, step = 1f,
                            valueText = { daysText },
                            onValueChange = { actions.onAgingDays(it.toInt()) }
                        )
                        SliderRow(
                            title = stringResource(R.string.settings_no_signal_after),
                            value = ui.noSignalMin.toFloat(), valueRange = 5f..60f, step = 1f,
                            valueText = { minutesText },
                            onValueChange = { actions.onNoSignalMin(it.toInt()) }
                        )
                    }
                }
            }
        }

        // O sensor transmite a cada 1min, mas a .so tem uma janela de confirmação interna antes de
        // "assumir" um novo valor (~5min, batendo com o app oficial SugarJoy). Só aplica ao
        // Sibionics Chinês (v1.1.2) — o Nacional não tem esse conceito exposto.
        if (currentSource == DataSourceType.SIBIONICS_CHINES && currentSource.isUsableOnThisDevice) {
            GluSection(stringResource(R.string.settings_glucose_display_mode),
                description = stringResource(R.string.settings_glucose_display_mode_desc)) {
                GluCard {
                    Column(Modifier.fillMaxWidth().selectableGroup()) {
                        GlucoseDisplayMode.entries.forEach { mode ->
                            RadioRow(displayModeLabel(mode), ui.displayMode == mode.id, { actions.onDisplayMode(mode.id) })
                        }
                    }
                }
            }
        }
    }
}
