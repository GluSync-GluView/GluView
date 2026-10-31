package com.gluview.app.ui.screen.report

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.gluview.app.R
import com.gluview.app.data.remote.dto.HbA1cResultDto
import com.gluview.app.data.remote.dto.SummaryReportDto
import com.gluview.app.ui.components.FollowedSubjectBanner
import com.gluview.app.ui.components.chart.rememberChartAppearance
import com.gluview.app.ui.components.kit.AppTopBar
import com.gluview.app.ui.components.kit.ErrorState
import com.gluview.app.ui.components.kit.Fact
import com.gluview.app.ui.components.kit.FactList
import com.gluview.app.ui.components.kit.GluCard
import com.gluview.app.ui.components.kit.ListSkeleton
import com.gluview.app.ui.components.rememberFollowedSubject
import com.gluview.app.ui.theme.Spacing
import com.gluview.app.util.GlucoseUnit
import com.gluview.app.util.ServerDates
import com.gluview.app.util.TimeFormat
import com.gluview.app.util.formatDateTime
import com.gluview.app.util.formatGlucoseWithUnit
import com.gluview.app.util.hba1cClassRes
import com.gluview.app.util.monthLabel
import java.time.YearMonth
import java.util.Locale

@Composable
fun ReportScreen(
    onBack: () -> Unit,
    vm: ReportViewModel = hiltViewModel()
) {
    val state by vm.uiState.collectAsState()
    val context = LocalContext.current

    // SnackBar de exportação
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(state.exportMsg) {
        state.exportMsg?.let {
            snackbarHostState.showSnackbar(context.getString(it))
            vm.clearExportMsg()
        }
    }

    Scaffold(
        topBar = { AppTopBar(title = stringResource(R.string.settings_medical_report), onBack = onBack) },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val appearance = rememberChartAppearance()
            ReportContent(
                state = state,
                unit = appearance.unit,
                timeFormat = appearance.timeFormat,
                subjectName = rememberFollowedSubject(),
                onMonthChanged = vm::onMonthChanged,
                onRetry = vm::retry,
                onExportPdf = vm::exportPdf,
                onExportCsv = vm::exportCsv
            )
        }
    }
}

/** Meses do seletor: do atual para trás até janeiro de 2 anos atrás — nenhum mês futuro. */
internal fun selectableMonths(now: YearMonth = YearMonth.now()): List<YearMonth> =
    generateSequence(now) { it.minusMonths(1) }.takeWhile { it.year >= now.year - 2 }.toList()

/** Classificação da HbA1c traduzida pelo código estável; GluSync antigo (sem código) → o texto dele. */
@Composable
private fun classificationLabel(h: HbA1cResultDto): String = hba1cClassRes(h.classificacaoCode)?.let { stringResource(it) } ?: h.classificacao

private fun pct(v: Double) = String.format(Locale.getDefault(), "%.1f%%", v)

/**
 * Relatório (redesign visual, Fase 4): o seletor de mês fica SEMPRE (carregando e erro aparecem
 * abaixo dele, com "Tentar novamente"); o resumo e cada mês são [FactList] em cards; valores no
 * idioma e na unidade do usuário; exportar mostra progresso e não dispara duas vezes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReportContent(
    state: ReportUiState,
    unit: GlucoseUnit,
    timeFormat: TimeFormat,
    subjectName: String?,
    onMonthChanged: (Int, Int) -> Unit,
    onRetry: (() -> Unit)?,
    onExportPdf: () -> Unit,
    onExportCsv: () -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        subjectName?.let { FollowedSubjectBanner(it) }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.md, vertical = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            val selected = YearMonth.of(state.ano, state.mes)
            var expanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(
                expanded         = expanded,
                onExpandedChange = { expanded = it },
                modifier         = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value         = monthLabel(selected),
                    onValueChange = {},
                    readOnly      = true,
                    label         = { Text(stringResource(R.string.report_period)) },
                    trailingIcon  = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                    modifier      = Modifier
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth()
                )
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    selectableMonths().forEach { m ->
                        DropdownMenuItem(
                            text    = { Text(monthLabel(m)) },
                            onClick = { onMonthChanged(m.monthValue, m.year); expanded = false }
                        )
                    }
                }
            }

            val report = state.report
            when {
                state.isLoading -> ListSkeleton(rows = 3)
                state.error != null -> ErrorState(
                    message = stringResource(R.string.history_load_error_body),
                    onRetry = onRetry,
                    detail = state.error
                )
                report != null -> ReportBody(report, unit, timeFormat, state.exporting, onExportPdf, onExportCsv)
            }
        }
    }
}

@Composable
private fun ReportBody(
    report: SummaryReportDto,
    unit: GlucoseUnit,
    timeFormat: TimeFormat,
    exporting: ExportKind?,
    onExportPdf: () -> Unit,
    onExportCsv: () -> Unit
) {
    val c = report.cabecalho
    GluCard {
        Text(stringResource(R.string.report_summary_title), style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() })
        FactList(buildList {
            // O período do bloco, como o servidor devolveu.
            add(Fact(stringResource(R.string.report_period), c.periodo))
            // Data no idioma e hora no formato do usuário (antes "dd/MM/yyyy HH:mm" fixo).
            add(Fact(stringResource(R.string.report_generated_label),
                ServerDates.toEpochMs(c.gerado)?.let { formatDateTime(it, timeFormat) } ?: c.gerado))
            add(Fact(stringResource(R.string.report_days_with_data), c.totalDias.toString()))
            c.hbA1c90dias?.let { h ->
                add(Fact(stringResource(R.string.report_hba1c_90days_label), pct(h.hbA1cEst)))
                add(Fact(stringResource(R.string.report_classification), classificationLabel(h)))
                add(Fact(stringResource(R.string.report_avg_glucose_90days), formatGlucoseWithUnit(h.mediaGlicemia, unit)))
            }
        })
    }

    if (report.resumoMensal.isNotEmpty()) {
        Text(stringResource(R.string.report_monthly_summary), style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = Spacing.xs).semantics { heading() })
        report.resumoMensal.forEach { m ->
            GluCard {
                Text(monthLabel(YearMonth.of(m.ano, m.mes)), style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.semantics { heading() })
                FactList(listOf(
                    Fact(stringResource(R.string.report_avg_glucose_label), formatGlucoseWithUnit(m.mediaMensal, unit)),
                    Fact(stringResource(R.string.report_time_in_range), pct(m.tirPercent)),
                    Fact(stringResource(R.string.report_hba1c_est), pct(m.hbA1cEstMensal)),
                    Fact(stringResource(R.string.report_days_with_data), m.totalDias.toString())
                ))
            }
        }
    }

    // Lado a lado; com fonte grande ou tela estreita, um embaixo do outro (senão "Exportar PDF" quebra).
    BoxWithConstraints(Modifier.fillMaxWidth().padding(top = Spacing.xs)) {
        val stacked = maxWidth < 320.dp || LocalDensity.current.fontScale >= 1.5f
        val csv: @Composable (Modifier) -> Unit = { m ->
            OutlinedButton(onClick = onExportCsv, enabled = exporting == null, modifier = m) {
                ExportLabel(stringResource(R.string.report_export_csv), busy = exporting == ExportKind.CSV)
            }
        }
        val pdf: @Composable (Modifier) -> Unit = { m ->
            Button(onClick = onExportPdf, enabled = exporting == null, modifier = m) {
                ExportLabel(stringResource(R.string.report_export_pdf), busy = exporting == ExportKind.PDF)
            }
        }
        if (stacked) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                pdf(Modifier.fillMaxWidth()); csv(Modifier.fillMaxWidth())
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                csv(Modifier.weight(1f)); pdf(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ExportLabel(text: String, busy: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
        if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
        Text(text, textAlign = TextAlign.Center)
    }
}
