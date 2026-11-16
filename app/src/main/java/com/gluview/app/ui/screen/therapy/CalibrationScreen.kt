package com.gluview.app.ui.screen.therapy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gluview.app.R
import com.gluview.app.data.datastore.AppPreferences
import com.gluview.app.therapy.CalAddResult
import com.gluview.app.therapy.CalibrationManager
import com.gluview.app.therapy.StoredCalPair
import com.gluview.app.ui.components.kit.ConfirmDialog
import com.gluview.app.ui.components.kit.GluCallout
import com.gluview.app.ui.components.kit.GluCard
import com.gluview.app.ui.components.kit.GluSection
import com.gluview.app.ui.components.kit.Page
import com.gluview.app.ui.components.kit.SwitchRow
import com.gluview.app.ui.components.kit.Tone
import com.gluview.app.ui.theme.Spacing
import com.gluview.app.util.GlucoseUnit
import com.gluview.therapy.CalFitProblem
import com.gluview.therapy.CalRejection
import com.gluview.therapy.Calibration
import com.gluview.therapy.SmoothingMethod
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

data class CalUi(val sensorKey: String?, val pairs: List<StoredCalPair>, val fit: Calibration.Fit?)

@HiltViewModel
class CalibrationViewModel @Inject constructor(
    private val preferences: AppPreferences,
    private val manager: CalibrationManager
) : ViewModel() {
    val enabled: StateFlow<Boolean> = preferences.calibrationEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val smoothing: StateFlow<SmoothingMethod> = preferences.smoothingMethod.stateIn(viewModelScope, SharingStarted.Eagerly, SmoothingMethod.OFF)
    val unit: StateFlow<GlucoseUnit> = preferences.glucoseUnit.map { GlucoseUnit.fromString(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, GlucoseUnit.MG_DL)
    private val _ui = MutableStateFlow(CalUi(null, emptyList(), null))
    val ui: StateFlow<CalUi> = _ui
    private val _last = MutableStateFlow<CalAddResult?>(null)
    val last: StateFlow<CalAddResult?> = _last

    fun refresh() = viewModelScope.launch {
        val key = manager.currentSensorKey()
        val pairs = manager.pairs().filter { it.sensorKey == key }
        _ui.value = CalUi(key, pairs, if (pairs.isEmpty()) null else Calibration.fit(pairs.map { it.pair }, System.currentTimeMillis()))
    }
    fun setEnabled(on: Boolean) = viewModelScope.launch { preferences.saveCalibrationEnabled(on) }
    fun setSmoothing(m: SmoothingMethod) = viewModelScope.launch { preferences.saveSmoothingMethod(m) }
    fun add(mgDl: Double) = viewModelScope.launch { _last.value = manager.add(mgDl); refresh() }
    fun undo() = viewModelScope.launch { manager.undoLast(); _last.value = null; refresh() }
    fun clear() = viewModelScope.launch { manager.clearCurrent(); _last.value = null; refresh() }
}

/**
 * Plano 21, F3 — calibração com glicemia capilar (com avisos e explicação) e suavização da curva.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CalibrationScreen(onBack: () -> Unit, vm: CalibrationViewModel = hiltViewModel()) {
    val enabled by vm.enabled.collectAsState()
    val smoothing by vm.smoothing.collectAsState()
    val unit by vm.unit.collectAsState()
    val ui by vm.ui.collectAsState()
    val last by vm.last.collectAsState()
    var askEnable by remember { mutableStateOf(false) }
    var askClear by remember { mutableStateOf(false) }
    var valueText by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { vm.refresh() }
    fun show(v: Double) = with(unit) { v.toDisplay() }

    Page(title = stringResource(R.string.cal_title), onBack = onBack) {
        GluCallout(stringResource(R.string.cal_warning), tone = Tone.Warning, title = stringResource(R.string.cal_warning_title))
        Text(stringResource(R.string.cal_how), style = MaterialTheme.typography.bodyMedium)
        GluCard {
            SwitchRow(stringResource(R.string.cal_enable), enabled, { on -> if (on) askEnable = true else vm.setEnabled(false) },
                description = stringResource(R.string.cal_enable_desc))
        }
        if (enabled) {
            GluSection(stringResource(R.string.cal_section_sensor)) {
                if (ui.sensorKey == null) GluCallout(stringResource(R.string.cal_no_sensor), tone = Tone.Info)
                else {
                    GluCard {
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                            when (val f = ui.fit) {
                                null -> Text(stringResource(R.string.cal_none_yet), style = MaterialTheme.typography.bodyMedium)
                                is Calibration.Fit.Ok -> {
                                    Text(stringResource(R.string.cal_active, f.calibration.pairs), style = MaterialTheme.typography.titleMedium)
                                    Text(stringResource(R.string.cal_effect, show(100.0), show(f.calibration.apply(100.0)),
                                        show(200.0), show(f.calibration.apply(200.0)), unit.label), style = MaterialTheme.typography.bodyMedium)
                                }
                                is Calibration.Fit.Rejected -> Text(stringResource(fitText(f.problem)), style = MaterialTheme.typography.bodyMedium,
                                    color = if (f.problem == CalFitProblem.TOO_FEW_PAIRS) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
                            }
                            ui.pairs.takeLast(8).forEach { p ->
                                Text(stringResource(R.string.cal_pair, android.text.format.DateFormat.format("dd/MM HH:mm", p.pair.atMs),
                                    show(p.pair.fingerstickMgDl), show(p.pair.sensorMgDl), unit.label), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    OutlinedTextField(valueText, { valueText = it.filter { c -> c.isDigit() || c == ',' || c == '.' }.take(5) },
                        label = { Text(stringResource(R.string.cal_value, unit.label)) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                    Button(onClick = {
                        valueText.replace(',', '.').toDoubleOrNull()?.let { v -> vm.add(if (unit == GlucoseUnit.MMOL_L) v * 18.0 else v); valueText = "" }
                    }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.cal_add)) }
                    last?.let { r ->
                        when (r) {
                            is CalAddResult.Rejected -> GluCallout(stringResource(rejectText(r.reason)), tone = Tone.Warning)
                            is CalAddResult.Added -> GluCallout(stringResource(if (r.calibration != null) R.string.cal_added_active else R.string.cal_added_waiting), tone = Tone.Success)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        OutlinedButton(onClick = vm::undo, enabled = ui.pairs.isNotEmpty()) { Text(stringResource(R.string.cal_undo)) }
                        OutlinedButton(onClick = { askClear = true }, enabled = ui.pairs.isNotEmpty()) { Text(stringResource(R.string.cal_clear)) }
                    }
                }
            }
        }
        GluSection(stringResource(R.string.smooth_title), description = stringResource(R.string.smooth_desc)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                SmoothingMethod.entries.forEach { m ->
                    FilterChip(selected = m == smoothing, onClick = { vm.setSmoothing(m) }, label = { Text(stringResource(smoothLabel(m))) })
                }
            }
        }
    }
    if (askEnable) ConfirmDialog(stringResource(R.string.cal_enable), stringResource(R.string.cal_enable_confirm),
        stringResource(R.string.cal_enable_ok), { vm.setEnabled(true); askEnable = false }, { askEnable = false }, destructive = false)
    if (askClear) ConfirmDialog(stringResource(R.string.cal_clear), stringResource(R.string.cal_clear_confirm),
        stringResource(R.string.cal_clear), { vm.clear(); askClear = false }, { askClear = false })
}

private fun fitText(p: CalFitProblem) = when (p) {
    CalFitProblem.TOO_FEW_PAIRS -> R.string.cal_fit_few
    CalFitProblem.SLOPE_LIMIT -> R.string.cal_fit_slope
    CalFitProblem.TOO_LARGE -> R.string.cal_fit_large
}

private fun rejectText(r: CalRejection) = when (r) {
    CalRejection.NO_SESSION -> R.string.cal_rej_nosession
    CalRejection.WARMUP -> R.string.cal_rej_warmup
    CalRejection.FAST_CHANGE -> R.string.cal_rej_fast
    CalRejection.NO_RECENT_SENSOR -> R.string.cal_rej_nosensor
    CalRejection.OUT_OF_RANGE -> R.string.cal_rej_range
}

private fun smoothLabel(m: SmoothingMethod) = when (m) {
    SmoothingMethod.OFF -> R.string.smooth_off
    SmoothingMethod.MOVING_AVERAGE -> R.string.smooth_avg
    SmoothingMethod.EXPONENTIAL -> R.string.smooth_exp
    SmoothingMethod.KALMAN -> R.string.smooth_kalman
}
