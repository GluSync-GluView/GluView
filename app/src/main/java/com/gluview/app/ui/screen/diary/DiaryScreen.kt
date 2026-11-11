package com.gluview.app.ui.screen.diary

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.Bloodtype
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DirectionsRun
import androidx.compose.material.icons.outlined.EventNote
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Medication
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.StickyNote2
import androidx.compose.material.icons.outlined.Vaccines
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.gluview.app.R
import com.gluview.app.data.diary.DaySummary
import com.gluview.app.data.diary.DiaryType
import com.gluview.app.data.diary.KetoneLevel
import com.gluview.app.data.diary.KetoneMethod
import com.gluview.app.data.diary.ketoneLevel
import com.gluview.app.data.local.entity.EventLogEntity
import com.gluview.app.data.local.entity.InsulinEntity
import com.gluview.app.data.local.entity.MedicationEntity
import com.gluview.app.ui.components.chart.rememberChartAppearance
import com.gluview.app.ui.components.kit.ConfirmDialog
import com.gluview.app.ui.components.kit.EmptyState
import com.gluview.app.ui.components.kit.Fact
import com.gluview.app.ui.components.kit.FactList
import com.gluview.app.ui.components.kit.GluCard
import com.gluview.app.ui.components.kit.ListSkeleton
import com.gluview.app.ui.components.kit.RowGroup
import com.gluview.app.ui.components.kit.StatusBadge
import com.gluview.app.ui.components.kit.Tone
import com.gluview.app.ui.components.kit.AppTopBar
import com.gluview.app.ui.theme.Spacing
import com.gluview.app.util.DiaryText
import com.gluview.app.util.GlucoseUnit
import com.gluview.app.util.TimeFormat
import com.gluview.app.util.dayLabel
import com.gluview.app.util.formatGlucoseWithUnit
import com.gluview.app.util.formatTime
import java.time.LocalDate
import java.time.ZoneId

/** Cor e ícone de cada tipo (lista, gráfico e Home usam os mesmos). */
internal fun DiaryType.icon(): ImageVector = when (this) {
    DiaryType.MEAL -> Icons.Outlined.Restaurant
    DiaryType.INSULIN -> Icons.Outlined.Vaccines
    DiaryType.FINGERSTICK -> Icons.Outlined.Bloodtype
    DiaryType.KETONE -> Icons.Outlined.Science
    DiaryType.MEDICATION -> Icons.Outlined.Medication
    DiaryType.EXERCISE -> Icons.Outlined.DirectionsRun
    DiaryType.NOTE -> Icons.Outlined.StickyNote2
}

/** Cores dos marcadores (mesmas no claro e no escuro: contraste ≥ 3:1 nos dois fundos do gráfico). */
internal fun DiaryType.color(): Color = Color(markerArgb())

internal fun DiaryType.markerArgb(): Int = when (this) {
    DiaryType.MEAL -> 0xFFE08A00.toInt()
    DiaryType.INSULIN -> 0xFF2F6FDB.toInt()
    DiaryType.FINGERSTICK -> 0xFFD0343B.toInt()
    DiaryType.KETONE -> 0xFF8E44AD.toInt()
    DiaryType.MEDICATION -> 0xFF1E9E8F.toInt()
    DiaryType.EXERCISE -> 0xFF3E9B3E.toInt()
    DiaryType.NOTE -> 0xFF7A7A7A.toInt()
}

@Composable
fun DiaryScreen(
    onBack: (() -> Unit)?,
    onMeal: (String?) -> Unit,
    onCatalog: () -> Unit,
    onReminders: () -> Unit,
    /** Registro rápido vindo de fora (Home, lembrete): abre direto a folha desse tipo. */
    initialType: DiaryType? = null,
    vm: DiaryViewModel = hiltViewModel()
) {
    val appearance = rememberChartAppearance()
    val day by vm.day.collectAsState()
    val entries by vm.entries.collectAsState()
    val summary by vm.summary.collectAsState()
    val insulins by vm.insulins.collectAsState()
    val medications by vm.medications.collectAsState()
    val cloudOn by vm.cloudOn.collectAsState()
    val message by vm.message.collectAsState()
    var editing by remember { mutableStateOf<EventLogEntity?>(null) }
    var deleting by remember { mutableStateOf<EventLogEntity?>(null) }
    var handledInitial by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(initialType) {
        if (!handledInitial && initialType != null) {
            handledInitial = true
            if (initialType == DiaryType.MEAL) onMeal(null) else editing = newEntry(initialType, insulins)
        }
    }
    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(context.getString(it)); vm.clearMessage() }
    }

    DiaryContent(
        day = day,
        entries = entries,
        summary = summary,
        unit = appearance.unit,
        timeFormat = appearance.timeFormat,
        cloudOn = cloudOn,
        snackbar = snackbar,
        onBack = onBack,
        onPrevDay = vm::previousDay,
        onNextDay = vm::nextDay,
        onToday = vm::goToday,
        onAdd = { type -> if (type == DiaryType.MEAL) onMeal(null) else editing = newEntry(type, insulins) },
        onOpen = { e -> if (e.type == DiaryType.MEAL.key) onMeal(e.uuid) else editing = e },
        onDelete = { deleting = it },
        onCatalog = onCatalog,
        onReminders = onReminders,
        onExport = vm::exportPdf
    )

    editing?.let { entry ->
        DiaryEntrySheet(
            initial = entry,
            insulins = insulins,
            medications = medications,
            unit = appearance.unit,
            timeFormat = appearance.timeFormat,
            onDismiss = { editing = null },
            onSave = { vm.save(it); editing = null },
            onManageCatalog = { editing = null; onCatalog() }
        )
    }
    deleting?.let { entry ->
        ConfirmDialog(
            title = stringResource(R.string.diary_delete_title),
            message = stringResource(R.string.diary_delete_body),
            confirmLabel = stringResource(R.string.watchface_delete_button),
            onConfirm = { vm.delete(entry); deleting = null },
            onDismiss = { deleting = null }
        )
    }
}

/** Registro novo do tipo, com a insulina mais provável já escolhida. */
internal fun newEntry(type: DiaryType, insulins: List<InsulinEntity>): EventLogEntity {
    val base = EventLogEntity(type = type.key, dirty = true)
    return when (type) {
        DiaryType.INSULIN -> insulins.firstOrNull()?.let { base.copy(subtype = it.kind, itemName = it.name) }
            ?: base.copy(subtype = "rapid")
        DiaryType.KETONE -> base.copy(subtype = KetoneMethod.BLOOD.key)
        else -> base
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DiaryContent(
    day: LocalDate,
    entries: List<EventLogEntity>?,
    summary: DaySummary,
    unit: GlucoseUnit,
    timeFormat: TimeFormat,
    cloudOn: Boolean,
    onBack: (() -> Unit)?,
    onPrevDay: () -> Unit,
    onNextDay: () -> Unit,
    onToday: () -> Unit,
    onAdd: (DiaryType) -> Unit,
    onOpen: (EventLogEntity) -> Unit,
    onDelete: (EventLogEntity) -> Unit,
    onCatalog: () -> Unit,
    onReminders: () -> Unit,
    onExport: (Int) -> Unit,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
    nowMs: Long = System.currentTimeMillis()
) {
    val context = LocalContext.current
    val text = remember(context, unit) { DiaryText(context, unit) }
    var menu by remember { mutableStateOf(false) }
    val zone = ZoneId.systemDefault()
    val today = java.time.Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()

    Scaffold(
        topBar = {
            AppTopBar(stringResource(R.string.diary_title), onBack) {
                IconButton(onClick = onReminders) { Icon(Icons.Outlined.Alarm, stringResource(R.string.reminders_title)) }
                IconButton(onClick = onCatalog) { Icon(Icons.Outlined.Inventory2, stringResource(R.string.diary_catalog_title)) }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.diary_more)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DiaryViewModel.EXPORT_DAYS.forEach { d ->
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.diary_export_days, d)) },
                                onClick = { menu = false; onExport(d) }
                            )
                        }
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = Spacing.gutter, vertical = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            item(key = "day") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onPrevDay) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.diary_prev_day))
                    }
                    val dayMs = day.atStartOfDay(zone).toInstant().toEpochMilli() + 12 * 3_600_000L
                    Text(context.dayLabel(dayMs, nowMs), style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f).semantics { heading() },
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    IconButton(onClick = onNextDay, enabled = day < today) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.diary_next_day))
                    }
                }
                if (day != today) {
                    TextButton(onClick = onToday, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.diary_go_today)) }
                }
            }
            item(key = "summary") { SummaryCard(summary, text, cloudOn) }
            item(key = "add") {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Text(stringResource(R.string.diary_add), style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary, modifier = Modifier.semantics { heading() })
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                        DiaryType.entries.forEach { type ->
                            AssistChip(
                                onClick = { onAdd(type) },
                                label = { Text(text.typeLabel(type)) },
                                leadingIcon = { Icon(type.icon(), null, tint = type.color()) }
                            )
                        }
                    }
                }
            }
            when {
                entries == null -> item { ListSkeleton(rows = 3) }
                entries.isEmpty() -> item {
                    EmptyState(
                        title = stringResource(R.string.diary_empty_title),
                        body = stringResource(R.string.diary_empty_body),
                        icon = Icons.Outlined.EventNote
                    )
                }
                else -> item(key = "list") {
                    GluCard {
                        RowGroup(entries.map { e -> { EntryRow(e, text, unit, timeFormat, { onOpen(e) }, { onDelete(e) }) } })
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryCard(s: DaySummary, text: DiaryText, cloudOn: Boolean) {
    GluCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.diary_summary_title), style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f).semantics { heading() })
            StatusBadge(
                stringResource(if (cloudOn) R.string.diary_badge_cloud else R.string.diary_badge_local),
                tone = if (cloudOn) Tone.Info else Tone.Neutral
            )
        }
        FactList(buildList {
            add(Fact(stringResource(R.string.diary_sum_carbs), stringResource(R.string.diary_value_grams, text.amount(s.carbsG))))
            if (s.kcal > 0) add(Fact(stringResource(R.string.diary_sum_kcal), stringResource(R.string.diary_value_kcal, text.amount(s.kcal))))
            add(Fact(stringResource(R.string.diary_insulin_rapid), stringResource(R.string.diary_value_units, text.amount(s.rapidU))))
            add(Fact(stringResource(R.string.diary_insulin_basal), stringResource(R.string.diary_value_units, text.amount(s.basalU))))
            if (s.mixedU > 0) add(Fact(stringResource(R.string.diary_insulin_mixed), stringResource(R.string.diary_value_units, text.amount(s.mixedU))))
            if (s.exerciseMin > 0) add(Fact(stringResource(R.string.diary_type_exercise), stringResource(R.string.diary_value_minutes, text.amount(s.exerciseMin))))
            add(Fact(stringResource(R.string.diary_sum_entries), s.entries.toString()))
        })
    }
}

@Composable
private fun EntryRow(
    e: EventLogEntity,
    text: DiaryText,
    unit: GlucoseUnit,
    timeFormat: TimeFormat,
    onOpen: () -> Unit,
    onDelete: () -> Unit
) {
    val type = DiaryType.of(e.type)
    val glucose = if (e.sgvAtEvent > 0) formatGlucoseWithUnit(e.sgvAtEvent, unit) else stringResource(R.string.eventlog_no_reading)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .clickable(onClickLabel = stringResource(R.string.diary_edit), onClick = onOpen)
            .padding(vertical = Spacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        Box(Modifier.size(36.dp).background(type.color().copy(alpha = 0.15f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(type.icon(), null, tint = type.color(), modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text.title(e), style = MaterialTheme.typography.bodyLarge)
            val value = text.value(e)
            if (value.isNotBlank()) Text(value, style = MaterialTheme.typography.bodyMedium)
            if (type == DiaryType.KETONE) {
                val level = ketoneLevel(KetoneMethod.of(e.subtype), e.value)
                if (level != KetoneLevel.NORMAL) StatusBadge(stringResource(ketoneLevelLabel(level)), tone = ketoneTone(level))
            }
            if (e.notes.isNotBlank()) Text(e.notes, style = MaterialTheme.typography.bodyMedium)
            Text(
                if (type == DiaryType.FINGERSTICK) formatTime(e.occurredAt, timeFormat)
                else stringResource(R.string.eventlog_time_glucose2, formatTime(e.occurredAt, timeFormat), glucose),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Outlined.Delete, stringResource(R.string.watchface_delete_button), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

internal fun ketoneLevelLabel(level: KetoneLevel): Int = when (level) {
    KetoneLevel.NORMAL -> R.string.diary_ketone_normal
    KetoneLevel.ATTENTION -> R.string.diary_ketone_attention
    KetoneLevel.RISK -> R.string.diary_ketone_risk
    KetoneLevel.URGENT -> R.string.diary_ketone_urgent
}

internal fun ketoneTone(level: KetoneLevel): Tone = when (level) {
    KetoneLevel.NORMAL -> Tone.Success
    KetoneLevel.ATTENTION -> Tone.Warning
    KetoneLevel.RISK, KetoneLevel.URGENT -> Tone.Danger
}

/** Registro do diário → marcador do gráfico (cor do tipo, altura da glicemia do momento). */
fun toChartMarker(e: EventLogEntity): com.gluview.app.ui.components.chart.ChartMarker {
    val type = DiaryType.of(e.type)
    val y = if (type == DiaryType.FINGERSTICK) e.value else e.sgvAtEvent
    return com.gluview.app.ui.components.chart.ChartMarker(e.occurredAt, y, type.markerArgb())
}
