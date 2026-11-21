package com.gluview.app.ui.screen.libre3

import android.content.Intent
import android.nfc.NfcAdapter
import android.provider.Settings
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.gluview.app.R
import com.gluview.app.data.source.DataSourceType
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

private enum class Libre3Nfc { ON, OFF, MISSING }

/** FreeStyle Libre 3: ativar sensor novo ou assumir um já ativo, por NFC (plano 21, F6.1). */
@Composable
fun Libre3ActivationScreen(
    onBack: () -> Unit,
    viewModel: Libre3ActivationViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var confirmForget by remember { mutableStateOf(false) }
    var nfc by remember { mutableStateOf(Libre3Nfc.ON) }
    LifecycleResumeEffect(Unit) {
        val adapter = NfcAdapter.getDefaultAdapter(context)
        nfc = when {
            adapter == null -> Libre3Nfc.MISSING
            !adapter.isEnabled -> Libre3Nfc.OFF
            else -> Libre3Nfc.ON
        }
        onPauseOrDispose {}
    }
    LaunchedEffect(Unit) { viewModel.autoStartNfcIfNeeded() }
    DisposableEffect(Unit) { onDispose { viewModel.stopNfc() } }

    if (confirmForget) {
        ConfirmDialog(
            title = stringResource(R.string.sensor_forget_title),
            message = stringResource(R.string.sensor_forget_body),
            confirmLabel = stringResource(R.string.libre3_forget_sensor),
            onConfirm = { confirmForget = false; viewModel.forget() },
            onDismiss = { confirmForget = false }
        )
    }

    val usable = DataSourceType.LIBRE3.isUsableOnThisDevice
    Page(title = stringResource(R.string.sensor_model_libre3), onBack = onBack) {
        GluCallout(stringResource(R.string.libre3_untested_warning), tone = Tone.Warning)
        GluSection(stringResource(R.string.libre3_activate_title), description = stringResource(R.string.libre3_activate_desc)) {
            GluCard {
                when {
                    !usable -> GluCallout(stringResource(R.string.libre3_unavailable_on_device), tone = Tone.Danger)
                    nfc == Libre3Nfc.MISSING -> GluCallout(stringResource(R.string.libre3_nfc_missing), tone = Tone.Danger)
                    nfc == Libre3Nfc.OFF && !state.activating -> GluCallout(
                        stringResource(R.string.libre3_nfc_off_body), tone = Tone.Warning, announce = true,
                        title = stringResource(R.string.libre2_nfc_off_title),
                        actionLabel = stringResource(R.string.libre2_nfc_open_settings),
                        onAction = { runCatching { context.startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) } })
                    state.activating -> {
                        NfcRow(stringResource(R.string.libre2_reading_sensor), stringResource(R.string.libre2_keep_close))
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    state.waitingForTag -> {
                        NfcRow(stringResource(R.string.libre2_tap_sensor_now), stringResource(R.string.libre2_where_to_tap))
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        OutlinedButton(onClick = viewModel::stopNfc, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.settings_cancel))
                        }
                    }
                    else -> {
                        ResultCallout(state.result)
                        Button(onClick = viewModel::startNfc, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Icon(Icons.Default.Nfc, null, modifier = Modifier.size(18.dp))
                            Text(stringResource(R.string.libre2_tap_sensor_nfc), modifier = Modifier.padding(start = Spacing.xs))
                        }
                    }
                }
                if (state.savedSerial.isNotBlank()) {
                    FactList(listOf(Fact(stringResource(R.string.sensorscan_serial_number), state.savedSerial)))
                }
            }
        }
        GluSection(stringResource(R.string.libre3_account_id_label), description = stringResource(R.string.libre3_account_id_help)) {
            GluCard {
                OutlinedTextField(
                    value = state.accountId,
                    onValueChange = viewModel::onAccountIdChange,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    label = { Text(stringResource(R.string.libre3_account_id_field)) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        if (state.savedSerial.isNotBlank()) ForgetButton(stringResource(R.string.libre3_forget_sensor)) { confirmForget = true }
    }
}

@Composable
private fun ResultCallout(result: Libre3ActivationResult?) {
    when (result) {
        null -> Unit
        is Libre3ActivationResult.Activated -> GluCallout(
            stringResource(R.string.libre3_activated_new_body, result.warmupMin), tone = Tone.Success, announce = true,
            title = stringResource(R.string.libre3_activated_title))
        Libre3ActivationResult.TakenOver -> GluCallout(
            stringResource(R.string.libre3_taken_over_body), tone = Tone.Success, announce = true,
            title = stringResource(R.string.libre3_activated_title))
        Libre3ActivationResult.WrongAccount -> GluCallout(
            stringResource(R.string.libre3_wrong_account_body), tone = Tone.Warning, announce = true,
            title = stringResource(R.string.libre3_wrong_account_title))
        Libre3ActivationResult.Ended -> GluCallout(stringResource(R.string.libre3_ended), tone = Tone.Danger, announce = true)
        Libre3ActivationResult.NotLibre3 -> GluCallout(stringResource(R.string.libre3_not_libre3), tone = Tone.Warning, announce = true)
        Libre3ActivationResult.Lingo -> GluCallout(stringResource(R.string.libre3_lingo), tone = Tone.Warning, announce = true)
        is Libre3ActivationResult.Failed -> {
            GluCallout(stringResource(R.string.libre3_failed_body), tone = Tone.Danger, announce = true,
                title = stringResource(R.string.libre3_failed_title))
            if (result.detail.isNotBlank()) Text(stringResource(R.string.common_load_error_detail, result.detail),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun NfcRow(title: String, body: String) {
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
