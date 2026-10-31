package com.gluview.app.ui.screen.miband

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import com.gluview.app.R
import com.gluview.app.ui.components.chart.rememberChartAppearance
import com.gluview.app.ui.components.kit.GluCallout
import com.gluview.app.ui.components.kit.GluCard
import com.gluview.app.ui.components.kit.GluSection
import com.gluview.app.ui.components.kit.Page
import com.gluview.app.ui.components.kit.SecretTextField
import com.gluview.app.ui.components.kit.SwitchRow
import com.gluview.app.ui.components.kit.Tone
import com.gluview.app.ui.theme.Spacing
import com.gluview.app.util.TimeFormat
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Tudo o que a tela mostra (vem dos flows do [MiBandViewModel]). */
internal data class MiBandUi(
    val mac: String = "",
    val key: String = "",
    val macError: Int? = null,
    val keyError: Int? = null,
    val running: Boolean = false,
    val outcome: MiBandOutcome? = null,
    val log: List<String> = emptyList(),
    val autoSend: Boolean = false,
    val updateEveryReading: Boolean = false,
    val dayIntervalMin: String = "5",
    val nightIntervalMin: String = "30",
    val nightStartMin: Int = 1320,
    val nightEndMin: Int = 420,
    val scheduleSaved: Boolean = false
)

internal class MiBandActions(
    val onBack: () -> Unit = {},
    val onMac: (String) -> Unit = {},
    val onKey: (String) -> Unit = {},
    val onTest: () -> Unit = {},
    val onSend: () -> Unit = {},
    val onAutoSend: (Boolean) -> Unit = {},
    val onEveryReading: (Boolean) -> Unit = {},
    val onNightStart: (Int, Int) -> Unit = { _, _ -> },
    val onNightEnd: (Int, Int) -> Unit = { _, _ -> },
    val onNightInterval: (String) -> Unit = {},
    val onDayInterval: (String) -> Unit = {},
    val onSaveSchedule: () -> Unit = {}
)

@Composable
fun MiBandScreen(
    onBack: () -> Unit,
    vm: MiBandViewModel = hiltViewModel()
) {
    val ui = MiBandUi(
        mac = vm.mac.collectAsState().value,
        key = vm.key.collectAsState().value,
        macError = vm.macError.collectAsState().value,
        keyError = vm.keyError.collectAsState().value,
        running = vm.running.collectAsState().value,
        outcome = vm.outcome.collectAsState().value,
        log = vm.log.collectAsState().value,
        autoSend = vm.autoSend.collectAsState().value,
        updateEveryReading = vm.updateEveryReading.collectAsState().value,
        dayIntervalMin = vm.dayIntervalMin.collectAsState().value,
        nightIntervalMin = vm.nightIntervalMin.collectAsState().value,
        nightStartMin = vm.nightStartMin.collectAsState().value,
        nightEndMin = vm.nightEndMin.collectAsState().value,
        scheduleSaved = vm.scheduleSaved.collectAsState().value
    )
    MiBandContent(ui, rememberChartAppearance().timeFormat, MiBandActions(
        onBack = onBack,
        onMac = vm::onMacChange,
        onKey = vm::onKeyChange,
        onTest = vm::testConnection,
        onSend = vm::sendCurrentWatchFace,
        onAutoSend = vm::onAutoSendChange,
        onEveryReading = vm::onUpdateEveryReadingChange,
        onNightStart = vm::onNightStartChange,
        onNightEnd = vm::onNightEndChange,
        onNightInterval = vm::onNightIntervalChange,
        onDayInterval = vm::onDayIntervalChange,
        onSaveSchedule = vm::saveSchedule
    ))
}

/**
 * Mi Band (redesign visual, Fase 8): seções Pareamento → Enviar agora → Atualização automática,
 * com introdução para leigos (antes "passo 1 do envio do visor"); chave escondida com olho
 * (antes 32 hex em texto aberto); MAC e chave inválidos no próprio campo; resultado resumido
 * ("Conectado e autenticado", "Tempo esgotado") com o log técnico em "Ver detalhes" (antes o log
 * cru em pt era o status); linhas de Switch inteiras tocáveis e lidas pelo TalkBack; horário no
 * formato do usuário (antes sempre 24h) e "min" como sufixo do campo.
 */
@Composable
internal fun MiBandContent(ui: MiBandUi, timeFormat: TimeFormat, actions: MiBandActions) {
    Page(title = stringResource(R.string.miband_title), onBack = actions.onBack) {
        Text(stringResource(R.string.miband_intro), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)

        GluSection(stringResource(R.string.miband_pairing_section)) {
            GluCard {
                OutlinedTextField(
                    value = ui.mac,
                    onValueChange = actions.onMac,
                    label = { Text(stringResource(R.string.miband_mac_label)) },
                    singleLine = true,
                    enabled = !ui.running,
                    isError = ui.macError != null,
                    supportingText = { Text(stringResource(ui.macError ?: R.string.miband_mac_help)) },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    modifier = Modifier.fillMaxWidth()
                )
                SecretTextField(
                    value = ui.key,
                    onValueChange = actions.onKey,
                    label = stringResource(R.string.miband_authkey_label),
                    supportingText = stringResource(ui.keyError ?: R.string.miband_key_help),
                    isError = ui.keyError != null,
                    enabled = !ui.running,
                    keyboardType = KeyboardType.Ascii
                )
                Button(onClick = actions.onTest, enabled = !ui.running, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    if (ui.running) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
                        Text(stringResource(R.string.miband_connecting), modifier = Modifier.padding(start = Spacing.xs))
                    } else {
                        Text(stringResource(R.string.miband_connect_authenticate))
                    }
                }
                OutlinedButton(onClick = actions.onSend, enabled = !ui.running, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.miband_send_current_watchface), textAlign = TextAlign.Center)
                }
                if (ui.outcome != null || ui.log.isNotEmpty()) StatusSummary(ui.outcome, ui.log)
            }
        }

        GluSection(stringResource(R.string.miband_auto_section)) {
            GluCard {
                SwitchRow(stringResource(R.string.miband_auto_update), ui.autoSend, actions.onAutoSend,
                    description = stringResource(R.string.miband_auto_update_desc))
                if (ui.autoSend) {
                    HorizontalDivider()
                    SwitchRow(stringResource(R.string.miband_update_every_reading), ui.updateEveryReading, actions.onEveryReading,
                        description = stringResource(R.string.miband_update_every_reading_desc))
                    if (!ui.updateEveryReading) ScheduleEditor(ui, timeFormat, actions)
                }
            }
        }
    }
}

@Composable
private fun StatusSummary(outcome: MiBandOutcome?, log: List<String>) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    if (outcome != null) {
        val (tone, res) = when (outcome) {
            MiBandOutcome.CONNECTED -> Tone.Success to R.string.miband_outcome_connected
            MiBandOutcome.SENT -> Tone.Success to R.string.miband_outcome_sent
            MiBandOutcome.FAILED -> Tone.Danger to R.string.miband_outcome_failed
            MiBandOutcome.TIMEOUT -> Tone.Warning to R.string.miband_outcome_timeout
        }
        GluCallout(stringResource(res), tone = tone, announce = true)
    }
    if (log.isNotEmpty()) {
        TextButton(onClick = { expanded = !expanded }) {
            Text(stringResource(if (expanded) R.string.miband_hide_details else R.string.applog_show_data))
        }
        if (expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                log.forEach { Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
            }
        }
    }
}

@Composable
private fun ScheduleEditor(ui: MiBandUi, timeFormat: TimeFormat, actions: MiBandActions) {
    HorizontalDivider()
    Text(stringResource(R.string.miband_special_period), style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.semantics { heading() })
    Text(stringResource(R.string.miband_special_period_desc), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), modifier = Modifier.fillMaxWidth()) {
        TimePickerField(stringResource(R.string.miband_from), ui.nightStartMin, timeFormat, actions.onNightStart, Modifier.weight(1f))
        TimePickerField(stringResource(R.string.miband_until), ui.nightEndMin, timeFormat, actions.onNightEnd, Modifier.weight(1f))
    }
    IntervalField(ui.nightIntervalMin, actions.onNightInterval)
    HorizontalDivider()
    Text(stringResource(R.string.miband_rest_of_day), style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.semantics { heading() })
    IntervalField(ui.dayIntervalMin, actions.onDayInterval)
    Button(onClick = actions.onSaveSchedule, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text(stringResource(if (ui.scheduleSaved) R.string.miband_saved_check else R.string.miband_save_frequency))
    }
}

@Composable
private fun IntervalField(value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(R.string.miband_update_every)) },
        suffix = { Text("min") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth()
    )
}

/** Minuto do dia no formato do usuário (24h ou 12h). */
private fun minutesLabel(minutesOfDay: Int, timeFormat: TimeFormat): String =
    LocalTime.of(minutesOfDay / 60, minutesOfDay % 60).format(
        if (timeFormat == TimeFormat.H24) DateTimeFormatter.ofPattern("HH:mm") else DateTimeFormatter.ofPattern("h:mm a", Locale.US)
    )

/** Botão que mostra um horário e abre um seletor Material3 ao tocar. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerField(
    label: String,
    minutesOfDay: Int,
    timeFormat: TimeFormat,
    onChange: (hour: Int, minute: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var showPicker by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { showPicker = true }, modifier = modifier.heightIn(min = 56.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(minutesLabel(minutesOfDay, timeFormat), style = MaterialTheme.typography.titleMedium)
        }
    }
    if (showPicker) {
        val state = rememberTimePickerState(
            initialHour = minutesOfDay / 60,
            initialMinute = minutesOfDay % 60,
            is24Hour = timeFormat == TimeFormat.H24
        )
        Dialog(onDismissRequest = { showPicker = false }) {
            Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) {
                // Rola: com fonte grande o relógio + botões passavam da altura da tela.
                Column(Modifier.verticalScroll(rememberScrollState()).padding(Spacing.lg),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = Spacing.sm))
                    TimePicker(state = state)
                    Row(Modifier.fillMaxWidth().padding(top = Spacing.xs), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { showPicker = false }) { Text(stringResource(R.string.common_cancel)) }
                        TextButton(onClick = {
                            onChange(state.hour, state.minute)
                            showPicker = false
                        }) { Text(stringResource(R.string.common_ok)) }
                    }
                }
            }
        }
    }
}
