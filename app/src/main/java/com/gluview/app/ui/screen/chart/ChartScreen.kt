package com.gluview.app.ui.screen.chart

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.gluview.app.R
import com.gluview.app.ui.components.chart.ChartAppearance
import com.gluview.app.ui.components.chart.GlucoseChart
import com.gluview.app.ui.components.chart.GlucoseChartPanel
import com.gluview.app.ui.components.chart.rememberChartAppearance
import com.gluview.app.ui.components.kit.LoadState
import com.gluview.app.ui.theme.Spacing
import com.gluview.app.util.GraphReading
import com.gluview.app.util.UiText
import com.gluview.app.util.formatDate
import com.gluview.app.util.graph.GraphWindow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

// Sem Scaffold próprio — vira o conteúdo de uma aba de HistoryScreen (Scaffold/TopAppBar
// compartilhada entre Gráfico/TIR/Padrão).
@Composable
fun ChartScreen(
    vm: ChartViewModel = hiltViewModel()
) {
    val mode      by vm.mode.collectAsState()
    val window    by vm.window.collectAsState()
    val isLoading by vm.isLoading.collectAsState()
    val error     by vm.error.collectAsState()
    val isEmpty   by vm.isEmpty.collectAsState()
    val points    by vm.points.collectAsState()
    ChartContent(
        mode = mode, window = window, isLoading = isLoading, error = error, isEmpty = isEmpty,
        points          = points,
        appearance      = rememberChartAppearance(),
        today           = vm::today,
        onRangeSelected = vm::onRangeSelected,
        onShiftDay      = vm::onShiftDay,
        onDateSelected  = vm::onDateSelected,
        onRetry         = vm::retry
    )
}

/**
 * Aba Gráfico do Histórico (redesign visual, Fase 4): o mesmo modelo da Home ([GlucoseChart]),
 * com a janela FIXA da busca (não anda com o relógio — um tick desenharia uma lacuna falsa no
 * fim), chips 3/6/12/24h, navegação por dia com a data no formato do idioma e os 3 estados.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChartContent(
    mode: ChartMode,
    window: ChartWindow,
    isLoading: Boolean,
    error: String?,
    isEmpty: Boolean,
    points: List<GraphReading>,
    appearance: ChartAppearance,
    today: () -> LocalDate,
    onRangeSelected: (ChartRange) -> Unit,
    onShiftDay: (Long) -> Unit,
    onDateSelected: (LocalDate) -> Unit,
    onRetry: (() -> Unit)? = null
) {
    var showDatePicker by remember { mutableStateOf(false) }

    if (showDatePicker) {
        val initialDate = (mode as? ChartMode.SpecificDay)?.date ?: today()
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = initialDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                    utcTimeMillis <= System.currentTimeMillis()
            }
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { millis ->
                        onDateSelected(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate())
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

    val specificDay = mode as? ChartMode.SpecificDay
    val state: LoadState<List<GraphReading>> = when {
        isLoading -> LoadState.Loading
        error != null -> LoadState.Error(UiText.Res(R.string.history_load_error_body), detail = error)
        isEmpty -> LoadState.Empty
        else -> LoadState.Ready(points)
    }

    GlucoseChartPanel(
        title = null,
        modifier = Modifier.fillMaxSize().padding(horizontal = Spacing.md).padding(top = Spacing.xxs, bottom = Spacing.xs),
        periods = ChartRange.entries.map { it.hours },
        // Dia escolhido: nenhum chip marcado (o período é o dia, não uma janela até agora).
        selectedPeriod = (mode as? ChartMode.Relative)?.range?.hours ?: -1,
        onSelectPeriod = { h -> ChartRange.entries.firstOrNull { it.hours == h }?.let(onRangeSelected) },
        extraControls = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (specificDay != null) {
                    IconButton(onClick = { onShiftDay(-1) }) {
                        Icon(Icons.Default.ChevronLeft, stringResource(R.string.chart_previous_day))
                    }
                }
                AssistChip(
                    onClick = { showDatePicker = true },
                    leadingIcon = { Icon(Icons.Default.CalendarMonth, null, modifier = Modifier.size(18.dp)) },
                    label = {
                        Text(specificDay?.date?.let {
                            // Data no formato do idioma (antes "dd/MM/yyyy" fixo).
                            formatDate(it.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli())
                        } ?: stringResource(R.string.chart_choose_date))
                    }
                )
                if (specificDay != null) {
                    IconButton(onClick = { onShiftDay(1) }, enabled = specificDay.date.isBefore(today())) {
                        Icon(Icons.Default.ChevronRight, stringResource(R.string.chart_next_day))
                    }
                }
            }
        }
    ) {
        GlucoseChart(
            state = state,
            window = GraphWindow.Fixed(window.startMs, window.endMs),
            appearance = appearance,
            onRetry = onRetry,
            modifier = Modifier.fillMaxWidth().weight(1f)
        )
    }
}
