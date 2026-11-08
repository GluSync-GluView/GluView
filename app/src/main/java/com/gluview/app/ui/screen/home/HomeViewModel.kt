package com.gluview.app.ui.screen.home

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gluview.app.data.datastore.AppPreferences
import com.gluview.app.data.local.dao.AlertLogDao
import com.gluview.app.data.local.dao.SensorHistoryDao
import com.gluview.app.data.local.entity.DayStats
import com.gluview.app.data.repository.CaregiverRepository
import com.gluview.app.data.repository.GlucoseRepository
import com.gluview.app.data.sibionics.GlucoseDisplayMode
import com.gluview.app.data.sibionics.SibionicsSensorStatus
import com.gluview.app.data.sibionics.SibionicsStatusHolder
import com.gluview.app.data.sibionics.SensorWarning
import com.gluview.app.data.source.DataSourceType
import com.gluview.app.domain.model.GlucoseReading
import com.gluview.app.service.GluSyncService
import com.gluview.app.ui.theme.ThemeMode
import com.gluview.app.util.GlucoseUnit
import com.gluview.app.util.GraphReading
import com.gluview.app.util.GraphTheme
import com.gluview.app.util.TimeFormat
import com.gluview.app.util.applyDisplayMode
import com.gluview.app.util.toGraphReading
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

data class HomeUiState(
    val reading:    GlucoseReading? = null,
    val unit:       GlucoseUnit     = GlucoseUnit.MG_DL,
    val dayStats:   DayStats?       = null,
    val isLoading:  Boolean         = false,
    val error:      String?         = null,
    // Epoch ms do último recebimento bem-sucedido — usado para exibir "X min sem sinal"
    val lastSyncMs: Long            = 0L,
    val timeFormat: TimeFormat      = TimeFormat.H24
)

/**
 * Badge clicável "Sensor Xd" na Home — aparece a partir do fim da vida nominal e permanece
 * visível enquanto durar (não é dispensável; só a notificação repetida do sistema foi removida).
 */
data class SensorAgingAlert(
    val visible:   Boolean = false,
    val days:      Int     = 0,
    val agingDays: Int     = 14
)

/**
 * Aquecimento do sensor Sibionics — os primeiros [SibionicsSensorStatus.WARMUP_MINUTES] minutos
 * depois da inserção, em que o sensor ainda está estabilizando no tecido.
 *
 * É **só sinalização**: leitura válida que chegue durante o aquecimento continua sendo gravada,
 * plotada no gráfico e considerada nos alertas como qualquer outra (o GluView nunca corta
 * leitura). O que este estado faz é (a) explicar a ausência de dados na Home e (b) impedir que
 * o silêncio esperado da 1ª hora vire aviso de "sem sinal"/"verifique o sensor".
 */
data class SensorWarmup(
    val active:      Boolean = false,
    val elapsedMin:  Int     = 0,
    val minutesLeft: Int     = 0
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: GlucoseRepository,
    private val preferences: AppPreferences,
    private val statusHolder: SibionicsStatusHolder,
    private val caregiverRepository: CaregiverRepository,
    private val sensorHistoryDao: SensorHistoryDao,
    alertDao: AlertLogDao,
    @ApplicationContext private val context: Context,
    serviceMonitor: com.gluview.app.util.ServiceMonitor
) : ViewModel() {

    /** O banco já respondeu (com ou sem leitura) — L5: sem leitura nenhuma, sai do spinner. */
    private val dbChecked = MutableStateFlow(false)
    private val syncError: StateFlow<String?> = serviceMonitor.syncError

    companion object {
        /**
         * Nunca deixa o card do topo mostrar algo mais velho do que o próprio histórico
         * local (mesma fonte do gráfico) já tem — ver [latestDbReading]. Extraída como
         * função pura (sem Hilt/Context) para dar pra testar isolada — ver
         * HomeViewModelTest.
         */
        fun pickFreshestReading(current: GlucoseReading?, latestDb: GlucoseReading?): GlucoseReading? =
            when {
                current == null -> latestDb
                latestDb != null && latestDb.epochMs > current.epochMs -> latestDb
                else -> current
            }
    }

    /** Fonte de dados atual — a Home mostra "Reconectar" quando é um sensor BLE local. */
    val isSensorSource: StateFlow<Boolean> = preferences.dataSource
        .map { DataSourceType.fromId(it).isLocalSensor }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Modo Seguidor — conta sem papel de paciente: a Home mostra o seletor de paciente
     * acompanhado em vez dos próprios dados (ver FollowerHomeViewModel/HomeScreen). */
    val isFollowerOnly: StateFlow<Boolean> = preferences.isFollowerOnly
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Estado ao vivo do sensor BLE (conexão, dias, bateria, última leitura). */
    val sensorStatus: StateFlow<SibionicsSensorStatus> = statusHolder.status
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SibionicsSensorStatus())

    /** Badge "sensor antigo": visível sempre que o sensor estiver além da vida nominal (permanente, não dispensável — só a notificação repetida do sistema foi removida). */
    val sensorAgingAlert: StateFlow<SensorAgingAlert> = combine(
        statusHolder.status,
        preferences.sensorWarnEnabled,
        preferences.sensorAgingDays
    ) { status, warnEnabled, agingDays ->
        val days = status.ageDays() ?: 0
        SensorAgingAlert(
            visible   = warnEnabled && status.ageDays() != null && days >= agingDays,
            days      = days,
            agingDays = agingDays
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SensorAgingAlert())

    /**
     * Sensor Sibionics ou SmartGuide dentro da janela de aquecimento (1ª hora). A idade vem do índice do
     * último pacote quando já houve algum; enquanto o sensor novo está mudo, do instante do
     * pareamento (`sibionicsSensorPairedMs`). Tica a cada 30 s para a contagem regressiva andar.
     */
    val sensorWarmup: StateFlow<SensorWarmup> = combine(
        statusHolder.status,
        // startedAt do período ATIVO em sensor_history — gravado no instante real do scan
        // (SensorScanViewModel.saveSensor → startNew), não um timestamp reconstruído depois.
        sensorHistoryDao.observeActive().map { it?.startedAt ?: 0L },
        preferences.dataSource,
        flow { while (true) { emit(System.currentTimeMillis()); delay(30_000L) } }
    ) { status, pairedMs, dataSourceId, nowMs ->
        val hasWarmup = DataSourceType.fromId(dataSourceId).hasWarmupWindow
        // Sensor que já anunciou o fim da sessão não está "aquecendo", mesmo dentro da 1ª hora:
        // a Home mostra "Sensor expirado" (achado no SmartGuide virtual, plano 14_, cenário 12).
        if (!hasWarmup || status.isExpired() || !status.isWarmingUp(pairedMs, nowMs)) SensorWarmup()
        else SensorWarmup(
            active      = true,
            elapsedMin  = status.minutesSinceStart(pairedMs, nowMs) ?: 0,
            minutesLeft = status.warmupMinutesLeft(pairedMs, nowMs)
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SensorWarmup())

    /**
     * Aviso de saúde do sensor com clock interno de 1 min.
     * [SensorWarning.CHECK_SENSOR] dispara quando o sensor fica silencioso ≥ 60 min.
     *
     * Fallback para reinicializações: se o holder não tem [lastReadingMs] (app reiniciou
     * sem o sensor ter conectado nesta sessão), usa o [epochMs] da última leitura do banco,
     * garantindo que o aviso apareça mesmo após um restart do app.
     */
    private val sensorWarningBase: Flow<SensorWarning> = combine(
        statusHolder.status,
        preferences.sensorWarnEnabled,
        preferences.sensorAgingDays,
        repository.currentReading,
        flow { while (true) { emit(System.currentTimeMillis()); delay(60_000L) } }
    ) { status, warnEnabled, agingDays, lastDbReading, nowMs ->
        if (!warnEnabled) return@combine SensorWarning.NONE
        val effectiveStatus = if (status.lastReadingMs == 0L && lastDbReading != null && lastDbReading.epochMs > 0L) {
            status.copy(lastReadingMs = lastDbReading.epochMs)
        } else status
        effectiveStatus.evaluateWarning(
            noSignalMin    = 15,
            checkSensorMin = 60,
            agingDays      = agingDays,
            nowMs          = nowMs
        )
    }

    /**
     * Durante o aquecimento o sensor legitimamente não entrega leitura — sem isto, trocar de
     * sensor acendia "Verifique o sensor" na Home (o fallback usa a última leitura do banco,
     * que é do sensor anterior) já nos primeiros minutos do sensor novo.
     */
    val sensorWarning: StateFlow<SensorWarning> = combine(
        sensorWarningBase,
        sensorWarmup
    ) { warning, warmup ->
        if (warmup.active) SensorWarning.NONE else warning
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SensorWarning.NONE)

    /** Nº de alertas recentes ainda não dispensados — alimenta o badge do sino. */
    val activeAlertCount: StateFlow<Int> = alertDao.observeRecent()
        .map { list -> list.count { !it.dismissed } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** Spinner do pull-to-refresh — controlado explicitamente para nunca travar. */
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    /** Pede ao serviço a reconexão da fonte BLE atualmente selecionada. */
    fun reconnectSensor() {
        context.startForegroundService(
            Intent(context, GluSyncService::class.java)
                .setAction(GluSyncService.ACTION_RECONNECT_ACTIVE_SENSOR)
        )
    }

    /**
     * Pull-to-refresh: para sensor BLE o dado chega sozinho, então "puxar" força uma
     * reconexão; para a fonte servidor, busca a leitura. O spinner SEMPRE reseta (finally).
     */
    fun onPullRefresh() {
        viewModelScope.launch {
            _isRefreshing.value = true
            try {
                if (isSensorSource.value) {
                    reconnectSensor()
                    delay(1200)            // janela mínima visível antes de fechar o spinner
                } else {
                    repository.fetchAndStore()
                }
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    // Estatisticas (min/media/max) das ULTIMAS 24 HORAS — janela a partir da abertura da Home.
    private val statsWindowStartMs = System.currentTimeMillis() - 24L * 60L * 60L * 1000L

    private val _lastSyncMs = MutableStateFlow(0L)

    // Espelha a última linha do histórico local (Room) — mesma fonte do gráfico. No sensor
    // BLE, só a leitura de maior índice de CADA pacote vira "atual" (ver
    // SibionicsBleSource.persist/makeCurrent); se o algoritmo do sensor entregar essa leitura
    // um ciclo atrasada, o histórico (e o gráfico) já avança na frente do valor exibido no
    // card. Comparando os dois por epochMs, o card do topo nunca mostra algo mais velho do
    // que o próprio gráfico já tem.
    private val latestDbReading: StateFlow<GlucoseReading?> = repository.latestFromDb
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // currentReading, mas nunca mais velha do que o histórico local (mesma fonte do gráfico)
    // já tem — ver [latestDbReading] e [pickFreshestReading]. Pré-combinada aqui pra manter o
    // combine de uiState dentro do overload de 5 flows do kotlinx.coroutines.
    private val freshestReading: Flow<GlucoseReading?> =
        combine(repository.currentReading, latestDbReading) { reading, latestDb ->
            pickFreshestReading(reading, latestDb)
        }

    val uiState: StateFlow<HomeUiState> = combine(
        combine(freshestReading, dbChecked, syncError) { freshest, checked, err -> Triple(freshest, checked, err) },
        preferences.glucoseUnit,
        repository.observeDayStats(statsWindowStartMs),
        _lastSyncMs,
        preferences.timeFormat
    ) { (freshest, checked, err), unitStr, stats, lastSyncMs, timeFormatStr ->
        HomeUiState(
            reading    = freshest,
            unit       = GlucoseUnit.fromString(unitStr),
            dayStats   = if (stats.count > 0) stats else null,
            // L5: carregando só até o banco responder (antes, sem leitura nenhuma, era eterno).
            isLoading  = freshest == null && !checked,
            // L5: a falha da busca na nuvem aparece quando não há leitura para mostrar.
            error      = if (freshest == null) err else null,
            lastSyncMs = lastSyncMs,
            timeFormat = TimeFormat.fromString(timeFormatStr)
        )
    }.stateIn(
        scope        = viewModelScope,
        started      = SharingStarted.WhileSubscribed(5_000),
        initialValue = HomeUiState(isLoading = true)
    )

    init {
        // T09 — evita a "home que não carrega": no startup a leitura atual (em memória) é null
        // até chegar a 1ª leitura nova, deixando um spinner infinito. Semeamos com a última
        // leitura do banco para mostrar o último valor conhecido imediatamente.
        viewModelScope.launch {
            if (repository.currentReading.value == null) {
                // Consulta única: latestFromDb nunca emite com o banco vazio (firstOrNull esperava para sempre).
                repository.getLatestOnce()?.let { repository.updateReading(it) }
            }
            dbChecked.value = true
        }
        viewModelScope.launch {
            repository.currentReading
                .filterNotNull()
                .collect { _lastSyncMs.value = System.currentTimeMillis() }
        }
        // Sessões feitas antes do modo seguidor existir nunca gravaram `myRoles` no
        // AppPreferences (só login/registro gravam) — sem isso, uma conta seguidora-apenas
        // cai na Home de paciente e fica com spinner infinito esperando leitura que nunca
        // chega. Ressincroniza a cada abertura da Home pra autocorrigir o cache.
        viewModelScope.launch {
            runCatching {
                if (!preferences.localOnlyMode.first()) {
                    preferences.saveMyRoles(caregiverRepository.me().roles)
                }
            }
        }
    }

    // ── Home chart ────────────────────────────────────────────────────────────

    val homeGraphHours: StateFlow<Int> = preferences.homeGraphHours
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 3)

    // Limiares configurados em Ajustes > Alertas ("Baixo"/"Alto") — linhas de referência do
    // gráfico da Home devem refletir o que o usuário configurou, não um valor fixo.
    val hipoThreshold: StateFlow<Float> = preferences.lowThreshold
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 70f)
    val hiperThreshold: StateFlow<Float> = preferences.highThreshold
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 180f)
    /** Limites urgentes (faixa vermelha e ponto urgente do gráfico, L2). */
    val urgentLowThreshold: StateFlow<Float> = preferences.urgentLowThreshold
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 55f)
    val urgentHighThreshold: StateFlow<Float> = preferences.urgentHighThreshold
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 250f)

    // Tema do gráfico — só SettingsViewModel expunha themeMode até agora; precisa aqui também
    // pra resolver GraphTheme.resolveDark() quando graphThemeMode = FOLLOW_APP.
    val themeMode: StateFlow<ThemeMode> = preferences.themeMode
        .map { runCatching { ThemeMode.valueOf(it) }.getOrDefault(ThemeMode.SYSTEM) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThemeMode.SYSTEM)
    val graphThemeMode: StateFlow<String> = preferences.graphThemeMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GraphTheme.FOLLOW_APP)

    // Reactive: switches DB query whenever hours preference changes. null = o banco ainda não
    // respondeu (L5: antes começava vazio e o gráfico piscava "Sem leituras").
    val homeChartReadings: StateFlow<List<GraphReading>?> = homeGraphHours
        .flatMapLatest { hours ->
            val fromMs = System.currentTimeMillis() - hours * 3_600_000L
            combine(
                repository.observeRecentReadings(fromMs),
                preferences.sibionicsCalFactor,
                preferences.glucoseDisplayMode
            ) { entities, calFactor, modeId ->
                entities.map { it.toGraphReading(calFactor) }
                    .applyDisplayMode(GlucoseDisplayMode.fromId(modeId))
            }
        }
        .stateIn<List<GraphReading>?>(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun onHomeGraphHoursChange(hours: Int) {
        viewModelScope.launch { preferences.saveHomeGraphHours(hours) }
    }

    fun refresh() {
        viewModelScope.launch { repository.fetchAndStore() }
    }
}
