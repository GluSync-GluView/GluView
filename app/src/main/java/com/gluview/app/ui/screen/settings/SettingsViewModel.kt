package com.gluview.app.ui.screen.settings

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.FileProvider
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gluview.app.R
import com.gluview.app.data.datastore.AppPreferences
import com.gluview.app.data.remote.dto.AccessRequestFeatures
import com.gluview.app.data.repository.AccessRequestRepository
import com.gluview.app.data.repository.AppConfigRepository
import com.gluview.app.data.repository.CloudAccessRepository
import com.gluview.app.data.source.DataSourceType
import com.gluview.app.data.repository.AuthRepository
import com.gluview.app.data.repository.CaregiverRepository
import com.gluview.app.data.repository.DebugRepository
import com.gluview.app.data.repository.GlucoseRepository
import com.gluview.app.service.GapBackfillWorker
import com.gluview.app.service.GluSyncService
import com.gluview.app.service.ServiceWatchdogWorker
import com.gluview.app.ui.theme.ThemeMode
import com.gluview.app.util.JwtClaims
import com.gluview.app.util.GlucoseUnit
import com.gluview.app.util.GraphTheme
import com.gluview.app.util.TimeFormat
import com.gluview.app.util.withAppLanguage
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class SettingsUiState(
    val baseUrl: String          = "",
    val email: String            = "",
    val pollingInterval: Int     = 3,
    val glucoseUnit: GlucoseUnit = GlucoseUnit.MG_DL,
    val themeMode: ThemeMode     = ThemeMode.SYSTEM,
    val timeFormat: TimeFormat   = TimeFormat.H24,
    val dataRetentionDays: Int   = 100,
    val backupMsg: String?       = null
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferences: AppPreferences,
    private val authRepository: AuthRepository,
    private val debugRepository: DebugRepository,
    private val caregiverRepository: CaregiverRepository,
    private val glucoseRepository: GlucoseRepository,
    private val accessRequestRepository: AccessRequestRepository,
    private val appConfigRepository: AppConfigRepository,
    private val cloudAccess: CloudAccessRepository,
    private val accuChekBle: com.gluview.app.data.accuchek.AccuChekBleManager,
    private val medLevSource: com.gluview.app.data.medlevensohn.MedLevensohnSource,
    @ApplicationContext private val context: Context
) : ViewModel() {

    /** Serial do Accu-Chek SmartGuide configurado ("" = nenhum) — seção "Sensor atual". */
    val smartGuideSerial: StateFlow<String> = preferences.accuChekSerial
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    /** Serial do Smart 2.0 configurado ("" = nenhum) — seção "Sensor atual". */
    val medLevSerial: StateFlow<String> = preferences.medLevensohnSerial
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    /** Smart 2.0: pede ao sensor para desparear na próxima conexão (resultado vem por notificação). */
    fun unpairMedLev() = medLevSource.requestUnpair()

    /**
     * Desfaz o pareamento do SmartGuide neste celular antes de parear de novo pelo assistente.
     * [onResult] recebe false quando o Android não deixa desfazer (aí o usuário esquece o
     * aparelho `AC-…` nos ajustes de Bluetooth).
     */
    fun forgetSmartGuidePairing(onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = accuChekBle.forgetPairing(preferences.accuChekSerial.first())
            onResult(ok)
        }
    }

    /** Esquece o SmartGuide: desfaz o pareamento, apaga serial/PIN/cursor e reinicia o serviço. */
    fun forgetSmartGuide() {
        viewModelScope.launch {
            accuChekBle.forgetPairing(preferences.accuChekSerial.first())
            preferences.clearAccuChekSensor()
            onDataSourceChange(preferences.dataSource.first())
        }
    }

    private val _state = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _state.asStateFlow()

    // A candidatura de beta de sensor (ADR-0029) mora no Passo 1 do assistente
    // (SensorSetupViewModel) desde o plano 15 — a aba Sensor não escolhe mais o sensor.

    /**
     * "Parar de usar este sensor": volta a ler só do GluSync (o sensor deixa de ser lido por este
     * celular). GluSync não está na lista do assistente — é a fonte de quem não tem sensor aqui.
     */
    fun stopUsingSensor() = onDataSourceChange(DataSourceType.GLUSYNC.id)

    init {
        viewModelScope.launch {
            combine(
                preferences.baseUrl,
                preferences.userEmail,
                preferences.pollingInterval,
                preferences.glucoseUnit,
                preferences.themeMode,
                preferences.dataRetentionDays,
                preferences.timeFormat
            ) { arr ->
                SettingsUiState(
                    baseUrl            = arr[0] as String,
                    email              = arr[1] as String,
                    pollingInterval    = arr[2] as Int,
                    glucoseUnit        = GlucoseUnit.fromString(arr[3] as String),
                    themeMode          = ThemeMode.valueOf(arr[4] as String),
                    dataRetentionDays  = arr[5] as Int,
                    timeFormat         = TimeFormat.fromString(arr[6] as String)
                )
            }.collect { _state.value = it }
        }
    }

    val logsEnabled: StateFlow<Boolean> = preferences.logsEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    // ── T85 — Passkeys (beta), default off ────────────────────────────────────
    val passkeysEnabled: StateFlow<Boolean> = preferences.passkeysEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun onPasskeysEnabledChange(enabled: Boolean) {
        viewModelScope.launch { preferences.savePasskeysEnabled(enabled) }
    }

    // ── API Keys — integração, default off ────────────────────────────────────
    val apiKeysEnabled: StateFlow<Boolean> = preferences.apiKeysEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun onApiKeysEnabledChange(enabled: Boolean) {
        viewModelScope.launch { preferences.saveApiKeysEnabled(enabled) }
    }

    val widgetGraphHours: StateFlow<Int> = preferences.widgetGraphHours
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 3)
    val notifGraphHours: StateFlow<Int> = preferences.notifGraphHours
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 3)

    // ── Fase 3 — Fonte de dados + saúde do sensor ─────────────────────────────
    val dataSource: StateFlow<String> = preferences.dataSource
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "GLUSYNC")
    val sensorWarnEnabled: StateFlow<Boolean> = preferences.sensorWarnEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val sensorAgingDays: StateFlow<Int> = preferences.sensorAgingDays
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 14)
    val sensorNoSignalMin: StateFlow<Int> = preferences.sensorNoSignalMin
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 20)
    val glucoseDisplayMode: StateFlow<String> = preferences.glucoseDisplayMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "ALL")

    // ── Envio ao GluSync — política de rede (poupar dados móveis) ─────────────
    val uploadOverMobileData: StateFlow<Boolean> = preferences.uploadOverMobileData
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val uploadMobileIntervalMin: StateFlow<Int> = preferences.uploadMobileIntervalMin
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 15)

    fun onUploadOverMobileDataChange(enabled: Boolean) {
        viewModelScope.launch {
            preferences.saveUploadNetworkPolicy(enabled, uploadMobileIntervalMin.value)
        }
    }

    fun onUploadMobileIntervalMinChange(min: Int) {
        viewModelScope.launch {
            preferences.saveUploadNetworkPolicy(uploadOverMobileData.value, min)
        }
    }

    fun onLogsEnabledChange(enabled: Boolean) {
        viewModelScope.launch { preferences.saveLogsEnabled(enabled) }
    }

    // Variante do sensor (modelo + região, SensorVariantCatalog) — só exibida aqui; quem a escolhe é
    // o assistente de sensor (plano 15).
    val sensorVariantId: StateFlow<String> = preferences.sensorVariantId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    fun onDataSourceChange(id: String) {
        viewModelScope.launch {
            preferences.saveDataSource(id)
            // Reinicia o serviço para aplicar a nova fonte imediatamente.
            // stopService() dispara onDestroy() que para BLE/polling/SignalR.
            ServiceWatchdogWorker.cancel(context)
            context.stopService(Intent(context, GluSyncService::class.java))
            delay(800) // aguarda onDestroy() completar antes de reiniciar
            context.startForegroundService(Intent(context, GluSyncService::class.java))
            ServiceWatchdogWorker.schedule(context)
        }
    }

    fun onSensorWarnEnabledChange(enabled: Boolean) {
        viewModelScope.launch {
            preferences.saveSensorWarn(enabled, sensorAgingDays.value, sensorNoSignalMin.value)
        }
    }

    fun onSensorAgingDaysChange(days: Int) {
        viewModelScope.launch {
            preferences.saveSensorWarn(sensorWarnEnabled.value, days, sensorNoSignalMin.value)
        }
    }

    fun onGlucoseDisplayModeChange(id: String) {
        viewModelScope.launch { preferences.saveGlucoseDisplayMode(id) }
    }

    fun onSensorNoSignalMinChange(min: Int) {
        viewModelScope.launch {
            preferences.saveSensorWarn(sensorWarnEnabled.value, sensorAgingDays.value, min)
        }
    }

    fun onWidgetGraphHoursChange(h: Int) { viewModelScope.launch { preferences.saveWidgetGraphHours(h) } }
    fun onNotifGraphHoursChange(h: Int)  { viewModelScope.launch { preferences.saveNotifGraphHours(h) } }

    // ── Android Auto — gráfico ─────────────────────────────────────────────────
    val autoShowGraph: StateFlow<Boolean> = preferences.autoShowGraph
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val autoGraphHours: StateFlow<Int> = preferences.autoGraphHours
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 3)
    val autoGraphTheme: StateFlow<String> = preferences.autoGraphTheme
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "AUTO")

    fun onAutoShowGraphChange(enabled: Boolean) { viewModelScope.launch { preferences.saveAutoShowGraph(enabled) } }
    fun onAutoGraphHoursChange(hours: Int)      { viewModelScope.launch { preferences.saveAutoGraphHours(hours) } }
    fun onAutoGraphThemeChange(theme: String)   { viewModelScope.launch { preferences.saveAutoGraphTheme(theme) } }

    // Salva na hora (mesmo padrão de AlertSettingsScreen) — sem botão "Salvar" pra lembrar de
    // apertar; o flow do DataStore realimenta _state sozinho a cada gravação.
    fun onPollingIntervalChange(v: Int) { viewModelScope.launch { preferences.savePollingInterval(v) } }
    fun onGlucoseUnitChange(v: GlucoseUnit) { viewModelScope.launch { preferences.saveGlucoseUnit(v.name) } }
    fun onThemeModeChange(v: ThemeMode) { viewModelScope.launch { preferences.saveThemeMode(v.name) } }
    fun onTimeFormatChange(v: TimeFormat) { viewModelScope.launch { preferences.saveTimeFormat(v.name) } }

    val graphThemeMode: StateFlow<String> = preferences.graphThemeMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GraphTheme.FOLLOW_APP)
    fun onGraphThemeModeChange(v: String) { viewModelScope.launch { preferences.saveGraphThemeMode(v) } }
    // Reduzir a retenção arrisca apagar dado existente — não aplica na hora, fica pendente por
    // 7 dias (ver GluSyncService.maybeCleanup) pra dar tempo do usuário desistir. Aumentar (ou
    // escolher "sem limite") nunca apaga nada, então aplica direto.
    fun onDataRetentionChange(days: Int) {
        viewModelScope.launch {
            val current = preferences.dataRetentionDays.first()
            fun effective(d: Int) = if (d == -1) Int.MAX_VALUE else d
            if (effective(days) < effective(current)) {
                preferences.scheduleRetentionReduction(days)
            } else {
                preferences.cancelPendingRetention()
                preferences.saveDataRetentionDays(days)
                glucoseRepository.pruneOldReadings(days)
            }
        }
    }

    fun onCancelPendingRetention() { viewModelScope.launch { preferences.cancelPendingRetention() } }

    fun logout(onDone: () -> Unit) {
        viewModelScope.launch {
            // Para o serviço + cancela watchdog; sem isso o watchdog reiniciaria o serviço
            ServiceWatchdogWorker.cancel(context)
            GapBackfillWorker.cancel(context)
            context.stopService(Intent(context, GluSyncService::class.java))
            authRepository.logout()
            onDone()
        }
    }

    // ── Backup ────────────────────────────────────────────────────────────────

    /** Texto no idioma do app (no Android < 13 o contexto da aplicação fica no do sistema). */
    private suspend fun text(@StringRes id: Int): String =
        context.withAppLanguage(preferences.appLanguage.first()).getString(id)

    fun exportBackup() {
        viewModelScope.launch {
            runCatching {
                val json = preferences.toJsonString()
                val dir  = File(context.cacheDir, "exports").also { it.mkdirs() }
                val file = File(dir, "gluview_backup.json").also { it.writeText(json) }
                val uri  = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/json"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    // A tela de compartilhar (Android 10+, roda como sistema) só lê o arquivo para a prévia
                    // se a permissão vier pelo ClipData; só com EXTRA_STREAM dava Permission Denial no log.
                    clipData = ClipData.newRawUri(null, uri)
                    putExtra(Intent.EXTRA_SUBJECT, text(R.string.settings_backup_subject))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(Intent.createChooser(intent, text(R.string.settings_backup_chooser)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            }.onFailure {
                _state.value = _state.value.copy(backupMsg = text(R.string.settings_backup_export_failed))
            }
        }
    }

    fun importBackup(uri: Uri) {
        viewModelScope.launch {
            runCatching {
                val json = context.contentResolver.openInputStream(uri)
                    ?.bufferedReader()?.readText() ?: error("empty file")
                preferences.fromJsonString(json)
                _state.value = _state.value.copy(backupMsg = text(R.string.settings_backup_restored))
            }.onFailure {
                _state.value = _state.value.copy(backupMsg = text(R.string.settings_backup_import_failed))
            }
        }
    }

    fun clearBackupMsg() {
        _state.value = _state.value.copy(backupMsg = null)
    }

    // ── Modo Debug/Admin — gerador de dados sintéticos ─────────────────────────
    // isAdmin lê a claim "admin" do access token já salvo (a mesma que o servidor exige nas rotas
    // de admin) — sem chamada de rede extra e sem e-mail fixo no código público.
    val isAdmin: StateFlow<Boolean> = preferences.accessToken
        .map { JwtClaims.isAdmin(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    // ── Modo Seguidor — esconde seções específicas de paciente/sensor ──────────
    val isFollowerOnly: StateFlow<Boolean> = preferences.isFollowerOnly
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    // ── Modo 100% local — esconde seções dependentes de conta/GluSync ──────────
    val isLocalOnly: StateFlow<Boolean> = preferences.localOnlyMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    // ── S29 — Retenção pendente (período de arrependimento de 7 dias) ──────────
    val pendingRetentionDays: StateFlow<Int> = preferences.pendingRetentionDays
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val pendingRetentionChangeAtMs: StateFlow<Long> = preferences.pendingRetentionChangeAtMs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    // ── Nome de exibição — cadastro já coletava, faltava editar depois. Sem nome, o
    // seguidor via o e-mail do paciente no seletor em vez de um nome (CaregiverLinkViewDto
    // .otherPartyName já prefere nome sobre e-mail; só faltava ter como preencher).
    private val _displayName = MutableStateFlow("")
    val displayName: StateFlow<String> = _displayName.asStateFlow()

    private val _displayNameSaving = MutableStateFlow(false)
    val displayNameSaving: StateFlow<Boolean> = _displayNameSaving.asStateFlow()

    private val _displayNameSaved = MutableStateFlow(false)
    val displayNameSaved: StateFlow<Boolean> = _displayNameSaved.asStateFlow()

    private val _displayNameMessage = MutableStateFlow<String?>(null)
    val displayNameMessage: StateFlow<String?> = _displayNameMessage.asStateFlow()

    /** L6: o nome não carregou — salvar mandaria o campo vazio e apagaria o nome no servidor. */
    private val _displayNameLoadFailed = MutableStateFlow(false)
    val displayNameLoadFailed: StateFlow<Boolean> = _displayNameLoadFailed.asStateFlow()

    init { loadDisplayName() }

    fun loadDisplayName() {
        viewModelScope.launch {
            if (!preferences.localOnlyMode.first()) {
                runCatching { caregiverRepository.me() }
                    .onSuccess { _displayName.value = it.displayName ?: ""; _displayNameLoadFailed.value = false }
                    .onFailure { _displayNameLoadFailed.value = true }
            }
        }
    }

    fun onDisplayNameChange(name: String) { _displayName.value = name; _displayNameSaved.value = false }

    fun saveDisplayName() {
        if (_displayNameLoadFailed.value) return
        viewModelScope.launch {
            _displayNameSaving.value = true
            runCatching { caregiverRepository.updateDisplayName(_displayName.value) }
                .onSuccess { ok ->
                    _displayNameMessage.value = if (ok)
                        context.getString(com.gluview.app.R.string.settings_display_name_saved)
                    else
                        context.getString(com.gluview.app.R.string.settings_display_name_error)
                    _displayNameSaved.value = ok
                }
                .onFailure { _displayNameMessage.value = context.getString(com.gluview.app.R.string.settings_display_name_error) }
            _displayNameSaving.value = false
        }
    }

    fun clearDisplayNameMessage() { _displayNameMessage.value = null }

    // ── Conta — troca de senha (mesmo par forgot/reset que a tela de login já usa; aqui o
    // e-mail já é conhecido, então pula direto pro "enviar código") ───────────────────────────
    private val _passwordCodeSent = MutableStateFlow(false)
    val passwordCodeSent: StateFlow<Boolean> = _passwordCodeSent.asStateFlow()

    private val _passwordCode = MutableStateFlow("")
    val passwordCode: StateFlow<String> = _passwordCode.asStateFlow()

    private val _newAccountPassword = MutableStateFlow("")
    val newAccountPassword: StateFlow<String> = _newAccountPassword.asStateFlow()

    private val _passwordChangeLoading = MutableStateFlow(false)
    val passwordChangeLoading: StateFlow<Boolean> = _passwordChangeLoading.asStateFlow()

    private val _accountMessage = MutableStateFlow<String?>(null)
    val accountMessage: StateFlow<String?> = _accountMessage.asStateFlow()

    // ── ADR-0030 — nuvem e acompanhamento (liberação por pedido) ───────────────
    val cloudSyncState: StateFlow<String> = preferences.cloudSyncState
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    private val _cloudSyncRequesting = MutableStateFlow(false)
    val cloudSyncRequesting: StateFlow<Boolean> = _cloudSyncRequesting.asStateFlow()

    /** Ao abrir a tela: reconsulta se a última consulta tem mais de 1 min (concessão recente aparece). */
    fun refreshCloudSync() {
        viewModelScope.launch { runCatching { cloudAccess.refreshIfStale(maxAgeMs = 60_000L) } }
    }

    fun requestCloudSync() {
        viewModelScope.launch {
            _cloudSyncRequesting.value = true
            val result = cloudAccess.request()
            _accountMessage.value = context.getString(
                if (result.isSuccess) com.gluview.app.R.string.settings_cloud_requested
                else com.gluview.app.R.string.settings_cloud_request_error)
            if (result.isFailure) runCatching { cloudAccess.refresh() } // ex.: pedido já existia
            _cloudSyncRequesting.value = false
        }
    }

    fun requestPasswordChangeCode() {
        viewModelScope.launch {
            _passwordChangeLoading.value = true
            val result = authRepository.forgotPassword(_state.value.email)
            _accountMessage.value = if (result.isSuccess)
                context.getString(com.gluview.app.R.string.settings_password_code_sent)
            else
                result.exceptionOrNull()?.message ?: context.getString(com.gluview.app.R.string.settings_password_change_error)
            if (result.isSuccess) _passwordCodeSent.value = true
            _passwordChangeLoading.value = false
        }
    }

    fun onPasswordCodeChange(v: String) { if (v.length <= 6) _passwordCode.value = v }
    fun onNewAccountPasswordChange(v: String) { _newAccountPassword.value = v }

    fun confirmPasswordChange() {
        viewModelScope.launch {
            _passwordChangeLoading.value = true
            val result = authRepository.resetPassword(_state.value.email, _passwordCode.value, _newAccountPassword.value)
            _accountMessage.value = if (result.isSuccess)
                context.getString(com.gluview.app.R.string.settings_password_changed)
            else
                result.exceptionOrNull()?.message ?: context.getString(com.gluview.app.R.string.settings_password_change_error)
            if (result.isSuccess) {
                _passwordCodeSent.value = false
                _passwordCode.value = ""
                _newAccountPassword.value = ""
            }
            _passwordChangeLoading.value = false
        }
    }

    fun cancelPasswordChange() {
        _passwordCodeSent.value = false
        _passwordCode.value = ""
        _newAccountPassword.value = ""
    }

    fun clearAccountMessage() { _accountMessage.value = null }

    // ── Idioma ────────────────────────────────────────────────────────────────
    val appLanguage: StateFlow<String> = preferences.appLanguage
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "pt")

    fun onLanguageChange(lang: String) {
        viewModelScope.launch {
            preferences.saveAppLanguage(lang)
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(lang))
        }
    }

    private val _debugSeedEnabled = MutableStateFlow(false)
    val debugSeedEnabled: StateFlow<Boolean> = _debugSeedEnabled.asStateFlow()

    private val _debugBusy = MutableStateFlow(false)
    val debugBusy: StateFlow<Boolean> = _debugBusy.asStateFlow()

    private val _debugMessage = MutableStateFlow<String?>(null)
    val debugMessage: StateFlow<String?> = _debugMessage.asStateFlow()

    /** Chamado pela tela ao entrar (se admin) — reflete o interruptor com o estado real do servidor. */
    fun refreshDebugStatus() {
        viewModelScope.launch {
            runCatching { debugRepository.isContinuousEnabled() }
                .onSuccess { _debugSeedEnabled.value = it }
        }
    }

    fun onDebugBackfillClick() {
        viewModelScope.launch {
            _debugBusy.value = true
            runCatching { debugRepository.backfillToday() }
                .onSuccess { count -> _debugMessage.value = "Geradas $count leituras de hoje." }
                .onFailure { e -> _debugMessage.value = "Erro: ${e.message}" }
            _debugBusy.value = false
        }
    }

    fun onDebugSeedToggle(enabled: Boolean) {
        viewModelScope.launch {
            _debugBusy.value = true
            runCatching { debugRepository.setContinuousEnabled(enabled) }
                .onSuccess { _debugSeedEnabled.value = enabled }
                .onFailure { e -> _debugMessage.value = "Erro: ${e.message}" }
            _debugBusy.value = false
        }
    }

    fun clearDebugMessage() { _debugMessage.value = null }
}
