package com.gluview.app.ui.screen.eventlog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.EventNote
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.gluview.app.R
import com.gluview.app.data.local.entity.EventLogEntity
import com.gluview.app.ui.components.chart.rememberChartAppearance
import com.gluview.app.ui.components.kit.AppModalBottomSheet
import com.gluview.app.ui.components.kit.AppTopBar
import com.gluview.app.ui.components.kit.ChoiceChips
import com.gluview.app.ui.components.kit.ConfirmDialog
import com.gluview.app.ui.components.kit.EmptyState
import com.gluview.app.ui.components.kit.GluCard
import com.gluview.app.ui.components.kit.ListSkeleton
import com.gluview.app.ui.components.kit.RowGroup
import com.gluview.app.ui.theme.Spacing
import com.gluview.app.util.GlucoseUnit
import com.gluview.app.util.TimeFormat
import com.gluview.app.util.dayLabel
import com.gluview.app.util.epochDay
import com.gluview.app.util.formatGlucoseWithUnit
import com.gluview.app.util.formatTime
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

@Composable
fun EventLogScreen(
    onBack: () -> Unit,
    vm: EventLogViewModel = hiltViewModel()
) {
    val events by vm.events.collectAsState()
    val appearance = rememberChartAppearance()
    var showSheet by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<EventLogEntity?>(null) }

    EventLogContent(events, appearance.unit, appearance.timeFormat, onAdd = { showSheet = true }, onDelete = { deleting = it }, onBack = onBack)

    if (showSheet) {
        AddEventSheet(
            onDismiss = { showSheet = false },
            onAdd     = { type, value, notes ->
                vm.addEvent(type, value, notes)
                showSheet = false
            }
        )
    }
    // Excluir sem volta: confirmação (L26, mesma regra das chaves e do backup).
    deleting?.let { event ->
        ConfirmDialog(
            title = stringResource(R.string.eventlog_delete_title),
            message = stringResource(R.string.eventlog_delete_body),
            confirmLabel = stringResource(R.string.watchface_delete_button),
            onConfirm = { vm.delete(event); deleting = null },
            onDismiss = { deleting = null }
        )
    }
}

@Composable
private fun typeLabel(type: String): String = stringResource(when (type) {
    "meal"     -> R.string.eventlog_type_meal
    "insulin"  -> R.string.eventlog_type_insulin
    "exercise" -> R.string.eventlog_type_exercise
    else       -> R.string.eventlog_type_note
})

@Composable
private fun unitLabel(type: String): String = when (type) {
    "meal"     -> stringResource(R.string.eventlog_unit_carbs)
    "insulin"  -> stringResource(R.string.eventlog_unit_insulin)
    "exercise" -> stringResource(R.string.eventlog_unit_minutes_short)
    else       -> ""
}

/** Valor com as casas que existem, no idioma (4,5 / 4.5) — antes truncava 4,5 U para "4 U". */
private fun formatAmount(v: Double): String =
    DecimalFormat("0.##", DecimalFormatSymbols.getInstance(Locale.getDefault())).format(v)

/**
 * Log de eventos (redesign visual, Fase 7): lista agrupada por dia ("Hoje", "Ontem", data) com
 * só a hora na linha, no formato do usuário; valor com as casas certas; glicemia na unidade do
 * usuário, ou "sem leitura" (antes "Glicemia: 0 mg/dL"); lixeira neutra com confirmação (antes
 * vermelha em toda linha e apagava num toque); "Registrar" no topo (o FAB cobria o último item);
 * carregando sem piscar "Nenhum evento" e vazio que diz o que registrar.
 */
@Composable
internal fun EventLogContent(
    events: List<EventLogEntity>?,
    unit: GlucoseUnit,
    timeFormat: TimeFormat,
    onAdd: () -> Unit,
    onDelete: (EventLogEntity) -> Unit,
    onBack: () -> Unit,
    nowMs: Long = System.currentTimeMillis()
) {
    val context = LocalContext.current
    Scaffold(topBar = { AppTopBar(stringResource(R.string.settings_event_log), onBack) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = Spacing.gutter, vertical = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            when {
                events == null -> item { ListSkeleton(rows = 3) }
                events.isEmpty() -> item {
                    EmptyState(
                        title = stringResource(R.string.eventlog_no_events),
                        body = stringResource(R.string.eventlog_empty_body),
                        icon = Icons.Outlined.EventNote,
                        actionLabel = stringResource(R.string.eventlog_add),
                        onAction = onAdd
                    )
                }
                else -> {
                    item {
                        Button(onClick = onAdd, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Add, null, modifier = Modifier.padding(end = Spacing.xs))
                            Text(stringResource(R.string.eventlog_add))
                        }
                    }
                    // observeRecent já vem do mais novo para o mais antigo.
                    events.groupBy { epochDay(it.occurredAt) }.forEach { (_, dayEvents) ->
                        item(key = "day-${dayEvents.first().occurredAt}") {
                            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                                Text(context.dayLabel(dayEvents.first().occurredAt, nowMs),
                                    style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.semantics { heading() })
                                GluCard {
                                    RowGroup(dayEvents.map { e -> { EventRow(e, unit, timeFormat) { onDelete(e) } } })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EventRow(event: EventLogEntity, unit: GlucoseUnit, timeFormat: TimeFormat, onDelete: () -> Unit) {
    val amount = if (event.value > 0) " — ${formatAmount(event.value)} ${unitLabel(event.type)}".trimEnd() else ""
    val glucose = if (event.sgvAtEvent > 0) formatGlucoseWithUnit(event.sgvAtEvent, unit)
                  else stringResource(R.string.eventlog_no_reading)
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = Spacing.xxs), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("${typeLabel(event.type)}$amount", style = MaterialTheme.typography.bodyLarge)
            if (event.notes.isNotBlank()) Text(event.notes, style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.eventlog_time_glucose2, formatTime(event.occurredAt, timeFormat), glucose),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Outlined.Delete, stringResource(R.string.watchface_delete_button), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Número digitado com vírgula ou ponto decimal ("4,5" ou "4.5"); null se não for número. */
internal fun parseDecimalInput(text: String): Double? = text.trim().replace(',', '.').toDoubleOrNull()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddEventSheet(
    onDismiss: () -> Unit,
    onAdd:     (EventType, Double, String) -> Unit
) {
    var selectedType by remember { mutableStateOf(EventType.MEAL) }
    var valueText    by remember { mutableStateOf("") }
    var notes        by remember { mutableStateOf("") }
    val typeKey = selectedType.name.lowercase()

    AppModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg).padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Text(stringResource(R.string.eventlog_new_event), style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() })

            // Quebra de linha em vez de cortar o 4º chip (antes Row sem quebra).
            ChoiceChips(EventType.entries, selectedType, { selectedType = it; valueText = "" }, { typeLabel(it.name.lowercase()) })

            if (selectedType != EventType.NOTE) {
                OutlinedTextField(
                    value         = valueText,
                    onValueChange = { valueText = it },
                    label         = { Text(stringResource(R.string.eventlog_value_label, typeLabel(typeKey), unitLabel(typeKey))) },
                    placeholder   = { Text(stringResource(when (selectedType) {
                        EventType.MEAL -> R.string.eventlog_example_meal
                        EventType.INSULIN -> R.string.eventlog_example_insulin
                        else -> R.string.eventlog_example_exercise
                    })) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine    = true,
                    modifier      = Modifier.fillMaxWidth()
                )
            }

            OutlinedTextField(
                value         = notes,
                onValueChange = { notes = it },
                label         = { Text(stringResource(R.string.eventlog_notes_optional)) },
                maxLines      = 3,
                modifier      = Modifier.fillMaxWidth()
            )

            Button(
                onClick  = {
                    // L23: aceita vírgula decimal ("4,5"), que antes virava 0.
                    val v = parseDecimalInput(valueText) ?: 0.0
                    onAdd(selectedType, v, notes.trim())
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.settings_save)) }
        }
    }
}
