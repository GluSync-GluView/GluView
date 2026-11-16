package com.gluview.app.ui.screen.nightscout

import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gluview.app.R
import com.gluview.app.data.diary.CareEvent
import com.gluview.app.nightscout.AapsStatus
import com.gluview.app.nightscout.CareAges
import com.gluview.app.nightscout.NightscoutRepository
import com.gluview.app.ui.components.kit.GluCallout
import com.gluview.app.ui.components.kit.GluCard
import com.gluview.app.ui.components.kit.GluSection
import com.gluview.app.ui.components.kit.Page
import com.gluview.app.ui.components.kit.Tone
import com.gluview.app.ui.theme.Spacing
import com.gluview.therapy.AgeLevel
import com.gluview.therapy.Ages
import com.gluview.therapy.DailyRow
import com.gluview.therapy.DistBucket
import com.gluview.therapy.HourlyRow
import com.gluview.therapy.RLimits
import com.gluview.therapy.RPoint
import com.gluview.therapy.Reports
import com.gluview.therapy.SuccessRow
import com.gluview.therapy.WeekRow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class NightscoutViewModel @Inject constructor(private val repo: NightscoutRepository) : ViewModel() {
    private val _ages = MutableStateFlow<CareAges?>(null); val ages: StateFlow<CareAges?> = _ages
    private val _aaps = MutableStateFlow<AapsStatus?>(null); val aaps: StateFlow<AapsStatus?> = _aaps
    private val _loadingAaps = MutableStateFlow(true); val loadingAaps: StateFlow<Boolean> = _loadingAaps
    private val _points = MutableStateFlow<List<RPoint>?>(null); val points: StateFlow<List<RPoint>?> = _points
    private val _limits = MutableStateFlow(RLimits()); val limits: StateFlow<RLimits> = _limits

    fun loadHub() = viewModelScope.launch {
        _ages.value = repo.ages()
        _loadingAaps.value = true; _aaps.value = repo.aapsStatus(); _loadingAaps.value = false
    }
    fun record(e: CareEvent, note: String) = viewModelScope.launch { repo.recordChange(e, note); _ages.value = repo.ages() }
    fun loadReports(days: Int) = viewModelScope.launch {
        val now = System.currentTimeMillis()
        _limits.value = repo.limits()
        _points.value = repo.points(now - days * 86_400_000L, now)
    }
}

// Limites das idades (horas): aviso / urgente — os padrões do Nightscout (CAGE/IAGE 48/72,
// BAGE 336/360) e, para o sensor, 13 e 14 dias.
private const val CANNULA_WARN = 48; private const val CANNULA_URGENT = 72
private const val INSULIN_WARN = 48; private const val INSULIN_URGENT = 72
private const val BATTERY_WARN = 336; private const val BATTERY_URGENT = 360
private const val SENSOR_WARN = 312; private const val SENSOR_URGENT = 336

/** Plano 21, F5.1/F5.2 — idades (sensor, cânula, insulina, bateria) e status da bomba/alça. */
@Composable
fun NightscoutHubScreen(onBack: () -> Unit, onReports: () -> Unit, vm: NightscoutViewModel = hiltViewModel()) {
    val ages by vm.ages.collectAsState()
    val aaps by vm.aaps.collectAsState()
    val loading by vm.loadingAaps.collectAsState()
    LaunchedEffect(Unit) { vm.loadHub() }
    val now = System.currentTimeMillis()
    val noteText = stringResource(R.string.ns_change_note)

    Page(title = stringResource(R.string.ns_title), onBack = onBack) {
        GluSection(stringResource(R.string.ns_ages), description = stringResource(R.string.ns_ages_desc)) {
            ages?.let { a ->
                AgeCard(stringResource(R.string.ns_age_sensor), a.sensorSince, now, SENSOR_WARN, SENSOR_URGENT, null)
                AgeCard(stringResource(R.string.ns_age_cannula), a.cannulaSince, now, CANNULA_WARN, CANNULA_URGENT) { vm.record(CareEvent.SITE_CHANGE, noteText) }
                AgeCard(stringResource(R.string.ns_age_insulin), a.insulinSince, now, INSULIN_WARN, INSULIN_URGENT) { vm.record(CareEvent.INSULIN_CHANGE, noteText) }
                AgeCard(stringResource(R.string.ns_age_battery), a.batterySince, now, BATTERY_WARN, BATTERY_URGENT) { vm.record(CareEvent.PUMP_BATTERY, noteText) }
            }
        }
        GluSection(stringResource(R.string.ns_pump), description = stringResource(R.string.ns_pump_desc)) {
            val s = aaps
            when {
                loading -> Text(stringResource(R.string.ns_loading), style = MaterialTheme.typography.bodyMedium)
                s == null -> GluCallout(stringResource(R.string.ns_pump_none), tone = Tone.Info)
                else -> AapsCard(s, now)
            }
        }
        Button(onClick = onReports, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.ns_reports)) }
    }
}

private fun hoursText(ms: Long): String {
    val h = (ms / 3_600_000L).toInt()
    return if (h >= 48) "${h / 24}d ${h % 24}h" else "${h}h"
}

@Composable
private fun AgeCard(title: String, since: Long?, now: Long, warn: Int, urgent: Int, onChanged: (() -> Unit)?) {
    val level = Ages.level(since, now, warn, urgent)
    GluCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(if (since == null) stringResource(R.string.ns_age_unknown) else hoursText(now - since),
                    style = MaterialTheme.typography.headlineSmall,
                    color = when (level) {
                        AgeLevel.URGENT -> MaterialTheme.colorScheme.error
                        AgeLevel.WARN -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.onSurface
                    })
            }
            onChanged?.let { TextButton(onClick = it) { Text(stringResource(R.string.ns_changed_now)) } }
        }
    }
}

@Composable
private fun AapsCard(s: AapsStatus, now: Long) {
    GluCard {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val ageMin = ((now - s.atMs) / 60_000L).toInt()
            Text(stringResource(R.string.ns_status_age, ageMin, s.device ?: "AAPS"), style = MaterialTheme.typography.bodySmall)
            if (ageMin > 15) Text(stringResource(R.string.ns_status_old), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            s.loopAtMs?.let { Text(stringResource(if (s.enacted) R.string.ns_loop_enacted else R.string.ns_loop_suggested, ((now - it) / 60_000L).toInt()),
                style = MaterialTheme.typography.titleMedium) }
            if (s.suspended) Text(stringResource(R.string.ns_suspended), color = MaterialTheme.colorScheme.error)
            Text(listOfNotNull(
                s.iob?.let { stringResource(R.string.ns_iob, fmt(it, 2)) },
                s.cob?.let { stringResource(R.string.ns_cob, fmt(it, 0)) },
                s.tempBasalRate?.let { stringResource(R.string.ns_temp_basal, fmt(it, 2), s.tempBasalMin ?: 0) }
            ).joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
            Text(listOfNotNull(
                s.reservoirU?.let { stringResource(R.string.ns_reservoir, fmt(it, 0)) },
                s.pumpBatteryPct?.let { stringResource(R.string.ns_pump_battery, it) },
                s.uploaderBatteryPct?.let { stringResource(R.string.ns_phone_battery, it) }
            ).joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
            s.eventualBg?.let { Text(stringResource(R.string.ns_eventual, fmt(it, 0)), style = MaterialTheme.typography.bodyMedium) }
            s.reason?.let { Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
        }
    }
}

private fun fmt(v: Double, d: Int) = String.format(Locale.getDefault(), "%.${d}f", v)

private enum class RTab { DAILY, DISTRIBUTION, HOURLY, WEEKLY, SUCCESS }

/** Plano 21, F5.3 — relatórios no estilo do Nightscout, calculados no aparelho, com CSV. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NightscoutReportsScreen(onBack: () -> Unit, vm: NightscoutViewModel = hiltViewModel()) {
    var days by remember { mutableIntStateOf(14) }
    var tab by remember { mutableStateOf(RTab.DAILY) }
    val points by vm.points.collectAsState()
    val limits by vm.limits.collectAsState()
    LaunchedEffect(days) { vm.loadReports(days) }
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()
    val df = DateTimeFormatter.ofPattern("dd/MM")
    val ctx = LocalContext.current
    fun c(id: Int) = ctx.getString(id)

    Page(title = stringResource(R.string.ns_reports), onBack = onBack) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            listOf(7, 14, 30, 90).forEach { d -> FilterChip(selected = d == days, onClick = { days = d }, label = { Text(stringResource(R.string.ns_days, d)) }) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            RTab.entries.forEach { t -> FilterChip(selected = t == tab, onClick = { tab = t }, label = { Text(stringResource(tabLabel(t))) }) }
        }
        val p = points ?: run { Text(stringResource(R.string.ns_loading)); return@Page }
        if (p.isEmpty()) { GluCallout(stringResource(R.string.ns_no_data), tone = Tone.Info); return@Page }
        val daily = remember(p, limits) { Reports.daily(p, limits, zone) }
        val (header, rows) = when (tab) {
            RTab.DAILY -> listOf(c(R.string.ns_col_day), "N", c(R.string.ns_col_mean), c(R.string.ns_col_sd), "CV%", c(R.string.ns_col_below), c(R.string.ns_col_range), c(R.string.ns_col_above), "GMI") to
                daily.map { r: DailyRow -> listOf(r.day.format(df), r.stats.n, r.stats.mean, r.stats.sd, r.stats.cv,
                    r.stats.pUrgentLow + r.stats.pLow, r.stats.pInRange, r.stats.pHigh + r.stats.pUrgentHigh, r.stats.gmi) }
            RTab.DISTRIBUTION -> listOf("mg/dL", "N", "%") to
                Reports.distribution(p).filter { it.count > 0 }.map { b: DistBucket -> listOf("${b.fromMgDl}-${b.toMgDl}", b.count, b.percent) }
            RTab.HOURLY -> listOf(c(R.string.ns_col_hour), "N", c(R.string.ns_col_mean), "P10", "P25", c(R.string.ns_col_median), "P75", "P90") to
                Reports.hourly(p, zone).map { h: HourlyRow -> listOf(String.format("%02d", h.hour), h.n, h.mean, h.p10, h.p25, h.median, h.p75, h.p90) }
            RTab.WEEKLY -> listOf(c(R.string.ns_col_week), "N", c(R.string.ns_col_mean), "CV%", c(R.string.ns_col_range), "GMI") to
                Reports.weekly(p, limits, zone).map { w: WeekRow -> listOf(w.weekStart.format(df), w.stats.n, w.stats.mean, w.stats.cv, w.stats.pInRange, w.stats.gmi) }
            RTab.SUCCESS -> listOf(c(R.string.ns_col_day), c(R.string.ns_col_range), c(R.string.ns_col_below), c(R.string.ns_col_goal_range), c(R.string.ns_col_goal_low)) to
                Reports.success(daily).map { s: SuccessRow -> listOf(s.day.format(df), s.pInRange, s.pBelow,
                    if (s.inRangeOk) "✓" else "✗", if (s.lowOk) "✓" else "✗") }
        }
        if (tab == RTab.SUCCESS) Text(stringResource(R.string.ns_success_goal), style = MaterialTheme.typography.bodySmall)
        Table(header, rows)
        OutlinedButton(onClick = {
            val csv = Reports.csv(header, rows)
            val i = Intent(Intent.ACTION_SEND).setType("text/csv").putExtra(Intent.EXTRA_TEXT, csv)
                .putExtra(Intent.EXTRA_SUBJECT, "GluView")
            context.startActivity(Intent.createChooser(i, null))
        }) { Text(stringResource(R.string.ns_share_csv)) }
    }
}

@Composable
private fun Table(header: List<String>, rows: List<List<Any>>) {
    GluCard {
        Column(Modifier.horizontalScroll(rememberScrollState())) {
            @Composable fun row(cells: List<String>, bold: Boolean) = Row {
                cells.forEach { c -> Text(c, modifier = Modifier.width(64.dp), style = if (bold) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodySmall) }
            }
            row(header, true)
            rows.forEach { r -> row(r.map { if (it is Double) fmt(it, 1) else it.toString() }, false) }
        }
    }
}

private fun tabLabel(t: RTab) = when (t) {
    RTab.DAILY -> R.string.ns_tab_daily
    RTab.DISTRIBUTION -> R.string.ns_tab_distribution
    RTab.HOURLY -> R.string.ns_tab_hourly
    RTab.WEEKLY -> R.string.ns_tab_weekly
    RTab.SUCCESS -> R.string.ns_tab_success
}
