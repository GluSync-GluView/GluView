package com.gluview.app.service

import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.gluview.app.alert.AlertManager
import com.gluview.app.data.datastore.AppPreferences
import com.gluview.app.data.local.dao.AlertLogDao
import com.gluview.app.data.local.dao.EventLogDao
import com.gluview.app.data.local.entity.AlertLogEntity
import com.gluview.app.data.repository.CaregiverRepository
import com.gluview.app.data.sibionics.GlucoseDisplayMode
import com.gluview.app.data.sibionics.oracle.OracleLog
import com.gluview.app.util.AppLogger
import com.gluview.app.util.ArrowMapper
import com.gluview.app.util.GlucoseGraphRenderer
import com.gluview.app.util.GluSyncConstants
import com.gluview.app.watchface.miband.MiBandWatchFacePusher
import com.gluview.app.util.NetworkMonitor
import com.gluview.app.util.ServiceMonitor
import com.gluview.app.util.applyDisplayMode
import com.gluview.app.util.toGraphReading
import com.gluview.app.data.remote.GluSyncUploader
import com.gluview.app.data.repository.CloudAccessRepository
import com.gluview.app.data.remote.auth.TokenRefresher
import com.gluview.app.data.remote.dto.NovaMedicaoDto
import com.gluview.app.data.repository.GlucoseRepository
import com.gluview.app.data.repository.SensorHistoryRepository
import com.gluview.app.data.source.DataSourceType
import com.gluview.app.data.source.GlucoseSource
import com.gluview.app.data.source.SensorSourceRegistry
import com.gluview.app.domain.model.GlucoseReading
import com.gluview.app.domain.model.GlucoseThresholds
import com.gluview.app.domain.model.GlucoseZone
import com.gluview.app.util.directionToArrow
import com.gluview.app.util.liveArrow
import com.gluview.app.notification.NotificationHelper
import com.gluview.app.notification.NotificationHelper.Companion.NOTIF_ID_PERSISTENT
import com.gluview.app.util.GlucoseUnit
import com.gluview.app.util.TimeFormat
import com.gluview.app.util.withAppLanguage
import com.gluview.app.util.graph.systemIsDark
import com.gluview.app.notification.buildPersistentNotificationContent
import com.gluview.app.util.agedFor
import com.gluview.app.widget.GluViewChartWidget
import com.gluview.app.widget.encodeWidgetPoints
import com.gluview.app.widget.GluViewWidget
import com.gluview.app.widget.GluViewXDripWidget
import com.microsoft.signalr.HubConnection
import com.microsoft.signalr.HubConnectionBuilder
import dagger.hilt.android.AndroidEntryPoint
import io.reactivex.rxjava3.core.Single
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@AndroidEntryPoint
class GluSyncService : LifecycleService() {

    companion object {
        /** Botão do alerta "upload_error" no sino: força fechar a conexão de upload e reenviar pendências. */
        const val ACTION_FORCE_UPLOAD_RECONNECT = "com.gluview.app.action.FORCE_UPLOAD_RECONNECT"
        /** Ícone Bluetooth da Home: reinicia apenas a fonte BLE selecionada pelo usuário. */
        const val ACTION_RECONNECT_ACTIVE_SENSOR = "com.gluview.app.action.RECONNECT_ACTIVE_SENSOR"
        /** Margem de antecedência pra renovar o access token (validade 1h) antes de expirar. */
        private val TOKEN_REFRESH_MARGIN_MS = TimeUnit.MINUTES.toMillis(15)
    }

    @Inject lateinit var repository: GlucoseRepository
    @Inject lateinit var preferences: AppPreferences
    @Inject lateinit var notificationHelper: NotificationHelper
    @Inject lateinit var alertManager: AlertManager
    @Inject lateinit var serviceMonitor: ServiceMonitor
    @Inject lateinit var alertLogDao: AlertLogDao
    @Inject lateinit var eventLogDao: EventLogDao
    @Inject lateinit var appLogger: AppLogger
    // Logger isolado da instrumentação de validação da .so Sibionics (temporário). Só p/ retenção aqui.
    @Inject lateinit var oracleLog: OracleLog
    @Inject lateinit var sensorSourceRegistry: SensorSourceRegistry
    @Inject lateinit var sensorHistory: SensorHistoryRepository
    @Inject lateinit var gluSyncUploader: GluSyncUploader
    @Inject lateinit var cloudAccess: CloudAccessRepository
    @Inject lateinit var miBandPusher: MiBandWatchFacePusher
    @Inject lateinit var networkMonitor: NetworkMonitor
    @Inject lateinit var tokenRefresher: TokenRefresher
    @Inject lateinit var caregiverRepository: CaregiverRepository

    private var pollingJob: Job? = null
    private var miBandAutoSendJob: Job? = null
    private var mobileUploadCatchupJob: Job? = null
    private var wifiUploadCatchupJob: Job? = null
    private var caregiverWatchJob: Job? = null
    private var followerPatientJob: Job? = null
    private var tokenRefreshJob: Job? = null
    private var visualRefreshTickJob: Job? = null
    // Serializa a inicialização/troca manual da fonte para não sobrepor dois ciclos BLE.
    private var sourceSetupJob: Job? = null
    // Coletor do pipeline de uma fonte local; precisa ser único entre reconexões manuais.
    private var localReadingJob: Job? = null
    // Fonte de sensor local ativa (Sibionics ou Libre 2); null quando a fonte é GluSync (nuvem).
    @Volatile private var activeSource: GlucoseSource? = null
    // Tag "device" enviada ao GluSync — derivada da fonte ativa.
    @Volatile private var uploadDevice = "GluView-Sibionics"
    @Volatile private var hubConnection: HubConnection? = null
    private var lastCleanupMs = 0L
    private var consecutiveErrors = 0
    private var errorNotificationShown = false
    @Volatile private var signalRRetryMs = 30_000L
    // Erro de gravação (upload) persistente: marca a 1ª falha após um sucesso e, se passar de
    // 1h sem nenhum sucesso no meio, dispara o alerta "upload_error" (uma vez só, até resolver).
    @Volatile private var uploadFirstFailureMs = 0L
    @Volatile private var uploadErrorAlerted = false

    override fun onCreate() {
        super.onCreate()
        serviceMonitor.recordStarted()
        // Limites atuais para classificar as leituras que chegam pelo SignalR (callback síncrono).
        lifecycleScope.launch { preferences.glucoseThresholds.collect { thresholds = it } }
        // Isolamento de glicemia por conta (migration 12→13): reclama o histórico legado pra
        // conta atualmente logada. Fica aqui (não só nos 4 pontos de login em AuthRepository)
        // porque uma sessão já autenticada antes desta atualização nunca passa por um login
        // novo — sem isso, o histórico de quem já estava logado ficaria "sem dono" para sempre.
        // Idempotente, seguro de rodar toda vez que o serviço sobe.
        lifecycleScope.launch { repository.claimUnownedReadings(preferences.userEmail.first()) }
        notificationHelper.createChannels()
        val initialNotif = notificationHelper.buildPersistentNotification(
            getString(com.gluview.app.R.string.notif_service_text)
        )
        // Escolhe o tipo de FGS conforme a fonte de dados configurada. Para a fonte BLE
        // Sibionics usamos CONNECTED_DEVICE, que NÃO tem limite diário de tempo. O tipo
        // DATA_SYNC tem teto diário (6 h) no Android 15+ com targetSdk >= 35 e, ao esgotá-lo, o startForeground lança
        // ForegroundServiceStartNotAllowedException e derruba o serviço (crash em onCreate).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForegroundSafely(initialNotif, resolveInitialFgsType())
        } else {
            startForeground(NOTIF_ID_PERSISTENT, initialNotif)
        }
        ServiceWatchdogWorker.schedule(this)
        GapBackfillWorker.schedule(this)
    }

    /**
     * Resolve o tipo de Foreground Service inicial a partir da fonte de dados persistida.
     * - Fonte BLE Sibionics (+ BLUETOOTH_CONNECT concedido) → CONNECTED_DEVICE (sem limite diário).
     * - Caso contrário → DATA_SYNC (polling/SignalR na nuvem).
     * Leitura síncrona de uma única chave do DataStore; em falha, assume DATA_SYNC.
     */
    private fun resolveInitialFgsType(): Int {
        val isLocalSensor = runCatching {
            runBlocking { DataSourceType.fromId(preferences.dataSource.first()).isLocalSensor }
        }.getOrDefault(false)
        return if (isLocalSensor && hasBtConnect())
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        else
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
    }

    private fun hasBtConnect(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED

    /**
     * Inicia o FGS tolerando restrições do Android 14+ (limite diário do dataSync, início
     * em background bloqueado, etc.). Em vez de deixar o app crashar, registra o erro e tenta
     * CONNECTED_DEVICE como fallback; se ainda falhar, encerra graciosamente — o
     * ServiceWatchdogWorker reinicia o serviço mais tarde.
     */
    private fun startForegroundSafely(notif: android.app.Notification, type: Int) {
        try {
            startForeground(NOTIF_ID_PERSISTENT, notif, type)
        } catch (e: Exception) {
            appLogger.error("SERVICE", "Falha ao iniciar FGS", mapOf(
                "erro" to e.javaClass.simpleName,
                "tipo" to type
            ))
            val fallback = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            if (type != fallback && hasBtConnect()) {
                runCatching { startForeground(NOTIF_ID_PERSISTENT, notif, fallback) }
                    .onSuccess { return }
            }
            // Não foi possível subir como FGS agora — encerra; o watchdog tentará de novo.
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_FORCE_UPLOAD_RECONNECT -> {
                forceUploadReconnect()
                return START_STICKY
            }
            ACTION_RECONNECT_ACTIVE_SENSOR -> {
                sourceSetupJob?.cancel()
                sourceSetupJob = lifecycleScope.launch { reconnectConfiguredSensor() }
                return START_STICKY
            }
        }
        appLogger.usage("SERVICE", "Serviço iniciado")

        // Reenvio do histórico de sensores que só existe no aparelho. Fica aqui, e não apenas na
        // tela de configuração de sensor, porque quem tem histórico perdido não tem motivo nenhum
        // pra abrir aquela tela — o sensor dele já está configurado. Custa um GET quando não há
        // nada a enviar, e é idempotente no servidor (ver SensorHistoryRepository).
        lifecycleScope.launch {
            if (!preferences.localOnlyMode.first()) sensorHistory.reconcileWithServer()
        }

        sourceSetupJob?.cancel()
        sourceSetupJob = lifecycleScope.launch {
            migrateStaleBaseUrlIfNeeded()
            // Modo Seguidor: conta sem papel de paciente não tem sensor próprio nem leitura
            // própria a sincronizar — em vez do pipeline de paciente, assina ao vivo o
            // paciente ativo (notificação persistente + Mi Band passam a mostrar ele).
            if (preferences.isFollowerOnly.first()) {
                startFollowerPatientWatch()
                return@launch
            }
            val localOnly = preferences.localOnlyMode.first()
            val source = DataSourceType.fromId(preferences.dataSource.first())
            // Sensor de biblioteca nativa salvo num aparelho 32 bits (NativeSensorSupport): não há
            // como ligá-lo, então segue pelo GluSync em vez de ficar sem nenhuma fonte.
            if (source.isLocalSensor && !source.isUsableOnThisDevice) {
                appLogger.error("SERVICE", "Sensor indisponível neste aparelho (sem arm64); usando GluSync",
                    mapOf("fonte" to source.id))
            }
            if (source.isLocalSensor && source.isUsableOnThisDevice) {
                startLocalSensor(source)
            } else if (!localOnly) {
                withContext(Dispatchers.IO) { repository.backfillIfNeeded() }
                startPolling()
                startSignalR()
            }
        }
        // F8 — visor da Mi Band: a cada leitura nova (qualquer fonte), tenta reenviar o visor;
        // o pusher aplica o toggle das preferências e o intervalo mínimo de 5 min.
        miBandAutoSendJob?.cancel()
        miBandAutoSendJob = lifecycleScope.launch {
            repository.currentReading.filterNotNull().collect { miBandPusher.maybeAutoSend() }
        }
        // F6 — Acompanhamento: convite recebido/aceito vira notificação + badge do sino.
        startCaregiverInviteWatch()
        // Renova o access token ANTES de expirar — evita o negotiate 401 permanente do SignalR.
        startTokenProactiveRefresh()
        // Mantém notificação/widgets/visor da Mi Band vivos mesmo sem leitura nova (sensor
        // desconectado) — ver startVisualRefreshTick.
        startVisualRefreshTick()
        return START_STICKY
    }

    /**
     * Notificação/widgets/visor da Mi Band só eram re-renderizados dentro do collect de uma
     * NOVA leitura (`repository.currentReading.filterNotNull()`). Se o sensor parar de entregar
     * dados (ex.: sensor não reconectou após o Bluetooth do sistema ser religado), a imagem do
     * gráfico ficava congelada para sempre no horário da última medição — em vez de continuar
     * avançando o eixo X e deixar claro, visualmente, que a leitura parou.
     *
     * Este tick roda independente de haver leitura nova: a cada minuto, redesenha o gráfico com
     * `agora` atualizado (o eixo X avança e a curva simplesmente para de ganhar pontos após a
     * última leitura real — o "buraco" fica visível) e recalcula `isStale` pela idade real
     * (ver [withDynamicStaleness]), fazendo o valor virar "sem sinal" depois do limiar
     * configurado em vez de continuar mostrando um número desatualizado como se fosse atual.
     */
    private fun startVisualRefreshTick() {
        visualRefreshTickJob?.cancel()
        visualRefreshTickJob = lifecycleScope.launch {
            while (true) {
                delay(TimeUnit.MINUTES.toMillis(1))
                val reading = repository.currentReading.value ?: continue
                runCatching { updateNotificationForReading(reading) }
                    .onFailure { e -> appLogger.error("SERVICE", "Falha no tick de atualização visual",
                        mapOf("erro" to (e.message ?: e::class.simpleName.orEmpty()))) }
                runCatching { miBandPusher.maybeAutoSend() }
            }
        }
    }

    /**
     * Fonte de sensor local (BLE direto: Sibionics ou Libre 2 Plus): o sensor é lido direto; cada
     * leitura cai no mesmo pipeline de notificação/widgets/alertas via observação de currentReading.
     * Sem polling/SignalR nesse modo. A implementação concreta vem do [SensorSourceRegistry].
     */
    private fun startLocalSensor(source: DataSourceType) {
        appLogger.usage("SERVICE", "Fonte: ${source.label}")
        val sensor = sensorSourceRegistry.forType(source)
        if (sensor == null) {
            if (!source.isUsableOnThisDevice) {
                appLogger.error("SERVICE", "Sensor indisponível neste aparelho (sem arm64)",
                    mapOf("fonte" to source.id))
            } else {
                appLogger.error("SERVICE", "Sem fonte de sensor para ${source.label}")
            }
            return
        }
        if (activeSource === sensor) {
            appLogger.usage("SERVICE", "Fonte já está ativa", mapOf("fonte" to source.id))
            return
        }
        activeSource?.stop()
        localReadingJob?.cancel()
        activeSource = sensor
        uploadDevice = if (source == DataSourceType.LIBRE2_PLUS) "GluView-Libre2" else "GluView-Sibionics"
        // FGS da fonte BLE: usa CONNECTED_DEVICE (sem limite diário de tempo). Evitamos
        // DATA_SYNC aqui de propósito — seu teto diário no Android 14+ derrubava o serviço.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = if (hasBtConnect())
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            else
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            startForegroundSafely(
                notificationHelper.buildPersistentNotification(
                    getString(com.gluview.app.R.string.notif_service_text)),
                type
            )
        }
        sensor.start(source)
        // Reenvio em lote do que ficou pendente enquanto o app esteve offline / servidor dormindo.
        // Antes, confere se a conta tem a nuvem liberada (ADR-0030) — sem isso o lote nem sai.
        lifecycleScope.launch {
            runCatching { cloudAccess.refreshIfStale() }
            maybeFlushPendingUploads()
        }
        // Poupa dados móveis: ao vivo só por Wi-Fi (uploadReadingToCloud); em dados móveis o
        // envio vira lote periódico, e ao reconectar em Wi-Fi tudo pendente sai de uma vez.
        startMobileUploadCatchup()
        startWifiUploadCatchup()
        localReadingJob = lifecycleScope.launch {
            repository.currentReading.filterNotNull().collect { reading ->
                alertManager.evaluate(reading)
                updateNotificationForReading(reading)
                maybeCleanup()
                // Fonte BLE: o GluView é a ORIGEM dos dados → publica no GluSync via WebSocket.
                uploadReadingToCloud(reading)
            }
        }
    }

    /**
     * Reconexão manual do ícone Bluetooth. A seleção vem sempre da fonte salva no DataStore,
     * portanto Libre 2 Plus e os dois protocolos Sibionics passam pelo mesmo comando, mas cada
     * um usa seu próprio transporte e handshake via [SensorSourceRegistry].
     */
    private suspend fun reconnectConfiguredSensor() {
        if (preferences.isFollowerOnly.first()) {
            appLogger.usage("SERVICE", "Reconexão de sensor ignorada no modo seguidor")
            return
        }
        val source = DataSourceType.fromId(preferences.dataSource.first())
        if (!source.isLocalSensor) {
            appLogger.usage("SERVICE", "Reconexão de sensor ignorada para fonte não BLE",
                mapOf("fonte" to source.id))
            return
        }

        appLogger.usage("SERVICE", "Reconexão manual do sensor ativo", mapOf("fonte" to source.id))
        localReadingJob?.cancel()
        localReadingJob = null
        activeSource?.stop()
        activeSource = null
        startLocalSensor(source)
    }

    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)

    // ── Polling ───────────────────────────────────────────────────────────────

    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = lifecycleScope.launch {
            while (true) {
                poll()
                val intervalMinutes = preferences.pollingInterval.first()
                delay(TimeUnit.MINUTES.toMillis(intervalMinutes.toLong()))
            }
        }
    }

    private suspend fun poll() {
        val reading = repository.fetchAndStore() ?: run {
            consecutiveErrors++
            serviceMonitor.recordError("Falha ao buscar dados")
            appLogger.error("POLL", "Falha ao buscar leitura", mapOf("consecutiveErrors" to consecutiveErrors))
            if (consecutiveErrors >= 3 && !errorNotificationShown) {
                errorNotificationShown = true
                // Após 10 falhas a sessão pode ter expirado — muda o texto da notificação
                val extendedFailure = consecutiveErrors >= 10
                notificationHelper.showErrorNotification(extendedFailure)
            }
            return
        }
        consecutiveErrors = 0
        if (errorNotificationShown) {
            errorNotificationShown = false
            notificationHelper.cancelError()
        }
        serviceMonitor.recordSync()
        appLogger.usage("POLL", "Leitura ok", mapOf(
            "sgv"   to reading.sgvMgDl,
            "arrow" to reading.arrow,
            "zone"  to reading.zone.name
        ))
        alertManager.evaluate(reading)
        updateNotificationForReading(reading)
        maybeCleanup()
    }

    /**
     * Recalcula `isStale` a partir da idade real da leitura (agora − epochMs), em vez de confiar
     * cegamente no valor gravado no momento em que ela chegou. Fontes locais (Sibionics/Libre2)
     * sempre gravam `isStale=false` (é verdade NAQUELE instante) — sem isto, uma leitura antiga
     * continuaria sendo desenhada como "atual" para sempre enquanto o sensor não reconectasse.
     * Mesmo limiar (`sensorNoSignalMin`) já usado pelo aviso de "sem sinal" e pelo visor da Mi Band.
     */
    private suspend fun withDynamicStaleness(reading: GlucoseReading): GlucoseReading =
        reading.agedFor(preferences.sensorNoSignalMin.first())

    private suspend fun updateNotificationForReading(rawReading: GlucoseReading) {
        val reading = withDynamicStaleness(rawReading)
        val unit = GlucoseUnit.fromString(preferences.glucoseUnit.first())
        val timeFormat = TimeFormat.fromString(preferences.timeFormat.first())
        // Notificação e widgets seguem o tema do SISTEMA, onde aparecem (decisão 5.4 #1) — não o
        // tema do gráfico do app (antes saía caixa escura em cartão claro).
        val dark = systemIsDark(resources)
        val notifHours = preferences.notifGraphHours.first()
        val calFactor = preferences.sibionicsCalFactor.first()
        val fromMs = System.currentTimeMillis() - notifHours * 3_600_000L
        val recentReadings = repository.getRecentReadingsForNotification(fromMs)
        val displayMode = GlucoseDisplayMode.fromId(preferences.glucoseDisplayMode.first())
        val graphReadings = recentReadings.map { it.toGraphReading(calFactor) }.applyDisplayMode(displayMode)
        val limits = preferences.glucoseThresholds.first()
        val content = buildPersistentNotificationContent(
            withAppLanguage(preferences.appLanguage.first()), reading, graphReadings, unit, timeFormat,
            hipo = limits.low, hiper = limits.high, hours = notifHours,
            urgentLow = limits.urgentLow, urgentHigh = limits.urgentHigh
        )
        val notif = notificationHelper.buildPersistentNotification(
            content.text, content.sgvArrowText, content.detailText, content.graph, content.dots
        )
        val mgr = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        mgr.notify(NOTIF_ID_PERSISTENT, notif)
        updateWidget(reading, unit, dark)
    }

    private suspend fun updateWidget(reading: GlucoseReading, unit: GlucoseUnit, dark: Boolean) {
        val manager = GlanceAppWidgetManager(this@GluSyncService)
        // Horário exibido no widget deve refletir o instante da MEDIÇÃO, não o do push —
        // assim um sensor sem sinal mostra o horário real mesmo que o serviço re-publique.
        val readingMs = reading.epochMs.takeIf { it > 0 } ?: System.currentTimeMillis()
        val timeFormatStr = preferences.timeFormat.first()
        val lang = preferences.appLanguage.first()

        // Simple widget (existing)
        val simpleIds = manager.getGlanceIds(GluViewWidget::class.java)
        simpleIds.forEach { id ->
            updateAppWidgetState(this@GluSyncService, id) { prefs ->
                prefs[GluViewWidget.KEY_SGV]         = reading.sgvMgDl.toFloat()
                prefs[GluViewWidget.KEY_ARROW]       = reading.arrow
                prefs[GluViewWidget.KEY_ZONE]        = reading.zone.name
                prefs[GluViewWidget.KEY_STALE]       = reading.isStale
                prefs[GluViewWidget.KEY_UNIT]        = unit.name
                prefs[GluViewWidget.KEY_LAST_MS]     = readingMs.toString()
                prefs[GluViewWidget.KEY_TIME_FORMAT] = timeFormatStr
                prefs[GluViewWidget.KEY_LANG]        = lang
            }
            GluViewWidget().update(this@GluSyncService, id)
        }

        // Chart widget + xDrip widget compartilham os mesmos dados de leitura recente
        val widgetHours = preferences.widgetGraphHours.first()
        val calFactor = preferences.sibionicsCalFactor.first()
        val limits = preferences.glucoseThresholds.first()
        val hipo = limits.low
        val hiper = limits.high
        val fromMs = System.currentTimeMillis() - widgetHours * 3_600_000L
        val recentReadings = repository.getRecentReadingsForNotification(fromMs) // seguidor: o buffer do paciente (L9)
        val displayMode = GlucoseDisplayMode.fromId(preferences.glucoseDisplayMode.first())
        val graphReadings = recentReadings.map { it.toGraphReading(calFactor) }.applyDisplayMode(displayMode)

        val chartIds = manager.getGlanceIds(GluViewChartWidget::class.java)
        if (chartIds.isNotEmpty()) {
            // As leituras (não um bitmap pronto): o widget desenha no tamanho real dele.
            val points = encodeWidgetPoints(graphReadings)
            with(unit) {
                val displayDelta = reading.sgvMgDl.deltaToDisplay(reading.delta)
                chartIds.forEach { id ->
                    updateAppWidgetState(this@GluSyncService, id) { prefs ->
                        prefs[GluViewChartWidget.KEY_SGV]         = reading.sgvMgDl.toFloat()
                        prefs[GluViewChartWidget.KEY_ARROW]       = reading.arrow
                        prefs[GluViewChartWidget.KEY_ZONE]        = reading.zone.name
                        prefs[GluViewChartWidget.KEY_STALE]       = reading.isStale
                        prefs[GluViewChartWidget.KEY_UNIT]        = unit.name
                        prefs[GluViewChartWidget.KEY_DELTA]       = displayDelta
                        prefs[GluViewChartWidget.KEY_POINTS]      = points
                        prefs[GluViewChartWidget.KEY_HIPO]        = hipo
                        prefs[GluViewChartWidget.KEY_HIPER]       = hiper
                        prefs[GluViewChartWidget.KEY_URGENT_LOW]  = limits.urgentLow
                        prefs[GluViewChartWidget.KEY_URGENT_HIGH] = limits.urgentHigh
                        prefs[GluViewChartWidget.KEY_LANG]        = lang
                        prefs[GluViewChartWidget.KEY_HOURS]       = widgetHours.toFloat()
                        prefs[GluViewChartWidget.KEY_LAST_MS]     = readingMs.toString()
                        prefs[GluViewChartWidget.KEY_IS_DARK]     = dark
                        prefs[GluViewChartWidget.KEY_TIME_FORMAT] = timeFormatStr
                    }
                    GluViewChartWidget().update(this@GluSyncService, id)
                }
            }
        }

        // Widget estilo xDrip
        val xdripIds = manager.getGlanceIds(GluViewXDripWidget::class.java)
        if (xdripIds.isNotEmpty()) {
            val dotsB64 = if (graphReadings.isNotEmpty()) {
                GluViewXDripWidget.renderDotsToBase64(graphReadings, widgetHours, hipo, hiper, limits.urgentLow, limits.urgentHigh)
            } else ""
            with(unit) {
                val displayDelta = reading.sgvMgDl.deltaToDisplay(reading.delta)
                xdripIds.forEach { id ->
                    updateAppWidgetState(this@GluSyncService, id) { prefs ->
                        prefs[GluViewXDripWidget.KEY_SGV]         = reading.sgvMgDl.toFloat()
                        prefs[GluViewXDripWidget.KEY_ARROW]       = reading.arrow
                        prefs[GluViewXDripWidget.KEY_ZONE]        = reading.zone.name
                        prefs[GluViewXDripWidget.KEY_STALE]       = reading.isStale
                        prefs[GluViewXDripWidget.KEY_UNIT]        = unit.name
                        prefs[GluViewXDripWidget.KEY_DELTA]       = displayDelta
                        prefs[GluViewXDripWidget.KEY_LAST_MS]     = readingMs.toString()
                        prefs[GluViewXDripWidget.KEY_DOTS]        = dotsB64
                        prefs[GluViewXDripWidget.KEY_IS_DARK]     = dark
                        prefs[GluViewXDripWidget.KEY_TIME_FORMAT] = timeFormatStr
                        prefs[GluViewXDripWidget.KEY_LANG]        = lang
                    }
                    GluViewXDripWidget().update(this@GluSyncService, id)
                }
            }
        }
    }

    // A URL do GluSync é fixa e igual pra todo mundo (GluSyncConstants.BASE_URL), mas só é
    // regravada em DataStore no momento do login (AuthRepository.login/register). Sessão que já
    // estava logada quando o domínio mudou (ex.: Render renomeou o serviço) fica presa na URL
    // antiga pra sempre, já que não passa pela tela de login de novo — corrige aqui, uma vez por
    // início do serviço, antes de qualquer chamada de rede.
    private suspend fun migrateStaleBaseUrlIfNeeded() {
        val stored = preferences.baseUrl.first()
        if (stored.isNotBlank() && stored.trimEnd('/') != GluSyncConstants.BASE_URL) {
            preferences.saveBaseUrl(GluSyncConstants.BASE_URL)
        }
    }

    private suspend fun maybeCleanup() {
        val now = System.currentTimeMillis()
        if (now - lastCleanupMs < TimeUnit.HOURS.toMillis(24)) return
        lastCleanupMs = now

        // Período de arrependimento: uma redução de retenção só é efetivada (e só então apaga
        // dado) 7 dias depois de escolhida — dá tempo do usuário desistir sem perder nada.
        val pendingAt = preferences.pendingRetentionChangeAtMs.first()
        if (pendingAt > 0 && now - pendingAt >= TimeUnit.DAYS.toMillis(7)) {
            preferences.commitPendingRetention(preferences.pendingRetentionDays.first())
        }

        // Retenção parametrizável (AppPreferences.dataRetentionDays) — histórico completo fica
        // no servidor (GluSync) quando há conta; em modo 100% local o aparelho é a única cópia.
        val retentionDays = preferences.dataRetentionDays.first()
        if (retentionDays != -1) {
            repository.pruneOldReadings(retentionDays) // piso de 1 mês garantido lá dentro
            val cutoff = now - TimeUnit.DAYS.toMillis(retentionDays.coerceAtLeast(30).toLong())
            alertLogDao.deleteOlderThan(cutoff)
            eventLogDao.deleteOlderThan(cutoff)
        }
        // F1/T05 — retenção dos logs do app (texto + Room), corte fixo de 7 dias. Independente
        // da retenção de glicemia (não é dado de glicemia) — sempre roda.
        appLogger.prune(now - TimeUnit.DAYS.toMillis(AppLogger.RETENTION_DAYS.toLong()))
        // Retenção dos logs de validação do oráculo Sibionics — corte de 7 dias (temporário).
        oracleLog.prune(now - TimeUnit.DAYS.toMillis(OracleLog.RETENTION_DAYS.toLong()))
    }

    // ── Envio ao GluSync via WebSocket (fonte BLE) ──────────────────────────────

    @Volatile private var lastUploadedMs = 0L

    /**
     * Publica a leitura no GluSync (SignalR GravarLeitura) — só AO VIVO por Wi-Fi, pra poupar
     * dados móveis. Em dados móveis, essa mesma leitura aparece como "prévia" periódica
     * ([uploadLatestPreviewOverMobile]) e é garantida por completo ao reconectar em Wi-Fi
     * ([startWifiUploadCatchup]). Idempotente; pula stale e já enviadas.
     */
    private suspend fun uploadReadingToCloud(reading: GlucoseReading) {
        if (preferences.localOnlyMode.first()) return
        if (reading.isStale) return
        if (!networkMonitor.isWifi()) return
        val dateMs = reading.epochMs.takeIf { it > 0 } ?: System.currentTimeMillis()
        if (dateMs <= lastUploadedMs) return
        if (uploadAndTrack(dateMs, reading.sgvMgDl, ArrowMapper.toAssetName(reading.arrow)))
            markUploaded(dateMs)
    }

    /**
     * Encaminha ao [gluSyncUploader] e acompanha sucesso/falha para o alerta de erro de
     * gravação persistente (>1h). Ponto único usado pelos 3 caminhos de envio (ao vivo,
     * reenvio em lote e prévia por dados móveis).
     */
    private suspend fun uploadAndTrack(dateMs: Long, sgvMgDl: Double, arrow: String): Boolean {
        // ADR-0030 — conta sem a nuvem liberada: as leituras ficam só no aparelho. Não é falha
        // (não conta para o alerta de erro de gravação) e o cursor não avança: se a liberação
        // vier depois, o reenvio em lote manda o que estiver dentro da janela dele.
        if (!preferences.cloudUploadAllowed.first()) return false
        val ok = gluSyncUploader.uploadReading(dateMs, sgvMgDl, arrow, uploadDevice)
        recordUploadResult(ok)
        return ok
    }

    private fun recordUploadResult(ok: Boolean) {
        if (ok) {
            serviceMonitor.recordSync()
            uploadFirstFailureMs = 0L
            if (uploadErrorAlerted) {
                uploadErrorAlerted = false
                lifecycleScope.launch { alertLogDao.dismissByKind("upload_error") }
            }
            return
        }
        val now = System.currentTimeMillis()
        if (uploadFirstFailureMs == 0L) {
            uploadFirstFailureMs = now
            return
        }
        if (!uploadErrorAlerted && now - uploadFirstFailureMs >= TimeUnit.HOURS.toMillis(1)) {
            uploadErrorAlerted = true
            fireUploadErrorAlert()
        }
    }

    private fun fireUploadErrorAlert() {
        val title = "Erro ao gravar glicemia"
        val message = "Nenhuma leitura foi salva na nuvem há mais de 1 hora."
        appLogger.error("WS_UP", "Erro de gravação persistente (>1h sem sucesso)")
        lifecycleScope.launch {
            alertLogDao.insert(AlertLogEntity(kind = "upload_error", title = title, message = message))
        }
        notificationHelper.showUploadErrorNotification(title, message)
    }

    /** Botão do alerta "upload_error": fecha a conexão de upload atual e reenvia o pendente. */
    private fun forceUploadReconnect() {
        appLogger.usage("WS_UP", "Reconexão manual solicitada pelo usuário")
        gluSyncUploader.stop()
        lifecycleScope.launch { flushPendingUploads() }
    }

    private suspend fun markUploaded(dateMs: Long) {
        if (dateMs > lastUploadedMs) {
            lastUploadedMs = dateMs
            preferences.saveWsLastUploadedMs(dateMs)
        }
    }

    /**
     * Reenvio em lote: envia ao GluSync as leituras gravadas localmente que ainda não subiram
     * (app offline / servidor dormindo / segurando por causa de dados móveis). Cursor persistido
     * (wsLastUploadedMs); cap de 24h e 300 leituras por flush. Upsert por (usuário, date) torna
     * idempotente. Para na 1ª falha.
     */
    private suspend fun flushPendingUploads() {
        if (preferences.localOnlyMode.first()) return
        val now = System.currentTimeMillis()
        val cursor = preferences.wsLastUploadedMs.first()
        lastUploadedMs = maxOf(lastUploadedMs, cursor)
        val fromMs = maxOf(cursor + 1, now - TimeUnit.HOURS.toMillis(24))
        val pending = repository.getRecentReadings(fromMs)
            .filter { it.dateMs > cursor && it.dateMs <= now }
            .sortedBy { it.dateMs }
            .take(300)
        if (pending.isEmpty()) return
        appLogger.usage("WS_UP", "Reenvio em lote", mapOf("qtd" to pending.size))
        for (e in pending) {
            val ok = uploadAndTrack(e.dateMs, e.sgvMgDl, ArrowMapper.toAssetName(e.arrow))
            if (ok) markUploaded(e.dateMs) else break
        }
    }

    /** Só flusha em dados móveis se a preferência permitir; por Wi-Fi sempre flusha. */
    private suspend fun maybeFlushPendingUploads() {
        if (networkMonitor.isWifi() || preferences.uploadOverMobileData.first()) {
            flushPendingUploads()
        }
    }

    /**
     * Em dados móveis, manda só a leitura MAIS RECENTE a cada ciclo — visibilidade "ao vivo"
     * sem gastar dados reenviando tudo que ficou pendente. NÃO avança o cursor real
     * ([markUploaded]/`wsLastUploadedMs`), então o que ficou pra trás (ex.: leituras 1-4 antes
     * da 5 ser mandada como prévia) continua marcado como pendente e é reenviado por completo,
     * de uma vez, quando reconectar em Wi-Fi ([startWifiUploadCatchup]/[flushPendingUploads]).
     * Reenviar a mesma leitura depois é inofensivo — o servidor faz upsert por
     * (usuário, timestamp).
     */
    private suspend fun uploadLatestPreviewOverMobile() {
        if (preferences.localOnlyMode.first()) return
        if (!preferences.uploadOverMobileData.first()) return
        val reading = repository.currentReading.value ?: return
        if (reading.isStale) return
        val dateMs = reading.epochMs.takeIf { it > 0 } ?: System.currentTimeMillis()
        uploadAndTrack(dateMs, reading.sgvMgDl, ArrowMapper.toAssetName(reading.arrow))
    }

    /**
     * Enquanto em dados móveis, a cada X min manda a prévia da leitura mais recente
     * ([uploadLatestPreviewOverMobile]) — o intervalo é relido a cada ciclo, então mudar nas
     * configurações se aplica sem reiniciar o serviço.
     */
    private fun startMobileUploadCatchup() {
        mobileUploadCatchupJob?.cancel()
        mobileUploadCatchupJob = lifecycleScope.launch {
            while (true) {
                val intervalMin = preferences.uploadMobileIntervalMin.first()
                delay(TimeUnit.MINUTES.toMillis(intervalMin.toLong()))
                // Liberação da nuvem pode mudar a qualquer momento (concedida ou revogada no
                // servidor); só vai à rede quando a última consulta tem mais de 6 h.
                runCatching { cloudAccess.refreshIfStale() }
                if (!networkMonitor.isWifi()) uploadLatestPreviewOverMobile()
            }
        }
    }

    /**
     * Ao reconectar em Wi-Fi, reenvia IMEDIATAMENTE tudo que ficou pendente em dados móveis.
     * `drop(1)` ignora o snapshot inicial da rede (se o serviço já start com Wi-Fi ativo, isso
     * já é coberto pelo flush de startup em [startLocalSensor] — sem o drop, os dois corriam
     * juntos e mandavam o mesmo lote em dobro toda vez que o app abre com Wi-Fi ligado).
     */
    private fun startWifiUploadCatchup() {
        wifiUploadCatchupJob?.cancel()
        wifiUploadCatchupJob = lifecycleScope.launch {
            networkMonitor.isWifiFlow.drop(1).filter { it }.collect {
                appLogger.usage("WS_UP", "Wi-Fi conectado — reenviando pendências")
                flushPendingUploads()
            }
        }
    }

    // ── Modo Seguidor — leitura ao vivo do paciente acompanhado ─────────────────

    /**
     * Observa o paciente ativo (trocado na Home) e mantém a assinatura SignalR nele — a
     * notificação persistente e o Mi Band reagem sozinhos, pois ambos já escutam
     * `repository.currentReading`/`getRecentReadingsForNotification`, alimentados aqui.
     */
    private fun startFollowerPatientWatch() {
        followerPatientJob?.cancel()
        followerPatientJob = lifecycleScope.launch {
            preferences.activeFollowedPatientId.collect { patientId -> connectFollowerSignalR(patientId) }
        }
    }

    /** Limites do paciente acompanhado (L1): zona, alerta e notificação do seguidor. */
    @Volatile private var followerThresholds = GlucoseThresholds.DEFAULT

    /**
     * Aplica uma leitura do paciente acompanhado (SignalR ou consulta): mesma trilha de antes
     * (leitura atual, buffer, alerta, notificação), datada pela MEDIÇÃO (L8) e classificada
     * pelos limites do paciente (L1). Leitura igual ou mais velha que a atual é ignorada.
     */
    private fun applyFollowerReading(sgv: Double, direction: String, arrow: String, measuredAtMs: Long) {
        val previous = repository.currentReading.value
        if (previous != null && measuredAtMs <= previous.epochMs) return
        val reading = GlucoseReading(
            sgvMgDl    = sgv,
            direction  = direction,
            arrow      = arrow,
            timestamp  = "",
            zone       = followerThresholds.classify(sgv, arrow),
            // Delta = esta menos a anterior (antes repetia o delta da leitura anterior); na 1ª
            // leitura após conectar, a anterior vem do histórico já carregado.
            delta      = (previous?.sgvMgDl ?: repository.lastFollowerSgvBefore(measuredAtMs))
                ?.let { (sgv - it).toInt() } ?: 0,
            isStale    = false,
            minutesAgo = 0,
            epochMs    = measuredAtMs
        )
        repository.updateReading(reading)
        repository.recordFollowerReading(reading)
        // Seguidor não envia: "última sincronização" = último dado recebido da nuvem (L32).
        serviceMonitor.recordSync()
        alertManager.evaluate(reading)
        lifecycleScope.launch { updateNotificationForReading(reading) }
    }

    private var followerPollJob: Job? = null

    private fun connectFollowerSignalR(patientId: String) {
        hubConnection?.stop()
        hubConnection = null
        followerPollJob?.cancel()
        repository.clearFollowerReadings()
        repository.clearCurrentReadingCache()
        followerThresholds = GlucoseThresholds.DEFAULT
        if (patientId.isBlank()) return // sem paciente selecionado — nada pra assinar ainda

        lifecycleScope.launch(Dispatchers.IO) {
            val baseUrl = preferences.baseUrl.first().trimEnd('/')
            if (baseUrl.isBlank()) return@launch

            caregiverRepository.patientThresholds(patientId)?.let { followerThresholds = it }

            // Semeia o buffer com o histórico já existente — evita gráfico vazio no início.
            runCatching {
                val now = System.currentTimeMillis()
                val hours = preferences.notifGraphHours.first()
                caregiverRepository.patientRange(patientId, now - hours * 3_600_000L, now)
            }.onSuccess { repository.seedFollowerReadings(it); serviceMonitor.recordSync() }

            // L31: a leitura atual já na conexão (antes a notificação ficava em "Monitorando
            // glicemia…" até o 1º evento ao vivo) e a cada minuto, como rede de segurança se o
            // SignalR cair (ele não reconecta sozinho no modo seguidor).
            followerPollJob = lifecycleScope.launch(Dispatchers.IO) {
                while (true) {
                    runCatching { caregiverRepository.patientCurrent(patientId) }.onSuccess { e ->
                        applyFollowerReading(e.sgv, e.direction, directionToArrow(e.direction), e.date)
                    }
                    delay(60_000L)
                }
            }

            val connection = HubConnectionBuilder
                .create("$baseUrl/ws/live")
                .withServerTimeout(300_000)
                .withKeepAliveInterval(15_000)
                .withAccessTokenProvider(Single.fromCallable {
                    runBlocking { preferences.accessToken.first() }
                })
                .build()

            connection.on("NovaMedicao", { dto: NovaMedicaoDto ->
                applyFollowerReading(dto.sgv, dto.direction, liveArrow(dto.arrow, dto.direction),
                    dto.date.takeIf { it > 0 } ?: System.currentTimeMillis())
            }, NovaMedicaoDto::class.java)

            hubConnection = connection
            connection.onClosed { e ->
                serviceMonitor.recordSignalR(false)
                appLogger.error("SIGNALR", "Conexão (seguidor) encerrada", mapOf("erro" to (e?.message ?: "desconhecido")))
            }
            connection.start().subscribe(
                {
                    serviceMonitor.recordSignalR(true)
                    runCatching { connection.send("SubscribePatient", patientId) }
                    appLogger.usage("SIGNALR", "Seguidor conectado ao paciente ativo", mapOf("patientId" to patientId))
                },
                { err ->
                    serviceMonitor.recordSignalR(false)
                    appLogger.error("SIGNALR", "Falha ao conectar (seguidor)", mapOf("erro" to (err?.message ?: "desconhecido")))
                }
            )
        }
    }

    // ── SignalR ───────────────────────────────────────────────────────────────

    private fun startSignalR() {
        hubConnection?.stop()
        hubConnection = null

        lifecycleScope.launch(Dispatchers.IO) {
            val baseUrl = preferences.baseUrl.first().trimEnd('/')
            if (baseUrl.isBlank()) return@launch

            val connection = HubConnectionBuilder
                .create("$baseUrl/ws/live")
                // 300s: Render free tier pode ficar ~60-90s sem enviar pings;
                // deixar margem para não desconectar prematuramente.
                .withServerTimeout(300_000)
                .withKeepAliveInterval(15_000)
                .withAccessTokenProvider(Single.fromCallable {
                    runBlocking { preferences.accessToken.first() }
                })
                .build()

            connection.on("NovaMedicao", { dto: NovaMedicaoDto ->
                val reading = GlucoseReading(
                    sgvMgDl    = dto.sgv,
                    direction  = dto.direction,
                    arrow      = dto.arrow.orEmpty(),
                    timestamp  = dto.timestamp.orEmpty(),
                    zone       = zoneFromSgv(dto.sgv, dto.arrow.orEmpty()),
                    delta      = repository.currentReading.value?.delta ?: 0,
                    isStale    = false,
                    minutesAgo = 0,
                    epochMs    = System.currentTimeMillis()
                )
                repository.updateReading(reading)
                alertManager.evaluate(reading)
                lifecycleScope.launch { updateNotificationForReading(reading) }
            }, NovaMedicaoDto::class.java)

            hubConnection = connection
            connection.onClosed { e ->
                serviceMonitor.recordSignalR(false)
                appLogger.error("SIGNALR", "Conexão encerrada", mapOf("erro" to (e?.message ?: "desconhecido")))
                if (hubConnection == connection) scheduleSignalRReconnect(connection)
            }
            connection.start().subscribe(
                {
                    signalRRetryMs = 30_000L   // reset backoff após conexão bem-sucedida
                    serviceMonitor.recordSignalR(true)
                    appLogger.usage("SIGNALR", "Conectado", mapOf("url" to "$baseUrl/ws/live"))
                },
                { err ->
                    serviceMonitor.recordSignalR(false)
                    val msg = err?.message
                    appLogger.error("SIGNALR", "Falha ao conectar", mapOf("erro" to (msg ?: "desconhecido")))
                    // Access token (validade 1h) pode ter expirado sem nenhuma chamada REST no
                    // meio pra renová-lo sozinha (TokenAuthenticator só cobre o caminho OkHttp).
                    // Sem isso, reconectaria pra sempre com o mesmo token vencido.
                    if (tokenRefresher.isAuthFailure(msg)) {
                        lifecycleScope.launch(Dispatchers.IO) {
                            if (tokenRefresher.refresh()) {
                                appLogger.usage("SIGNALR", "Token renovado — reconectando")
                                signalRRetryMs = 30_000L
                                if (hubConnection == connection) startSignalR()
                            } else if (hubConnection == connection) {
                                scheduleSignalRReconnect(connection)
                            }
                        }
                    } else {
                        // onClosed não dispara quando start() falha; retry explícito necessário
                        scheduleSignalRReconnect(connection)
                    }
                }
            )
        }
    }

    // Backoff exponencial: 30s → 60s → 120s → 300s (máx).
    // Reduz o "storm" de reconexões durante falhas persistentes (ex: servidor frio no Render).
    private fun scheduleSignalRReconnect(fromConnection: HubConnection) {
        val delay = signalRRetryMs
        signalRRetryMs = (signalRRetryMs * 2).coerceAtMost(300_000L)
        lifecycleScope.launch(Dispatchers.IO) {
            delay(delay)
            if (hubConnection == fromConnection) startSignalR()
        }
    }

    /** Limites de Ajustes > Alertas, mantidos atualizados desde o onCreate (L2). */
    @Volatile private var thresholds = GlucoseThresholds.DEFAULT

    // Regra única de zona (GlucoseThresholds — a mesma do AlertEngine.ClassifyZone do GluSync),
    // com os limites do usuário; antes 55/70/180/250 fixos (L2).
    private fun zoneFromSgv(sgv: Double, arrow: String = ""): GlucoseZone = thresholds.classify(sgv, arrow)

    // ── Renovação proativa do access token ──────────────────────────────────────
    // O access token dura 1h; se expirar sem nenhuma chamada REST no meio tempo (comum no modo
    // BLE puro), o SignalR passa a falhar com negotiate 401 pra sempre (ver TokenRefresher.kt).
    // Loop PRÓPRIO (não acoplado ao callback de leitura do sensor) — se o BLE parar de entregar
    // leituras (ex.: sensor com falha de decodificação), essa checagem continua rodando do
    // mesmo jeito, então nunca fica "presa" esperando um evento que não vem.
    private fun startTokenProactiveRefresh() {
        tokenRefreshJob?.cancel()
        tokenRefreshJob = lifecycleScope.launch {
            while (true) {
                if (preferences.isConfigured.first() && !preferences.localOnlyMode.first()) {
                    runCatching { maybeRefreshTokenProactively() }
                        .onFailure { e ->
                            appLogger.error("AUTH", "Falha na checagem de renovação proativa", mapOf("erro" to (e.message ?: "?")))
                        }
                }
                delay(TimeUnit.MINUTES.toMillis(5))
            }
        }
    }

    private suspend fun maybeRefreshTokenProactively() {
        val expiresAt = preferences.accessTokenExpiresAtMs.first()
        if (expiresAt <= 0L) return // desconhecido (sessão de antes desta chave existir) — nada a antecipar
        val now = System.currentTimeMillis()
        if (expiresAt - now > TOKEN_REFRESH_MARGIN_MS) return
        if (tokenRefresher.refresh()) {
            appLogger.usage("AUTH", "Token renovado proativamente", mapOf("faltavamMs" to (expiresAt - now)))
        }
    }

    // ── F6 — Acompanhamento: observa convites/aceites e notifica localmente ────
    // Decisão de projeto: alerta/decisão fica no GluView (local), não no GluSync — por isso
    // isso é um poll periódico do próprio app, sem depender de push do servidor.

    private fun startCaregiverInviteWatch() {
        caregiverWatchJob?.cancel()
        caregiverWatchJob = lifecycleScope.launch {
            while (true) {
                if (preferences.isConfigured.first() && !preferences.localOnlyMode.first()) {
                    runCatching { checkCaregiverInvites() }
                        .onFailure { e ->
                            appLogger.error("CAREGIVER", "Falha ao checar convites", mapOf("erro" to (e.message ?: "?")))
                        }
                }
                delay(TimeUnit.MINUTES.toMillis(5))
            }
        }
    }

    private suspend fun checkCaregiverInvites() {
        val myId = caregiverRepository.me().id
        val links = caregiverRepository.links()
        val notifiedReceived = preferences.notifiedInvitesReceived.first()
        val notifiedAccepted = preferences.notifiedInvitesAccepted.first()

        for (link in links) {
            val iAmFollower = link.iAmFollower(myId)
            val otherName = link.otherPartyName(myId)

            // Convite recebido: alguém me convidou e eu ainda não respondi nem fui avisado.
            if (link.status == "pending" && link.initiatedBy != myId && link.id !in notifiedReceived) {
                val action = if (iAmFollower) "convidou você para acompanhar a glicemia dele(a)"
                             else "pediu para acompanhar sua glicemia"
                val message = "$otherName $action."
                alertLogDao.insert(
                    AlertLogEntity(kind = "caregiver_invite", title = "Novo convite de acompanhamento", message = message)
                )
                notificationHelper.showCaregiverNotification(
                    "Novo convite de acompanhamento", message, NotificationHelper.NOTIF_ID_CAREGIVER_INVITE
                )
                preferences.markInviteReceivedNotified(link.id)
                appLogger.usage("CAREGIVER", "Convite recebido notificado", mapOf("linkId" to link.id))
            }

            // Convite aceito: SÓ quando aceito (não em recusa/revogação) — e só quem convidou é avisado.
            if (link.status == "accepted" && link.initiatedBy == myId && link.id !in notifiedAccepted) {
                val message = "$otherName aceitou seu convite de acompanhamento."
                alertLogDao.insert(
                    AlertLogEntity(kind = "caregiver_accepted", title = "Convite aceito", message = message)
                )
                notificationHelper.showCaregiverNotification(
                    "Convite aceito", message, NotificationHelper.NOTIF_ID_CAREGIVER_ACCEPTED
                )
                preferences.markInviteAcceptedNotified(link.id)
                appLogger.usage("CAREGIVER", "Convite aceito notificado", mapOf("linkId" to link.id))
            }
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onDestroy() {
        appLogger.usage("SERVICE", "Serviço encerrado")
        sourceSetupJob?.cancel()
        localReadingJob?.cancel()
        pollingJob?.cancel()
        mobileUploadCatchupJob?.cancel()
        wifiUploadCatchupJob?.cancel()
        caregiverWatchJob?.cancel()
        followerPatientJob?.cancel()
        followerPollJob?.cancel()
        tokenRefreshJob?.cancel()
        visualRefreshTickJob?.cancel()
        hubConnection?.stop()
        hubConnection = null
        activeSource?.stop()
        activeSource = null
        gluSyncUploader.stop()
        serviceMonitor.recordStopped()
        super.onDestroy()
    }
}
