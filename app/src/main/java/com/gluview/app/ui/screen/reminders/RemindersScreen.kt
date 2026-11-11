package com.gluview.app.ui.screen.reminders

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.gluview.app.R
import com.gluview.app.data.diary.InsulinKind
import com.gluview.app.data.local.entity.InsulinEntity
import com.gluview.app.data.local.entity.MedicationEntity
import com.gluview.app.data.local.entity.ReminderEntity
import com.gluview.app.data.local.entity.ReminderLogEntity
import com.gluview.app.data.reminder.ReminderKind
import com.gluview.app.data.reminder.ReminderRepository
import com.gluview.app.data.reminder.ReminderSchedule
import com.gluview.app.ui.components.chart.rememberChartAppearance
import com.gluview.app.ui.components.kit.AppModalBottomSheet
import com.gluview.app.ui.components.kit.ChoiceChips
import com.gluview.app.ui.components.kit.ConfirmDialog
import com.gluview.app.ui.components.kit.GluCallout
import com.gluview.app.ui.components.kit.GluCard
import com.gluview.app.ui.components.kit.GluSection
import com.gluview.app.ui.components.kit.Page
import com.gluview.app.ui.components.kit.SwitchRow
import com.gluview.app.ui.components.kit.Tone
import com.gluview.app.ui.screen.diary.DecimalField
import com.gluview.app.ui.screen.diary.parseDecimalInput
import com.gluview.app.ui.theme.Spacing
import com.gluview.app.util.DiaryText
import com.gluview.app.util.TimeFormat
import com.gluview.app.util.formatTime
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

@Composable
fun RemindersScreen(onBack: () -> Unit, onCatalog: () -> Unit, vm: RemindersViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val appearance = rememberChartAppearance()
    val reminders by vm.reminders.collectAsState()
    val insulins by vm.insulins.collectAsState()
    val medications by vm.medications.collectAsState()
    val log by vm.todayLog.collectAsState()
    val smart by vm.smartMealPrompt.collectAsState()
    val exactAllowed by vm.exactAllowed.collectAsState()
    val notificationsAllowed by vm.notificationsAllowed.collectAsState()
    var editing by remember { mutableStateOf<ReminderEntity?>(null) }
    var deleting by remember { mutableStateOf<ReminderEntity?>(null) }

    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) vm.refreshPermissions() }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    Page(stringResource(R.string.reminders_title), onBack = onBack) {
        Text(stringResource(R.string.reminders_intro), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!notificationsAllowed) {
            GluCallout(stringResource(R.string.reminders_notifications_off), tone = Tone.Danger,
                actionLabel = stringResource(R.string.reminders_open_settings), onAction = {
                    context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                })
        }
        if (!exactAllowed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            GluCallout(stringResource(R.string.reminders_exact_off), tone = Tone.Warning,
                actionLabel = stringResource(R.string.reminders_allow_exact), onAction = {
                    runCatching {
                        context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                            Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                })
        }

        GluSection(stringResource(R.string.reminders_mine), count = reminders?.size) {
            val list = reminders.orEmpty()
            if (reminders != null && list.isEmpty()) {
                Text(stringResource(R.string.reminders_empty), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (list.isNotEmpty()) GluCard {
                list.forEachIndexed { i, r ->
                    if (i > 0) HorizontalDivider()
                    ReminderRow(r, appearance.timeFormat, onClick = { editing = r }, onToggle = { vm.setEnabled(r, it) })
                }
            }
            Button(onClick = { editing = ReminderEntity(title = "", kind = ReminderKind.MEASURE.key, timesCsv = "480") },
                modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Add, null); Text(" " + stringResource(R.string.reminders_add))
            }
            Text(stringResource(R.string.reminders_templates), style = MaterialTheme.typography.labelLarge)
            TemplateChips(insulins) { editing = it }
        }

        GluSection(stringResource(R.string.reminders_today)) {
            if (log.isEmpty()) Text(stringResource(R.string.reminders_today_empty), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            else GluCard {
                val names = reminders.orEmpty().associate { it.id to it.title }
                log.take(20).forEach { l ->
                    Text(stringResource(R.string.reminders_log_line, formatTime(l.at, appearance.timeFormat),
                        names[l.reminderId].orEmpty(), stringResource(actionLabel(l))),
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        GluSection(stringResource(R.string.reminders_smart), description = stringResource(R.string.reminders_smart_desc)) {
            SwitchRow(stringResource(R.string.reminders_smart_meal), smart, vm::setSmartMealPrompt,
                description = stringResource(R.string.reminders_smart_meal_desc))
        }
    }

    editing?.let { r ->
        ReminderEditorSheet(
            initial = r, insulins = insulins, medications = medications, timeFormat = appearance.timeFormat,
            onDismiss = { editing = null },
            onSave = { vm.save(it); editing = null },
            onDelete = if (r.id != 0L) ({ editing = null; deleting = r }) else null,
            onCatalog = { editing = null; onCatalog() }
        )
    }
    deleting?.let { r ->
        ConfirmDialog(
            title = stringResource(R.string.reminders_delete_title),
            message = stringResource(R.string.reminders_delete_body),
            confirmLabel = stringResource(R.string.watchface_delete_button),
            onConfirm = { vm.delete(r); deleting = null },
            onDismiss = { deleting = null }
        )
    }
}

private fun actionLabel(l: ReminderLogEntity): Int = when (l.action) {
    ReminderRepository.ACTION_DONE -> R.string.reminders_log_done
    ReminderRepository.ACTION_SNOOZED -> R.string.reminders_log_snoozed
    ReminderRepository.ACTION_SKIPPED -> R.string.reminders_log_skipped
    ReminderRepository.ACTION_SUPPRESSED -> R.string.reminders_log_suppressed
    else -> R.string.reminders_log_fired
}

@Composable
internal fun kindLabel(kind: ReminderKind): String = stringResource(when (kind) {
    ReminderKind.MEASURE -> R.string.reminder_kind_measure
    ReminderKind.INSULIN -> R.string.reminder_kind_insulin
    ReminderKind.MEDICATION -> R.string.reminder_kind_medication
    ReminderKind.BASAL_CHECK -> R.string.reminder_kind_basal_check
    ReminderKind.CUSTOM -> R.string.reminder_kind_custom
})

@Composable
private fun daysText(mask: Int): String {
    if (mask and ReminderSchedule.ALL_DAYS == ReminderSchedule.ALL_DAYS) return stringResource(R.string.reminders_every_day)
    return DayOfWeek.entries.filter { ReminderSchedule.isActiveOn(mask, it) }
        .joinToString(", ") { it.getDisplayName(TextStyle.SHORT, Locale.getDefault()) }
}

@Composable
private fun ReminderRow(r: ReminderEntity, timeFormat: TimeFormat, onClick: () -> Unit, onToggle: (Boolean) -> Unit) {
    val times = ReminderSchedule.parseTimes(r.timesCsv).joinToString(" · ") { minutesText(it, timeFormat) }
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick).padding(vertical = Spacing.xxs),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(r.title.ifBlank { kindLabel(ReminderKind.of(r.kind)) }, style = MaterialTheme.typography.bodyLarge)
            Text("$times — ${daysText(r.daysMask)}", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = r.enabled, onCheckedChange = onToggle)
    }
}

internal fun minutesText(m: Int, timeFormat: TimeFormat): String {
    val zone = ZoneId.systemDefault()
    val ms = java.time.LocalDate.now(zone).atTime(ReminderSchedule.formatMinutes(m)).atZone(zone).toInstant().toEpochMilli()
    return formatTime(ms, timeFormat, zone)
}

/** Modelos prontos: um toque e o editor já vem preenchido. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TemplateChips(insulins: List<InsulinEntity>, onPick: (ReminderEntity) -> Unit) {
    val basal = insulins.firstOrNull { InsulinKind.of(it.kind) == InsulinKind.BASAL }
    val tBasal = stringResource(R.string.reminder_template_basal)
    val tCheck = stringResource(R.string.reminder_template_basal_check)
    val tMorning = stringResource(R.string.reminder_template_measure_morning)
    val tMed = stringResource(R.string.reminder_template_medication)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        AssistChip(onClick = { onPick(ReminderEntity(title = tBasal, kind = ReminderKind.INSULIN.key, timesCsv = "1320",
            linkedUuid = basal?.uuid ?: "")) }, label = { Text(tBasal) })
        AssistChip(onClick = { onPick(ReminderEntity(title = tCheck, kind = ReminderKind.BASAL_CHECK.key, timesCsv = "1380",
            linkedUuid = basal?.uuid ?: "")) }, label = { Text(tCheck) })
        AssistChip(onClick = { onPick(ReminderEntity(title = tMorning, kind = ReminderKind.MEASURE.key, timesCsv = "420")) },
            label = { Text(tMorning) })
        AssistChip(onClick = { onPick(ReminderEntity(title = tMed, kind = ReminderKind.MEDICATION.key, timesCsv = "480")) },
            label = { Text(tMed) })
    }
}

@OptIn(ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ReminderEditorSheet(
    initial: ReminderEntity,
    insulins: List<InsulinEntity>,
    medications: List<MedicationEntity>,
    timeFormat: TimeFormat,
    onDismiss: () -> Unit,
    onSave: (ReminderEntity) -> Unit,
    onDelete: (() -> Unit)?,
    onCatalog: () -> Unit
) {
    val context = LocalContext.current
    val text = remember(context) { DiaryText(context) }
    var kind by remember { mutableStateOf(ReminderKind.of(initial.kind)) }
    var title by remember { mutableStateOf(initial.title) }
    var times by remember { mutableStateOf(ReminderSchedule.parseTimes(initial.timesCsv)) }
    var mask by remember { mutableStateOf(initial.daysMask) }
    var linked by remember { mutableStateOf(initial.linkedUuid) }
    var doseText by remember { mutableStateOf(if (initial.dose > 0) text.amount(initial.dose) else "") }
    var addingTime by remember { mutableStateOf(false) }
    val usesInsulin = kind == ReminderKind.INSULIN || kind == ReminderKind.BASAL_CHECK
    val valid = times.isNotEmpty() && mask and ReminderSchedule.ALL_DAYS != 0

    AppModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.lg).padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Text(stringResource(if (initial.id == 0L) R.string.reminders_new else R.string.reminders_edit),
                style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            ChoiceChips(ReminderKind.entries, kind, { kind = it; linked = "" }, { kindLabel(it) })
            if (kind == ReminderKind.BASAL_CHECK) {
                GluCallout(stringResource(R.string.reminder_kind_basal_check_desc), tone = Tone.Info)
            }
            OutlinedTextField(title, { title = it }, label = { Text(stringResource(R.string.reminders_field_title)) },
                placeholder = { Text(kindLabel(kind)) }, singleLine = true, modifier = Modifier.fillMaxWidth())

            if (usesInsulin || kind == ReminderKind.MEDICATION) {
                val options: List<Pair<String, String>> = if (usesInsulin)
                    insulins.map { it.uuid to "${it.name} · ${text.insulinKind(InsulinKind.of(it.kind))}" }
                else medications.map { it.uuid to it.name }
                if (options.isEmpty()) {
                    GluCallout(stringResource(if (usesInsulin) R.string.diary_no_insulins else R.string.reminders_no_medications),
                        actionLabel = stringResource(R.string.diary_catalog_title), onAction = onCatalog)
                } else {
                    Text(stringResource(R.string.reminders_linked), style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        options.forEach { (uuid, label) ->
                            FilterChip(selected = linked == uuid, onClick = { linked = if (linked == uuid) "" else uuid }, label = { Text(label) })
                        }
                    }
                    DecimalField(doseText, { doseText = it }, stringResource(R.string.reminders_field_dose))
                    Text(stringResource(R.string.reminders_dose_hint), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Text(stringResource(R.string.reminders_times), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                times.forEach { m ->
                    InputChip(selected = false, onClick = {}, label = { Text(minutesText(m, timeFormat)) },
                        trailingIcon = { Icon(Icons.Outlined.Close, stringResource(R.string.reminders_remove_time, minutesText(m, timeFormat)),
                            modifier = Modifier.clickable { times = times - m }) })
                }
                AssistChip(onClick = { addingTime = true }, label = { Text(stringResource(R.string.reminders_add_time)) },
                    leadingIcon = { Icon(Icons.Outlined.Add, null) })
            }

            Text(stringResource(R.string.reminders_days), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                DayOfWeek.entries.forEach { d ->
                    FilterChip(selected = ReminderSchedule.isActiveOn(mask, d),
                        onClick = { mask = mask xor ReminderSchedule.dayBit(d) },
                        label = { Text(d.getDisplayName(TextStyle.SHORT, Locale.getDefault())) })
                }
            }

            Button(enabled = valid, modifier = Modifier.fillMaxWidth(), onClick = {
                onSave(initial.copy(
                    title = title.trim(), kind = kind.key, timesCsv = ReminderSchedule.encodeTimes(times), daysMask = mask,
                    linkedUuid = linked, dose = parseDecimalInput(doseText) ?: 0.0, enabled = true
                ))
            }) { Text(stringResource(R.string.settings_save)) }
            if (onDelete != null) OutlinedButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.reminders_delete))
            }
        }
    }

    if (addingTime) {
        val state = rememberTimePickerState(8, 0, is24Hour = timeFormat == TimeFormat.H24)
        AlertDialog(
            onDismissRequest = { addingTime = false },
            confirmButton = { TextButton(onClick = { times = (times + (state.hour * 60 + state.minute)).distinct().sorted(); addingTime = false }) {
                Text(stringResource(R.string.common_ok)) } },
            dismissButton = { TextButton(onClick = { addingTime = false }) { Text(stringResource(R.string.common_cancel)) } },
            text = { TimePicker(state = state) }
        )
    }
}
