package com.gluview.app.ui.screen.applog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.gluview.app.R
import com.gluview.app.data.local.entity.AppLogEntity
import com.gluview.app.ui.components.chart.rememberChartAppearance
import com.gluview.app.ui.components.kit.AppTopBar
import com.gluview.app.ui.components.kit.EmptyState
import com.gluview.app.ui.components.kit.ListSkeleton
import com.gluview.app.ui.components.kit.StatusBadge
import com.gluview.app.ui.components.kit.Tone
import com.gluview.app.ui.theme.Spacing
import com.gluview.app.util.TimeFormat
import com.gluview.app.util.formatDate
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.log10
import kotlin.math.pow

/**
 * F1/T08 — Tela de logs do app: lista os registros de um dia escolhido (do banco), com filtro
 * de nível e exportação dos arquivos de texto completos.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(
    onBack: () -> Unit,
    viewModel: LogViewModel = hiltViewModel()
) {
    val logs by viewModel.logs.collectAsState()
    val selectedDate by viewModel.selectedDate.collectAsState()
    val errorsOnly by viewModel.errorsOnly.collectAsState()
    val logSizeBytes by viewModel.logSizeBytes.collectAsState()

    var showDatePicker by remember { mutableStateOf(false) }

    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = selectedDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                    val date = Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate()
                    return !date.isAfter(viewModel.today()) && !date.isBefore(viewModel.oldestSelectableDate())
                }
            }
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { millis ->
                        val date = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                        viewModel.onDateSelected(date)
                    }
                    showDatePicker = false
                }) { Text(stringResource(R.string.chart_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text(stringResource(R.string.settings_cancel)) }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    val snackbar = remember { SnackbarHostState() }
    val exportFailed by viewModel.exportFailed.collectAsState()
    val context = LocalContext.current
    LaunchedEffect(exportFailed) {
        if (exportFailed) { snackbar.showSnackbar(context.getString(R.string.applog_export_failed)); viewModel.clearExportFailed() }
    }

    LogContent(
        logs = logs,
        selectedDate = selectedDate,
        errorsOnly = errorsOnly,
        logSizeBytes = logSizeBytes,
        timeFormat = rememberChartAppearance().timeFormat,
        canPrev = selectedDate.isAfter(viewModel.oldestSelectableDate()),
        canNext = selectedDate.isBefore(viewModel.today()),
        onShiftDay = viewModel::onShiftDay,
        onPickDate = { showDatePicker = true },
        onToggleErrorsOnly = viewModel::onToggleErrorsOnly,
        onExport = viewModel::export,
        onBack = onBack,
        snackbarHostState = snackbar
    )
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB")
    val exp = (log10(bytes.toDouble()) / log10(1024.0)).toInt().coerceIn(1, units.size)
    val value = bytes / 1024.0.pow(exp)
    return String.format(Locale.getDefault(), "%.1f %s", value, units[exp - 1])
}

/** Hora com segundos no formato do usuário ("14:32:05" / "2:32:05 PM"); o dia já está no topo. */
private fun timeWithSeconds(ms: Long, format: TimeFormat): String =
    Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern(if (format == TimeFormat.H24) "HH:mm:ss" else "h:mm:ss a", Locale.getDefault()))

/**
 * Logs do app (redesign visual, Fase 7): o dia navega numa linha e "Só erros" desce para a sua
 * (antes tudo numa Row que estourava com fonte grande ou em inglês); a data no idioma; lista
 * PLANA com divisória (antes um card elevado por registro, centenas por dia); erro com selo
 * "Erro" + ícone, não só a cor da tag; só a hora, com segundos; dados extras (JSON) recolhidos,
 * abrem ao tocar; falha ao exportar vira aviso (antes engolida); carregando sem piscar "Nenhum log".
 */
@Composable
internal fun LogContent(
    logs: List<AppLogEntity>?,
    selectedDate: LocalDate,
    errorsOnly: Boolean,
    logSizeBytes: Long,
    timeFormat: TimeFormat,
    canPrev: Boolean,
    canNext: Boolean,
    onShiftDay: (Long) -> Unit,
    onPickDate: () -> Unit,
    onToggleErrorsOnly: () -> Unit,
    onExport: () -> Unit,
    onBack: () -> Unit,
    snackbarHostState: SnackbarHostState? = null
) {
    Scaffold(
        topBar = {
            AppTopBar(stringResource(R.string.applog_title), onBack) {
                IconButton(onClick = onExport) {
                    Icon(Icons.Default.Share, contentDescription = stringResource(R.string.applog_export))
                }
            }
        },
        snackbarHost = { snackbarHostState?.let { SnackbarHost(it) } }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = Spacing.gutter, vertical = Spacing.sm)
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                        IconButton(onClick = { onShiftDay(-1) }, enabled = canPrev) {
                            Icon(Icons.Default.ChevronLeft, stringResource(R.string.chart_previous_day))
                        }
                        AssistChip(
                            modifier = Modifier.weight(1f, fill = false),
                            onClick = onPickDate,
                            leadingIcon = { Icon(Icons.Default.CalendarMonth, null, modifier = Modifier.size(18.dp)) },
                            label = { Text(formatDate(selectedDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli())) }
                        )
                        IconButton(onClick = { onShiftDay(1) }, enabled = canNext) {
                            Icon(Icons.Default.ChevronRight, stringResource(R.string.chart_next_day))
                        }
                    }
                    FilterChip(selected = errorsOnly, onClick = onToggleErrorsOnly,
                        label = { Text(stringResource(R.string.applog_filter_errors_only)) })
                    val count = logs?.size ?: 0
                    Text(
                        pluralStringResource(R.plurals.applog_size_info, count, count, formatBytes(logSizeBytes)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = Spacing.xs)
                    )
                }
            }
            when {
                logs == null -> item { ListSkeleton(rows = 4) }
                logs.isEmpty() -> item {
                    EmptyState(stringResource(R.string.applog_no_logs), stringResource(R.string.applog_empty_body), Icons.Outlined.Description)
                }
                else -> itemsIndexed(logs, key = { _, l -> l.id }) { i, log ->
                    if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    LogRow(log, timeWithSeconds(log.ts, timeFormat))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LogRow(log: AppLogEntity, time: String) {
    val isError = log.level == "error"
    val hasData = log.data.isNotBlank() && log.data != "{}"
    var expanded by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth()
            .then(if (hasData) Modifier.clickable { expanded = !expanded } else Modifier)
            .padding(vertical = Spacing.xs),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(time, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (isError) StatusBadge(stringResource(R.string.applog_level_error), tone = Tone.Danger, icon = Icons.Default.ErrorOutline)
            Text(log.tag, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
        Text(log.msg, style = MaterialTheme.typography.bodyMedium)
        if (log.origin.isNotBlank()) {
            // Pontos de quebra depois de "." e antes de ":" (o identificador não tem espaço).
            Text(log.origin.replace(".", ".\u200B").replace(":", "\u200B:"), style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (hasData) {
            if (expanded) {
                Text(log.data, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(stringResource(R.string.applog_show_data), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
