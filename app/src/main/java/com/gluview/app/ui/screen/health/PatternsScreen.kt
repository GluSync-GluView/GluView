package com.gluview.app.ui.screen.health

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gluview.app.R
import com.gluview.app.data.datastore.AppPreferences
import com.gluview.app.data.health.HealthConnectMapper
import com.gluview.app.data.health.HealthConnectSync
import com.gluview.app.data.health.HealthInsights
import com.gluview.app.data.local.dao.HealthRecordDao
import com.gluview.app.data.repository.GlucoseRepository
import com.gluview.app.ui.components.chart.rememberChartAppearance
import com.gluview.app.ui.components.kit.ChoiceChips
import com.gluview.app.ui.components.kit.Fact
import com.gluview.app.ui.components.kit.FactList
import com.gluview.app.ui.components.kit.GluCallout
import com.gluview.app.ui.components.kit.GluCard
import com.gluview.app.ui.components.kit.GluSection
import com.gluview.app.ui.components.kit.ListSkeleton
import com.gluview.app.ui.components.kit.Page
import com.gluview.app.ui.components.kit.Tone
import com.gluview.app.ui.theme.Spacing
import com.gluview.app.util.GlucoseUnit
import com.gluview.app.util.formatGlucoseWithUnit
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject
import kotlin.math.roundToInt

typealias PatternsUi = com.gluview.app.data.health.PatternsResult

@HiltViewModel
class PatternsViewModel @Inject constructor(
    private val patterns: com.gluview.app.data.health.HealthPatterns
) : ViewModel() {
    private val _ui = MutableStateFlow<PatternsUi?>(null)
    val ui: StateFlow<PatternsUi?> = _ui.asStateFlow()

    init { load(30) }

    fun load(days: Int) {
        _ui.value = null
        viewModelScope.launch { _ui.value = patterns.compute(days) }
    }
}

/** Padrões (plano 17, F3.4): observações do que já aconteceu — nunca recomendação de dose. */
@Composable
fun PatternsScreen(onBack: () -> Unit, onHealthConnect: () -> Unit, vm: PatternsViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsState()
    val appearance = rememberChartAppearance()
    val unit = appearance.unit
    val context = LocalContext.current

    Page(stringResource(R.string.patterns_title), onBack = onBack) {
        GluCallout(stringResource(R.string.patterns_disclaimer), tone = Tone.Info)
        ChoiceChips(listOf(14, 30, 90), ui?.days ?: 30, vm::load, { stringResource(R.string.hc_days, it) })
        val u = ui
        if (u == null) { ListSkeleton(rows = 3); return@Page }
        if (u.readings == 0) {
            Text(stringResource(R.string.patterns_no_glucose), style = MaterialTheme.typography.bodyMedium)
            return@Page
        }

        GluSection(stringResource(R.string.patterns_exercise)) {
            if (u.exercise.isEmpty()) EmptyHint(onHealthConnect)
            u.exercise.forEach { g ->
                GluCard {
                    Text(HealthConnectSync.exerciseLabel(context, g.typeKey), style = MaterialTheme.typography.titleSmall)
                    FactList(listOf(
                        Fact(stringResource(R.string.patterns_sessions), stringResource(R.string.patterns_sessions_value, g.sessions, g.minutes.roundToInt())),
                        Fact(stringResource(R.string.patterns_avg_during), avg(g.during, unit)),
                        Fact(stringResource(R.string.patterns_avg_after), avg(g.after, unit)),
                        Fact(stringResource(R.string.patterns_min_after), if (g.after.count > 0) formatGlucoseWithUnit(g.after.min, unit) else "—"),
                        Fact(stringResource(R.string.patterns_hypo_after), stringResource(R.string.patterns_of_sessions, g.sessionsWithHypoAfter, g.sessions))
                    ))
                }
            }
        }

        GluSection(stringResource(R.string.patterns_sleep)) {
            val s = u.sleep
            if (s.nights == 0) EmptyHint(onHealthConnect)
            else GluCard {
                FactList(listOf(
                    Fact(stringResource(R.string.patterns_nights), s.nights.toString()),
                    Fact(stringResource(R.string.patterns_avg_sleep), hours(s.avgAsleepMin)),
                    Fact(stringResource(R.string.patterns_tir_short), tir(s.shortSleep) + " (" + s.shortNights + ")"),
                    Fact(stringResource(R.string.patterns_tir_long), tir(s.longSleep) + " (" + (s.nights - s.shortNights) + ")"),
                    Fact(stringResource(R.string.patterns_night_hypos), stringResource(R.string.patterns_of_nights, s.nightsWithHypo, s.nights))
                ))
                if (s.shortNights > 0 && s.nights - s.shortNights > 0 && s.shortSleep.count > 0 && s.longSleep.count > 0) {
                    Text(stringResource(R.string.patterns_sleep_sentence, tir(s.shortSleep), tir(s.longSleep)),
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        GluSection(stringResource(R.string.patterns_cycle)) {
            if (!u.hasCycleData) EmptyHint(onHealthConnect)
            else GluCard {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    u.cycle.forEach { p ->
                        Text(stringResource(phaseLabel(p.phase)), style = MaterialTheme.typography.titleSmall)
                        FactList(listOf(
                            Fact(stringResource(R.string.patterns_avg), avg(p.stats, unit)),
                            Fact(stringResource(R.string.patterns_tir), tir(p.stats)),
                            Fact(stringResource(R.string.patterns_cv), if (p.stats.count > 0) pct(p.stats.cvPct) else "—"),
                            Fact(stringResource(R.string.patterns_days), p.days.toString())
                        ))
                    }
                    Text(stringResource(R.string.patterns_cycle_note), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun EmptyHint(onHealthConnect: () -> Unit) {
    GluCallout(stringResource(R.string.patterns_empty), tone = Tone.Neutral,
        actionLabel = stringResource(R.string.hc_title), onAction = onHealthConnect)
}

private fun pct(v: Double) = String.format(Locale.getDefault(), "%.0f%%", v)
private fun tir(s: HealthInsights.Stats) = if (s.count > 0) pct(s.tirPct) else "—"
private fun avg(s: HealthInsights.Stats, unit: GlucoseUnit) = if (s.count > 0) formatGlucoseWithUnit(s.avg, unit) else "—"
private fun hours(min: Double) = String.format(Locale.getDefault(), "%dh%02d", (min / 60).toInt(), (min % 60).roundToInt().coerceAtMost(59))

private fun phaseLabel(p: HealthInsights.CyclePhase) = when (p) {
    HealthInsights.CyclePhase.MENSTRUAL -> R.string.patterns_phase_menstrual
    HealthInsights.CyclePhase.FOLLICULAR -> R.string.patterns_phase_follicular
    HealthInsights.CyclePhase.LUTEAL -> R.string.patterns_phase_luteal
}
