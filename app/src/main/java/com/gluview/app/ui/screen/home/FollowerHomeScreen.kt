package com.gluview.app.ui.screen.home

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.minimumInteractiveComponentSize
import com.gluview.app.domain.model.GlucoseZone
import com.gluview.app.ui.components.glucose.glucoseZoneFor
import com.gluview.app.ui.components.glucose.GlucoseHeroCard
import com.gluview.app.ui.components.chart.rememberChartAppearance
import com.gluview.app.util.graph.GraphWindow
import com.gluview.app.ui.theme.LocalAppDarkTheme
import com.gluview.app.ui.components.kit.LoadState
import com.gluview.app.ui.components.chart.GlucoseChartPanel
import com.gluview.app.ui.components.chart.GlucoseChart
import com.gluview.app.ui.components.chart.ChartAppearance
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PersonSearch
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.gluview.app.R
import com.gluview.app.ui.components.LocalClock
import com.gluview.app.ui.screen.caregiver.CaregiverItem
import kotlinx.coroutines.delay

/**
 * Home de uma conta seguidor-apenas: escolhe qual paciente acompanhar (pode ter vários) e
 * mostra a leitura dele em tempo real. Sem paciente selecionado, mostra um estado vazio com
 * o convite pra escolher — nunca tenta ler/parear sensor (isso é coisa de paciente).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FollowerHomeScreen(
    onNavigateToAlertHistory: () -> Unit,
    followerVm: FollowerHomeViewModel = hiltViewModel(),
    homeVm: HomeViewModel = hiltViewModel()
) {
    val ui by followerVm.ui.collectAsState()
    val alertCount by homeVm.activeAlertCount.collectAsState()
    FollowerHomeContent(
        ui                       = ui,
        alertCount               = alertCount,
        onNavigateToAlertHistory = onNavigateToAlertHistory,
        onOpenPicker             = followerVm::openPicker,
        onClosePicker            = followerVm::closePicker,
        onSelectPatient          = followerVm::selectPatient,
        appearance               = rememberChartAppearance()
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FollowerHomeContent(
    ui: FollowerHomeUiState,
    alertCount: Int,
    onNavigateToAlertHistory: () -> Unit,
    onOpenPicker: () -> Unit,
    onClosePicker: () -> Unit,
    onSelectPatient: (CaregiverItem) -> Unit,
    // Tema do gráfico resolvido pela tela (preferência); limiares 70/180 até a decisão L1.
    appearance: ChartAppearance = ChartAppearance(dark = LocalAppDarkTheme.current)
) {
    val clock = LocalClock.current
    // Período do gráfico: filtra as 24h já buscadas; a escolha não é salva (decisão 5.2 #6).
    var hours by rememberSaveable { mutableIntStateOf(24) }
    // Relógio que tica a cada 10s — base do "há X min" dinâmico, mesmo padrão da Home do paciente.
    var nowMs by remember { mutableLongStateOf(clock()) }
    LaunchedEffect(Unit) { while (true) { nowMs = clock(); delay(10_000L) } }

    Scaffold(
        topBar = {
            TopAppBar(
                // Nome do paciente ativo vira o próprio título, clicável — troca de paciente sem
                // precisar de uma linha extra de chip abaixo da barra (menos espaço em branco).
                title = {
                    if (ui.patientId.isNotBlank()) {
                        // Seletor de paciente: alvo de 48dp, papel de botão e rótulo da ação no TalkBack.
                        val pickLabel = stringResource(R.string.follower_home_choose_patient_title)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(onClickLabel = pickLabel, role = Role.Button, onClick = onOpenPicker)
                                .minimumInteractiveComponentSize()
                                .padding(horizontal = 4.dp)
                        ) {
                            Icon(Icons.Default.PersonSearch, null, modifier = Modifier.size(20.dp))
                            Text(
                                ui.patientName.ifBlank { stringResource(R.string.role_patient) },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Icon(Icons.Default.ArrowDropDown, null)
                        }
                    } else {
                        Text("GluView")
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateToAlertHistory) {
                        BadgedBox(badge = {
                            if (alertCount > 0) Badge { Text(if (alertCount > 9) "9+" else "$alertCount") }
                        }) {
                            Icon(Icons.Default.Notifications, contentDescription = stringResource(R.string.home_alert_history))
                        }
                    }
                }
            )
        }
    ) { padding ->
        // Como na Home: o gráfico ocupa o que sobra; se a tela é baixa demais (fonte grande em tela
        // estreita), a coluna rola e o gráfico ganha altura fixa em vez de ser espremido.
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            val tight = maxHeight < 560.dp
            Column(
                modifier = Modifier.fillMaxSize()
                    .then(if (tight) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                when {
                    ui.patientId.isBlank() -> EmptyPatientState(onSelect = onOpenPicker)
                    ui.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                    ui.error != null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                        Text(ui.error!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(24.dp))
                    }
                    else -> {
                        // Card único da leitura (o mesmo da Home): valor na unidade, cor e selo da zona,
                        // delta e "há X min" — uma parada só do TalkBack.
                        val minutesAgo = if (ui.lastUpdatedMs > 0) ((nowMs - ui.lastUpdatedMs) / 60_000L).toInt() else -1
                        val ageText = when {
                            minutesAgo < 0  -> null
                            minutesAgo <= 0 -> stringResource(R.string.home_age_now)
                            else            -> stringResource(R.string.home_age_minutes_ago, minutesAgo)
                        }
                        val deltaText = if (ui.delta != 0) with(appearance.unit) { 0.0.deltaToDisplay(ui.delta) } else null
                        GlucoseHeroCard(
                            valueText = ui.sgv?.let { with(appearance.unit) { it.toDouble().toDisplay() } } ?: "--",
                            arrow = ui.arrow,
                            unitLabel = appearance.unit.label,
                            // Zona pelos limites do PACIENTE (L1).
                            zone = ui.sgv?.let { glucoseZoneFor(it.toDouble(), ui.thresholds, ui.arrow) } ?: GlucoseZone.STALE,
                            subtitle = listOfNotNull(deltaText, ageText).joinToString(" · ").ifBlank { null }
                        )
                        // weight(1f): o painel ocupa o espaço vertical restante, igual à Home do paciente.
                        GlucoseChartPanel(
                            title = stringResource(R.string.home_history),
                            modifier = if (tight) Modifier.fillMaxWidth() else Modifier.fillMaxWidth().weight(1f),
                            periods = listOf(1, 3, 6, 12, 24),
                            selectedPeriod = hours,
                            onSelectPeriod = { hours = it }
                        ) {
                            GlucoseChart(
                                state = if (ui.readings.isEmpty()) LoadState.Empty else LoadState.Ready(ui.readings),
                                window = GraphWindow.Trailing(hours),
                                appearance = appearance.copy(hipo = ui.thresholds.low, hiper = ui.thresholds.high,
                                    urgentLow = ui.thresholds.urgentLow, urgentHigh = ui.thresholds.urgentHigh),
                                modifier = if (tight) Modifier.fillMaxWidth().height(240.dp) else Modifier.fillMaxWidth().weight(1f)
                            )
                        }
                    }
                }
            }
        }
    }

    if (ui.showPicker) {
        PatientPickerDialog(
            following = ui.following,
            onDismiss = onClosePicker,
            onSelect  = onSelectPatient
        )
    }
}

@Composable
private fun EmptyPatientState(onSelect: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.Groups,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.follower_home_select_patient_prompt),
            style = MaterialTheme.typography.titleMedium,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onSelect) { Text(stringResource(R.string.follower_home_select_patient_button)) }
    }
}

@Composable
private fun PatientPickerDialog(
    following: List<CaregiverItem>,
    onDismiss: () -> Unit,
    onSelect: (CaregiverItem) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.follower_home_choose_patient_title)) },
        text = {
            if (following.isEmpty()) {
                Text(stringResource(R.string.follower_home_no_following_yet))
            } else {
                LazyColumn {
                    items(following, key = { it.link.id }) { item ->
                        ListItem(
                            headlineContent = { Text(item.otherName) },
                            modifier = Modifier.clickable { onSelect(item) }
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } }
    )
}

