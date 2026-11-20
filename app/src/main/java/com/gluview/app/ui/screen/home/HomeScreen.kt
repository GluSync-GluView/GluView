package com.gluview.app.ui.screen.home

import com.gluview.app.ui.components.kit.Tone
import com.gluview.app.ui.components.kit.GluCallout
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.minimumInteractiveComponentSize
import com.gluview.app.ui.theme.glucoseZoneTextColor
import com.gluview.app.ui.components.glucose.trendLabel
import com.gluview.app.ui.components.glucose.GlucoseHeroCard
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.Vaccines
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.gluview.app.util.formatTime
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.gluview.app.util.graph.GraphWindow
import com.gluview.app.ui.components.kit.LoadState
import com.gluview.app.ui.components.chart.GlucoseChartPanel
import com.gluview.app.ui.components.chart.GlucoseChart
import com.gluview.app.ui.components.chart.ChartAppearance
import com.gluview.app.R
import com.gluview.app.data.sibionics.SensorWarning
import com.gluview.app.data.sibionics.SibionicsSensorStatus
import com.gluview.app.domain.model.GlucoseZone
import com.gluview.app.ui.components.LocalClock
import com.gluview.app.ui.theme.*
import com.gluview.app.util.GlucoseUnit
import com.gluview.app.util.GraphReading
import com.gluview.app.util.GraphTheme
import com.gluview.app.util.TimeFormat
import com.gluview.app.util.epochMsToTimeOfDay
import kotlinx.coroutines.delay

/**
 * Modo Seguidor: conta sem papel de paciente delega pra [FollowerHomeScreen] (seletor de
 * paciente + leitura em tempo real dele) em vez da Home de paciente abaixo — ver plano de
 * remodelagem do modo seguidor.
 */
@Composable
fun HomeScreen(
    onNavigateToSettings:      () -> Unit,
    onNavigateToAlertHistory:  () -> Unit,
    onNavigateToDiary:         (String?) -> Unit = {},
    onNavigateToDiaryCatalog:  () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel()
) {
    val isFollowerOnly by viewModel.isFollowerOnly.collectAsState()
    if (isFollowerOnly) {
        FollowerHomeScreen(
            onNavigateToAlertHistory = onNavigateToAlertHistory
        )
    } else {
        PatientHomeScreen(
            onNavigateToSettings     = onNavigateToSettings,
            onNavigateToAlertHistory = onNavigateToAlertHistory,
            onNavigateToDiary        = onNavigateToDiary,
            onNavigateToDiaryCatalog = onNavigateToDiaryCatalog,
            viewModel                = viewModel
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PatientHomeScreen(
    onNavigateToSettings:      () -> Unit,
    onNavigateToAlertHistory:  () -> Unit,
    onNavigateToDiary:         (String?) -> Unit,
    onNavigateToDiaryCatalog:  () -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val diaryToday   by viewModel.diaryToday.collectAsState()
    val diaryMarkers by viewModel.diaryMarkers.collectAsState()
    // Com o ciclo de vida: fora da tela, para de reler o Health Connect.
    val activityToday by viewModel.activityToday.collectAsStateWithLifecycle()
    val showStats by viewModel.homeShowStats.collectAsState()
    val showDiary by viewModel.homeShowDiary.collectAsState()
    var quickAdd     by remember { mutableStateOf(false) }
    if (quickAdd) {
        com.gluview.app.ui.screen.diary.QuickAddFlow(
            onDone = { quickAdd = false },
            onOpenMealEditor = { quickAdd = false; onNavigateToDiary("meal") },
            onOpenCatalog = { quickAdd = false; onNavigateToDiaryCatalog() }
        )
    }
    val uiState      by viewModel.uiState.collectAsState()
    val homeReadings by viewModel.homeChartReadings.collectAsState()
    val homeHours    by viewModel.homeGraphHours.collectAsState()
    val isSensorSource by viewModel.isSensorSource.collectAsState()
    val sensorStatus   by viewModel.sensorStatus.collectAsState()
    val sensorAgingAlert by viewModel.sensorAgingAlert.collectAsState()
    val sensorWarning  by viewModel.sensorWarning.collectAsState()
    val sensorWarmup   by viewModel.sensorWarmup.collectAsState()
    val alertCount     by viewModel.activeAlertCount.collectAsState()
    val isRefreshing   by viewModel.isRefreshing.collectAsState()
    val hipoThreshold  by viewModel.hipoThreshold.collectAsState()
    val hiperThreshold by viewModel.hiperThreshold.collectAsState()
    val urgentLowThreshold by viewModel.urgentLowThreshold.collectAsState()
    val urgentHighThreshold by viewModel.urgentHighThreshold.collectAsState()
    val themeMode      by viewModel.themeMode.collectAsState()
    val graphThemeMode by viewModel.graphThemeMode.collectAsState()
    val systemDark     = isSystemInDarkTheme()
    val darkGraph = remember(graphThemeMode, themeMode, systemDark) {
        GraphTheme.resolveDark(graphThemeMode, themeMode.name, systemDark)
    }

    PatientHomeContent(
        state = PatientHomeState(
            uiState          = uiState,
            homeReadings     = homeReadings,
            homeHours        = homeHours,
            isSensorSource   = isSensorSource,
            sensorStatus     = sensorStatus,
            sensorAgingAlert = sensorAgingAlert,
            sensorWarning    = sensorWarning,
            sensorWarmup     = sensorWarmup,
            alertCount       = alertCount,
            isRefreshing     = isRefreshing,
            hipoThreshold    = hipoThreshold,
            hiperThreshold   = hiperThreshold,
            urgentLowThreshold = urgentLowThreshold,
            urgentHighThreshold = urgentHighThreshold,
            darkGraph        = darkGraph,
            diaryToday       = diaryToday,
            diaryMarkers     = diaryMarkers,
            activityToday    = activityToday,
            showStats        = showStats,
            showDiary        = showDiary
        ),
        onHideStats              = viewModel::hideHomeStats,
        onHideDiary              = viewModel::hideHomeDiary,
        onReconnectSensor        = viewModel::reconnectSensor,
        onRefresh                = viewModel::refresh,
        onPullRefresh            = viewModel::onPullRefresh,
        onHomeGraphHoursChange   = viewModel::onHomeGraphHoursChange,
        onNavigateToAlertHistory = onNavigateToAlertHistory,
        onOpenDiary              = onNavigateToDiary,
        onQuickAdd               = { quickAdd = true }
    )
}

/** Tudo que a Home do paciente desenha — montado pelo [PatientHomeScreen] a partir do VM, ou pelo
 *  harness visual a partir de dados fixos. */
internal data class PatientHomeState(
    val uiState:          HomeUiState,
    val homeReadings:     List<GraphReading>?,
    val homeHours:        Int,
    val isSensorSource:   Boolean,
    val sensorStatus:     SibionicsSensorStatus,
    val sensorAgingAlert: SensorAgingAlert,
    val sensorWarning:    SensorWarning,
    val sensorWarmup:     SensorWarmup,
    val alertCount:       Int,
    val isRefreshing:     Boolean,
    val hipoThreshold:    Float,
    val hiperThreshold:   Float,
    val urgentLowThreshold: Float = 55f,
    val urgentHighThreshold: Float = 250f,
    val darkGraph:        Boolean,
    // Plano 17 — null = não mostra a linha do diário (seguidor, harness antigo).
    val diaryToday:       com.gluview.app.data.diary.DaySummary? = null,
    val diaryMarkers:     List<com.gluview.app.ui.components.chart.ChartMarker> = emptyList(),
    // Passos e calorias do Health Connect; vazio = nada aparece.
    val activityToday:    com.gluview.app.data.health.TodayActivity = com.gluview.app.data.health.TodayActivity(),
    // Ajustes › Home: linhas acima do gráfico (ocultas = o gráfico ocupa o espaço).
    val showStats:        Boolean = true,
    val showDiary:        Boolean = true
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PatientHomeContent(
    state: PatientHomeState,
    onReconnectSensor:        () -> Unit,
    onRefresh:                () -> Unit,
    onPullRefresh:            () -> Unit,
    onHomeGraphHoursChange:   (Int) -> Unit,
    onNavigateToAlertHistory: () -> Unit,
    onOpenDiary:              (String?) -> Unit = {},
    onQuickAdd:               () -> Unit = {},
    onHideStats:              () -> Unit = {},
    onHideDiary:              () -> Unit = {}
) {
    val uiState          = state.uiState
    val homeReadings     = state.homeReadings
    val homeHours        = state.homeHours
    val isSensorSource   = state.isSensorSource
    val sensorStatus     = state.sensorStatus
    val sensorAgingAlert = state.sensorAgingAlert
    val sensorWarning    = state.sensorWarning
    val sensorWarmup     = state.sensorWarmup
    val alertCount       = state.alertCount
    val isRefreshing     = state.isRefreshing
    val hipoThreshold    = state.hipoThreshold
    val hiperThreshold   = state.hiperThreshold
    val darkGraph        = state.darkGraph
    val clock            = LocalClock.current
    var showAgingDialog by remember { mutableStateOf(false) }
    var showCheckDialog by remember { mutableStateOf(false) }
    // Toque longo numa linha → pergunta se oculta (volta em Ajustes › Home).
    var hideAsk by remember { mutableStateOf<HomeRow?>(null) }

    // Relógio que tica a cada 10 s — base da idade dinâmica ("agora"/"há Xs"/"há X min").
    // No nível da tela (robusto): independe de a leitura mudar.
    var nowMs by remember { mutableLongStateOf(clock()) }
    LaunchedEffect(Unit) {
        while (true) { nowMs = clock(); delay(10_000L) }
    }

    // Contador real-time: quantos minutos desde a última sincronização
    var minutesSinceSync by remember { mutableLongStateOf(-1L) }
    LaunchedEffect(uiState.lastSyncMs) {
        while (true) {
            minutesSinceSync = if (uiState.lastSyncMs > 0L)
                (clock() - uiState.lastSyncMs) / 60_000L
            else -1L
            delay(30_000L)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("GluView") },
                expandedHeight = 48.dp,
                actions = {
                    // Plano 17 — registro rápido no diário, à esquerda do Bluetooth/atualizar.
                    IconButton(onClick = onQuickAdd) {
                        Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.quick_add_title))
                    }
                    if (isSensorSource) {
                        val connected = sensorStatus.connected
                        IconButton(onClick = onReconnectSensor) {
                            Icon(
                                imageVector = if (connected) Icons.Default.BluetoothConnected else Icons.Default.BluetoothSearching,
                                contentDescription = if (connected) stringResource(R.string.home_sensor_connected_reconnect)
                                                     else stringResource(R.string.home_sensor_disconnected_reconnect),
                                tint = if (connected) SensorAccent else MaterialTheme.colorScheme.error
                            )
                        }
                    } else {
                        IconButton(onClick = onRefresh) {
                            Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.home_refresh))
                        }
                    }
                    IconButton(onClick = onNavigateToAlertHistory) {
                        BadgedBox(
                            badge = {
                                if (alertCount > 0) {
                                    Badge { Text(if (alertCount > 9) "9+" else "$alertCount") }
                                }
                            }
                        ) {
                            Icon(Icons.Default.Notifications, contentDescription = stringResource(R.string.home_alert_history))
                        }
                    }
                }
            )
        }
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh    = onPullRefresh,
            modifier     = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                // Home SEM rolagem: o conteúdo se ajusta à tela (gráfico ocupa o espaço restante).
                // Só rola se a tela for pequena demais para caber tudo.
                val tight = maxHeight < 620.dp
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(if (tight) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Sensor recém-trocado: durante o aquecimento a ausência de dados é
                    // esperada, então o banner informativo substitui o alerta vermelho de
                    // "sem atualização" (que seria alarme falso a cada troca de sensor).
                    // Um jeito só de dizer "sem dado" (antes eram faixa de aquecimento, faixa vermelha
                    // e texto solto): o aviso do kit, com o tom de cada caso.
                    if (sensorWarmup.active) {
                        // Aquecimento: a ausência de dados é esperada — informativo, não alarme.
                        GluCallout(stringResource(R.string.home_sensor_warmup_banner, sensorWarmup.minutesLeft), tone = Tone.Info)
                    } else if (minutesSinceSync >= 15 && uiState.reading != null) {
                        GluCallout(stringResource(R.string.home_no_update_warning, minutesSinceSync), tone = Tone.Danger, announce = true)
                    }
                    // O próprio sensor (SmartGuide) pede ponta de dedo: aviso com a ação ali mesmo.
                    if (state.isSensorSource && state.sensorStatus.calibrationRequired) {
                        var showCalibration by remember { mutableStateOf(false) }
                        GluCallout(
                            message = stringResource(R.string.home_sensor_calibration_banner),
                            title = stringResource(R.string.home_sensor_calibration_title),
                            tone = Tone.Warning,
                            actionLabel = stringResource(R.string.home_sensor_calibration_action),
                            onAction = { showCalibration = true },
                            announce = true
                        )
                        if (showCalibration) {
                            com.gluview.app.ui.screen.smartguide.SmartGuideCalibrationDialog(onDismiss = { showCalibration = false })
                        }
                    }

                    if (uiState.isLoading && uiState.reading == null) {
                        Box(
                            modifier = Modifier.fillMaxWidth().then(if (tight) Modifier.height(240.dp) else Modifier.weight(1f)),
                            contentAlignment = Alignment.Center
                        ) { CircularProgressIndicator() }
                    } else {
                        uiState.reading?.let { reading ->
                            GlucoseCard(
                                reading            = reading,
                                unit               = uiState.unit,
                                nowMs              = nowMs,
                                timeFormat         = uiState.timeFormat,
                                isSensorSource     = isSensorSource,
                                sensorStatus       = sensorStatus,
                                sensorWarning      = sensorWarning,
                                sensorWarmup       = sensorWarmup,
                                showAgingAlert     = sensorAgingAlert.visible,
                                onAgingAlertClick  = { showAgingDialog = true },
                                onCheckSensorClick = { showCheckDialog = true },
                                activity           = state.activityToday
                            )
                        } ?: if (uiState.error != null && !sensorWarmup.active) {
                            // L5: sem leitura E a busca falhou — diz que falhou (antes "aguardando dados").
                            GluCallout(stringResource(R.string.home_fetch_error), tone = Tone.Danger,
                                title = stringResource(R.string.common_load_error_title), announce = true)
                        } else GluCallout(
                            if (sensorWarmup.active) stringResource(R.string.home_sensor_warmup_waiting, sensorWarmup.minutesLeft)
                            else stringResource(R.string.home_waiting_sensor_data),
                            tone = Tone.Info
                        )

                        if (state.showStats) uiState.dayStats?.let { stats ->
                            with(uiState.unit) {
                                // Uma linha só, na mesma altura do diário (pedido do Felipe, 14/11/2026).
                                HomeStatsRow(
                                    min = stats.minSgv?.toDisplay() ?: "--",
                                    avg = stats.avgSgv?.toDisplay() ?: "--",
                                    max = stats.maxSgv?.toDisplay() ?: "--",
                                    onLongPress = { hideAsk = HomeRow.STATS }
                                )
                            }
                        }

                        if (state.showDiary) state.diaryToday?.let { d -> DiaryTodayRow(d, onOpenDiary) { hideAsk = HomeRow.DIARY } }

                        HomeChart(
                            readings      = homeReadings,
                            selectedHours = homeHours,
                            onHoursChange = onHomeGraphHoursChange,
                            appearance    = ChartAppearance(darkGraph, hipoThreshold, hiperThreshold, uiState.unit, uiState.timeFormat,
                                state.urgentLowThreshold, state.urgentHighThreshold),
                            modifier      = if (tight) Modifier.fillMaxWidth() else Modifier.fillMaxWidth().weight(1f),
                            fill          = !tight,
                            markers       = state.diaryMarkers
                        )
                    }
                }
            }
        }
    }

    if (showAgingDialog) {
        SensorAgingDialog(
            days      = sensorAgingAlert.days,
            agingDays = sensorAgingAlert.agingDays,
            onDismiss = { showAgingDialog = false }
        )
    }

    hideAsk?.let { row ->
        com.gluview.app.ui.components.kit.AppDialog(
            title = stringResource(if (row == HomeRow.STATS) R.string.home_hide_stats_title else R.string.home_hide_diary_title),
            body = stringResource(R.string.home_hide_body),
            onDismiss = { hideAsk = null },
            confirmLabel = stringResource(R.string.home_hide_confirm),
            onConfirm = {
                if (row == HomeRow.STATS) onHideStats() else onHideDiary()
                hideAsk = null
            },
            dismissLabel = stringResource(R.string.setup_cancel)
        )
    }

    if (showCheckDialog) {
        SensorCheckDialog(
            onReconnect = { showCheckDialog = false; onReconnectSensor() },
            onDismiss   = { showCheckDialog = false }
        )
    }
}

/**
 * Gráfico da Home (pedido do Felipe, 14/11/2026): sem os chips de horas — como no xDrip, arrastar
 * na horizontal volta no tempo (até [HOME_SCROLL_HOURS] h) e pinçar muda a janela (1 a 24 h). A
 * janela escolhida fica salva; "Agora" volta para o fim.
 */
@Composable
private fun HomeChart(
    readings: List<GraphReading>?,
    selectedHours: Int,
    onHoursChange: (Int) -> Unit,
    appearance: ChartAppearance,
    modifier: Modifier = Modifier,
    fill: Boolean = false,
    markers: List<com.gluview.app.ui.components.chart.ChartMarker> = emptyList()
) {
    val clock = LocalClock.current
    var hours by remember(selectedHours) { mutableStateOf(selectedHours.coerceIn(1, 24)) }
    // Quanto a janela está para trás de "agora" (0 = ao vivo).
    var offsetMs by remember { mutableLongStateOf(0L) }
    var zoomAcc by remember { mutableStateOf(1f) }
    val spanMs = hours * 3_600_000L
    val maxOffset = (HOME_SCROLL_HOURS * 3_600_000L - spanMs).coerceAtLeast(0L)
    val gestures = remember(hours, maxOffset) {
        com.gluview.app.ui.components.chart.ChartGestures(
            onPan = { frac -> offsetMs = (offsetMs + (frac * spanMs).toLong()).coerceIn(0L, maxOffset) },
            onZoom = { z ->
                zoomAcc *= z
                val idx = HOME_ZOOM_STEPS.indexOf(hours).let { if (it < 0) HOME_ZOOM_STEPS.indexOfFirst { h -> h >= hours } else it }
                if (zoomAcc > 1.25f && idx > 0) { hours = HOME_ZOOM_STEPS[idx - 1]; zoomAcc = 1f }
                else if (zoomAcc < 0.8f && idx < HOME_ZOOM_STEPS.lastIndex) { hours = HOME_ZOOM_STEPS[idx + 1]; zoomAcc = 1f }
            },
            onGestureEnd = {
                zoomAcc = 1f
                if (hours != selectedHours) onHoursChange(hours)
            }
        )
    }
    val window = if (offsetMs <= 0L) GraphWindow.Trailing(hours)
                 else (clock() - offsetMs).let { to -> GraphWindow.Fixed(to - spanMs, to) }

    GlucoseChartPanel(
        title = null,
        modifier = modifier,
        extraControls = {
            // Altura fixa: com ou sem o botão "Agora" a linha mede o mesmo e o gráfico não muda de
            // tamanho ao arrastar (pedido do Felipe, 15/11/2026).
            Row(Modifier.fillMaxWidth().height(HOME_CHART_HEADER_HEIGHT), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (window is GraphWindow.Fixed) chartRangeLabel(window.fromMs, window.toMs, appearance.timeFormat)
                    else stringResource(R.string.home_chart_last_hours, hours),
                    maxLines = 1,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f).semantics { heading() }
                )
                if (offsetMs > 0L) TextButton(onClick = { offsetMs = 0L }) { Text(stringResource(R.string.home_chart_now)) }
            }
        }
    ) {
        GlucoseChart(
            // Sem leitura: estado "vazio" que explica, não o spinner eterno de antes.
            state = when {
                readings == null -> LoadState.Loading
                readings.isEmpty() -> LoadState.Empty
                else -> LoadState.Ready(readings)
            },
            window = window,
            appearance = appearance,
            modifier = if (fill) Modifier.fillMaxWidth().weight(1f) else Modifier.fillMaxWidth().aspectRatio(900f / 400f),
            markers = markers,
            gestures = gestures,
            description = null
        )
    }
}

/** Linha acima do gráfico que a pessoa pode ocultar (Ajustes › Home). */
internal enum class HomeRow { STATS, DIARY }

/** Altura da linha de título do gráfico da Home: a do botão "Agora" (alvo de toque de 48 dp). */
internal val HOME_CHART_HEADER_HEIGHT = 48.dp

/**
 * Intervalo do gráfico arrastado: só as horas quando os dois lados caem no mesmo dia; com o dia da
 * semana quando atravessa a meia-noite ("sáb. 22:38 – dom. 22:38", antes "22:38 – 22:38").
 */
internal fun chartRangeLabel(
    fromMs: Long,
    toMs: Long,
    format: com.gluview.app.util.TimeFormat,
    locale: java.util.Locale = java.util.Locale.getDefault(),
    zone: java.time.ZoneId = java.time.ZoneId.systemDefault()
): String {
    val from = java.time.Instant.ofEpochMilli(fromMs).atZone(zone)
    val to = java.time.Instant.ofEpochMilli(toMs).atZone(zone)
    val hFrom = formatTime(fromMs, format, zone)
    val hTo = formatTime(toMs, format, zone)
    if (from.toLocalDate() == to.toLocalDate()) return "$hFrom – $hTo"
    val day = java.time.format.DateTimeFormatter.ofPattern("EEE", locale)
    return "${from.format(day)} $hFrom – ${to.format(day)} $hTo"
}

/** Passos do pinçar (horas na tela) e até onde dá para voltar no gráfico da Home. */
internal val HOME_ZOOM_STEPS = listOf(1, 2, 3, 4, 6, 8, 12, 24)
internal const val HOME_SCROLL_HOURS = 48

/** Dialog com as possíveis causas quando o sensor fica silencioso por muito tempo. */
@Composable
private fun SensorCheckDialog(onReconnect: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.home_sensor_check_dialog_title)) },
        text = {
            Text(
                stringResource(R.string.home_sensor_check_dialog_body),
                style = MaterialTheme.typography.bodyMedium
            )
        },
        confirmButton = {
            TextButton(onClick = onReconnect) {
                Text(stringResource(R.string.home_sensor_check_reconnect))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.home_understood)) }
        }
    )
}

/** Popup explicando o badge de sensor além da vida nominal. Pode ser reaberto a qualquer momento. */
@Composable
private fun SensorAgingDialog(days: Int, agingDays: Int, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.home_sensor_aging_title)) },
        text = {
            Text(
                stringResource(R.string.home_sensor_aging_body, days, agingDays),
                style = MaterialTheme.typography.bodyMedium
            )
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.home_understood)) } }
    )
}

/** "👣 8.432 passos · 🔥 320 kcal" — só o que existir. */
@Composable
private fun ActivityTodayLine(a: com.gluview.app.data.health.TodayActivity) {
    val nf = remember { java.text.NumberFormat.getIntegerInstance() }
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    val steps = a.steps?.let { pluralStringResource(R.plurals.home_activity_steps, it.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), nf.format(it)) }
    val kcal = a.activeKcal?.let { stringResource(R.string.home_activity_kcal, nf.format(Math.round(it))) }
    val a11y = listOfNotNull(stringResource(R.string.home_activity_today), steps, kcal).joinToString(", ")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.clearAndSetSemantics { contentDescription = a11y }
    ) {
        if (steps != null) {
            Icon(Icons.AutoMirrored.Filled.DirectionsWalk, null, tint = color, modifier = Modifier.size(14.dp))
            Text(steps, style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1)
        }
        if (steps != null && kcal != null) Spacer(Modifier.width(6.dp))
        if (kcal != null) {
            Icon(Icons.Default.LocalFireDepartment, null, tint = color, modifier = Modifier.size(14.dp))
            Text(kcal, style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1)
        }
    }
}

/** Card da leitura atual da Home: o [GlucoseHeroCard] com os slots do sensor. */
@Composable
private fun GlucoseCard(
    reading: com.gluview.app.domain.model.GlucoseReading,
    unit: GlucoseUnit,
    nowMs: Long,
    timeFormat: TimeFormat,
    isSensorSource: Boolean,
    sensorStatus: SibionicsSensorStatus,
    sensorWarning: SensorWarning,
    sensorWarmup: SensorWarmup,
    showAgingAlert: Boolean,
    onAgingAlertClick: () -> Unit,
    onCheckSensorClick: () -> Unit,
    activity: com.gluview.app.data.health.TodayActivity = com.gluview.app.data.health.TodayActivity()
) {
    // Horário real da medição, no formato 24h/AM-PM do usuário; vermelho se passar de 12 min.
    val ageSec = if (reading.epochMs > 0L) ((nowMs - reading.epochMs) / 1_000L).toInt().coerceAtLeast(0)
                 else reading.minutesAgo * 60
    val measuredAtMs = if (reading.epochMs > 0L) reading.epochMs else nowMs - reading.minutesAgo * 60_000L
    val connected = sensorStatus.connected
    val sensorDays = sensorStatus.ageDays(nowMs)

    GlucoseHeroCard(
        valueText = with(unit) { reading.sgvMgDl.toDisplay() },
        arrow = reading.arrow,
        unitLabel = unit.label,
        zone = reading.zone,
        trendDescription = trendLabel(reading.direction) ?: trendLabel(reading.arrow),
        timeText = epochMsToTimeOfDay(measuredAtMs, timeFormat),
        timeIsOld = ageSec >= 12 * 60,
        compact = true,
        // Pedido do Felipe (14/11/2026): passos e calorias acima do "Conectado"; sem dado, nada.
        activity = if (activity.isEmpty) null else { { ActivityTodayLine(activity) } },
        statusStart = if (!isSensorSource) null else {
            {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.size(7.dp).clip(CircleShape)
                        .background(if (connected) SensorAccent else MaterialTheme.colorScheme.outline))
                    Text(
                        text  = stringResource(if (connected) R.string.home_sensor_status_connected else R.string.home_sensor_status_disconnected),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (connected) SensorAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
        },
        statusEnd = if (!isSensorSource) null else {
            {
                when {
                    sensorWarmup.active -> Text(
                        text  = stringResource(R.string.home_sensor_warmup_progress, sensorWarmup.elapsedMin, SibionicsSensorStatus.WARMUP_MINUTES),
                        style = MaterialTheme.typography.labelSmall,
                        color = SensorAccent
                    )
                    sensorWarning == SensorWarning.EXPIRED -> Text(
                        text  = stringResource(R.string.home_sensor_expired_label),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    // Ação de verdade: botão de texto (alvo de 48dp), não um texto pequeno clicável.
                    sensorWarning == SensorWarning.CHECK_SENSOR -> TextButton(
                        onClick = onCheckSensorClick,
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) { Text(stringResource(R.string.home_sensor_check_label) + " ›", style = MaterialTheme.typography.labelMedium) }
                    else -> {
                        val daysText = sensorDays?.let { stringResource(R.string.home_sensor_days, it) } ?: stringResource(R.string.home_sensor_days_unknown)
                        if (showAgingAlert) {
                            // Sensor além da vida nominal: a linha inteira é o alvo (48dp) e tem rótulo.
                            val agingLabel = stringResource(R.string.home_sensor_aging_description)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable(onClickLabel = agingLabel, onClick = onAgingAlertClick)
                                    .minimumInteractiveComponentSize()
                                    .padding(horizontal = 4.dp)
                            ) {
                                Text(daysText, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Icon(Icons.Default.ErrorOutline, contentDescription = agingLabel,
                                    tint = glucoseZoneTextColor(GlucoseZone.HIGH), modifier = Modifier.size(18.dp))
                            }
                        } else {
                            Text(daysText, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        below = if (!(reading.isStale && !sensorWarmup.active)) null else {
            {
                // S32 — aviso pulsante de sensor sem sinal (nunca no aquecimento: o banner de
                // aquecimento já explica). O pulso fica só no fundo; o texto mantém o contraste.
                val pulse by rememberInfiniteTransition(label = "stale").animateFloat(
                    initialValue = 0.4f, targetValue = 1.0f,
                    animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
                    label = "stale_alpha"
                )
                Surface(color = MaterialTheme.colorScheme.outline.copy(alpha = pulse * 0.25f), shape = RoundedCornerShape(8.dp)) {
                    Text(
                        text     = stringResource(R.string.home_sensor_no_signal),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        style    = MaterialTheme.typography.labelMedium,
                        color    = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    )
}


/**
 * Diário de hoje na Home (plano 17, F1.6): carboidrato e insulina do dia numa linha só e os atalhos
 * de registro mais usados. Toque na linha abre o diário.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun DiaryTodayRow(d: com.gluview.app.data.diary.DaySummary, onOpen: (String?) -> Unit, onLongPress: () -> Unit = {}) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val text = remember(context) { com.gluview.app.util.DiaryText(context) }
    // Uma linha só e mais fina (14/11/2026): o gráfico fica com o espaço. Toque longo: ocultar.
    Surface(
        modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
            .combinedClickable(onClick = { onOpen(null) }, onLongClick = onLongPress),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.home_diary_compact, text.amount(d.carbsG), text.amount(d.rapidU + d.mixedU), text.amount(d.basalU)),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { onOpen("meal") }) {
                Icon(Icons.Outlined.Restaurant, stringResource(R.string.home_diary_add_meal))
            }
            IconButton(onClick = { onOpen("insulin") }) {
                Icon(Icons.Outlined.Vaccines, stringResource(R.string.home_diary_add_insulin))
            }
        }
    }
}


/** Mínima, média e máxima das últimas 24 h numa linha, do mesmo tamanho da linha do diário. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun HomeStatsRow(min: String, avg: String, max: String, onLongPress: () -> Unit = {}) {
    val lMin = stringResource(R.string.home_stat_min)
    val lAvg = stringResource(R.string.home_stat_avg)
    val lMax = stringResource(R.string.home_stat_max)
    val period = stringResource(R.string.home_stats_24h)
    val a11y = "${stringResource(R.string.home_last_24_hours)}: $lMin $min, $lAvg $avg, $lMax $max"
    Surface(
        modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
            .combinedClickable(onClick = {}, onLongClick = onLongPress)
            .semantics(mergeDescendants = true) { contentDescription = a11y },
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(period, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            listOf(lMin to min, lAvg to avg, lMax to max).forEach { (label, value) ->
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Text(label + " ", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                }
            }
        }
    }
}
