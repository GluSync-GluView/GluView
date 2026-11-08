package com.gluview.app.ui.screen.alertsettings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gluview.app.alert.AlertType
import com.gluview.app.alert.ALERT_ZONES
import com.gluview.app.data.datastore.AppPreferences
import com.gluview.app.data.remote.IftttWebhookClient
import com.gluview.app.data.remote.dto.CaregiverLinkViewDto
import com.gluview.app.data.repository.AlertPreferencesRepository
import com.gluview.app.data.repository.CaregiverRepository
import com.gluview.app.data.repository.CloudAccessRepository
import com.gluview.app.domain.model.GlucoseZone
import com.gluview.app.util.GlucoseUnit
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.json.JSONArray
import javax.inject.Inject

data class AlertSettingsUiState(
    val toggles: Map<Pair<GlucoseZone, AlertType>, Boolean> = emptyMap(),
    // Faixas de glicemia (migraram de Ajustes pra cá — tudo sobre alerta numa tela só)
    val urgentLow: Float           = 55f,
    val low: Float                 = 70f,
    val high: Float                = 180f,
    val urgentHigh: Float          = 250f,
    // S14
    val predictiveEnabled: Boolean = true,
    val predictiveMinutes: Int     = 15,
    val reAlarmEnabled: Boolean    = false,
    val reAlarmInterval: Int       = 5,
    // S15
    val ttsEnabled: Boolean        = false,
    val quietEnabled: Boolean      = false,
    val quietStart: Int            = 23,
    val quietEnd: Int              = 7,
    // S16
    val callEnabled: Boolean       = false,
    val callPhone: String          = "",
    val callDelay: Int             = 5,
    // S17
    val smsEnabled: Boolean        = false,
    val smsContact: String         = "",
    val smsMessage: String         = "",
    // IFTTT
    val iftttEnabled: Boolean                = false,
    val iftttApiKey: String                  = "",
    val iftttEventPrefix: String             = "gluview_",
    val iftttZoneToggles: Map<GlucoseZone, Boolean> = emptyMap(),
    val iftttPredictive: Boolean             = false,
    // Pushover
    val pushoverEnabled: Boolean   = false,
    val pushoverUserKey: String    = "",
    // Avisar seguidores
    val notifyFollowersEnabled: Boolean        = false,
    val notifyFollowersSpecificIds: Set<String> = emptySet(),
    val followers: List<CaregiverLinkViewDto>   = emptyList(),
    val myUserId: String                        = "",
    val saved: Boolean             = false
)

/** Resultado de um teste de integração: [ok] decide o texto e a cor; [detail] = mensagem extra. */
data class TestResult(val ok: Boolean, val detail: String? = null)

private data class IftttPrefs(
    val enabled: Boolean,
    val apiKey: String,
    val prefix: String,
    val zones: Map<GlucoseZone, Boolean>,
    val predictive: Boolean
)

@HiltViewModel
class AlertSettingsViewModel @Inject constructor(
    private val prefs: AppPreferences,
    private val iftttClient: IftttWebhookClient,
    private val alertPrefsRepo: AlertPreferencesRepository,
    private val caregiverRepo: CaregiverRepository,
    private val cloudAccess: CloudAccessRepository
) : ViewModel() {

    /** Resultado do teste (some depois de alguns segundos): a tela traduz o texto pelo [TestResult.ok]. */
    private val _iftttTestResult = MutableStateFlow<TestResult?>(null)
    val iftttTestResult: StateFlow<TestResult?> = _iftttTestResult.asStateFlow()

    private val _pushoverTestResult = MutableStateFlow<TestResult?>(null)
    val pushoverTestResult: StateFlow<TestResult?> = _pushoverTestResult.asStateFlow()

    /** Falha ao sincronizar com o GluSync: só o detalhe técnico (a tela põe a frase traduzida). */
    private val _syncError = MutableStateFlow<String?>(null)
    val syncError: StateFlow<String?> = _syncError.asStateFlow()

    // Seguidores e ID próprio (carregados no init)
    private val _followers = MutableStateFlow<List<CaregiverLinkViewDto>>(emptyList())
    private val _myUserId  = MutableStateFlow("")

    val uiState: StateFlow<AlertSettingsUiState> = combine(
        prefs.alertToggles,
        prefs.predictiveEnabled, prefs.predictiveMinutes,
        prefs.reAlarmEnabled,    prefs.reAlarmInterval
    ) { toggles, predEnabled, predMin, reEnabled, reInterval ->
        AlertSettingsUiState(
            toggles           = toggles,
            predictiveEnabled = predEnabled,
            predictiveMinutes = predMin,
            reAlarmEnabled    = reEnabled,
            reAlarmInterval   = reInterval
        )
    }.combine(
        combine(
            prefs.ttsEnabled, prefs.quietPeriodEnabled,
            prefs.quietStartHour, prefs.quietEndHour
        ) { tts, quiet, start, end -> listOf<Any>(tts, quiet, start, end) }
    ) { state, ttsQuiet ->
        state.copy(
            ttsEnabled   = ttsQuiet[0] as Boolean,
            quietEnabled = ttsQuiet[1] as Boolean,
            quietStart   = ttsQuiet[2] as Int,
            quietEnd     = ttsQuiet[3] as Int
        )
    }.combine(
        combine(prefs.callAlertEnabled, prefs.callAlertPhone, prefs.callAlertDelay) { en, ph, del ->
            Triple(en, ph, del)
        }
    ) { state, call ->
        state.copy(callEnabled = call.first, callPhone = call.second, callDelay = call.third)
    }.combine(
        combine(prefs.smsAlertEnabled, prefs.smsContact, prefs.smsMessage) { en, ct, msg ->
            Triple(en, ct, msg)
        }
    ) { state, sms ->
        state.copy(smsEnabled = sms.first, smsContact = sms.second, smsMessage = sms.third)
    }.combine(
        combine(
            prefs.urgentLowThreshold, prefs.lowThreshold,
            prefs.highThreshold, prefs.urgentHighThreshold
        ) { ul, l, h, uh -> listOf(ul, l, h, uh) }
    ) { state, thresholds ->
        state.copy(
            urgentLow  = thresholds[0], low = thresholds[1],
            high       = thresholds[2], urgentHigh = thresholds[3]
        )
    }.combine(
        combine(
            prefs.iftttEnabled,
            prefs.iftttApiKey,
            prefs.iftttEventPrefix,
            prefs.iftttZoneToggles,
            prefs.iftttPredictiveEnabled
        ) { en, key, prefix, zones, pred -> IftttPrefs(en, key, prefix, zones, pred) }
    ) { state, ifttt ->
        state.copy(
            iftttEnabled     = ifttt.enabled,
            iftttApiKey      = ifttt.apiKey,
            iftttEventPrefix = ifttt.prefix,
            iftttZoneToggles = ifttt.zones,
            iftttPredictive  = ifttt.predictive
        )
    }.combine(
        combine(prefs.pushoverEnabled, prefs.pushoverUserKey) { en, key -> en to key }
    ) { state, (en, key) ->
        state.copy(pushoverEnabled = en, pushoverUserKey = key)
    }.combine(
        combine(prefs.notifyFollowersEnabled, prefs.notifyFollowersIdsJson) { en, idsJson ->
            val ids = try {
                val arr = JSONArray(idsJson)
                (0 until arr.length()).map { arr.getString(it) }.toSet()
            } catch (_: Exception) { emptySet() }
            en to ids
        }
    ) { state, (en, ids) ->
        state.copy(notifyFollowersEnabled = en, notifyFollowersSpecificIds = ids)
    }.combine(
        combine(_followers, _myUserId) { f, id -> f to id }
    ) { state, (followers, myId) ->
        state.copy(followers = followers, myUserId = myId)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AlertSettingsUiState())

    init {
        loadFollowers()
        // Estado da liberação da nuvem velho/desconhecido: reconsulta (decide se Pushover e
        // "avisar seguidores" podem funcionar). Sem conta não faz nada.
        viewModelScope.launch { runCatching { cloudAccess.refreshIfStale() } }
    }

    private fun loadFollowers() {
        viewModelScope.launch {
            if (!prefs.hasAccount.first()) return@launch // modo antigo sem conta: não há seguidores
            runCatching {
                val myEmail = prefs.userEmail.first()
                val links   = caregiverRepo.links()
                _myUserId.value = links.firstOrNull()?.let {
                    if (it.followerEmail == myEmail) it.followerId else it.patientId
                } ?: ""
                // Só quem me segue (eu sou o paciente)
                _followers.value = links.filter { it.status == "accepted" && it.patientEmail == myEmail }
            }
        }
    }

    // ── Toggles ───────────────────────────────────────────────────────────────

    fun onToggle(zone: GlucoseZone, type: AlertType, enabled: Boolean) {
        viewModelScope.launch {
            prefs.setAlertEnabled(zone, type, enabled)
            syncToServer()
        }
    }

    fun savePredictive(enabled: Boolean, minutes: Int) {
        viewModelScope.launch { prefs.savePredictive(enabled, minutes); syncToServer() }
    }

    fun saveReAlarm(enabled: Boolean, interval: Int) {
        viewModelScope.launch { prefs.saveReAlarm(enabled, interval); syncToServer() }
    }

    fun saveTts(enabled: Boolean) {
        viewModelScope.launch { prefs.saveTts(enabled) }
    }

    fun saveQuiet(enabled: Boolean, start: Int, end: Int) {
        viewModelScope.launch { prefs.saveQuietPeriod(enabled, start, end) }
    }

    fun saveCall(enabled: Boolean, phone: String, delay: Int) {
        viewModelScope.launch { prefs.saveCallAlert(enabled, phone, delay) }
    }

    fun saveSms(enabled: Boolean, contact: String, message: String) {
        viewModelScope.launch { prefs.saveSmsAlert(enabled, contact, message) }
    }

    // Faixas de glicemia são avaliadas sobre a leitura LOCAL própria — não existem pra conta
    // seguidor-apenas (mesma regra que já valia quando essa seção morava em Ajustes).
    val isFollowerOnly: StateFlow<Boolean> = prefs.isFollowerOnly
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    // Tem conta no GluSync. Antes era `isConfigured`, que também vale para o modo antigo sem conta
    // (ADR-0030): a tela mostrava Pushover/seguidores e cada ajuste tentava sincronizar com um
    // servidor que não existe, com erro na tela.
    val isGluSyncConnected: StateFlow<Boolean> = prefs.hasAccount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /**
     * Pushover e "avisar seguidores" são disparados pelo GLUSYNC quando ele recebe a leitura — sem
     * a nuvem liberada (ADR-0030) nenhuma leitura chega lá e esses avisos nunca saem, mesmo com o
     * teste do Pushover funcionando. Estado ainda não consultado ("") não esconde nada.
     */
    val cloudAlertsAllowed: StateFlow<Boolean> = combine(prefs.hasAccount, prefs.cloudSyncState) { account, state ->
        account && cloudAlertsAllowedFor(state)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    // Unidade de medida do usuário — as faixas/zonas de glicemia precisam exibir e aceitar
    // digitação em mmol/L quando for essa a unidade escolhida (o valor interno continua sempre
    // em mg/dL, é só a apresentação que muda).
    val glucoseUnit: StateFlow<GlucoseUnit> = prefs.glucoseUnit
        .map { GlucoseUnit.fromString(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GlucoseUnit.MG_DL)

    // Faixas de glicemia — salva na hora (mesmo padrão do resto desta tela, sem botão "Salvar").
    private fun saveThresholds(ul: Float, l: Float, h: Float, uh: Float) {
        viewModelScope.launch { prefs.saveThresholds(ul, l, h, uh); syncToServer() }
    }
    fun onUrgentLowChange(v: Float)  { val s = uiState.value; saveThresholds(v, s.low, s.high, s.urgentHigh) }
    fun onLowChange(v: Float)        { val s = uiState.value; saveThresholds(s.urgentLow, v, s.high, s.urgentHigh) }
    fun onHighChange(v: Float)       { val s = uiState.value; saveThresholds(s.urgentLow, s.low, v, s.urgentHigh) }
    fun onUrgentHighChange(v: Float) { val s = uiState.value; saveThresholds(s.urgentLow, s.low, s.high, v) }

    // IFTTT
    fun saveIftttEnabled(enabled: Boolean)  { viewModelScope.launch { prefs.saveIftttEnabled(enabled) } }
    fun saveIftttApiKey(key: String)        { viewModelScope.launch { prefs.saveIftttApiKey(key) } }
    fun saveIftttPrefix(prefix: String)     { viewModelScope.launch { prefs.saveIftttEventPrefix(prefix) } }
    fun setIftttZone(zone: GlucoseZone, enabled: Boolean) {
        viewModelScope.launch { prefs.setIftttZoneEnabled(zone, enabled) }
    }
    fun setIftttPredictive(enabled: Boolean) {
        viewModelScope.launch { prefs.setIftttPredictiveEnabled(enabled) }
    }

    fun testIfttt() {
        val s = uiState.value
        viewModelScope.launch {
            val ok = iftttClient.triggerTest(s.iftttApiKey, s.iftttEventPrefix)
            _iftttTestResult.value = TestResult(ok)
            delay(4_000)
            _iftttTestResult.value = null
        }
    }

    // ── Pushover ──────────────────────────────────────────────────────────────

    fun savePushover(enabled: Boolean, userKey: String, zonesJson: String = "[]") {
        viewModelScope.launch {
            prefs.savePushover(enabled, userKey, zonesJson)
            syncToServer()
        }
    }

    fun testPushover() {
        val key = uiState.value.pushoverUserKey
        viewModelScope.launch {
            val result = alertPrefsRepo.testPushover(key)
            // Sucesso traz a mensagem do servidor; falha, o detalhe técnico.
            _pushoverTestResult.value = result.fold({ TestResult(true, it) }, { TestResult(false, it.message) })
            delay(5_000)
            _pushoverTestResult.value = null
        }
    }

    // ── Avisar seguidores ─────────────────────────────────────────────────────

    fun saveNotifyFollowers(enabled: Boolean) {
        viewModelScope.launch {
            val cur = uiState.value.notifyFollowersSpecificIds
            val json = JSONArray(cur.toList()).toString()
            prefs.saveNotifyFollowers(enabled, json)
            syncToServer()
        }
    }

    fun toggleFollowerSelection(followerId: String) {
        viewModelScope.launch {
            val cur = uiState.value.notifyFollowersSpecificIds.toMutableSet()
            if (!cur.remove(followerId)) cur.add(followerId)
            val json = JSONArray(cur.toList()).toString()
            prefs.saveNotifyFollowers(uiState.value.notifyFollowersEnabled, json)
            syncToServer()
        }
    }

    // ── Sincronização com GluSync ─────────────────────────────────────────────

    private var syncJob: Job? = null

    /**
     * L24: espera 800 ms sem mudança antes de enviar (antes cada passo do arraste de um slider era
     * uma chamada ao GluSync). A gravação local continua imediata.
     */
    private fun syncToServer() {
        if (!isGluSyncConnected.value) return
        syncJob?.cancel()
        syncJob = viewModelScope.launch {
            delay(SYNC_DEBOUNCE_MS)
            alertPrefsRepo.syncToServer().onFailure { err ->
                _syncError.value = err.message ?: err.javaClass.simpleName
                delay(4_000)
                _syncError.value = null
            }
        }
    }

    fun pullFromServer() {
        viewModelScope.launch {
            alertPrefsRepo.syncFromServer()
        }
    }

    /** Saiu da tela com um envio esperando o debounce: envia mesmo assim (fora do viewModelScope). */
    override fun onCleared() {
        if (syncJob?.isActive == true) {
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { alertPrefsRepo.syncToServer() }
        }
        super.onCleared()
    }

    companion object {
        private const val SYNC_DEBOUNCE_MS = 800L

        /** Função pura (testável): avisos pela nuvem só com envio liberado; "" = ainda não se sabe. */
        fun cloudAlertsAllowedFor(cloudSyncState: String): Boolean =
            cloudSyncState.isEmpty() || cloudSyncState == CloudAccessRepository.STATE_APPROVED
    }
}
