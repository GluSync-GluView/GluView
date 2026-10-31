package com.gluview.app.ui.screen.sensorsetup

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.mlkit.common.MlKitException
import com.gluview.app.R
import com.gluview.app.data.sensor.FailureReason
import com.gluview.app.data.sensor.ScanNotice
import com.gluview.app.data.sensor.SetupState
import com.gluview.app.data.source.DataSourceType
import com.gluview.app.ui.components.label
import com.gluview.app.ui.components.kit.GluCallout
import com.gluview.app.ui.components.kit.GluCard
import com.gluview.app.ui.components.kit.GluSection
import com.gluview.app.ui.components.kit.ListSkeleton
import com.gluview.app.ui.components.kit.Page
import com.gluview.app.ui.components.kit.StatusBadge
import com.gluview.app.ui.components.kit.Tone
import com.gluview.app.ui.theme.Spacing

/**
 * Fluxo guiado de configuração e troca de sensor.
 *
 * Mesmo caminho para o primeiro sensor e para a troca: escolher o sensor → pegar a caixa →
 * escanear → o app encontra e conecta sozinho. O usuário nunca escolhe um dispositivo Bluetooth,
 * nunca vê endereço de hardware e nunca precisa saber que existe um scan acontecendo.
 *
 * A tela antiga (lista de dispositivos, código/serial manuais, calibração) continua existindo em
 * "Ajustes avançados do sensor" — é a saída de emergência para casos que fujam da regra.
 */
@Composable
fun SensorSetupScreen(
    onDone: () -> Unit,
    onAdvanced: () -> Unit,
    onLibre2Activation: () -> Unit = {},
    viewModel: SensorSetupViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val options by viewModel.options.collectAsState()
    val context = LocalContext.current

    val codeScanner = remember {
        GmsBarcodeScanning.getClient(
            context,
            GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_DATA_MATRIX, Barcode.FORMAT_QR_CODE)
                .enableAutoZoom()
                .build()
        )
    }

    // Permissões de Bluetooth são pedidas ANTES de abrir a câmera: descobrir só depois de
    // escanear que faltava permissão obrigaria o usuário a repetir o passo do código.
    val blePerms = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    // L19: cancelar ou falhar a leitura volta ao passo da caixa com o motivo (antes ficava preso
    // em "Lendo o código" sem câmera nenhuma aberta).
    fun openScanner() {
        codeScanner.startScan()
            .addOnSuccessListener { barcode ->
                barcode.rawValue?.let { viewModel.onPackageScanned(it) } ?: viewModel.scanAborted(ScanNotice.SCAN_CANCELLED)
            }
            .addOnCanceledListener { viewModel.scanAborted(ScanNotice.SCAN_CANCELLED) }
            // O leitor do Google entrega o "voltar" do usuário como falha, não como cancelamento.
            .addOnFailureListener { e ->
                android.util.Log.w("SensorSetup", "Leitor de código falhou: ${e.javaClass.name} ${(e as? MlKitException)?.errorCode}", e)
                viewModel.scanAborted(if (e.isScannerCancelled()) ScanNotice.SCAN_CANCELLED else ScanNotice.CAMERA_FAILED)
            }
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results -> if (results.values.all { it }) openScanner() else viewModel.scanAborted(ScanNotice.PERMISSION_DENIED) }

    fun blePermsGranted() = blePerms.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    fun scanWithPermissions() {
        if (blePermsGranted()) openScanner() else permLauncher.launch(blePerms)
    }

    // Tentar de novo com a caixa já lida: pede a permissão que faltar e repete só a busca.
    val retryPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { if (viewModel.retry()) scanWithPermissions() }

    fun retry() {
        val failed = state as? SetupState.Failed
        if (failed?.code != null && !blePermsGranted()) retryPermLauncher.launch(blePerms)
        else if (viewModel.retry()) scanWithPermissions()
    }

    LaunchedEffect(Unit) { viewModel.start() }

    SensorSetupContent(
        state = state,
        options = options,
        onBack = { viewModel.cancel(); onDone() },
        // L19: Libre 2 Plus se ativa por NFC — antes o assistente pedia a caixa e a lia como
        // Sibionics ("Este código não é de um sensor compatível").
        onChoose = { option ->
            viewModel.rememberVariant(option)
            if (option.type == DataSourceType.LIBRE2_PLUS) { viewModel.cancel(); onLibre2Activation() } else viewModel.choose(option.type)
        },
        onContinue = { viewModel.readyToScan(); scanWithPermissions() },
        onCancel = viewModel::cancel,
        onDone = { viewModel.cancel(); onDone() },
        onRetry = ::retry,
        onBluetoothSettings = {
            runCatching { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)) }
        },
        onAdvanced = onAdvanced
    )
}

/**
 * Assistente de sensor (redesign visual, Fase 8): "Passo N de 3" (Escolher · Escanear ·
 * Conectar; antes nada dizia onde se estava); título neutro "Configurar sensor" (dizia "Trocar"
 * também no 1º sensor); disponíveis em linhas com seta e o atual com selo, "Em breve" numa lista
 * compacta que não parece clicável (antes 5 de 7 cards desabilitados dominavam a tela); espera com
 * ícone do passo e barra de progresso (antes um spinner solto); ações secundárias em largura toda.
 */
@Composable
internal fun SensorSetupContent(
    state: SetupState,
    options: List<SensorOption>,
    onBack: () -> Unit,
    onChoose: (SensorOption) -> Unit,
    onContinue: () -> Unit,
    onCancel: () -> Unit,
    onDone: () -> Unit,
    onRetry: () -> Unit,
    onAdvanced: () -> Unit,
    onBluetoothSettings: () -> Unit = {}
) {
    Page(title = stringResource(R.string.setup_title), onBack = onBack) {
        stepOf(state)?.let { StepIndicator(it) }
        when (state) {
            is SetupState.Idle, is SetupState.ChoosingSensor -> ChooseSensorStep(options, onChoose)
            is SetupState.Instructions -> InstructionsStep(state.notice, onContinue)
            is SetupState.ReadingPackage -> WaitingStep(Icons.Default.QrCodeScanner,
                stringResource(R.string.setup_scanning_title), stringResource(R.string.setup_scanning_body), onCancel)
            is SetupState.Searching -> WaitingStep(Icons.Default.BluetoothSearching,
                stringResource(R.string.setup_searching_title), stringResource(R.string.setup_searching_body), onCancel)
            is SetupState.Connecting -> WaitingStep(Icons.Default.BluetoothSearching,
                stringResource(R.string.setup_connecting_title), stringResource(R.string.setup_connecting_body), null)
            is SetupState.Ready -> ReadyStep(state.warmupMinutes, onDone)
            is SetupState.Failed -> FailedStep(state.reason, onRetry, onAdvanced, onBluetoothSettings)
        }
    }
}

/** Passo só de apresentação: escolher → 1; caixa/câmera → 2; busca/conexão → 3. */
private fun stepOf(state: SetupState): Int? = when (state) {
    is SetupState.Idle, is SetupState.ChoosingSensor -> 1
    is SetupState.Instructions, is SetupState.ReadingPackage -> 2
    is SetupState.Searching, is SetupState.Connecting -> 3
    is SetupState.Failed -> if (state.reason == FailureReason.INVALID_CODE) 2 else 3
    is SetupState.Ready -> null
}

@Composable
private fun StepIndicator(step: Int) {
    val labels = listOf(R.string.setup_step_choose, R.string.setup_step_scan, R.string.setup_step_connect).map { stringResource(it) }
    val description = stringResource(R.string.setup_step_of, step, labels.size, labels[step - 1])
    Column(Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = description },
        verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
        Text(description, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
            repeat(labels.size) { i ->
                Box(
                    Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp))
                        .background(if (i < step) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)
                )
            }
        }
    }
}

@Composable
private fun ColumnScope.StepHeader(title: String, body: String) {
    Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.semantics { heading() })
    Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ColumnScope.ChooseSensorStep(options: List<SensorOption>, onChoose: (SensorOption) -> Unit) {
    StepHeader(stringResource(R.string.setup_choose_title), stringResource(R.string.setup_choose_body))
    if (options.isEmpty()) {
        ListSkeleton(rows = 2)
        return
    }
    val (available, later) = options.partition { it.isSupported }
    if (available.isNotEmpty()) {
        GluSection(stringResource(R.string.setup_available)) {
            available.forEach { option ->
                GluCard(onClick = { onChoose(option) }, selected = option.isCurrent) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                        modifier = Modifier.heightIn(min = 40.dp)) {
                        Icon(Icons.Outlined.Sensors, null, tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                            Text(option.variant.label(), style = MaterialTheme.typography.titleMedium)
                            option.variant.region?.let {
                                Text(stringResource(it.nameRes), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (option.isCurrent) StatusBadge(stringResource(R.string.setup_in_use), tone = Tone.Info)
                        }
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
    if (later.isNotEmpty()) {
        // Lista simples, sem cara de clicável: o estado vai na descrição da seção.
        GluSection(stringResource(R.string.setup_coming_soon), description = stringResource(R.string.setup_coming_soon_desc)) {
            GluCard {
                later.forEach { option ->
                    Text(
                        if (option.unavailableOnDevice)
                            stringResource(R.string.setup_label_with_note, option.variant.label(),
                                stringResource(R.string.setup_unavailable_on_device))
                        else option.variant.label(),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.InstructionsStep(notice: ScanNotice?, onContinue: () -> Unit) {
    StepIcon(Icons.Default.QrCodeScanner)
    StepHeader(stringResource(R.string.setup_package_title), stringResource(R.string.setup_package_body))
    notice?.let {
        GluCallout(stringResource(when (it) {
            ScanNotice.PERMISSION_DENIED -> R.string.setup_notice_permission
            ScanNotice.SCAN_CANCELLED -> R.string.setup_notice_cancelled
            ScanNotice.CAMERA_FAILED -> R.string.setup_notice_camera
        }), tone = if (it == ScanNotice.SCAN_CANCELLED) Tone.Info else Tone.Warning, announce = true)
    }
    Button(onClick = onContinue, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text(stringResource(R.string.setup_package_cta), textAlign = TextAlign.Center)
    }
    Text(stringResource(R.string.setup_package_hint), style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ColumnScope.WaitingStep(icon: ImageVector, title: String, body: String, onCancel: (() -> Unit)?) {
    StepIcon(icon)
    StepHeader(title, body)
    LinearProgressIndicator(Modifier.fillMaxWidth())
    if (onCancel != null) {
        OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.setup_cancel)) }
    }
}

@Composable
private fun ColumnScope.ReadyStep(warmupMinutes: Int, onDone: () -> Unit) {
    StepIcon(Icons.Default.CheckCircle, tone = Tone.Success)
    StepHeader(stringResource(R.string.setup_ready_title), stringResource(R.string.setup_ready_body, warmupMinutes))
    Button(onClick = onDone, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text(stringResource(R.string.setup_ready_cta))
    }
}

@Composable
private fun ColumnScope.FailedStep(reason: FailureReason, onRetry: () -> Unit, onAdvanced: () -> Unit, onBluetoothSettings: () -> Unit) {
    StepIcon(Icons.Default.ErrorOutline, tone = Tone.Danger)
    StepHeader(
        title = stringResource(when (reason) {
            FailureReason.INVALID_CODE          -> R.string.setup_error_code_title
            FailureReason.SENSOR_NOT_FOUND      -> R.string.setup_error_notfound_title
            FailureReason.BLUETOOTH_UNAVAILABLE -> R.string.setup_error_bluetooth_title
            FailureReason.BLUETOOTH_PERMISSION  -> R.string.setup_error_permission_title
            FailureReason.UNKNOWN               -> R.string.setup_error_unknown_title
        }),
        body = stringResource(when (reason) {
            FailureReason.INVALID_CODE          -> R.string.setup_error_code_body
            FailureReason.SENSOR_NOT_FOUND      -> R.string.setup_error_notfound_body
            FailureReason.BLUETOOTH_UNAVAILABLE -> R.string.setup_error_bluetooth_body
            FailureReason.BLUETOOTH_PERMISSION  -> R.string.setup_error_permission_body
            FailureReason.UNKNOWN               -> R.string.setup_error_unknown_body
        })
    )
    // Bluetooth desligado: atalho para os ajustes do Bluetooth (L19).
    if (reason == FailureReason.BLUETOOTH_UNAVAILABLE) {
        OutlinedButton(onClick = onBluetoothSettings, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.setup_open_bluetooth_settings), textAlign = TextAlign.Center)
        }
    }
    Button(onClick = onRetry, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text(stringResource(if (reason == FailureReason.BLUETOOTH_PERMISSION) R.string.setup_allow_and_retry else R.string.setup_try_again))
    }
    OutlinedButton(onClick = onAdvanced, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.setup_advanced_link), textAlign = TextAlign.Center)
    }
}

/** Ilustração do passo: ícone num círculo no tom (primário por padrão). */
@Composable
private fun StepIcon(icon: ImageVector, tone: Tone = Tone.Info) {
    val (bg, fg) = when (tone) {
        Tone.Danger -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        Tone.Success -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
    }
    Box(Modifier.padding(top = Spacing.xs).size(72.dp).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(40.dp), tint = fg)
    }
}

/**
 * O usuário fechou o leitor de código. O Google entrega isso como falha: no S23 FE (18/10/2026,
 * play-services-code-scanner 16.1.0) o "voltar" chega como MlKitException INTERNAL (13, "Failed to
 * scan code"); versões novas usam CODE_SCANNER_CANCELLED (201).
 */
internal fun Exception.isScannerCancelled(): Boolean =
    (this as? MlKitException)?.errorCode.let { it == MlKitException.CODE_SCANNER_CANCELLED || it == MlKitException.INTERNAL }
