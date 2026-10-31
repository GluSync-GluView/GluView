package com.gluview.app.ui.screen.caregiver

import kotlinx.coroutines.delay
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.LaunchedEffect
import com.gluview.app.ui.components.LocalClock
import com.gluview.app.util.formatElapsed
import com.gluview.app.ui.components.glucose.GlucoseStatsCard
import com.gluview.app.ui.components.glucose.GlucoseStat
import com.gluview.app.domain.model.GlucoseZone
import com.gluview.app.ui.components.glucose.glucoseZoneFor
import com.gluview.app.ui.components.glucose.GlucoseHeroCard
import com.gluview.app.util.graph.GraphWindow
import com.gluview.app.ui.theme.Spacing
import com.gluview.app.ui.theme.LocalAppDarkTheme
import com.gluview.app.ui.components.kit.LoadState
import com.gluview.app.ui.components.kit.ErrorState
import com.gluview.app.ui.components.chart.rememberChartAppearance
import com.gluview.app.ui.components.chart.GlucoseChartPanel
import com.gluview.app.ui.components.chart.GlucoseChart
import com.gluview.app.ui.components.chart.ChartAppearance
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gluview.app.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FollowedPatientScreen(
    patientName: String,
    onBack: () -> Unit,
    vm: FollowedPatientViewModel = hiltViewModel()
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    FollowedPatientContent(patientName = patientName, ui = ui, onBack = onBack,
        onRetry = vm::refresh, appearance = rememberChartAppearance())
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FollowedPatientContent(
    patientName: String,
    ui: FollowedPatientUiState,
    onBack: () -> Unit,
    onRetry: (() -> Unit)? = null,
    // Tema do gráfico resolvido pela tela (preferência); limiares do paciente (L1) vêm do estado.
    appearance: ChartAppearance = ChartAppearance(dark = LocalAppDarkTheme.current)
) {
    // "há X min" anda sozinho (mesmo relógio da Home do seguidor; fixo no harness).
    val clock = LocalClock.current
    var nowMs by remember { mutableLongStateOf(clock()) }
    LaunchedEffect(Unit) { while (true) { nowMs = clock(); delay(10_000L) } }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(patientName.ifBlank { stringResource(R.string.role_patient) }) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.settings_back)) } }
            )
        }
    ) { padding ->
        when {
            ui.loading -> Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) { CircularProgressIndicator() }
            ui.error != null -> Box(Modifier.fillMaxSize().padding(padding).padding(Spacing.md), Alignment.Center) {
                // Erro com "Tentar novamente" (refresh() do ViewModel), nunca só um texto vermelho.
                ErrorState(ui.error!!, onRetry)
            }
            else -> Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Card único da leitura (o mesmo da Home e do seguidor).
                // L10: delta e "há X min" pela hora da medição, como na Home do seguidor.
                val ageText = if (ui.measuredAtMs > 0) formatElapsed(ui.measuredAtMs, nowMs) else null
                val deltaText = if (ui.delta != 0) with(appearance.unit) { 0.0.deltaToDisplay(ui.delta) } else null
                GlucoseHeroCard(
                    valueText = ui.sgv?.let { with(appearance.unit) { it.toDouble().toDisplay() } } ?: "--",
                    arrow = ui.arrow,
                    unitLabel = appearance.unit.label,
                    zone = ui.sgv?.let { glucoseZoneFor(it.toDouble(), ui.thresholds, ui.arrow) } ?: GlucoseZone.STALE,
                    subtitle = listOfNotNull(deltaText, ageText).joinToString(" · ").ifBlank { null }
                )
                ui.stats?.let { s ->
                    with(appearance.unit) {
                        GlucoseStatsCard(
                            stats = listOf(
                                GlucoseStat(stringResource(R.string.home_stat_min), s.min.toDisplay()),
                                GlucoseStat(stringResource(R.string.home_stat_avg), s.avg.toDisplay()),
                                GlucoseStat(stringResource(R.string.home_stat_max), s.max.toDisplay())
                            ),
                            caption = stringResource(R.string.home_last_24_hours)
                        )
                    }
                }
                // Tamanho real (sem o bitmap 900x400 esticado) e o tema do gráfico resolvido.
                GlucoseChartPanel(title = stringResource(R.string.home_history), modifier = Modifier.fillMaxWidth().weight(1f)) {
                    GlucoseChart(
                        state = if (ui.readings.isEmpty()) LoadState.Empty else LoadState.Ready(ui.readings),
                        window = GraphWindow.Trailing(24),
                        appearance = appearance.copy(hipo = ui.thresholds.low, hiper = ui.thresholds.high,
                            urgentLow = ui.thresholds.urgentLow, urgentHigh = ui.thresholds.urgentHigh),
                        modifier = Modifier.fillMaxWidth().weight(1f)
                    )
                }
            }
        }
    }
}

