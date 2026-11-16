package com.gluview.app.ui.screen.therapy

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gluview.app.R
import com.gluview.app.data.datastore.AppPreferences
import com.gluview.app.therapy.TherapyRepository
import com.gluview.app.therapy.TherapySnapshot
import com.gluview.app.ui.components.kit.ConfirmDialog
import com.gluview.app.ui.components.kit.GluCallout
import com.gluview.app.ui.components.kit.GluCard
import com.gluview.app.ui.components.kit.GluSection
import com.gluview.app.ui.components.kit.Page
import com.gluview.app.ui.components.kit.RowGroup
import com.gluview.app.ui.components.kit.SwitchRow
import com.gluview.app.ui.components.kit.Tone
import com.gluview.app.ui.theme.Spacing
import com.gluview.app.util.GlucoseUnit
import com.gluview.therapy.BolusCalculator
import com.gluview.therapy.BolusInput
import com.gluview.therapy.BolusResult
import com.gluview.therapy.BolusWarning
import com.gluview.therapy.Prediction
import com.gluview.therapy.PredictionResult
import com.gluview.therapy.TherapyProfile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class BolusViewModel @Inject constructor(
    private val preferences: AppPreferences,
    private val therapy: TherapyRepository
) : ViewModel() {
    val profile: StateFlow<TherapyProfile?> = preferences.therapyProfile.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val enabled: StateFlow<Boolean> = preferences.doseFeatures.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val accepted: StateFlow<Boolean> = preferences.doseIntroAcceptedAt.map { it > 0L }.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val unit: StateFlow<GlucoseUnit> = preferences.glucoseUnit.map { GlucoseUnit.fromString(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, GlucoseUnit.MG_DL)
    private val _snap = MutableStateFlow<TherapySnapshot?>(null)
    val snapshot: StateFlow<TherapySnapshot?> = _snap
    private val _saved = MutableStateFlow(false)
    val saved: StateFlow<Boolean> = _saved

    fun refresh() { viewModelScope.launch { _snap.value = therapy.snapshot() } }

    fun accept() { viewModelScope.launch { preferences.saveDoseIntroAccepted(System.currentTimeMillis()); preferences.saveDoseFeatures(true) } }
    fun disable() { viewModelScope.launch { preferences.saveDoseFeatures(false) } }

    fun record(carbs: Double, units: Double) {
        val p = profile.value ?: return
        viewModelScope.launch { therapy.record(p, carbs, units); _saved.value = true; _snap.value = therapy.snapshot() }
    }
    fun clearSaved() { _saved.value = false }
}

/**
 * Plano 21, F4 — calculadora de bolus para quem usa caneta (e base da bomba no futuro).
 * Sempre SUGESTÃO: ao confirmar, só registra no diário; quem aplica é a pessoa.
 */
@Composable
fun BolusScreen(onBack: () -> Unit, onProfile: () -> Unit, vm: BolusViewModel = hiltViewModel()) {
    val profile by vm.profile.collectAsState()
    val enabled by vm.enabled.collectAsState()
    val accepted by vm.accepted.collectAsState()
    val snap by vm.snapshot.collectAsState()
    val unit by vm.unit.collectAsState()
    val saved by vm.saved.collectAsState()
    LaunchedEffect(Unit) { while (true) { vm.refresh(); delay(60_000) } }

    Page(title = stringResource(R.string.bolus_title), onBack = onBack) {
        if (!enabled || !accepted) {
            IntroCard(onAccept = vm::accept)
            return@Page
        }
        val p = profile
        if (p == null || p.problems().isNotEmpty()) {
            GluCallout(stringResource(R.string.bolus_need_profile), tone = Tone.Warning,
                title = stringResource(R.string.bolus_need_profile_title),
                actionLabel = stringResource(R.string.therapy_profile_title), onAction = onProfile)
            return@Page
        }
        val s = snap ?: return@Page
        Calculator(p, s, unit, onRecord = vm::record)
        if (saved) GluCallout(stringResource(R.string.bolus_saved), tone = Tone.Success, actionLabel = stringResource(android.R.string.ok), onAction = vm::clearSaved)
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            OutlinedButton(onClick = onProfile) { Text(stringResource(R.string.therapy_profile_title)) }
            OutlinedButton(onClick = vm::disable) { Text(stringResource(R.string.bolus_turn_off)) }
        }
    }
}

@Composable
private fun IntroCard(onAccept: () -> Unit) {
    GluCallout(stringResource(R.string.bolus_intro_body), tone = Tone.Warning, title = stringResource(R.string.bolus_intro_title))
    Text(stringResource(R.string.bolus_intro_how), style = MaterialTheme.typography.bodyMedium)
    Button(onClick = onAccept, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.bolus_intro_accept)) }
}

private fun fmt(v: Double, d: Int = 1) = String.format(Locale.getDefault(), "%.${d}f", v)
private fun glu(v: Double, unit: GlucoseUnit) = with(unit) { v.toDisplay() }

@Composable
private fun Calculator(p: TherapyProfile, s: TherapySnapshot, unit: GlucoseUnit, onRecord: (Double, Double) -> Unit) {
    var carbsText by remember { mutableStateOf("") }
    var useBg by remember { mutableStateOf(true) }
    var useIob by remember { mutableStateOf(true) }
    var useCob by remember { mutableStateOf(true) }
    var useTrend by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<BolusResult?>(null) }
    val carbs = carbsText.replace(',', '.').toDoubleOrNull()?.coerceIn(0.0, 500.0) ?: 0.0
    val r = BolusCalculator.calculate(BolusInput(carbs, s.glucose, useBg, useIob, useCob, useTrend), p, s.doses, s.carbs, s.nowMs, s.minuteOfDay)

    GluCard {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val g = s.glucose
            Text(if (g != null) stringResource(R.string.bolus_glucose_now, glu(g.mgDl, unit), unit.label, g.ageMin)
                 else stringResource(R.string.bolus_no_glucose), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.bolus_iob_cob, fmt(r.iob, 2), fmt(r.cob, 0)), style = MaterialTheme.typography.bodyMedium)
        }
    }
    OutlinedTextField(carbsText, { carbsText = it.filter { c -> c.isDigit() || c == ',' || c == '.' }.take(5) },
        label = { Text(stringResource(R.string.bolus_carbs)) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
    GluCard {
        RowGroup(listOf(
            { SwitchRow(stringResource(R.string.bolus_use_bg), useBg, { useBg = it }) },
            { SwitchRow(stringResource(R.string.bolus_use_iob), useIob, { useIob = it }, description = stringResource(R.string.bolus_use_iob_desc)) },
            { SwitchRow(stringResource(R.string.bolus_use_cob), useCob, { useCob = it }) },
            { SwitchRow(stringResource(R.string.bolus_use_trend), useTrend, { useTrend = it }) }
        ))
    }
    GluSection(stringResource(R.string.bolus_parts)) {
        GluCard {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Part(stringResource(R.string.bolus_part_carbs, fmt(carbs, 0), fmt(r.ic)), r.fromCarbs)
                Part(stringResource(R.string.bolus_part_correction, glu(r.target, unit), glu(r.isf, unit)), r.fromCorrection)
                Part(stringResource(R.string.bolus_part_iob), r.fromIob)
                if (useCob) Part(stringResource(R.string.bolus_part_cob), r.fromCob)
                if (useTrend) Part(stringResource(R.string.bolus_part_trend), r.fromTrend)
                Text(stringResource(R.string.bolus_suggested, fmt(r.suggestedUnits, if (p.penStep < 0.5) 2 else 1)),
                    style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                if (r.carbsNeededG >= 1.0) Text(stringResource(R.string.bolus_carbs_needed, fmt(r.carbsNeededG, 0)),
                    style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
            }
        }
    }
    r.warnings.forEach { w -> GluCallout(stringResource(warningText(w)), tone = if (w == BolusWarning.LOW_GLUCOSE_NO_INSULIN) Tone.Danger else Tone.Warning) }
    Text(stringResource(R.string.bolus_always_check), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Button(onClick = { confirm = r }, enabled = carbs > 0.0 || r.suggestedUnits > 0.0, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.bolus_record))
    }
    PredictionCard(p, s, unit)

    confirm?.let { c ->
        ConfirmDialog(stringResource(R.string.bolus_record), stringResource(R.string.bolus_record_confirm, fmt(c.suggestedUnits, 2), fmt(carbs, 0)),
            stringResource(R.string.bolus_record_ok), { onRecord(carbs, c.suggestedUnits); confirm = null; carbsText = "" }, { confirm = null }, destructive = false)
    }
}

@Composable
private fun Part(label: String, units: Double) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        val shown = if (kotlin.math.abs(units) < 0.005) 0.0 else units
        Text("${if (shown > 0) "+" else ""}${fmt(shown, 2)} U", style = MaterialTheme.typography.bodyMedium)
    }
}

private fun warningText(w: BolusWarning) = when (w) {
    BolusWarning.PROFILE_INVALID -> R.string.bolus_w_profile
    BolusWarning.NO_GLUCOSE -> R.string.bolus_w_no_glucose
    BolusWarning.GLUCOSE_OLD -> R.string.bolus_w_old
    BolusWarning.LOW_GLUCOSE_NO_INSULIN -> R.string.bolus_w_low
    BolusWarning.SENSOR_WARMUP -> R.string.bolus_w_warmup
    BolusWarning.CAPPED_AT_MAX -> R.string.bolus_w_capped
    BolusWarning.MORE_CARBS_SUGGESTED -> R.string.bolus_w_carbs
    BolusWarning.HIGH_IOB -> R.string.bolus_w_high_iob
    BolusWarning.ROUNDED_DOWN -> R.string.bolus_w_rounded
}

/** Previsão das próximas 3 h (curvas IOB, COB e UAM; faixa entre a menor e a maior). */
@Composable
private fun PredictionCard(p: TherapyProfile, s: TherapySnapshot, unit: GlucoseUnit) {
    val g = s.glucose
    GluSection(stringResource(R.string.prediction_title), description = stringResource(R.string.prediction_desc)) {
        if (g == null || g.ageMin > BolusCalculator.MAX_GLUCOSE_AGE_MIN || s.delta5 == null) {
            Text(stringResource(R.string.prediction_unavailable), style = MaterialTheme.typography.bodyMedium); return@GluSection
        }
        val pred = remember(s, p) { Prediction.predict(s.nowMs, g.mgDl, s.delta5, p, s.minuteOfDay, s.doses, s.carbs) }
        val in30 = pred.cob.getOrNull(5)?.mgDl
        val lowest = pred.low.minByOrNull { it.mgDl } // só curvas com carbo (ver Prediction)
        GluCard {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                in30?.let { Text(stringResource(R.string.prediction_in30, glu(it, unit), unit.label), style = MaterialTheme.typography.titleMedium) }
                lowest?.let { Text(stringResource(R.string.prediction_lowest, glu(it.mgDl, unit), unit.label,
                    ((it.atMs - s.nowMs) / 60_000L).toInt()), style = MaterialTheme.typography.bodyMedium) }
                val desc = stringResource(R.string.prediction_chart_desc)
                PredictionChart(pred, g.mgDl, p.targetLow.at(s.minuteOfDay), p.targetHigh.at(s.minuteOfDay),
                    Modifier.fillMaxWidth().height(180.dp).semantics { contentDescription = desc })
                Text(stringResource(R.string.prediction_legend), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun PredictionChart(pred: PredictionResult, nowBg: Double, tLow: Double, tHigh: Double, modifier: Modifier) {
    val iobColor = MaterialTheme.colorScheme.primary
    val cobColor = MaterialTheme.colorScheme.tertiary
    val uamColor = MaterialTheme.colorScheme.secondary
    val band = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val grid = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier) {
        val all = pred.low.map { it.mgDl } + pred.high.map { it.mgDl } + listOf(nowBg, tLow, tHigh)
        val minV = (all.minOrNull() ?: 40.0).coerceAtMost(70.0) - 10
        val maxV = (all.maxOrNull() ?: 200.0).coerceAtLeast(180.0) + 10
        val n = pred.iob.size
        fun x(i: Int) = size.width * i / n
        fun y(v: Double) = (size.height * (1 - (v - minV) / (maxV - minV))).toFloat()
        for (lvl in listOf(tLow, tHigh)) drawLine(grid, Offset(0f, y(lvl)), Offset(size.width, y(lvl)), 2f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f)))
        val bandPath = Path().apply {
            moveTo(x(0), y(nowBg))
            pred.high.forEachIndexed { i, pt -> lineTo(x(i + 1), y(pt.mgDl)) }
            for (i in pred.low.indices.reversed()) lineTo(x(i + 1), y(pred.low[i].mgDl))
            close()
        }
        drawPath(bandPath, band)
        fun curve(pts: List<com.gluview.therapy.PredPoint>, c: Color) {
            val path = Path().apply { moveTo(x(0), y(nowBg)); pts.forEachIndexed { i, pt -> lineTo(x(i + 1), y(pt.mgDl)) } }
            drawPath(path, c, style = Stroke(width = 4f))
        }
        curve(pred.iob, iobColor); curve(pred.cob, cobColor); curve(pred.uam, uamColor)
        drawCircle(iobColor, 8f, Offset(x(0), y(nowBg)))
    }
}
