package com.gluview.app.ui.screen.smartguide

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gluview.app.R
import com.gluview.app.data.accuchek.AccuChekBleManager
import com.gluview.app.data.accuchek.AccuChekBleManager.CalibrationOutcome
import com.gluview.app.data.datastore.AppPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Manda a glicemia de ponta de dedo ao SmartGuide (SOCP `04`); usado pela Home e pela aba Sensor. */
@HiltViewModel
class SmartGuideCalibrationViewModel @Inject constructor(
    private val ble: AccuChekBleManager,
    private val preferences: AppPreferences
) : ViewModel() {

    /** Minuto do sensor = agora − início da sessão salvo pela fonte; próxima em +12 h, como o xDrip. */
    fun calibrate(mgdl: Int, onResult: (CalibrationOutcome) -> Unit) {
        viewModelScope.launch {
            val startMs = preferences.accuChekStartMs.first()
            val outcome = if (startMs <= 0L) {
                CalibrationOutcome.NotConnected
            } else {
                val minute = ((System.currentTimeMillis() - startMs) / 60_000L).toInt()
                ble.calibrate(mgdl, minute, minute + 12 * 60)
            }
            onResult(outcome)
        }
    }
}

/** Janela "Calibrar o SmartGuide": valor do dedo (40–400 mg/dL) e o resultado real do sensor. */
@Composable
fun SmartGuideCalibrationDialog(
    onDismiss: () -> Unit,
    viewModel: SmartGuideCalibrationViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    var text by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    val mgdl = text.trim().toIntOrNull()
    val valid = mgdl != null && mgdl in 40..400
    AlertDialog(
        onDismissRequest = { if (!sending) onDismiss() },
        title = { Text(stringResource(R.string.settings_smartguide_calibrate_title)) },
        text = {
            Column {
                Text(stringResource(R.string.settings_smartguide_calibrate_body))
                OutlinedTextField(
                    value = text,
                    onValueChange = { v -> text = v.filter { it.isDigit() }.take(3) },
                    label = { Text(stringResource(R.string.settings_smartguide_calibrate_hint)) },
                    singleLine = true,
                    enabled = !sending,
                    isError = text.isNotEmpty() && !valid,
                    supportingText = if (text.isNotEmpty() && !valid) {
                        { Text(stringResource(R.string.settings_smartguide_calibrate_invalid)) }
                    } else null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(enabled = valid && !sending, onClick = {
                val value = mgdl ?: return@TextButton
                sending = true
                viewModel.calibrate(value) { outcome ->
                    sending = false
                    onDismiss()
                    Toast.makeText(context, outcomeMessage(context, outcome), Toast.LENGTH_LONG).show()
                }
            }) {
                Text(stringResource(if (sending) R.string.settings_smartguide_calibrate_sending else R.string.settings_smartguide_calibrate_confirm))
            }
        },
        dismissButton = {
            TextButton(enabled = !sending, onClick = onDismiss) { Text(stringResource(R.string.setup_cancel)) }
        }
    )
}

private fun outcomeMessage(context: android.content.Context, outcome: CalibrationOutcome): String = when (outcome) {
    CalibrationOutcome.Accepted -> context.getString(R.string.settings_smartguide_calibrate_ok)
    CalibrationOutcome.Pending -> context.getString(R.string.settings_smartguide_calibrate_pending)
    is CalibrationOutcome.RejectedBySensor -> context.getString(R.string.settings_smartguide_calibrate_rejected_by_sensor)
    is CalibrationOutcome.Rejected -> context.getString(R.string.settings_smartguide_calibrate_rejected, outcome.code)
    CalibrationOutcome.NotConnected -> context.getString(R.string.settings_smartguide_calibrate_not_connected)
    CalibrationOutcome.NoAnswer -> context.getString(R.string.settings_smartguide_calibrate_no_answer)
}
