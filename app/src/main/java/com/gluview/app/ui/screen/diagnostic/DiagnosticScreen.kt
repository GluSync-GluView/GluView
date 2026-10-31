package com.gluview.app.ui.screen.diagnostic

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.navigation.compose.hiltViewModel
import com.gluview.app.R
import com.gluview.app.ui.components.chart.rememberChartAppearance
import com.gluview.app.ui.components.kit.Fact
import com.gluview.app.ui.components.kit.FactList
import com.gluview.app.ui.components.kit.GluCallout
import com.gluview.app.ui.components.kit.GluCard
import com.gluview.app.ui.components.kit.GluSection
import com.gluview.app.ui.components.kit.ListSkeleton
import com.gluview.app.ui.components.kit.Page
import com.gluview.app.ui.components.kit.Tone
import com.gluview.app.ui.theme.Spacing
import com.gluview.app.util.TimeFormat
import com.gluview.app.util.formatElapsed
import com.gluview.app.util.formatTime
import java.util.Locale
import kotlin.math.abs

@Composable
fun DiagnosticScreen(
    onBack: () -> Unit,
    vm: DiagnosticViewModel = hiltViewModel()
) {
    val state by vm.uiState.collectAsState()
    DiagnosticContent(state, rememberChartAppearance().timeFormat, vm::recordFingerstick, onBack)
}

/**
 * Diagnóstico (redesign visual, Fase 7): seções "Conexão com a nuvem" e "Sensor" em rótulo →
 * valor com o status ESCRITO (antes um ponto colorido de 6dp sem texto para o TalkBack, com "ok"
 * em azul); última sincronização "há X" (antes "HH:mm dd/MM"); último erro num aviso; seção do
 * sensor com nome genérico (antes "Sibionics" até no Libre 2); bateria baixa por extenso;
 * validação com o motivo do "Registrar" desligado e comparações que não se sobrepõem.
 */
@Composable
internal fun DiagnosticContent(
    state: DiagnosticUiState?,
    timeFormat: TimeFormat,
    onRecord: (Double) -> Unit,
    onBack: () -> Unit,
    nowMs: Long = System.currentTimeMillis()
) {
    val context = LocalContext.current
    val error = MaterialTheme.colorScheme.error
    Page(title = stringResource(R.string.settings_diagnostic_section), onBack = onBack) {
        if (state == null) {
            ListSkeleton(rows = 3)
            return@Page
        }

        GluSection(stringResource(R.string.diagnostic_cloud_section)) {
            GluCard {
                FactList(buildList {
                    add(Fact(stringResource(R.string.diagnostic_glusync_service),
                        stringResource(if (state.serviceRunning) R.string.diagnostic_running else R.string.diagnostic_stopped),
                        valueColor = if (state.serviceRunning) null else error))
                    if (!state.isSibionicsActive) {
                        add(Fact(stringResource(R.string.diagnostic_signalr_realtime),
                            stringResource(if (state.signalRConnected) R.string.diagnostic_connected else R.string.diagnostic_disconnected),
                            valueColor = if (state.signalRConnected) null else error))
                        add(Fact(stringResource(R.string.diagnostic_last_sync),
                            if (state.lastSyncAt > 0L) context.formatElapsed(state.lastSyncAt, nowMs) else stringResource(R.string.diagnostic_never),
                            valueColor = if (state.lastSyncAt > 0L) null else error))
                    }
                })
            }
            state.syncError?.let { GluCallout(it, tone = Tone.Danger, title = stringResource(R.string.diagnostic_last_error)) }
        }

        // T27 — sensor local (Sibionics ou Libre 2)
        if (state.isSibionicsActive) {
            GluSection(stringResource(R.string.diagnostic_sensor_section)) {
                GluCard {
                    val lastMin = state.sensorLastMin
                    FactList(buildList {
                        add(Fact(stringResource(R.string.diagnostic_ble_connection),
                            stringResource(if (state.sensorConnected) R.string.diagnostic_connected else R.string.diagnostic_disconnected),
                            valueColor = if (state.sensorConnected) null else error))
                        add(Fact(stringResource(R.string.diagnostic_sensor_age),
                            state.sensorDaysElapsed?.let { pluralStringResource(R.plurals.settings_days_value, it, it) }
                                ?: stringResource(R.string.diagnostic_unknown)))
                        add(Fact(stringResource(R.string.diagnostic_last_reading),
                            lastMin?.let {
                                when {
                                    it < 2  -> stringResource(R.string.diagnostic_now)
                                    it < 60 -> stringResource(R.string.diagnostic_min_ago, it)
                                    else    -> stringResource(R.string.diagnostic_hours_min_ago, it / 60, it % 60)
                                }
                            } ?: stringResource(R.string.diagnostic_none),
                            valueColor = if (lastMin != null && lastMin < 10) null else error))
                        state.sensorRssi?.let { rssi ->
                            add(Fact(stringResource(R.string.diagnostic_ble_signal_rssi), stringResource(R.string.diagnostic_dbm_value, rssi),
                                valueColor = if (rssi >= -80) null else error))
                        }
                        if (state.sensorBatteryMv > 0) {
                            val low = state.sensorBatteryMv < 2800
                            val volts = String.format(Locale.getDefault(), "%.2f V", state.sensorBatteryMv / 1000.0)
                            add(Fact(stringResource(R.string.diagnostic_sensor_battery),
                                if (low) stringResource(R.string.diagnostic_battery_low_value, volts) else volts,
                                valueColor = if (low) error else null))
                        }
                    })
                }
            }

            // T28 — validação contra o glicosímetro (entrada em mg/dL, como antes)
            GluSection(stringResource(R.string.diagnostic_validation_vs_meter)) {
                FingerstickSection(state, timeFormat, onRecord)
            }
        }
    }
}

@Composable
private fun FingerstickSection(state: DiagnosticUiState, timeFormat: TimeFormat, onRecord: (Double) -> Unit) {
    var inputText by remember { mutableStateOf("") }
    val validValue = inputText.toDoubleOrNull()?.takeIf { it in 20.0..600.0 }
    val current = state.currentSgvMgDl

    GluCard {
        current?.let { Text(stringResource(R.string.diagnostic_sensor_now, it), style = MaterialTheme.typography.bodyLarge) }
        OutlinedTextField(
            value = inputText,
            onValueChange = { inputText = it.filter { c -> c.isDigit() || c == '.' } },
            label = { Text(stringResource(R.string.diagnostic_meter_mgdl)) },
            // Diz por que "Registrar" está desligado (antes só desligava).
            supportingText = {
                Text(stringResource(if (current == null) R.string.diagnostic_no_current_reading else R.string.diagnostic_meter_range))
            },
            isError = inputText.isNotEmpty() && validValue == null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = { validValue?.let { onRecord(it); inputText = "" } },
            enabled = validValue != null && current != null,
            modifier = Modifier.fillMaxWidth()
        ) { Text(stringResource(R.string.diagnostic_record)) }
        state.mard?.let {
            FactList(listOf(Fact(stringResource(R.string.diagnostic_mard_label), String.format(Locale.getDefault(), "%.1f%%", it),
                valueColor = if (it <= 10.0) null else MaterialTheme.colorScheme.error)))
        }
    }

    if (state.pairs.isNotEmpty()) {
        GluCard {
            Text(stringResource(R.string.diagnostic_last_comparisons), style = MaterialTheme.typography.titleSmall)
            // Linhas de 2 níveis (antes 3 textos lado a lado que se sobrepunham com fonte grande).
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                state.pairs.forEach { p ->
                    val diff = String.format(Locale.getDefault(), "%+.1f%%", p.diffPct)
                    Column {
                        Text(stringResource(R.string.diagnostic_sensor_meter_values, p.sensorMgDl, p.fingerstickMgDl),
                            style = MaterialTheme.typography.bodyMedium)
                        Text("${formatTime(p.ts, timeFormat)} · $diff", style = MaterialTheme.typography.bodySmall,
                            color = if (abs(p.diffPct) <= 15) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}
