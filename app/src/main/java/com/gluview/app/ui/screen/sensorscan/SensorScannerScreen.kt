package com.gluview.app.ui.screen.sensorscan

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.SignalCellularAlt
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.gluview.app.ui.screen.sensorsetup.isScannerCancelled
import com.gluview.app.R
import com.gluview.app.data.local.entity.SensorHistoryEntity
import com.gluview.app.ui.components.glucose.CalibrationCard
import com.gluview.app.ui.components.kit.AppDialog
import com.gluview.app.ui.components.kit.ConfirmDialog
import com.gluview.app.ui.components.kit.Fact
import com.gluview.app.ui.components.kit.FactList
import com.gluview.app.ui.components.kit.GluCallout
import com.gluview.app.ui.components.kit.GluCard
import com.gluview.app.ui.components.kit.GluSection
import com.gluview.app.ui.components.kit.Page
import com.gluview.app.ui.components.kit.RowGroup
import com.gluview.app.ui.components.kit.StatusBadge
import com.gluview.app.ui.components.kit.Tone
import com.gluview.app.ui.theme.Spacing
import com.gluview.app.util.formatDate
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.delay

/** Ações da tela (todas do [SensorScanViewModel]), separadas para a captura sem ViewModel. */
internal class SensorScanActions(
    val onBack: () -> Unit = {},
    val onHelp: () -> Unit = {},
    val onScanPackage: () -> Unit = {},
    val onCustomerId: (String) -> Unit = {},
    val onSerial: (String) -> Unit = {},
    val onSaveConfig: () -> Unit = {},
    val onReconnect: () -> Unit = {},
    val onForget: () -> Unit = {},
    val onToggleSearch: () -> Unit = {},
    val onSelectDevice: (String) -> Unit = {},
    val onCalibrate: (Int) -> Unit = {},
    val onResetCalibration: () -> Unit = {}
)

@Composable
fun SensorScannerScreen(
    onBack: () -> Unit,
    viewModel: SensorScanViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var confirmForget by remember { mutableStateOf(false) }

    // Google Code Scanner (ML Kit) — rápido e robusto para DataMatrix; UI do sistema.
    val codeScanner = remember {
        GmsBarcodeScanning.getClient(
            context,
            GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_DATA_MATRIX, Barcode.FORMAT_QR_CODE)
                .enableAutoZoom()
                .build()
        )
    }

    val sensorSavedMsg = stringResource(R.string.sensorscan_saved_msg)
    val calibrationUpdatedMsg = stringResource(R.string.sensorscan_calibration_updated_msg)
    val reconnectingMsg = stringResource(R.string.sensorscan_reconnecting_msg)
    val configErasedMsg = stringResource(R.string.sensorscan_config_erased_msg)

    LaunchedEffect(state.saved) {
        if (state.saved) {
            snackbarHostState.showSnackbar(sensorSavedMsg)
            viewModel.clearSaved()
        }
    }
    LaunchedEffect(state.calibrated) {
        if (state.calibrated) {
            snackbarHostState.showSnackbar(calibrationUpdatedMsg)
            viewModel.clearCalibrated()
        }
    }
    LaunchedEffect(state.reconnected) {
        if (state.reconnected) {
            snackbarHostState.showSnackbar(reconnectingMsg)
            viewModel.clearReconnected()
        }
    }
    LaunchedEffect(state.forgotten) {
        if (state.forgotten) {
            snackbarHostState.showSnackbar(configErasedMsg)
            viewModel.clearForgotten()
        }
    }
    LaunchedEffect(state.scanError) {
        state.scanError?.let {
            snackbarHostState.showSnackbar(it.asString(context))
            viewModel.clearScanError()
        }
    }

    // BLE scan requer BLUETOOTH_SCAN + BLUETOOTH_CONNECT (API 31+) ou ACCESS_FINE_LOCATION (< 31)
    val blePerms = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results -> if (results.values.all { it }) viewModel.startScan() }

    fun checkAndScan() {
        val allGranted = blePerms.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (allGranted) viewModel.startScan() else permLauncher.launch(blePerms)
    }

    if (state.showHelp) HelpDialog(onDismiss = viewModel::onDismissHelp)
    // Esquecer para o serviço e encerra o sensor no histórico: confirmação (L26).
    if (confirmForget) {
        ConfirmDialog(
            title = stringResource(R.string.sensor_forget_title),
            message = stringResource(R.string.sensor_forget_body),
            confirmLabel = stringResource(R.string.sensorscan_forget_sensor),
            onConfirm = { confirmForget = false; viewModel.forgetSensor() },
            onDismiss = { confirmForget = false }
        )
    }

    SensorScannerContent(
        state = state,
        snackbarHostState = snackbarHostState,
        actions = SensorScanActions(
            onBack = { viewModel.stopScan(); onBack() },
            onHelp = viewModel::onShowHelp,
            onScanPackage = {
                codeScanner.startScan()
                    .addOnSuccessListener { barcode -> barcode.rawValue?.let { viewModel.onScanResult(it) } }
                    .addOnCanceledListener { /* usuário cancelou — ignora */ }
                    .addOnFailureListener { e -> if (!e.isScannerCancelled()) viewModel.onScannerUnavailable() }
            },
            onCustomerId = viewModel::onCustomerIdChange,
            onSerial = viewModel::onSerialChange,
            onSaveConfig = viewModel::saveConfig,
            onReconnect = viewModel::reconnectSensor,
            onForget = { confirmForget = true },
            onToggleSearch = { if (state.scanning) viewModel.stopScan() else checkAndScan() },
            onSelectDevice = viewModel::saveSensor,
            onCalibrate = viewModel::calibrate,
            onResetCalibration = viewModel::resetCalibration
        )
    )
}

/** Depois disso buscando sem achar nada, a tela explica (a busca continua). */
private const val EMPTY_SEARCH_HINT_MS = 15_000L

/**
 * Ajustes avançados do sensor Sibionics (redesign visual, Fase 8): título igual ao da entrada
 * ("Ajustes avançados do sensor", antes "Selecionar sensor BLE"); seções na ordem de uso —
 * Sensor atual (rótulo → valor, reconectar e esquecer com confirmação) → Conectar um sensor
 * (passo 1 caixa, passo 2 Bluetooth) → Ajuste fino → Histórico — com um só estilo de card (eram
 * 5, um deles rosa fora da marca); ajuste fino no card de calibração comum ao Libre (título
 * legível no claro, teclado numérico, botões empilhados); busca com barra e dica quando nada
 * aparece; dispositivos em linhas neutras com "Selecionado"; datas no idioma.
 */
@Composable
internal fun SensorScannerContent(
    state: SensorScanUiState,
    actions: SensorScanActions,
    snackbarHostState: SnackbarHostState? = null
) {
    Page(
        title = stringResource(R.string.sensorscan_title),
        onBack = actions.onBack,
        snackbarHostState = snackbarHostState
    ) {
        val configured = state.savedMac.isNotBlank()
        if (configured) CurrentSensorSection(state, actions)

        // Ajuda na seção que ela explica (na barra, o ícone cortava o título).
        GluSection(stringResource(R.string.sensorscan_connect_section), onHelp = actions.onHelp) {
            GluCard {
                StepTitle(stringResource(R.string.sensorscan_step1_title))
                Text(stringResource(R.string.sensorscan_step1_desc), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = actions.onScanPackage, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Icon(Icons.Default.QrCodeScanner, null, modifier = Modifier.padding(end = Spacing.xs).size(18.dp))
                    Text(stringResource(R.string.sensorscan_scan_package_code))
                }
                OutlinedTextField(
                    value = state.customerId,
                    onValueChange = actions.onCustomerId,
                    label = { Text(stringResource(R.string.sensorscan_connection_code)) },
                    placeholder = { Text(stringResource(R.string.sensorscan_connection_code_example)) },
                    singleLine = true,
                    supportingText = { Text(stringResource(R.string.sensorscan_connection_code_help)) },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = state.serial,
                    onValueChange = actions.onSerial,
                    label = { Text(stringResource(R.string.sensorscan_serial_number)) },
                    placeholder = { Text(stringResource(R.string.sensorscan_serial_number_example)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                // Salvar ID/série mantendo o MAC já configurado (sensor conectado some da lista).
                if (configured) {
                    OutlinedButton(onClick = actions.onSaveConfig, enabled = state.customerId.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.sensorscan_save_code_serial))
                    }
                }
            }
            GluCard {
                StepTitle(stringResource(R.string.sensorscan_step2_title))
                Text(stringResource(R.string.sensorscan_step2_desc), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = actions.onToggleSearch, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(if (state.scanning) R.string.sensorscan_stop_search else R.string.sensorscan_search_nearby))
                }
                if (state.scanning) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(stringResource(R.string.sensorscan_searching_nearby), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    var longSearch by remember { mutableStateOf(false) }
                    LaunchedEffect(Unit) { delay(EMPTY_SEARCH_HINT_MS); longSearch = true }
                    if (longSearch && state.devices.isEmpty()) {
                        GluCallout(stringResource(R.string.sensorscan_nothing_found), tone = Tone.Info,
                            title = stringResource(R.string.sensorscan_nothing_found_title))
                    }
                }
            }
            if (state.devices.isNotEmpty()) {
                Text(stringResource(R.string.sensorscan_devices_found, state.devices.size), style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = Spacing.xs).semantics { heading() })
                state.devices.forEach { device ->
                    DeviceRow(device, isCurrent = device.mac == state.savedMac) { actions.onSelectDevice(device.mac) }
                }
            }
        }

        // Calibração da glicemia — OPCIONAL (valor de fábrica funciona sem calibrar)
        if (configured) {
            CalibrationCard(
                title = stringResource(R.string.sensorscan_fine_tune_title),
                description = stringResource(R.string.sensorscan_fine_tune_desc),
                rawMmol = state.lastRawMmol,
                factor = state.calFactor,
                onCalibrate = actions.onCalibrate,
                onReset = actions.onResetCalibration
            )
        }

        // Histórico local de sensores (3 meses) — mesmo modelo do histórico de glicemia
        if (state.history.isNotEmpty()) {
            GluSection(stringResource(R.string.sensorscan_history_title)) {
                GluCard { RowGroup(state.history.take(20).map { entry -> { HistoryRow(entry) } }) }
            }
        }
    }
}

@Composable
private fun StepTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
}

@Composable
private fun CurrentSensorSection(state: SensorScanUiState, actions: SensorScanActions) {
    GluSection(stringResource(R.string.sensorscan_current_section)) {
        GluCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                // Ponto desenhado (antes "●" em texto, que o TalkBack lia como símbolo).
                Box(Modifier.size(10.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                Text(stringResource(R.string.sensorscan_connected_now), style = MaterialTheme.typography.titleSmall)
            }
            FactList(buildList {
                if (state.savedSerial.isNotBlank()) add(Fact(stringResource(R.string.sensorscan_serial_number), state.savedSerial, wide = true))
                add(Fact(stringResource(R.string.sensorscan_connection_code),
                    state.savedCustomerId.ifBlank { stringResource(R.string.sensorscan_missing_code_short) },
                    valueColor = if (state.savedCustomerId.isBlank()) MaterialTheme.colorScheme.error else null))
                add(Fact(stringResource(R.string.sensorscan_mac_label), breakableMac(state.savedMac)))
            })
            if (state.savedCustomerId.isBlank()) {
                GluCallout(stringResource(R.string.sensorscan_missing_connection_code), tone = Tone.Warning)
            }
            Button(onClick = actions.onReconnect, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Icon(Icons.Default.Refresh, null, modifier = Modifier.padding(end = Spacing.xs).size(18.dp))
                Text(stringResource(R.string.sensorscan_reconnect_sensor))
            }
            ForgetButton(stringResource(R.string.sensorscan_forget_sensor), actions.onForget)
        }
    }
}

/** MAC com pontos de quebra depois de cada ":" (com fonte grande ele quebrava no meio de um par). */
private fun breakableMac(mac: String) = mac.replace(":", ":\u200B")

/** Esquecer sensor: mesmo estilo de perigo no Sibionics e no Libre (antes só um deles era vermelho). */
@Composable
internal fun ForgetButton(label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
    ) {
        Icon(Icons.Default.Delete, null, modifier = Modifier.padding(end = Spacing.xs).size(18.dp))
        Text(label)
    }
}

@Composable
private fun HelpDialog(onDismiss: () -> Unit) {
    // Corpo rolável (5 parágrafos cortavam com fonte grande).
    val body = listOf(R.string.sensorscan_help_intro, R.string.sensorscan_help_step1, R.string.sensorscan_help_step2,
        R.string.sensorscan_help_step3, R.string.sensorscan_help_step4).map { stringResource(it) }.joinToString("\n\n")
    AppDialog(
        title = stringResource(R.string.sensorscan_help_title),
        body = body,
        onDismiss = onDismiss,
        confirmLabel = stringResource(R.string.home_understood)
    )
}

@Composable
private fun HistoryRow(entry: SensorHistoryEntity) {
    val isActive = entry.endedAt == null
    Row(Modifier.fillMaxWidth().padding(vertical = Spacing.xxs), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Column(Modifier.weight(1f)) {
            Text(entry.serial?.takeIf { it.isNotBlank() } ?: entry.mac, style = MaterialTheme.typography.bodyMedium)
            Text(
                if (isActive) stringResource(R.string.sensorscan_in_use_since, formatDate(entry.startedAt))
                else stringResource(R.string.sensorscan_used_from_to, formatDate(entry.startedAt), formatDate(entry.endedAt!!)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (isActive) StatusBadge(stringResource(R.string.sensorscan_current), tone = Tone.Info)
    }
}

/**
 * Sensor achado na busca. Só chegam nomes "LT…" (o gerenciador BLE filtra), então o destaque
 * "Sensor Sibionics" valia para todos e saiu; fica o nome, o MAC, o sinal e o que o toque faz.
 */
@Composable
private fun DeviceRow(device: FoundDevice, isCurrent: Boolean, onSelect: () -> Unit) {
    val signal = when {
        device.rssi >= -60 -> stringResource(R.string.sensorscan_signal_strong)
        device.rssi >= -80 -> stringResource(R.string.sensorscan_signal_medium)
        else               -> stringResource(R.string.sensorscan_signal_weak)
    }
    GluCard(onClick = onSelect, selected = isCurrent) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(device.name ?: stringResource(R.string.sensorscan_bluetooth_device), style = MaterialTheme.typography.titleSmall)
                Text(breakableMac(device.mac), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                    Icon(Icons.Outlined.SignalCellularAlt, null, modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(signal, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (isCurrent) StatusBadge(stringResource(R.string.sensorscan_selected), tone = Tone.Info)
            else Text(stringResource(R.string.sensorscan_tap_to_use), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary)
        }
    }
}
