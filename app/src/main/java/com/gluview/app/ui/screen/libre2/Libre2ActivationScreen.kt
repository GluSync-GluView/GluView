package com.gluview.app.ui.screen.libre2

import androidx.lifecycle.compose.LifecycleResumeEffect
import android.provider.Settings
import android.nfc.NfcAdapter
import android.content.Intent
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
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.gluview.app.R
import com.gluview.app.data.source.DataSourceType
import com.gluview.app.ui.components.glucose.CalibrationCard
import com.gluview.app.ui.components.kit.ConfirmDialog
import com.gluview.app.ui.components.kit.Fact
import com.gluview.app.ui.components.kit.FactList
import com.gluview.app.ui.components.kit.GluCallout
import com.gluview.app.ui.components.kit.GluCard
import com.gluview.app.ui.components.kit.GluSection
import com.gluview.app.ui.components.kit.Page
import com.gluview.app.ui.components.kit.Tone
import com.gluview.app.ui.screen.sensorscan.ForgetButton
import com.gluview.app.ui.theme.Spacing

/** Estado do NFC do aparelho — a ativação do Libre 2 depende dele. */
internal enum class NfcAvailability { ON, OFF, MISSING }

@Composable
fun Libre2ActivationScreen(
    onBack: () -> Unit,
    viewModel: Libre2ActivationViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val calibrationSavedMsg = stringResource(R.string.libre2_calibration_saved_msg)
    var confirmForget by remember { mutableStateOf(false) }
    // NFC desligado: antes a tela ficava "Encoste o sensor" sem nada acontecer. Reavalia ao voltar
    // dos ajustes do sistema.
    var nfc by remember { mutableStateOf(NfcAvailability.ON) }
    LifecycleResumeEffect(Unit) {
        val adapter = NfcAdapter.getDefaultAdapter(context)
        nfc = when {
            adapter == null    -> NfcAvailability.MISSING
            !adapter.isEnabled -> NfcAvailability.OFF
            else               -> NfcAvailability.ON
        }
        onPauseOrDispose {}
    }

    // Ao entrar nesta tela o objetivo já é ler o Libre: arma o reader-mode imediatamente para
    // que o primeiro toque no sensor não dependa de um segundo botão intermediário.
    LaunchedEffect(Unit) { viewModel.autoStartNfcIfNeeded() }
    DisposableEffect(Unit) {
        onDispose { viewModel.stopNfc() }
    }

    LaunchedEffect(state.error) {
        state.error?.let { snackbar.showSnackbar(it.asString(context)); viewModel.clearError() }
    }
    LaunchedEffect(state.calibrated) {
        if (state.calibrated) { snackbar.showSnackbar(calibrationSavedMsg); viewModel.clearCalibrated() }
    }
    // Esquecer para o serviço e apaga a sessão do sensor: confirmação (L26).
    if (confirmForget) {
        ConfirmDialog(
            title = stringResource(R.string.sensor_forget_title),
            message = stringResource(R.string.sensor_forget_body),
            confirmLabel = stringResource(R.string.libre2_forget_sensor),
            onConfirm = { confirmForget = false; viewModel.forget() },
            onDismiss = { confirmForget = false }
        )
    }

    Libre2ActivationContent(
        state = state,
        usable = DataSourceType.LIBRE2_PLUS.isUsableOnThisDevice,
        nfc = nfc,
        onOpenNfcSettings = { runCatching { context.startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) } },
        snackbarHostState = snackbar,
        onBack = onBack,
        onStartNfc = viewModel::startNfc,
        onStopNfc = viewModel::stopNfc,
        onCalibrate = viewModel::calibrate,
        onResetCalibration = viewModel::resetCalibration,
        onForget = { confirmForget = true }
    )
}

/**
 * FreeStyle Libre 2 Plus (redesign visual, Fase 8): um card de estado com 4 aparências —
 * aguardando (ícone NFC, barra de progresso, Cancelar em largura toda), lendo, ativado (com o
 * que acontece agora; antes só aparecia "Sensor: serial") e falha (fica na tela, sem nome de
 * classe da exceção; antes um snackbar que sumia); aviso fixo quando o sensor não habilita o
 * streaming (antes snackbar, com o texto já existente sem uso); calibração no card comum ao
 * Sibionics; esquecer no estilo de perigo e com confirmação; título pelo nome do sensor.
 */
@Composable
internal fun Libre2ActivationContent(
    state: Libre2ActivationUiState,
    usable: Boolean,
    onBack: () -> Unit,
    onStartNfc: () -> Unit,
    onStopNfc: () -> Unit,
    onCalibrate: (Int) -> Unit,
    onResetCalibration: () -> Unit,
    onForget: () -> Unit,
    snackbarHostState: SnackbarHostState? = null,
    nfc: NfcAvailability = NfcAvailability.ON,
    onOpenNfcSettings: () -> Unit = {}
) {
    Page(title = stringResource(DataSourceType.LIBRE2_PLUS.labelRes), onBack = onBack, snackbarHostState = snackbarHostState) {
        GluSection(stringResource(R.string.libre2_activate_sensor), description = stringResource(R.string.libre2_activate_desc)) {
            GluCard {
                when {
                    // Aparelho 32 bits (NativeSensorSupport): sem a biblioteca da Abbott.
                    !usable -> GluCallout(stringResource(R.string.libre2_unavailable_on_device), tone = Tone.Danger)
                    nfc == NfcAvailability.MISSING -> GluCallout(stringResource(R.string.libre2_nfc_missing), tone = Tone.Danger)
                    nfc == NfcAvailability.OFF && !state.activating -> GluCallout(
                        stringResource(R.string.libre2_nfc_off_body), tone = Tone.Warning, announce = true,
                        title = stringResource(R.string.libre2_nfc_off_title),
                        actionLabel = stringResource(R.string.libre2_nfc_open_settings), onAction = onOpenNfcSettings)
                    state.activating -> {
                        NfcStatus(stringResource(R.string.libre2_reading_sensor), stringResource(R.string.libre2_keep_close))
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    state.waitingForTag -> {
                        NfcStatus(stringResource(R.string.libre2_tap_sensor_now), stringResource(R.string.libre2_where_to_tap))
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        OutlinedButton(onClick = onStopNfc, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.settings_cancel))
                        }
                    }
                    else -> {
                        state.readFailure?.let { detail ->
                            GluCallout(stringResource(R.string.libre2_read_failed_body), tone = Tone.Danger, announce = true,
                                title = stringResource(R.string.libre2_read_failed_title))
                            if (detail.isNotBlank()) {
                                Text(stringResource(R.string.common_load_error_detail, detail), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (state.activated) {
                            GluCallout(stringResource(R.string.libre2_activated_body), tone = Tone.Success, announce = true,
                                title = stringResource(R.string.libre2_activated_title))
                        }
                        Button(onClick = onStartNfc, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Icon(Icons.Default.Nfc, null, modifier = Modifier.size(18.dp))
                            Text(stringResource(if (state.readFailure != null) R.string.setup_try_again else R.string.libre2_tap_sensor_nfc),
                                modifier = Modifier.padding(start = Spacing.xs))
                        }
                    }
                }
                if (!state.streamingSupported) {
                    GluCallout(stringResource(R.string.libre2_streaming_not_enabled), tone = Tone.Warning)
                }
                val serial = state.serial.ifBlank { state.savedSerial }
                if (serial.isNotBlank()) FactList(listOf(Fact(stringResource(R.string.sensorscan_serial_number), serial)))
            }
        }

        if (state.savedSerial.isNotBlank()) {
            CalibrationCard(
                title = stringResource(R.string.libre2_calibration_optional),
                description = stringResource(R.string.libre2_calibration_desc),
                rawMmol = state.lastRawMmol,
                factor = state.calFactor,
                onCalibrate = onCalibrate,
                onReset = onResetCalibration
            )
            ForgetButton(stringResource(R.string.libre2_forget_sensor), onForget)
        }
    }
}

@Composable
private fun NfcStatus(title: String, body: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Box(Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Nfc, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
