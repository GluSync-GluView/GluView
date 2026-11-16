package com.gluview.app.ui.screen.therapy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gluview.app.R
import com.gluview.app.data.datastore.AppPreferences
import com.gluview.app.ui.components.kit.GluCallout
import com.gluview.app.ui.components.kit.GluCard
import com.gluview.app.ui.components.kit.GluSection
import com.gluview.app.ui.components.kit.Page
import com.gluview.app.ui.components.kit.SliderRow
import com.gluview.app.ui.components.kit.Tone
import com.gluview.app.ui.theme.Spacing
import com.gluview.app.util.GlucoseUnit
import com.gluview.therapy.Block
import com.gluview.therapy.InsulinPreset
import com.gluview.therapy.PersonType
import com.gluview.therapy.ProfileProblem
import com.gluview.therapy.Schedule
import com.gluview.therapy.TherapyProfile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject
import kotlin.math.roundToInt

@HiltViewModel
class TherapyProfileViewModel @Inject constructor(private val preferences: AppPreferences) : ViewModel() {
    suspend fun load(): Pair<TherapyProfile, GlucoseUnit> =
        (preferences.therapyProfile.first() ?: TherapyProfile()) to GlucoseUnit.fromString(preferences.glucoseUnit.first())
    fun save(p: TherapyProfile) { viewModelScope.launch { preferences.saveTherapyProfile(p) } }
}

/**
 * Plano 21, F4.1 — perfil terapêutico: ISF, relação carbo, alvo (por horário), insulina, DIA,
 * absorção, passo da caneta e bolus máximo, com os limites fixos do AndroidAPS pelo tipo de pessoa.
 * Valores de glicemia aparecem na unidade do app; por dentro tudo é mg/dL.
 */
@Composable
fun TherapyProfileScreen(onBack: () -> Unit, vm: TherapyProfileViewModel = hiltViewModel()) {
    var draft by remember { mutableStateOf<TherapyProfile?>(null) }
    var unit by remember { mutableStateOf(GlucoseUnit.MG_DL) }
    var savedMsg by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.load().let { (p, u) -> draft = p; unit = u } }
    val p = draft ?: return
    val problems = p.problems()
    val mmol = unit == GlucoseUnit.MMOL_L
    val gluFactor = if (mmol) 18.0 else 1.0

    Page(title = stringResource(R.string.therapy_profile_title), onBack = onBack) {
        Text(stringResource(R.string.therapy_profile_intro), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        GluSection(stringResource(R.string.therapy_person)) {
            ChipRow(PersonType.entries, p.person, { stringResource(personLabel(it)) }) { draft = p.copy(person = it) }
            Text(stringResource(R.string.therapy_person_limits, fmt(p.person.maxBolus), fmt(p.person.maxDiaHours)),
                style = MaterialTheme.typography.bodySmall)
        }
        GluSection(stringResource(R.string.therapy_insulin)) {
            ChipRow(InsulinPreset.entries, p.insulin, { it.label }) { draft = p.copy(insulin = it) }
            GluCard {
                Column {
                    if (p.insulin == InsulinPreset.CUSTOM) SliderRow(stringResource(R.string.therapy_peak), p.customPeakMin.toFloat(), 35f..120f, 5f,
                        { "${it.roundToInt()} min" }, { draft = p.copy(customPeakMin = it.toDouble()) })
                    SliderRow(stringResource(R.string.therapy_dia), p.diaHours.toFloat(), 5f..p.person.maxDiaHours.toFloat(), 0.5f,
                        { "${fmt(it.toDouble())} h" }, { draft = p.copy(diaHours = it.toDouble()) })
                    SliderRow(stringResource(R.string.therapy_absorption), p.carbAbsorptionMin.toFloat(), 60f..480f, 15f,
                        { "${it.roundToInt()} min" }, { draft = p.copy(carbAbsorptionMin = it.roundToInt()) })
                    SliderRow(stringResource(R.string.therapy_max_bolus), p.maxBolus.toFloat().coerceAtMost(p.person.maxBolus.toFloat()),
                        0.5f..p.person.maxBolus.toFloat(), 0.5f, { "${fmt(it.toDouble())} U" }, { draft = p.copy(maxBolus = it.toDouble()) })
                }
            }
            Text(stringResource(R.string.therapy_pen_step), style = MaterialTheme.typography.titleSmall)
            ChipRow(listOf(0.5, 1.0, 0.1, 0.05), p.penStep, { "${fmt(it, 2)} U" }) { draft = p.copy(penStep = it) }
            Text(stringResource(R.string.therapy_concentration), style = MaterialTheme.typography.titleSmall)
            ChipRow(listOf(1, 2, 5), p.concentration, { "U${it * 100}" }) { draft = p.copy(concentration = it) }
        }
        ScheduleEditor(stringResource(R.string.therapy_isf, unit.label), p.isf, gluFactor) { draft = p.copy(isf = it) }
        ScheduleEditor(stringResource(R.string.therapy_ic), p.ic, 1.0) { draft = p.copy(ic = it) }
        ScheduleEditor(stringResource(R.string.therapy_target_low, unit.label), p.targetLow, gluFactor) { draft = p.copy(targetLow = it) }
        ScheduleEditor(stringResource(R.string.therapy_target_high, unit.label), p.targetHigh, gluFactor) { draft = p.copy(targetHigh = it) }

        problems.forEach { GluCallout(stringResource(problemText(it)), tone = Tone.Danger) }
        Button(onClick = { vm.save(p); savedMsg = true }, enabled = problems.isEmpty(), modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.therapy_save))
        }
        if (savedMsg) GluCallout(stringResource(R.string.therapy_saved), tone = Tone.Success)
    }
}

private fun fmt(v: Double, d: Int = 1) = String.format(Locale.getDefault(), "%.${d}f", v)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChipRow(items: List<T>, selected: T, label: @Composable (T) -> String, onPick: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), modifier = Modifier.fillMaxWidth()) {
        items.forEach { FilterChip(selected = it == selected, onClick = { onPick(it) }, label = { Text(label(it)) }) }
    }
}

/**
 * Horários de um valor (ex.: ISF). [factor] converte para a unidade mostrada (18 em mmol/L).
 * O primeiro horário é sempre 00:00; dá para acrescentar e tirar os outros.
 */
@Composable
private fun ScheduleEditor(title: String, schedule: Schedule, factor: Double, onChange: (Schedule) -> Unit) {
    GluSection(title) {
        GluCard {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                schedule.blocks.forEachIndexed { i, b ->
                    var timeText by remember(b.startMin, i) { mutableStateOf(String.format(Locale.US, "%02d:%02d", b.startMin / 60, b.startMin % 60)) }
                    var valueText by remember(b.value, i) { mutableStateOf(fmt(b.value / factor, if (factor > 1) 1 else 0).replace(",", ".")) }
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        OutlinedTextField(timeText, { t ->
                            timeText = t
                            parseTime(t)?.takeIf { i > 0 }?.let { m -> runCatching { onChange(Schedule(schedule.blocks.toMutableList().also { l -> l[i] = b.copy(startMin = m) }.sortedBy { it.startMin })) } }
                        }, enabled = i > 0, singleLine = true, label = { Text(stringResource(R.string.therapy_from)) }, modifier = Modifier.width(110.dp))
                        OutlinedTextField(valueText, { t ->
                            valueText = t
                            t.replace(',', '.').toDoubleOrNull()?.let { v -> onChange(Schedule(schedule.blocks.toMutableList().also { l -> l[i] = b.copy(value = v * factor) })) }
                        }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            label = { Text(stringResource(R.string.therapy_value)) }, modifier = Modifier.weight(1f))
                        if (i > 0) TextButton(onClick = { onChange(Schedule(schedule.blocks.filterIndexed { j, _ -> j != i })) }) { Text("✕") }
                    }
                }
                TextButton(onClick = {
                    val last = schedule.blocks.last()
                    val next = (last.startMin + 180).coerceAtMost(1380)
                    if (next > last.startMin) onChange(Schedule(schedule.blocks + Block(next, last.value)))
                }) { Text(stringResource(R.string.therapy_add_time)) }
            }
        }
    }
}

private fun parseTime(t: String): Int? {
    val m = Regex("^(\\d{1,2}):(\\d{2})$").find(t.trim()) ?: return null
    val h = m.groupValues[1].toInt(); val mi = m.groupValues[2].toInt()
    return if (h in 0..23 && mi in 0..59) h * 60 + mi else null
}

private fun personLabel(p: PersonType) = when (p) {
    PersonType.CHILD -> R.string.therapy_person_child
    PersonType.TEEN -> R.string.therapy_person_teen
    PersonType.ADULT -> R.string.therapy_person_adult
    PersonType.INSULIN_RESISTANT -> R.string.therapy_person_resistant
    PersonType.PREGNANT -> R.string.therapy_person_pregnant
}

private fun problemText(p: ProfileProblem) = when (p) {
    ProfileProblem.ISF_RANGE -> R.string.therapy_p_isf
    ProfileProblem.IC_RANGE -> R.string.therapy_p_ic
    ProfileProblem.TARGET_RANGE -> R.string.therapy_p_target
    ProfileProblem.TARGET_ORDER -> R.string.therapy_p_target_order
    ProfileProblem.DIA_RANGE -> R.string.therapy_p_dia
    ProfileProblem.PEAK_RANGE -> R.string.therapy_p_peak
    ProfileProblem.ABSORPTION_RANGE -> R.string.therapy_p_abs
    ProfileProblem.PEN_STEP -> R.string.therapy_p_step
    ProfileProblem.MAX_BOLUS -> R.string.therapy_p_max
    ProfileProblem.CONCENTRATION -> R.string.therapy_p_conc
}
