package com.gluview.app.ui.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import com.gluview.app.R
import com.gluview.app.data.sibionics.GlucoseDisplayMode
import com.gluview.app.data.source.DataSourceType
import com.gluview.app.data.source.SensorVariantCatalog
import com.gluview.app.ui.components.kit.GluCard
import com.gluview.app.ui.components.kit.GluSection
import com.gluview.app.ui.components.kit.Page
import com.gluview.app.ui.components.kit.RadioRow
import com.gluview.app.ui.components.kit.SliderRow
import com.gluview.app.ui.components.kit.StatusBadge
import com.gluview.app.ui.components.kit.SwitchRow
import com.gluview.app.ui.components.kit.Tone
import com.gluview.app.ui.components.label
import com.gluview.app.ui.theme.Spacing

/** Estado do Sensor (para o harness desenhar sem ViewModel). */
internal data class SensorUi(
    val dataSource: String,
    val warnEnabled: Boolean,
    val agingDays: Int,
    val noSignalMin: Int,
    val displayMode: String,
    /** Variante escolhida/detectada (modelo + região); "" = padrão da fonte. */
    val sensorVariantId: String = "",
    /** Serial do Accu-Chek SmartGuide configurado ("" = nenhum). */
    val smartGuideSerial: String = "",
    /** Serial do Smart 2.0 configurado ("" = nenhum). */
    val medLevSerial: String = ""
)

internal data class SensorActions(
    val onWarnEnabled: (Boolean) -> Unit,
    val onAgingDays: (Int) -> Unit,
    val onNoSignalMin: (Int) -> Unit,
    val onDisplayMode: (String) -> Unit,
    /** Abre o assistente — o único lugar onde se escolhe o sensor (plano 15). */
    val onSensorSetup: () -> Unit,
    val onSensorScanner: () -> Unit,
    val onLibre2Activation: () -> Unit,
    /** Volta a ler só do GluSync (pede confirmação antes). */
    val onStopSensor: () -> Unit = {},
    /** SmartGuide: desfaz o pareamento e abre o assistente para parear de novo com o PIN. */
    val onSmartGuideRepair: () -> Unit = {},
    /** SmartGuide: esquece o sensor configurado (serial, PIN, pareamento). */
    val onSmartGuideForget: () -> Unit = {},
    /** Smart 2.0: desparear (libera o sensor para outro celular). */
    val onMedLevUnpair: () -> Unit = {}
)

/**
 * Aba principal "Sensor" da barra inferior — o sensor em uso e os ajustes dele (BLE/NFC, aviso de
 * saúde). Separado de Sincronização (envio ao GluSync, intervalo de polling, retenção), que é outra
 * tela em Ajustes.
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
    val smartGuideSerial by viewModel.smartGuideSerial.collectAsState()
    val medLevSerial by viewModel.medLevSerial.collectAsState()
    var confirmMedLevUnpair by remember { mutableStateOf(false) }
    var confirmForgetSmartGuide by remember { mutableStateOf(false) }
    var confirmStopSensor by remember { mutableStateOf(false) }
    val context = LocalContext.current

    SensorContent(
        ui = SensorUi(dataSource, sensorWarnEnabled, sensorAgingDays, sensorNoSignalMin, glucoseDisplayMode,
            sensorVariantId, smartGuideSerial, medLevSerial),
        actions = SensorActions(viewModel::onSensorWarnEnabledChange,
            viewModel::onSensorAgingDaysChange, viewModel::onSensorNoSignalMinChange, viewModel::onGlucoseDisplayModeChange,
            onSensorSetup, onSensorScanner, onLibre2Activation,
            onStopSensor = { confirmStopSensor = true },
            onSmartGuideRepair = {
                viewModel.forgetSmartGuidePairing { ok ->
                    if (ok) onSensorSetup()
                    else runCatching { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)) }
                }
            },
            onSmartGuideForget = { confirmForgetSmartGuide = true },
            onMedLevUnpair = { confirmMedLevUnpair = true }),
        onBack = onBack
    )

    if (confirmStopSensor) {
        AlertDialog(
            onDismissRequest = { confirmStopSensor = false },
            title = { Text(stringResource(R.string.settings_stop_sensor_title)) },
            text = { Text(stringResource(R.string.settings_stop_sensor_body)) },
            confirmButton = {
                TextButton(onClick = { confirmStopSensor = false; viewModel.stopUsingSensor() }) {
                    Text(stringResource(R.string.settings_stop_sensor_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmStopSensor = false }) { Text(stringResource(R.string.setup_cancel)) }
            }
        )
    }

    if (confirmMedLevUnpair) {
        AlertDialog(
            onDismissRequest = { confirmMedLevUnpair = false },
            title = { Text(stringResource(R.string.settings_medlev_unpair_title)) },
            text = { Text(stringResource(R.string.settings_medlev_unpair_body)) },
            confirmButton = {
                TextButton(onClick = { confirmMedLevUnpair = false; viewModel.unpairMedLev() }) {
                    Text(stringResource(R.string.settings_medlev_unpair_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmMedLevUnpair = false }) { Text(stringResource(R.string.setup_cancel)) }
            }
        )
    }

    if (confirmForgetSmartGuide) {
        AlertDialog(
            onDismissRequest = { confirmForgetSmartGuide = false },
            title = { Text(stringResource(R.string.settings_smartguide_forget_title)) },
            text = { Text(stringResource(R.string.settings_smartguide_forget_body)) },
            confirmButton = {
                TextButton(onClick = { confirmForgetSmartGuide = false; viewModel.forgetSmartGuide() }) {
                    Text(stringResource(R.string.settings_smartguide_forget_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmForgetSmartGuide = false }) { Text(stringResource(R.string.setup_cancel)) }
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
 * Sensor. Plano 15 (07/11/2026): o dropdown "Fonte de dados" saiu — ele trocava a fonte na hora,
 * sem parear nada, e repetia a lista do assistente. Agora a aba só MOSTRA o sensor atual (um card
 * com nome + região e as ações próprias de cada sensor) e "Trocar de sensor" / "Configurar sensor"
 * abre o assistente, único lugar de escolha (inclusive candidatura de beta dos "Em breve").
 * Seguem Aviso de saúde (Sibionics) e Modo de leitura (Sibionics China).
 */
@Composable
internal fun SensorContent(ui: SensorUi, actions: SensorActions, onBack: (() -> Unit)?, snackbarHostState: SnackbarHostState? = null) {
    val currentSource = DataSourceType.fromId(ui.dataSource)

    // Aba principal da barra inferior: sem seta (o voltar do sistema continua).
    Page(title = stringResource(R.string.settings_sensor_section_title), onBack = onBack, snackbarHostState = snackbarHostState) {
        GluSection(stringResource(R.string.settings_current_sensor_section)) {
            CurrentSensorCard(ui, currentSource)
            CurrentSensorActions(ui, currentSource, actions)
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

/** Nome + região do sensor em uso (ou "nenhum sensor", lendo só do GluSync) e o serial, se houver. */
@Composable
private fun CurrentSensorCard(ui: SensorUi, source: DataSourceType) {
    val noSensor = source == DataSourceType.GLUSYNC
    val variant = SensorVariantCatalog.resolve(ui.sensorVariantId, ui.dataSource)
    val serial = when (source) {
        DataSourceType.ACCU_CHEK_SMARTGUIDE -> ui.smartGuideSerial
        DataSourceType.MEDLEVENSOHN_SMART2  -> ui.medLevSerial
        else -> ""
    }
    GluCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Icon(if (noSensor) Icons.Outlined.CloudQueue else Icons.Outlined.Sensors, null,
                tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                if (noSensor) {
                    Text(stringResource(R.string.settings_no_sensor_title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.settings_no_sensor_body), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text(variant.label(), style = MaterialTheme.typography.titleMedium)
                    variant.region?.let {
                        Text(stringResource(it.nameRes), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (serial.isNotBlank()) {
                        Text(stringResource(R.string.settings_smartguide_serial, serial), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (!source.isUsableOnThisDevice) {
                        StatusBadge(stringResource(R.string.setup_unavailable_on_device), tone = Tone.Warning)
                    }
                }
            }
        }
    }
}

/**
 * Ações do sensor em uso. Todo sensor (e "nenhum") tem o caminho para o assistente — antes GluSync
 * e Libre só trocavam pelo dropdown. As ações próprias de cada um continuam como eram.
 */
@Composable
private fun CurrentSensorActions(ui: SensorUi, source: DataSourceType, actions: SensorActions) {
    val usable = source.isUsableOnThisDevice
    when {
        source == DataSourceType.GLUSYNC -> {
            Button(onClick = actions.onSensorSetup, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_setup_sensor))
            }
        }
        source == DataSourceType.LIBRE2_PLUS && usable -> {
            // Todo Libre novo (14 dias) passa pela ativação NFC — fica como ação principal.
            Button(onClick = actions.onLibre2Activation, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_activate_sensor_nfc))
            }
            OutlinedButton(onClick = actions.onSensorSetup, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_change_sensor))
            }
        }
        source.isSibionics && usable -> {
            // Caminho principal: escanear a caixa e deixar o app achar o sensor.
            Button(onClick = actions.onSensorSetup, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_change_sensor))
            }
            // Saída de emergência: lista de dispositivos, código/serial manuais, calibração.
            TextButton(onClick = actions.onSensorScanner, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_sensor_advanced))
            }
        }
        source == DataSourceType.ACCU_CHEK_SMARTGUIDE -> {
            // Assistente: ler a embalagem (ou digitar o serial), PIN da tampa, parear.
            Button(onClick = actions.onSensorSetup, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(if (ui.smartGuideSerial.isBlank()) R.string.settings_smartguide_setup else R.string.settings_change_sensor))
            }
            if (ui.smartGuideSerial.isNotBlank()) {
                // Sensor levado a outro celular (ou pareamento corrompido): desfaz e pareia de novo.
                OutlinedButton(onClick = actions.onSmartGuideRepair, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.settings_smartguide_repair))
                }
                TextButton(onClick = actions.onSmartGuideForget, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.settings_smartguide_forget))
                }
            }
        }
        source == DataSourceType.MEDLEVENSOHN_SMART2 -> {
            Button(onClick = actions.onSensorSetup, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(if (ui.medLevSerial.isBlank()) R.string.settings_medlev_setup else R.string.settings_change_sensor))
            }
            if (ui.medLevSerial.isNotBlank()) {
                OutlinedButton(onClick = actions.onMedLevUnpair, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.settings_medlev_unpair))
                }
            }
        }
        // Dexcom (só com a liberação de desenvolvimento) e sensor de .so num aparelho 32 bits.
        else -> {
            Button(onClick = actions.onSensorSetup, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_change_sensor))
            }
        }
    }
    if (source.isLocalSensor) {
        TextButton(onClick = actions.onStopSensor, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.settings_stop_sensor), color = MaterialTheme.colorScheme.error)
        }
    }
}
