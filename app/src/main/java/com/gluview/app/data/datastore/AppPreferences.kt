package com.gluview.app.data.datastore

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.gluview.app.alert.AlertType
import com.gluview.app.domain.model.GlucoseThresholds
import com.gluview.app.domain.model.GlucoseZone
import com.gluview.app.util.GraphTheme
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppPreferences @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    @ApplicationContext private val appContext: Context
) {
    // Auditoria 2026-07-09 (achado #3) — access_token/refresh_token saíram do DataStore (texto
    // plano em disco) e foram pra um SharedPreferences separado, cifrado via Android Keystore
    // (EncryptedSharedPreferences/Tink). Só os tokens moram aqui — todo o resto do app continua
    // no DataStore normal, sem mudança de comportamento. Lazy: a primeira leitura/escrita cria a
    // master key no Keystore, então isso só deve rodar em background (ver KEY_ACCESS_TOKEN/
    // KEY_REFRESH_TOKEN abaixo, que não existem mais como PreferencesKey do DataStore).
    private val securePrefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            "gluview_secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    companion object {
        // access_token/refresh_token NÃO ficam mais aqui — ver securePrefs acima (achado #3).
        private const val SECURE_KEY_ACCESS_TOKEN  = "access_token"
        private const val SECURE_KEY_REFRESH_TOKEN = "refresh_token"
        // Expiração do access token (epoch ms) — permite renovar PROATIVAMENTE antes do vencimento,
        // em vez de só reagir depois que a conexão já falhou com negotiate 401.
        private const val SECURE_KEY_ACCESS_EXPIRES_AT = "access_expires_at"
        // Idempotency key da próxima tentativa de refresh — estável enquanto o refresh token
        // salvo não mudar, pra o servidor reconhecer um retry (perda de resposta de rede) do
        // MESMO client em vez de tratar como reuso de token roubado. Ver TokenRefreshCoordinator.
        private const val SECURE_KEY_PENDING_REFRESH_REQUEST_ID = "pending_refresh_request_id"

        // ── Conexão / Conta ───────────────────────────────────────────────────
        val KEY_BASE_URL              = stringPreferencesKey("base_url")
        val KEY_USER_EMAIL            = stringPreferencesKey("user_email")
        // Pedido de acesso ao app ainda sem decisão — ID devolvido por POST /access-requests,
        // salvo aqui (antes só vivia em LoginUiState, perdido ao fechar o app) pra dar pra
        // consultar o status depois (AccessRequestStatusScreen). Limpo quando o pedido é decidido
        // (a pessoa já loga normal a partir daí) ou quando uma nova candidatura é criada.
        val KEY_PENDING_APP_ACCESS_REQUEST_ID = stringPreferencesKey("pending_app_access_request_id")

        // ── Display ───────────────────────────────────────────────────────────
        val KEY_POLLING_INTERVAL      = intPreferencesKey("polling_interval_minutes")
        val KEY_GLUCOSE_UNIT          = stringPreferencesKey("glucose_unit")
        val KEY_THEME_MODE            = stringPreferencesKey("theme_mode")
        // Tema do gráfico (Home/widgets/notificação) — separado do tema geral do app; ver GraphTheme.
        val KEY_GRAPH_THEME           = stringPreferencesKey("graph_theme_mode")
        // Modos de leitura Sibionics: ALL / CONFIRMED_ONLY / ALL_WITH_MARKERS — ver GlucoseDisplayMode.
        val KEY_GLUCOSE_DISPLAY_MODE  = stringPreferencesKey("glucose_display_mode")
        // Formato do horário da medição (Home/widgets/Mi Band) — "H24" (padrão) ou "H12" (AM/PM). Ver TimeFormat.
        val KEY_TIME_FORMAT           = stringPreferencesKey("time_format")

        // ── Faixas ────────────────────────────────────────────────────────────
        val KEY_THRESHOLD_URGENT_LOW  = floatPreferencesKey("threshold_urgent_low")
        val KEY_THRESHOLD_LOW         = floatPreferencesKey("threshold_low")
        val KEY_THRESHOLD_HIGH        = floatPreferencesKey("threshold_high")
        val KEY_THRESHOLD_URGENT_HIGH = floatPreferencesKey("threshold_urgent_high")

        // ── S13 — Alert toggles (gerados por zona + tipo) ─────────────────────
        fun alertKey(zone: GlucoseZone, type: AlertType) =
            booleanPreferencesKey("alert_${type.name.lowercase()}_${zone.name.lowercase()}")

        fun defaultAlertEnabled(zone: GlucoseZone, type: AlertType): Boolean = when (zone) {
            GlucoseZone.URGENT_LOW  -> true
            GlucoseZone.LOW         -> type == AlertType.NOTIFICATION
            GlucoseZone.HIGH        -> type == AlertType.NOTIFICATION
            GlucoseZone.URGENT_HIGH -> type != AlertType.DND
            GlucoseZone.STALE       -> type == AlertType.NOTIFICATION
            GlucoseZone.QUICK_DROP  -> type != AlertType.DND
            else                    -> false
        }

        // ── S14 — Preditivo + re-alarme ───────────────────────────────────────
        val KEY_PREDICTIVE_ENABLED    = booleanPreferencesKey("predictive_enabled")
        val KEY_PREDICTIVE_MINUTES    = intPreferencesKey("predictive_threshold_minutes")
        val KEY_REALARM_ENABLED       = booleanPreferencesKey("realarm_enabled")
        val KEY_REALARM_INTERVAL      = intPreferencesKey("realarm_interval_minutes")

        // ── S15 — TTS + silêncio noturno ──────────────────────────────────────
        val KEY_TTS_ENABLED           = booleanPreferencesKey("tts_enabled")
        val KEY_QUIET_PERIOD_ENABLED  = booleanPreferencesKey("quiet_period_enabled")
        val KEY_QUIET_START_HOUR      = intPreferencesKey("quiet_start_hour")
        val KEY_QUIET_END_HOUR        = intPreferencesKey("quiet_end_hour")

        // ── S16 — Alerta por ligação ──────────────────────────────────────────
        val KEY_CALL_ALERT_ENABLED    = booleanPreferencesKey("call_alert_enabled")
        val KEY_CALL_ALERT_PHONE      = stringPreferencesKey("call_alert_phone")
        val KEY_CALL_ALERT_DELAY      = intPreferencesKey("call_alert_delay_minutes")

        // ── S17 — SMS emergência ──────────────────────────────────────────────
        val KEY_SMS_ALERT_ENABLED     = booleanPreferencesKey("sms_alert_enabled")
        val KEY_SMS_CONTACT           = stringPreferencesKey("sms_contact")
        val KEY_SMS_MESSAGE           = stringPreferencesKey("sms_message")

        // ── S29 — Retenção de dados local ─────────────────────────────────────
        val KEY_DATA_RETENTION_DAYS   = intPreferencesKey("data_retention_days")
        // Período de arrependimento (7 dias): redução de retenção fica pendente antes de
        // apagar dado de verdade. "0" em KEY_PENDING_RETENTION_AT_MS = nada pendente.
        val KEY_PENDING_RETENTION_DAYS  = intPreferencesKey("pending_retention_days")
        val KEY_PENDING_RETENTION_AT_MS = longPreferencesKey("pending_retention_at_ms")

        // ── S35 — Onboarding (primeira execução) ──────────────────────────────
        val KEY_ONBOARDING_DONE       = booleanPreferencesKey("onboarding_done")

        // ── F7 — Termos de uso (aceite: versão + data + usuário) ───────────────
        val KEY_TERMS_VERSION         = intPreferencesKey("terms_accepted_version")
        val KEY_TERMS_ACCEPTED_AT     = longPreferencesKey("terms_accepted_at")
        val KEY_TERMS_ACCEPTED_EMAIL  = stringPreferencesKey("terms_accepted_email")
        val KEY_TERMS_RECORDED_VERSION = intPreferencesKey("terms_recorded_version") // já registrado no GluSync

        // ── Gráficos — horas exibidas por contexto ────────────────────────────
        val KEY_HOME_GRAPH_HOURS      = intPreferencesKey("home_graph_hours")
        val KEY_WIDGET_GRAPH_HOURS    = intPreferencesKey("widget_graph_hours")
        val KEY_NOTIF_GRAPH_HOURS     = intPreferencesKey("notif_graph_hours")

        // ── Diagnóstico — logs de homologação ─────────────────────────────────
        val KEY_LOGS_ENABLED          = booleanPreferencesKey("logs_enabled")

        // ── T85 — Passkeys (beta), default off. NÃO entra em clearSession(): a flag e as
        // passkeys cadastradas (servidor) têm que sobreviver ao logout, senão o botão de
        // login sem senha sumiria da tela toda vez que o usuário saísse da conta.
        val KEY_PASSKEYS_ENABLED      = booleanPreferencesKey("passkeys_enabled")

        // ── API Keys — integração com outros apps/serviços, default off ────────
        val KEY_API_KEYS_ENABLED      = booleanPreferencesKey("api_keys_enabled")

        // ── Background — isenção de bateria ───────────────────────────────────
        val KEY_BATTERY_OPT_ASKED     = booleanPreferencesKey("battery_opt_asked")

        // ── F6 — Acompanhamento: dedupe de notificações locais (convite recebido/aceito) ──
        val KEY_NOTIFIED_INVITES_RECEIVED = stringPreferencesKey("notified_invites_received")
        val KEY_NOTIFIED_INVITES_ACCEPTED = stringPreferencesKey("notified_invites_accepted")

        // ── Modo 100% local — sem GluSync, tudo no aparelho ─────────────────────
        val KEY_LOCAL_ONLY_MODE = booleanPreferencesKey("local_only_mode")
        // ADR-0030 — "agora não" da conta (só instalações antigas sem conta) e liberação da nuvem
        val KEY_ACCOUNT_PROMPT_POSTPONED_AT = longPreferencesKey("account_prompt_postponed_at")
        val KEY_CLOUD_SYNC_STATE            = stringPreferencesKey("cloud_sync_state")
        val KEY_CLOUD_SYNC_CHECKED_AT       = longPreferencesKey("cloud_sync_checked_at")
        // Plano 17 — diário e lembretes (GluView first; nuvem só com liberação).
        val KEY_DIARY_CLOUD_BLOCKED_UNTIL   = longPreferencesKey("diary_cloud_blocked_until")
        val KEY_DIARY_LAST_PULL_MS          = longPreferencesKey("diary_last_pull_ms")
        val KEY_DIARY_LAST_SYNC_MS          = longPreferencesKey("diary_last_sync_ms")
        val KEY_SMART_MEAL_PROMPT           = booleanPreferencesKey("smart_meal_prompt")
        val KEY_SMART_MEAL_PROMPT_LAST_AT   = longPreferencesKey("smart_meal_prompt_last_at")
        val KEY_NOTIF_QUICK_ADD             = booleanPreferencesKey("notif_quick_add")
        // Variante do sensor por modelo e região (SensorVariantCatalog); "" = padrão da fonte
        val KEY_SENSOR_VARIANT_ID           = stringPreferencesKey("sensor_variant_id")

        // ── Idioma do app — "pt" (padrão) | "en" ─────────────────────────────────
        val KEY_APP_LANGUAGE = stringPreferencesKey("app_language")

        // ── Modo Seguidor — papéis da conta (cache do login) + paciente ativo na Home ──
        val KEY_MY_ROLES                      = stringPreferencesKey("my_roles")
        val KEY_ACTIVE_FOLLOWED_PATIENT_ID    = stringPreferencesKey("active_followed_patient_id")
        val KEY_ACTIVE_FOLLOWED_PATIENT_NAME  = stringPreferencesKey("active_followed_patient_name")

        // ── Fase 3 — Fonte de dados + saúde do sensor ─────────────────────────
        val KEY_DATA_SOURCE           = stringPreferencesKey("data_source")
        val KEY_SENSOR_WARN_ENABLED   = booleanPreferencesKey("sensor_warn_enabled")
        val KEY_SENSOR_AGING_DAYS     = intPreferencesKey("sensor_aging_warn_days")
        val KEY_SENSOR_NOSIGNAL_MIN   = intPreferencesKey("sensor_nosignal_warn_min")
        // BLE Sibionics (Frente B)
        val KEY_SIBIONICS_MAC         = stringPreferencesKey("sibionics_mac")
        val KEY_SIBIONICS_SERIAL      = stringPreferencesKey("sibionics_serial")
        val KEY_SIBIONICS_CUSTOMER_ID = stringPreferencesKey("sibionics_customer_id")
        // (Legado) fator de calibração — NÃO é mais aplicado à leitura. Mantido só para
        // não quebrar telas antigas; a glicemia agora é a saída pura do algoritmo de fábrica.
        val KEY_SIBIONICS_CAL_FACTOR  = doublePreferencesKey("sibionics_cal_factor")
        // Estado serializado do algoritmo JNI (v1.1.2). Salvo após cada índice e restaurado no
        // reinício do processo para manter o filtro "quente". O CÓDIGO (连接码) é guardado junto:
        // o contexto só é restaurado se o código bater (evita estado de outro sensor/lixo).
        val KEY_SIBIONICS_ALGO_CTX    = stringPreferencesKey("sibionics_algo_ctx")
        val KEY_SIBIONICS_ALGO_IDX    = intPreferencesKey("sibionics_algo_idx")
        val KEY_SIBIONICS_ALGO_CODE   = stringPreferencesKey("sibionics_algo_code")
        // Âncora de início do sensor (epoch ms), reconstruída do índice na 1ª leitura e persistida
        // daí em diante — sem isto, cada reinício de processo perdia a âncora em memória e
        // reancorava usando o índice do primeiro pacote de BACKFILL (sempre atrasado em relação
        // ao "ao vivo"), empurrando todas as leituras (dateMs) para o futuro.
        val KEY_SIBIONICS_SENSOR_START = longPreferencesKey("sibionics_sensor_start_ms")
        // Nome anunciado pelo sensor no ar. Guardado junto do endereço porque é ele que carrega a
        // IDENTIDADE do sensor (os 4 últimos caracteres batem com o código da embalagem) — o
        // endereço sozinho não diz de qual sensor é, e foi exatamente isso que permitiu o app
        // ficar conectado ao rádio do sensor anterior sem perceber.
        val KEY_SIBIONICS_BLE_NAME     = stringPreferencesKey("sibionics_ble_name")
        // BLE FreeStyle Libre 2 Plus — sessão derivada na ativação por NFC + âncora de calibração.
        // A sessão (UID + patchInfo + chave BLE + params de calibração da FRAM) é lida UMA vez ao
        // encostar o sensor no celular; depois o streaming BLE roda headless (como o Sibionics).
        val KEY_LIBRE2_MAC            = stringPreferencesKey("libre2_mac")
        val KEY_LIBRE2_SERIAL         = stringPreferencesKey("libre2_serial")
        val KEY_LIBRE2_UID            = stringPreferencesKey("libre2_uid")            // hex do UID (8 bytes)
        val KEY_LIBRE2_PATCH_INFO     = stringPreferencesKey("libre2_patch_info")     // hex do patchInfo (6 bytes)
        val KEY_LIBRE2_SESSION_KEY    = stringPreferencesKey("libre2_session_key")    // hex do material de chave BLE
        val KEY_LIBRE2_STREAMING_VALID = booleanPreferencesKey("libre2_streaming_valid") // handshake BLE concluído
        val KEY_LIBRE2_UNLOCK_COUNT   = intPreferencesKey("libre2_unlock_count")      // contador de unlock do streaming
        val KEY_LIBRE2_CALIBRATION    = stringPreferencesKey("libre2_calibration")    // params de calibração (JSON) da FRAM
        val KEY_LIBRE2_FRAM           = stringPreferencesKey("libre2_fram")           // FRAM 344B para reconstruir scanstate
        val KEY_LIBRE2_SENSOR_START   = longPreferencesKey("libre2_sensor_start_ms")  // ativação (epoch ms) p/ idade
        val KEY_LIBRE2_CAL_FACTOR     = doublePreferencesKey("libre2_cal_factor")     // âncora ponta de dedo; 1.0 = fábrica
        // BLE Accu-Chek SmartGuide — serviço CGM padrão (0x181F), sem cripto proprietária. O
        // histórico vem pelo RACP filtrado por MINUTO desde o início do sensor (ver AccuChekSource).
        val KEY_ACCUCHEK_MAC           = stringPreferencesKey("accuchek_mac")       // endereço do aparelho pareado
        val KEY_ACCUCHEK_SERIAL        = stringPreferencesKey("accuchek_serial")    // serial da embalagem (nome AC-<serial>)
        val KEY_ACCUCHEK_PIN           = stringPreferencesKey("accuchek_pin")       // PIN de 6 dígitos da tampa (re-parear)
        val KEY_ACCUCHEK_LAST_MINUTE   = intPreferencesKey("accuchek_last_minute")  // minuto da última leitura salva
        val KEY_ACCUCHEK_START_MS      = longPreferencesKey("accuchek_start_ms")    // início do sensor (epoch ms)
        // Chave do driver antigo (perfil 0x1808, cursor por sequência) — só para apagar.
        val KEY_ACCUCHEK_LAST_SEQUENCE_OLD = intPreferencesKey("accuchek_last_sequence")
        // BLE Smart 2.0 (MedLevensohn / Microtech MD AiDEX X-LinX-Lumi) — cripto própria
        // (MD5+AES-CFB128 derivados do serial). sessionKey é derivada no handshake; persistida
        // pra pular o handshake completo na próxima conexão (ver MedLevensohnBleManager).
        val KEY_MEDLEVENSOHN_SERIAL      = stringPreferencesKey("medlevensohn_serial")
        val KEY_MEDLEVENSOHN_MAC         = stringPreferencesKey("medlevensohn_mac")
        // Chave antiga: guardava a chave de SESSÃO (errado — ela muda a cada conexão). Só para apagar.
        val KEY_MEDLEVENSOHN_SESSION_KEY = stringPreferencesKey("medlevensohn_session_key")
        // Chave-mestra (f001) — é ela que se guarda; a sessão é derivada de novo a cada conexão (Juggluco).
        val KEY_MEDLEVENSOHN_MASTER_KEY  = stringPreferencesKey("medlevensohn_master_key")
        val KEY_MEDLEVENSOHN_START_SEC   = longPreferencesKey("medlevensohn_start_sec")    // início do sensor (0x121)
        val KEY_MEDLEVENSOHN_LAST_ID     = intPreferencesKey("medlevensohn_last_id")       // último minuto gravado
        val KEY_MEDLEVENSOHN_HAS_TIME    = booleanPreferencesKey("medlevensohn_has_time")
        // BLE Dexcom G6 — handshake desafio-resposta AES-128 (chave derivada do Transmitter ID,
        // sem troca de chave). Transmitter ID é digitado pelo usuário (6 caracteres impressos no
        // transmissor), não descoberto por scan/NFC. Ver DexcomG6Source/DexcomG6BleManager.
        val KEY_DEXCOM_G6_TRANSMITTER_ID = stringPreferencesKey("dexcom_g6_transmitter_id")
        val KEY_DEXCOM_G6_MAC            = stringPreferencesKey("dexcom_g6_mac")
        // F8 — pareamento da Mi Band para envio do visor (auth key Huami + MAC)
        val KEY_MIBAND_MAC            = stringPreferencesKey("miband_mac")
        val KEY_MIBAND_AUTH_KEY       = stringPreferencesKey("miband_auth_key")
        val KEY_MIBAND_AUTO_SEND      = booleanPreferencesKey("miband_auto_send")
        // Agendamento do envio automático — período especial (ex.: madrugada) com intervalo
        // próprio; fora dele usa o intervalo padrão. Horários em minutos desde 00:00 (permite
        // granularidade de minuto, ex. 00:30, não só hora cheia).
        val KEY_MIBAND_DAY_INTERVAL_MIN   = intPreferencesKey("miband_day_interval_min")
        val KEY_MIBAND_NIGHT_INTERVAL_MIN = intPreferencesKey("miband_night_interval_min")
        val KEY_MIBAND_NIGHT_START_MIN    = intPreferencesKey("miband_night_start_min")
        val KEY_MIBAND_NIGHT_END_MIN      = intPreferencesKey("miband_night_end_min")
        val KEY_MIBAND_UPDATE_EVERY_READING = booleanPreferencesKey("miband_update_every_reading")
        // Envio ao GluSync via WS — date (ms) da última leitura enviada com sucesso (cursor)
        val KEY_WS_LAST_UPLOADED_MS   = longPreferencesKey("ws_last_uploaded_ms")
        // Plano OTA (E7) — soneca dos alarmes persistida: o processo morre ao atualizar o app e a
        // soneca não pode sumir junto (antes vivia só na memória do AlertManager).
        val KEY_ALERT_SILENCED_UNTIL_MS = longPreferencesKey("alert_silenced_until_ms")
        // Migração de banco (2026-07-23) — e-mails de conta que já receberam o backfill único do
        // histórico local (evita repetir a cada login futuro).
        val KEY_HISTORY_BACKFILL_DONE_EMAILS = stringPreferencesKey("history_backfill_done_emails")
        // Política de rede do envio ao GluSync: permite dados móveis? com que intervalo (min)
        // de reenvio em lote enquanto em dados móveis (por Wi-Fi o envio é sempre ao vivo).
        val KEY_UPLOAD_OVER_MOBILE_DATA    = booleanPreferencesKey("upload_over_mobile_data")
        val KEY_UPLOAD_MOBILE_INTERVAL_MIN = intPreferencesKey("upload_mobile_interval_min")

        // ── IFTTT Webhooks ────────────────────────────────────────────────────
        val KEY_IFTTT_ENABLED         = booleanPreferencesKey("ifttt_enabled")
        val KEY_IFTTT_API_KEY         = stringPreferencesKey("ifttt_api_key")
        val KEY_IFTTT_EVENT_PREFIX    = stringPreferencesKey("ifttt_event_prefix")
        val KEY_IFTTT_ZONE_PREDICTIVE = booleanPreferencesKey("ifttt_zone_predictive")
        fun iftttZoneKey(zone: GlucoseZone) = booleanPreferencesKey("ifttt_zone_${zone.name.lowercase()}")

        // ── Android Auto — gráfico ────────────────────────────────────────────
        // Tema "AUTO" = usa CarContext.isDarkMode (padrão); "DARK"/"LIGHT" = fixo.
        val KEY_AUTO_SHOW_GRAPH  = booleanPreferencesKey("auto_show_graph")
        val KEY_AUTO_GRAPH_HOURS = intPreferencesKey("auto_graph_hours")
        val KEY_AUTO_GRAPH_THEME = stringPreferencesKey("auto_graph_theme")

        // ── Pushover — notificações push ricas (via GluSync) ─────────────────
        val KEY_PUSHOVER_ENABLED  = booleanPreferencesKey("pushover_enabled")
        val KEY_PUSHOVER_USER_KEY = stringPreferencesKey("pushover_user_key")
        // JSON array de nomes de GlucoseZone habilitados para Pushover (ex.: ["URGENT_LOW","LOW"])
        val KEY_PUSHOVER_ZONES_JSON = stringPreferencesKey("pushover_zones_json")

        // ── Avisar seguidores ─────────────────────────────────────────────────
        val KEY_NOTIFY_FOLLOWERS_ENABLED  = booleanPreferencesKey("notify_followers_enabled")
        // JSON array de UUIDs (String) de seguidores específicos; vazio = notifica todos
        val KEY_NOTIFY_FOLLOWERS_IDS_JSON = stringPreferencesKey("notify_followers_ids_json")
    }

    private fun <T> Flow<Preferences>.safeMap(default: T, block: (Preferences) -> T): Flow<T> =
        catch { if (it is IOException) emit(emptyPreferences()) else throw it }
            .map { block(it) }

    // ── Conexão ───────────────────────────────────────────────────────────────
    val baseUrl: Flow<String>      = dataStore.data.safeMap("") { it[KEY_BASE_URL] ?: "" }
    // Cold flow: lê o SharedPreferences cifrado a cada coleta (todo consumidor no app usa
    // .first(), nunca .collect() contínuo, então não precisa observar mudanças ao vivo).
    val accessToken: Flow<String>  = flow { emit(securePrefs.getString(SECURE_KEY_ACCESS_TOKEN, "") ?: "") }
    val refreshToken: Flow<String> = flow { emit(securePrefs.getString(SECURE_KEY_REFRESH_TOKEN, "") ?: "") }
    // 0L = desconhecido (login antigo, de antes desta chave existir) — quem consome trata como "não sei quando expira".
    val accessTokenExpiresAtMs: Flow<Long> = flow { emit(securePrefs.getLong(SECURE_KEY_ACCESS_EXPIRES_AT, 0L)) }
    val pendingRefreshRequestId: Flow<String> = flow { emit(securePrefs.getString(SECURE_KEY_PENDING_REFRESH_REQUEST_ID, "") ?: "") }
    val userEmail: Flow<String>    = dataStore.data.safeMap("") { it[KEY_USER_EMAIL] ?: "" }
    val pendingAppAccessRequestId: Flow<String> =
        dataStore.data.safeMap("") { it[KEY_PENDING_APP_ACCESS_REQUEST_ID] ?: "" }

    // ── Display ───────────────────────────────────────────────────────────────
    val pollingInterval: Flow<Int>    = dataStore.data.safeMap(3)       { it[KEY_POLLING_INTERVAL] ?: 3 }
    val glucoseUnit: Flow<String>     = dataStore.data.safeMap("MG_DL") { it[KEY_GLUCOSE_UNIT] ?: "MG_DL" }
    val themeMode: Flow<String>       = dataStore.data.safeMap("SYSTEM") { it[KEY_THEME_MODE] ?: "SYSTEM" }
    val graphThemeMode: Flow<String>  = dataStore.data.safeMap(GraphTheme.FOLLOW_APP) { it[KEY_GRAPH_THEME] ?: GraphTheme.FOLLOW_APP }
    // Padrão CONFIRMED_ONLY: o valor mostrado/alertado segue a leitura CONSOLIDADA (a que a
    // calibração confirmou, ~5 min), não cada pacote de minuto. Nada deixa de ser gravado — o
    // histórico, o gráfico e a sincronização continuam recebendo todas as leituras.
    // Sem risco de atrasar alarme: um alerta só dispara quando a glicemia MUDA de zona, e mudar
    // de valor é exatamente o que marca uma leitura como confirmada (ver Reading.confirmed).
    val glucoseDisplayMode: Flow<String> = dataStore.data.safeMap("CONFIRMED_ONLY") { it[KEY_GLUCOSE_DISPLAY_MODE] ?: "CONFIRMED_ONLY" }
    val timeFormat: Flow<String>      = dataStore.data.safeMap("H24") { it[KEY_TIME_FORMAT] ?: "H24" }

    // ── Faixas ────────────────────────────────────────────────────────────────
    val urgentLowThreshold: Flow<Float>  = dataStore.data.safeMap(55f)  { it[KEY_THRESHOLD_URGENT_LOW]  ?: 55f }
    val lowThreshold: Flow<Float>        = dataStore.data.safeMap(70f)  { it[KEY_THRESHOLD_LOW]         ?: 70f }
    val highThreshold: Flow<Float>       = dataStore.data.safeMap(180f) { it[KEY_THRESHOLD_HIGH]        ?: 180f }
    val urgentHighThreshold: Flow<Float> = dataStore.data.safeMap(250f) { it[KEY_THRESHOLD_URGENT_HIGH] ?: 250f }

    /** Os 4 limites juntos: a regra única de zona do app (L2, ver [GlucoseThresholds]). */
    val glucoseThresholds: Flow<GlucoseThresholds> =
        combine(urgentLowThreshold, lowThreshold, highThreshold, urgentHighThreshold) { ul, l, h, uh ->
            GlucoseThresholds(ul, l, h, uh)
        }

    // access_token agora mora no securePrefs cifrado (achado #3) — combina com o resto,
    // que continua no DataStore normal.
    val isConfigured: Flow<Boolean> = combine(
        dataStore.data.safeMap(false to "") { prefs ->
            (prefs[KEY_LOCAL_ONLY_MODE] ?: false) to (prefs[KEY_BASE_URL] ?: "")
        },
        accessToken
    ) { (localOnly, baseUrl), token ->
        localOnly || (baseUrl.isNotBlank() && token.isNotBlank())
    }

    // ── Modo 100% local — sem conta/GluSync, tudo no aparelho ───────────────────
    val localOnlyMode: Flow<Boolean> = dataStore.data.safeMap(false) { it[KEY_LOCAL_ONLY_MODE] ?: false }
    suspend fun saveLocalOnlyMode(enabled: Boolean) = dataStore.edit { it[KEY_LOCAL_ONLY_MODE] = enabled }

    /** Tem conta no GluSync (login feito). Diferente de [isConfigured], que também vale para quem
     *  usava o app sem conta antes da ADR-0030 e é o que decide se o serviço do sensor roda. */
    val hasAccount: Flow<Boolean> = combine(dataStore.data.safeMap("") { it[KEY_BASE_URL] ?: "" }, accessToken) { baseUrl, token ->
        baseUrl.isNotBlank() && token.isNotBlank()
    }

    /** Variante escolhida/detectada do sensor (ver SensorVariantCatalog.resolve). */
    val sensorVariantId: Flow<String> = dataStore.data.safeMap("") { it[KEY_SENSOR_VARIANT_ID] ?: "" }
    suspend fun saveSensorVariantId(id: String) = dataStore.edit { it[KEY_SENSOR_VARIANT_ID] = id }

    val accountPromptPostponedAtMs: Flow<Long> = dataStore.data.safeMap(0L) { it[KEY_ACCOUNT_PROMPT_POSTPONED_AT] ?: 0L }
    suspend fun saveAccountPromptPostponedAtMs(ms: Long) = dataStore.edit { it[KEY_ACCOUNT_PROMPT_POSTPONED_AT] = ms }

    // ── ADR-0030 — liberação da nuvem e acompanhamento (pedido "cloud_sync") ─────
    // Estado devolvido pelo GluSync: approved | pending | waitlisted | rejected | revoked | none;
    // "" = ainda não consultado. Quem decide de verdade é o servidor; isto só evita que o app
    // tente enviar (e gaste bateria, dados e servidor) quando já sabe que não pode.
    val cloudSyncState: Flow<String> = dataStore.data.safeMap("") { it[KEY_CLOUD_SYNC_STATE] ?: "" }
    val cloudUploadAllowed: Flow<Boolean> = cloudSyncState.map { it == "approved" }
    val cloudSyncCheckedAtMs: Flow<Long> = dataStore.data.safeMap(0L) { it[KEY_CLOUD_SYNC_CHECKED_AT] ?: 0L }
    suspend fun saveCloudSyncState(state: String, checkedAtMs: Long = System.currentTimeMillis()) = dataStore.edit {
        it[KEY_CLOUD_SYNC_STATE] = state
        it[KEY_CLOUD_SYNC_CHECKED_AT] = checkedAtMs
    }

    // ── Diário e lembretes (plano 17) ─────────────────────────────────────────
    /** O GluSync recusou o diário (sem liberação): não tenta de novo antes disto. */
    val diaryCloudBlockedUntil: Flow<Long> = dataStore.data.safeMap(0L) { it[KEY_DIARY_CLOUD_BLOCKED_UNTIL] ?: 0L }
    suspend fun saveDiaryCloudBlockedUntil(ms: Long) = dataStore.edit { it[KEY_DIARY_CLOUD_BLOCKED_UNTIL] = ms }
    val diaryLastPullMs: Flow<Long> = dataStore.data.safeMap(0L) { it[KEY_DIARY_LAST_PULL_MS] ?: 0L }
    suspend fun saveDiaryLastPullMs(ms: Long) = dataStore.edit { it[KEY_DIARY_LAST_PULL_MS] = ms }
    val diaryLastSyncMs: Flow<Long> = dataStore.data.safeMap(0L) { it[KEY_DIARY_LAST_SYNC_MS] ?: 0L }
    suspend fun saveDiaryLastSyncMs(ms: Long) = dataStore.edit { it[KEY_DIARY_LAST_SYNC_MS] = ms }
    /** Aviso "subindo rápido sem refeição registrada" (F2.4) — desligado por padrão. */
    val smartMealPrompt: Flow<Boolean> = dataStore.data.safeMap(false) { it[KEY_SMART_MEAL_PROMPT] ?: false }
    suspend fun saveSmartMealPrompt(enabled: Boolean) = dataStore.edit { it[KEY_SMART_MEAL_PROMPT] = enabled }
    val smartMealPromptLastAt: Flow<Long> = dataStore.data.safeMap(0L) { it[KEY_SMART_MEAL_PROMPT_LAST_AT] ?: 0L }
    suspend fun saveSmartMealPromptLastAt(ms: Long) = dataStore.edit { it[KEY_SMART_MEAL_PROMPT_LAST_AT] = ms }
    /** Botão "Registrar no diário" na notificação fixa (pedido do Felipe, 10/11/2026) — ligado por padrão. */
    val notifQuickAdd: Flow<Boolean> = dataStore.data.safeMap(true) { it[KEY_NOTIF_QUICK_ADD] ?: true }
    suspend fun saveNotifQuickAdd(enabled: Boolean) = dataStore.edit { it[KEY_NOTIF_QUICK_ADD] = enabled }

    // ── Idioma ────────────────────────────────────────────────────────────────
    // L21: sem escolha salva, segue o aparelho — inglês se o celular estiver em inglês, senão português.
    val appLanguage: Flow<String> = dataStore.data.safeMap(deviceLanguage()) { it[KEY_APP_LANGUAGE] ?: deviceLanguage() }
    suspend fun saveAppLanguage(lang: String) = dataStore.edit { it[KEY_APP_LANGUAGE] = lang }

    // ── S13 — Alert toggles ───────────────────────────────────────────────────
    suspend fun isAlertEnabled(zone: GlucoseZone, type: AlertType): Boolean =
        dataStore.data.first()[alertKey(zone, type)] ?: defaultAlertEnabled(zone, type)

    suspend fun setAlertEnabled(zone: GlucoseZone, type: AlertType, enabled: Boolean) =
        dataStore.edit { it[alertKey(zone, type)] = enabled }

    // Flow para a tela de config — todos os toggles de uma vez
    val alertToggles: Flow<Map<Pair<GlucoseZone, AlertType>, Boolean>> =
        dataStore.data.safeMap(emptyMap()) { prefs ->
            val alertZones = listOf(
                GlucoseZone.URGENT_LOW, GlucoseZone.LOW, GlucoseZone.HIGH,
                GlucoseZone.URGENT_HIGH, GlucoseZone.STALE, GlucoseZone.QUICK_DROP
            )
            buildMap {
                for (zone in alertZones) {
                    for (type in AlertType.entries) {
                        put(zone to type, prefs[alertKey(zone, type)] ?: defaultAlertEnabled(zone, type))
                    }
                }
            }
        }

    // ── S14 — Preditivo + re-alarme ───────────────────────────────────────────
    val predictiveEnabled: Flow<Boolean> = dataStore.data.safeMap(true)  { it[KEY_PREDICTIVE_ENABLED] ?: true }
    val predictiveMinutes: Flow<Int>     = dataStore.data.safeMap(15)    { it[KEY_PREDICTIVE_MINUTES] ?: 15 }
    val reAlarmEnabled: Flow<Boolean>    = dataStore.data.safeMap(false) { it[KEY_REALARM_ENABLED] ?: false }
    val reAlarmInterval: Flow<Int>       = dataStore.data.safeMap(5)     { it[KEY_REALARM_INTERVAL] ?: 5 }

    // ── S15 — TTS + silêncio noturno ──────────────────────────────────────────
    val ttsEnabled: Flow<Boolean>         = dataStore.data.safeMap(false) { it[KEY_TTS_ENABLED] ?: false }
    val quietPeriodEnabled: Flow<Boolean> = dataStore.data.safeMap(false) { it[KEY_QUIET_PERIOD_ENABLED] ?: false }
    val quietStartHour: Flow<Int>         = dataStore.data.safeMap(23)   { it[KEY_QUIET_START_HOUR] ?: 23 }
    val quietEndHour: Flow<Int>           = dataStore.data.safeMap(7)    { it[KEY_QUIET_END_HOUR] ?: 7 }

    // ── S16 — Alerta por ligação ──────────────────────────────────────────────
    val callAlertEnabled: Flow<Boolean>   = dataStore.data.safeMap(false) { it[KEY_CALL_ALERT_ENABLED] ?: false }
    val callAlertPhone: Flow<String>      = dataStore.data.safeMap("") { it[KEY_CALL_ALERT_PHONE] ?: "" }
    val callAlertDelay: Flow<Int>         = dataStore.data.safeMap(5)    { it[KEY_CALL_ALERT_DELAY] ?: 5 }

    // ── S17 — SMS emergência ──────────────────────────────────────────────────
    val smsAlertEnabled: Flow<Boolean>    = dataStore.data.safeMap(false) { it[KEY_SMS_ALERT_ENABLED] ?: false }
    val smsContact: Flow<String>          = dataStore.data.safeMap("") { it[KEY_SMS_CONTACT] ?: "" }
    val smsMessage: Flow<String>          = dataStore.data.safeMap("") { it[KEY_SMS_MESSAGE] ?: "" }

    // ── S29 — Retenção de dados ───────────────────────────────────────────────
    // Default 100 (não 30): é o que sempre foi aplicado de fato (GluSyncService tinha o valor
    // hardcoded) enquanto essa preferência não era lida em lugar nenhum — subir o default evita
    // que instalações existentes "herdem" uma redução de 100→30 e caiam no período de
    // arrependimento assim que a feature for pro ar.
    val dataRetentionDays: Flow<Int> = dataStore.data.safeMap(100) { it[KEY_DATA_RETENTION_DAYS] ?: 100 }
    val pendingRetentionDays: Flow<Int> = dataStore.data.safeMap(0) { it[KEY_PENDING_RETENTION_DAYS] ?: 0 }
    val pendingRetentionChangeAtMs: Flow<Long> = dataStore.data.safeMap(0L) { it[KEY_PENDING_RETENTION_AT_MS] ?: 0L }

    // ── S35 — Onboarding ─────────────────────────────────────────────────────
    val onboardingDone: Flow<Boolean>     = dataStore.data.safeMap(false) { it[KEY_ONBOARDING_DONE] ?: false }

    // ── F7 — Termos de uso ────────────────────────────────────────────────────
    /** Versão dos termos já aceita pelo usuário (0 = nunca aceitou). */
    val termsAcceptedVersion: Flow<Int>   = dataStore.data.safeMap(0) { it[KEY_TERMS_VERSION]        ?: 0 }
    val termsAcceptedAt: Flow<Long>       = dataStore.data.safeMap(0L) { it[KEY_TERMS_ACCEPTED_AT]    ?: 0L }
    val termsAcceptedEmail: Flow<String>  = dataStore.data.safeMap("") { it[KEY_TERMS_ACCEPTED_EMAIL] ?: "" }
    val termsRecordedVersion: Flow<Int>   = dataStore.data.safeMap(0) { it[KEY_TERMS_RECORDED_VERSION] ?: 0 }
    suspend fun saveTermsRecordedVersion(version: Int) = dataStore.edit { it[KEY_TERMS_RECORDED_VERSION] = version }

    // ── Gráficos ──────────────────────────────────────────────────────────────
    val homeGraphHours:   Flow<Int> = dataStore.data.safeMap(3) { it[KEY_HOME_GRAPH_HOURS]   ?: 3 }
    val widgetGraphHours: Flow<Int> = dataStore.data.safeMap(3) { it[KEY_WIDGET_GRAPH_HOURS] ?: 3 }
    val notifGraphHours:  Flow<Int> = dataStore.data.safeMap(3) { it[KEY_NOTIF_GRAPH_HOURS]  ?: 3 }

    // ── Diagnóstico ───────────────────────────────────────────────────────────
    val logsEnabled: Flow<Boolean>        = dataStore.data.safeMap(false) { it[KEY_LOGS_ENABLED] ?: false }

    // ── T85 — Passkeys (beta) ─────────────────────────────────────────────────
    val passkeysEnabled: Flow<Boolean> = dataStore.data.safeMap(false) { it[KEY_PASSKEYS_ENABLED] ?: false }
    val apiKeysEnabled: Flow<Boolean>  = dataStore.data.safeMap(false) { it[KEY_API_KEYS_ENABLED]  ?: false }

    // ── Background ────────────────────────────────────────────────────────────
    val batteryOptAsked: Flow<Boolean>    = dataStore.data.safeMap(false) { it[KEY_BATTERY_OPT_ASKED] ?: false }

    // ── F6 — Acompanhamento: ids de convite já notificados localmente ──────────
    val notifiedInvitesReceived: Flow<Set<String>> = dataStore.data.safeMap(emptySet()) {
        (it[KEY_NOTIFIED_INVITES_RECEIVED] ?: "").split(',').filter(String::isNotBlank).toSet()
    }
    val notifiedInvitesAccepted: Flow<Set<String>> = dataStore.data.safeMap(emptySet()) {
        (it[KEY_NOTIFIED_INVITES_ACCEPTED] ?: "").split(',').filter(String::isNotBlank).toSet()
    }

    // ── Modo Seguidor ─────────────────────────────────────────────────────────
    val myRoles: Flow<Set<String>> = dataStore.data.safeMap(emptySet()) {
        (it[KEY_MY_ROLES] ?: "").split(',').filter(String::isNotBlank).toSet()
    }
    /** true só quando a conta tem papel de seguidor e NÃO tem papel de paciente. */
    val isFollowerOnly: Flow<Boolean> = myRoles.map { it.isNotEmpty() && "patient" !in it }
    val activeFollowedPatientId: Flow<String>   = dataStore.data.safeMap("") { it[KEY_ACTIVE_FOLLOWED_PATIENT_ID]   ?: "" }
    val activeFollowedPatientName: Flow<String> = dataStore.data.safeMap("") { it[KEY_ACTIVE_FOLLOWED_PATIENT_NAME] ?: "" }

    // ── Fase 3 — Fonte de dados + saúde do sensor ──────────────────────────────
    val dataSource: Flow<String>          = dataStore.data.safeMap("GLUSYNC") { it[KEY_DATA_SOURCE] ?: "GLUSYNC" }
    val sensorWarnEnabled: Flow<Boolean>  = dataStore.data.safeMap(true)  { it[KEY_SENSOR_WARN_ENABLED] ?: true }
    // Vida nominal do Sibionics ~14 dias; aviso informativo, NUNCA corta leitura
    val sensorAgingDays: Flow<Int>        = dataStore.data.safeMap(14)    { it[KEY_SENSOR_AGING_DAYS] ?: 14 }
    val sensorNoSignalMin: Flow<Int>      = dataStore.data.safeMap(20)    { it[KEY_SENSOR_NOSIGNAL_MIN] ?: 20 }
    val sibionicsMac: Flow<String>          = dataStore.data.safeMap("") { it[KEY_SIBIONICS_MAC]         ?: "" }
    val sibionicsSerial: Flow<String>       = dataStore.data.safeMap("") { it[KEY_SIBIONICS_SERIAL]      ?: "" }
    val sibionicsCustomerId: Flow<String>   = dataStore.data.safeMap("") { it[KEY_SIBIONICS_CUSTOMER_ID] ?: "" }
    /** Fator de calibração (mmol_exibida = mmol_algoritmo × fator). 1.0 = valor de fábrica. */
    val sibionicsCalFactor: Flow<Double>    = dataStore.data.safeMap(1.0) { it[KEY_SIBIONICS_CAL_FACTOR]  ?: 1.0 }
    val sibionicsAlgoCtx: Flow<String>      = dataStore.data.safeMap("") { it[KEY_SIBIONICS_ALGO_CTX]     ?: "" }
    val sibionicsAlgoIdx: Flow<Int>         = dataStore.data.safeMap(-1) { it[KEY_SIBIONICS_ALGO_IDX]     ?: -1 }
    val sibionicsAlgoCode: Flow<String>     = dataStore.data.safeMap("") { it[KEY_SIBIONICS_ALGO_CODE]    ?: "" }
    val sibionicsSensorStartMs: Flow<Long>  = dataStore.data.safeMap(0L) { it[KEY_SIBIONICS_SENSOR_START] ?: 0L }
    val sibionicsBleName: Flow<String>      = dataStore.data.safeMap("") { it[KEY_SIBIONICS_BLE_NAME]     ?: "" }
    // FreeStyle Libre 2 Plus
    val libre2Mac: Flow<String>             = dataStore.data.safeMap("") { it[KEY_LIBRE2_MAC]          ?: "" }
    val libre2Serial: Flow<String>          = dataStore.data.safeMap("") { it[KEY_LIBRE2_SERIAL]       ?: "" }
    val libre2Uid: Flow<String>             = dataStore.data.safeMap("") { it[KEY_LIBRE2_UID]          ?: "" }
    val libre2PatchInfo: Flow<String>       = dataStore.data.safeMap("") { it[KEY_LIBRE2_PATCH_INFO]   ?: "" }
    val libre2SessionKey: Flow<String>      = dataStore.data.safeMap("") { it[KEY_LIBRE2_SESSION_KEY]  ?: "" }
    val libre2StreamingValid: Flow<Boolean> = dataStore.data.safeMap(false) { it[KEY_LIBRE2_STREAMING_VALID] ?: false }
    val libre2UnlockCount: Flow<Int>        = dataStore.data.safeMap(0)  { it[KEY_LIBRE2_UNLOCK_COUNT] ?: 0 }
    val libre2Calibration: Flow<String>     = dataStore.data.safeMap("") { it[KEY_LIBRE2_CALIBRATION]  ?: "" }
    val libre2Fram: Flow<String>            = dataStore.data.safeMap("") { it[KEY_LIBRE2_FRAM]         ?: "" }
    val libre2SensorStartMs: Flow<Long>     = dataStore.data.safeMap(0L) { it[KEY_LIBRE2_SENSOR_START] ?: 0L }
    /** Fator de calibração Libre 2 (mmol_exibida = mmol_algoritmo × fator). 1.0 = valor de fábrica. */
    val libre2CalFactor: Flow<Double>       = dataStore.data.safeMap(1.0) { it[KEY_LIBRE2_CAL_FACTOR]  ?: 1.0 }

    val accuChekMac: Flow<String>          = dataStore.data.safeMap("") { it[KEY_ACCUCHEK_MAC]           ?: "" }
    val accuChekSerial: Flow<String>       = dataStore.data.safeMap("") { it[KEY_ACCUCHEK_SERIAL]        ?: "" }
    val accuChekPin: Flow<String>          = dataStore.data.safeMap("") { it[KEY_ACCUCHEK_PIN]           ?: "" }
    /** -1 = nenhuma leitura salva ainda (pede todo o histórico guardado no sensor). */
    val accuChekLastMinute: Flow<Int>      = dataStore.data.safeMap(-1) { it[KEY_ACCUCHEK_LAST_MINUTE]   ?: -1 }
    val accuChekStartMs: Flow<Long>        = dataStore.data.safeMap(0L) { it[KEY_ACCUCHEK_START_MS]      ?: 0L }

    val medLevensohnSerial: Flow<String>     = dataStore.data.safeMap("") { it[KEY_MEDLEVENSOHN_SERIAL]      ?: "" }
    val medLevensohnMac: Flow<String>        = dataStore.data.safeMap("") { it[KEY_MEDLEVENSOHN_MAC]         ?: "" }
    val medLevensohnMasterKey: Flow<String>  = dataStore.data.safeMap("") { it[KEY_MEDLEVENSOHN_MASTER_KEY]  ?: "" }
    val medLevensohnStartSec: Flow<Long>     = dataStore.data.safeMap(0L) { it[KEY_MEDLEVENSOHN_START_SEC]   ?: 0L }
    val medLevensohnLastId: Flow<Int>        = dataStore.data.safeMap(-1) { it[KEY_MEDLEVENSOHN_LAST_ID]     ?: -1 }
    val medLevensohnHasTime: Flow<Boolean>   = dataStore.data.safeMap(false) { it[KEY_MEDLEVENSOHN_HAS_TIME] ?: false }

    val dexcomG6TransmitterId: Flow<String> = dataStore.data.safeMap("") { it[KEY_DEXCOM_G6_TRANSMITTER_ID] ?: "" }
    val dexcomG6Mac: Flow<String>           = dataStore.data.safeMap("") { it[KEY_DEXCOM_G6_MAC]            ?: "" }
    // F8 — Mi Band: MAC e auth key (hex de 32 chars) para o envio do visor via BLE Huami
    val miBandMac: Flow<String>             = dataStore.data.safeMap("") { it[KEY_MIBAND_MAC]      ?: "" }
    val miBandAuthKey: Flow<String>         = dataStore.data.safeMap("") { it[KEY_MIBAND_AUTH_KEY] ?: "" }
    val miBandAutoSend: Flow<Boolean>       = dataStore.data.safeMap(false) { it[KEY_MIBAND_AUTO_SEND] ?: false }
    // Agendamento: dia = intervalo curto (padrão 5 min); noite = intervalo mais espaçado
    // (padrão 30 min, das 22h às 7h) — configurável pelo usuário na tela do Mi Band.
    val miBandDayIntervalMin: Flow<Int>     = dataStore.data.safeMap(5)    { it[KEY_MIBAND_DAY_INTERVAL_MIN]   ?: 5 }
    val miBandNightIntervalMin: Flow<Int>   = dataStore.data.safeMap(30)   { it[KEY_MIBAND_NIGHT_INTERVAL_MIN] ?: 30 }
    // Minutos desde 00:00 — default 22:00 (1320) até 07:00 (420)
    val miBandNightStartMin: Flow<Int>      = dataStore.data.safeMap(1320) { it[KEY_MIBAND_NIGHT_START_MIN]    ?: 1320 }
    val miBandNightEndMin: Flow<Int>        = dataStore.data.safeMap(420)  { it[KEY_MIBAND_NIGHT_END_MIN]      ?: 420 }
    // Ignora o intervalo dia/noite acima e reenvia o visor a cada leitura nova (~1/min).
    val miBandUpdateEveryReading: Flow<Boolean> = dataStore.data.safeMap(false) { it[KEY_MIBAND_UPDATE_EVERY_READING] ?: false }
    val wsLastUploadedMs: Flow<Long>        = dataStore.data.safeMap(0L) { it[KEY_WS_LAST_UPLOADED_MS] ?: 0L }
    val historyBackfillDoneEmails: Flow<Set<String>> = dataStore.data.safeMap(emptySet()) {
        (it[KEY_HISTORY_BACKFILL_DONE_EMAILS] ?: "").split(',').filter(String::isNotBlank).toSet()
    }
    /** Permite enviar leituras ao GluSync usando dados móveis (default: permite). */
    val uploadOverMobileData: Flow<Boolean> = dataStore.data.safeMap(true) { it[KEY_UPLOAD_OVER_MOBILE_DATA] ?: true }
    /** Intervalo (min) de reenvio em lote enquanto em dados móveis. */
    val uploadMobileIntervalMin: Flow<Int>  = dataStore.data.safeMap(15)   { it[KEY_UPLOAD_MOBILE_INTERVAL_MIN] ?: 15 }

    // ── Android Auto — gráfico ────────────────────────────────────────────────
    val autoShowGraph:  Flow<Boolean> = dataStore.data.safeMap(false)   { it[KEY_AUTO_SHOW_GRAPH]  ?: false }
    val autoGraphHours: Flow<Int>     = dataStore.data.safeMap(3)       { it[KEY_AUTO_GRAPH_HOURS] ?: 3 }
    val autoGraphTheme: Flow<String>  = dataStore.data.safeMap("AUTO")  { it[KEY_AUTO_GRAPH_THEME] ?: "AUTO" }

    // ── IFTTT Webhooks ────────────────────────────────────────────────────────
    val iftttEnabled: Flow<Boolean>        = dataStore.data.safeMap(false)       { it[KEY_IFTTT_ENABLED]      ?: false }
    val iftttApiKey: Flow<String>          = dataStore.data.safeMap("")           { it[KEY_IFTTT_API_KEY]      ?: "" }
    val iftttEventPrefix: Flow<String>     = dataStore.data.safeMap("gluview_")  { it[KEY_IFTTT_EVENT_PREFIX] ?: "gluview_" }
    val iftttPredictiveEnabled: Flow<Boolean> = dataStore.data.safeMap(false)    { it[KEY_IFTTT_ZONE_PREDICTIVE] ?: false }
    fun iftttZoneEnabled(zone: GlucoseZone): Flow<Boolean> =
        dataStore.data.safeMap(false) { it[iftttZoneKey(zone)] ?: false }
    val iftttZoneToggles: Flow<Map<GlucoseZone, Boolean>> = dataStore.data.safeMap(emptyMap()) { prefs ->
        val zones = listOf(GlucoseZone.URGENT_LOW, GlucoseZone.LOW, GlucoseZone.HIGH,
                           GlucoseZone.URGENT_HIGH, GlucoseZone.STALE, GlucoseZone.QUICK_DROP)
        buildMap { zones.forEach { put(it, prefs[iftttZoneKey(it)] ?: false) } }
    }

    // ── Pushover ──────────────────────────────────────────────────────────────
    val pushoverEnabled: Flow<Boolean>   = dataStore.data.safeMap(false) { it[KEY_PUSHOVER_ENABLED]  ?: false }
    val pushoverUserKey: Flow<String>    = dataStore.data.safeMap("")    { it[KEY_PUSHOVER_USER_KEY] ?: "" }
    val pushoverZonesJson: Flow<String>  = dataStore.data.safeMap("[]")  { it[KEY_PUSHOVER_ZONES_JSON] ?: "[]" }

    // ── Avisar seguidores ─────────────────────────────────────────────────────
    val notifyFollowersEnabled: Flow<Boolean> = dataStore.data.safeMap(false) { it[KEY_NOTIFY_FOLLOWERS_ENABLED]  ?: false }
    val notifyFollowersIdsJson: Flow<String>  = dataStore.data.safeMap("[]")  { it[KEY_NOTIFY_FOLLOWERS_IDS_JSON] ?: "[]" }

    // ── Escritas ──────────────────────────────────────────────────────────────
    suspend fun saveBaseUrl(url: String) = dataStore.edit { it[KEY_BASE_URL] = url.trimEnd('/') }

    suspend fun saveTokens(accessToken: String, refreshToken: String, accessExpiresAtMs: Long) = withContext(Dispatchers.IO) {
        securePrefs.edit()
            .putString(SECURE_KEY_ACCESS_TOKEN, accessToken)
            .putString(SECURE_KEY_REFRESH_TOKEN, refreshToken)
            .putLong(SECURE_KEY_ACCESS_EXPIRES_AT, accessExpiresAtMs)
            // Novo refresh token salvo — o próximo ciclo de renovação começa um idempotency key do zero.
            .remove(SECURE_KEY_PENDING_REFRESH_REQUEST_ID)
            .commit()
    }

    /** Reusa o id pendente (retry do refresh token atual) ou gera e salva um novo. */
    suspend fun getOrCreatePendingRefreshRequestId(): String = withContext(Dispatchers.IO) {
        securePrefs.getString(SECURE_KEY_PENDING_REFRESH_REQUEST_ID, "")?.takeIf { it.isNotBlank() }
            ?: java.util.UUID.randomUUID().toString().also {
                securePrefs.edit().putString(SECURE_KEY_PENDING_REFRESH_REQUEST_ID, it).commit()
            }
    }

    suspend fun saveUserEmail(email: String) = dataStore.edit { it[KEY_USER_EMAIL] = email }
    suspend fun savePendingAppAccessRequestId(id: String) = dataStore.edit { it[KEY_PENDING_APP_ACCESS_REQUEST_ID] = id }
    suspend fun clearPendingAppAccessRequestId() = dataStore.edit { it.remove(KEY_PENDING_APP_ACCESS_REQUEST_ID) }
    suspend fun savePollingInterval(minutes: Int) = dataStore.edit {
        it[KEY_POLLING_INTERVAL] = minutes.coerceIn(1, 15)
    }
    suspend fun saveGlucoseUnit(unit: String) = dataStore.edit { it[KEY_GLUCOSE_UNIT] = unit }
    suspend fun saveThemeMode(mode: String)   = dataStore.edit { it[KEY_THEME_MODE] = mode }
    suspend fun saveGraphThemeMode(mode: String) = dataStore.edit { it[KEY_GRAPH_THEME] = mode }
    suspend fun saveGlucoseDisplayMode(mode: String) = dataStore.edit { it[KEY_GLUCOSE_DISPLAY_MODE] = mode }
    suspend fun saveTimeFormat(format: String) = dataStore.edit { it[KEY_TIME_FORMAT] = format }

    suspend fun saveThresholds(urgentLow: Float, low: Float, high: Float, urgentHigh: Float) =
        dataStore.edit {
            it[KEY_THRESHOLD_URGENT_LOW]  = urgentLow
            it[KEY_THRESHOLD_LOW]         = low
            it[KEY_THRESHOLD_HIGH]        = high
            it[KEY_THRESHOLD_URGENT_HIGH] = urgentHigh
        }

    suspend fun savePredictive(enabled: Boolean, minutes: Int) = dataStore.edit {
        it[KEY_PREDICTIVE_ENABLED] = enabled
        it[KEY_PREDICTIVE_MINUTES] = minutes
    }

    suspend fun saveReAlarm(enabled: Boolean, intervalMinutes: Int) = dataStore.edit {
        it[KEY_REALARM_ENABLED]  = enabled
        it[KEY_REALARM_INTERVAL] = intervalMinutes
    }

    suspend fun saveTts(enabled: Boolean) = dataStore.edit { it[KEY_TTS_ENABLED] = enabled }

    suspend fun saveQuietPeriod(enabled: Boolean, startHour: Int, endHour: Int) = dataStore.edit {
        it[KEY_QUIET_PERIOD_ENABLED] = enabled
        it[KEY_QUIET_START_HOUR]     = startHour
        it[KEY_QUIET_END_HOUR]       = endHour
    }

    suspend fun saveCallAlert(enabled: Boolean, phone: String, delayMinutes: Int) = dataStore.edit {
        it[KEY_CALL_ALERT_ENABLED] = enabled
        it[KEY_CALL_ALERT_PHONE]   = phone
        it[KEY_CALL_ALERT_DELAY]   = delayMinutes
    }

    suspend fun saveSmsAlert(enabled: Boolean, contact: String, message: String) = dataStore.edit {
        it[KEY_SMS_ALERT_ENABLED] = enabled
        it[KEY_SMS_CONTACT]       = contact
        it[KEY_SMS_MESSAGE]       = message
    }

    suspend fun saveDataRetentionDays(days: Int) = dataStore.edit { it[KEY_DATA_RETENTION_DAYS] = days }

    // Início (ou reinício) do período de arrependimento de 7 dias — sobrescreve qualquer
    // pendência anterior (o relógio sempre reinicia na última escolha do usuário).
    suspend fun scheduleRetentionReduction(days: Int) = dataStore.edit {
        it[KEY_PENDING_RETENTION_DAYS]  = days
        it[KEY_PENDING_RETENTION_AT_MS] = System.currentTimeMillis()
    }

    suspend fun cancelPendingRetention() = dataStore.edit {
        it.remove(KEY_PENDING_RETENTION_DAYS)
        it.remove(KEY_PENDING_RETENTION_AT_MS)
    }

    // Só deve ser chamado depois que os 7 dias já passaram (ver GluSyncService.maybeCleanup).
    suspend fun commitPendingRetention(days: Int) = dataStore.edit {
        it[KEY_DATA_RETENTION_DAYS] = days
        it.remove(KEY_PENDING_RETENTION_DAYS)
        it.remove(KEY_PENDING_RETENTION_AT_MS)
    }

    suspend fun saveOnboardingDone()             = dataStore.edit { it[KEY_ONBOARDING_DONE] = true }

    /** F7/T56 — registra o aceite dos termos: versão + data + usuário. */
    suspend fun saveTermsAccepted(version: Int, email: String) = dataStore.edit {
        it[KEY_TERMS_VERSION]        = version
        it[KEY_TERMS_ACCEPTED_AT]    = System.currentTimeMillis()
        it[KEY_TERMS_ACCEPTED_EMAIL] = email
    }
    suspend fun saveHomeGraphHours(hours: Int)   = dataStore.edit { it[KEY_HOME_GRAPH_HOURS]   = hours }
    suspend fun saveWidgetGraphHours(hours: Int) = dataStore.edit { it[KEY_WIDGET_GRAPH_HOURS] = hours }
    suspend fun saveNotifGraphHours(hours: Int)  = dataStore.edit { it[KEY_NOTIF_GRAPH_HOURS]  = hours }
    suspend fun saveLogsEnabled(enabled: Boolean) = dataStore.edit { it[KEY_LOGS_ENABLED] = enabled }

    suspend fun savePasskeysEnabled(enabled: Boolean) = dataStore.edit { it[KEY_PASSKEYS_ENABLED] = enabled }
    suspend fun saveApiKeysEnabled(enabled: Boolean)  = dataStore.edit { it[KEY_API_KEYS_ENABLED]  = enabled }
    suspend fun saveBatteryOptAsked()             = dataStore.edit { it[KEY_BATTERY_OPT_ASKED] = true }

    /** Marca um vínculo como já notificado (evita repetir a notificação a cada poll). */
    suspend fun markInviteReceivedNotified(linkId: String) = dataStore.edit {
        val cur = (it[KEY_NOTIFIED_INVITES_RECEIVED] ?: "").split(',').filter(String::isNotBlank).toMutableSet()
        cur += linkId
        it[KEY_NOTIFIED_INVITES_RECEIVED] = cur.joinToString(",")
    }
    suspend fun markInviteAcceptedNotified(linkId: String) = dataStore.edit {
        val cur = (it[KEY_NOTIFIED_INVITES_ACCEPTED] ?: "").split(',').filter(String::isNotBlank).toMutableSet()
        cur += linkId
        it[KEY_NOTIFIED_INVITES_ACCEPTED] = cur.joinToString(",")
    }

    // ── Modo Seguidor ─────────────────────────────────────────────────────────
    suspend fun saveMyRoles(roles: List<String>) = dataStore.edit { it[KEY_MY_ROLES] = roles.joinToString(",") }

    suspend fun saveActiveFollowedPatient(id: String, name: String) = dataStore.edit {
        it[KEY_ACTIVE_FOLLOWED_PATIENT_ID]   = id
        it[KEY_ACTIVE_FOLLOWED_PATIENT_NAME] = name
    }

    suspend fun clearActiveFollowedPatient() = dataStore.edit {
        it.remove(KEY_ACTIVE_FOLLOWED_PATIENT_ID)
        it.remove(KEY_ACTIVE_FOLLOWED_PATIENT_NAME)
    }

    // ── Fase 3 — Fonte de dados + saúde do sensor ──────────────────────────────
    suspend fun saveDataSource(id: String) = dataStore.edit { it[KEY_DATA_SOURCE] = id }
    suspend fun saveSensorWarn(enabled: Boolean, agingDays: Int, noSignalMin: Int) = dataStore.edit {
        it[KEY_SENSOR_WARN_ENABLED] = enabled
        it[KEY_SENSOR_AGING_DAYS]   = agingDays.coerceIn(1, 60)
        it[KEY_SENSOR_NOSIGNAL_MIN] = noSignalMin.coerceIn(5, 120)
    }
    suspend fun saveSibionicsSensor(mac: String, serial: String, customerId: String = "") = dataStore.edit {
        it[KEY_SIBIONICS_MAC]         = mac
        it[KEY_SIBIONICS_SERIAL]      = serial
        it[KEY_SIBIONICS_CUSTOMER_ID] = customerId
    }

    /**
     * Salva o estado do algoritmo JNI (JSON + último índice + código do sensor) após cada índice,
     * para manter o filtro quente entre reinícios do processo. O código permite restaurar só
     * quando for o MESMO sensor.
     */
    suspend fun saveWsLastUploadedMs(ms: Long) = dataStore.edit { it[KEY_WS_LAST_UPLOADED_MS] = ms }

    val alertSilencedUntilMs: Flow<Long> = dataStore.data.safeMap(0L) { it[KEY_ALERT_SILENCED_UNTIL_MS] ?: 0L }
    suspend fun saveAlertSilencedUntilMs(ms: Long) = dataStore.edit { it[KEY_ALERT_SILENCED_UNTIL_MS] = ms }

    /** Marca que o backfill único do histórico local já subiu pra essa conta (não repete em logins futuros). */
    suspend fun markHistoryBackfillDone(email: String) = dataStore.edit {
        val cur = (it[KEY_HISTORY_BACKFILL_DONE_EMAILS] ?: "").split(',').filter(String::isNotBlank).toMutableSet()
        cur += email
        it[KEY_HISTORY_BACKFILL_DONE_EMAILS] = cur.joinToString(",")
    }

    suspend fun saveUploadNetworkPolicy(overMobileData: Boolean, mobileIntervalMin: Int) = dataStore.edit {
        it[KEY_UPLOAD_OVER_MOBILE_DATA]    = overMobileData
        it[KEY_UPLOAD_MOBILE_INTERVAL_MIN] = mobileIntervalMin.coerceIn(1, 180)
    }

    // F8 — pareamento da Mi Band para envio do visor
    suspend fun saveMiBandPairing(mac: String, authKey: String) = dataStore.edit {
        it[KEY_MIBAND_MAC]      = mac.trim()
        it[KEY_MIBAND_AUTH_KEY] = authKey.trim().lowercase().removePrefix("0x")
    }

    suspend fun saveMiBandAutoSend(enabled: Boolean) = dataStore.edit {
        it[KEY_MIBAND_AUTO_SEND] = enabled
    }

    suspend fun saveMiBandUpdateEveryReading(enabled: Boolean) = dataStore.edit {
        it[KEY_MIBAND_UPDATE_EVERY_READING] = enabled
    }

    suspend fun saveMiBandSchedule(dayIntervalMin: Int, nightIntervalMin: Int, nightStartMin: Int, nightEndMin: Int) = dataStore.edit {
        it[KEY_MIBAND_DAY_INTERVAL_MIN]   = dayIntervalMin.coerceIn(1, 180)
        it[KEY_MIBAND_NIGHT_INTERVAL_MIN] = nightIntervalMin.coerceIn(1, 180)
        it[KEY_MIBAND_NIGHT_START_MIN]    = nightStartMin.coerceIn(0, 1439)
        it[KEY_MIBAND_NIGHT_END_MIN]      = nightEndMin.coerceIn(0, 1439)
    }

    suspend fun saveAlgoContext(code: String, json: String, index: Int) = dataStore.edit {
        it[KEY_SIBIONICS_ALGO_CODE] = code
        it[KEY_SIBIONICS_ALGO_CTX]  = json
        it[KEY_SIBIONICS_ALGO_IDX]  = index
    }

    /** Descarta o estado do algoritmo (força aquecimento limpo na próxima conexão). */
    suspend fun clearAlgoContext() = dataStore.edit {
        it.remove(KEY_SIBIONICS_ALGO_CODE)
        it.remove(KEY_SIBIONICS_ALGO_CTX)
        it.remove(KEY_SIBIONICS_ALGO_IDX)
    }

    /** Persiste a âncora de início do sensor assim que ela é calculada pela 1ª vez na sessão. */
    suspend fun saveSibionicsSensorStart(startMs: Long) = dataStore.edit { it[KEY_SIBIONICS_SENSOR_START] = startMs }

    /** Zera a âncora de início persistida — só quando o sensor físico realmente mudou. */
    suspend fun clearSibionicsSensorStart() = dataStore.edit { it.remove(KEY_SIBIONICS_SENSOR_START) }

    /** Memoriza o nome anunciado do sensor (identidade verificável do rádio). */
    suspend fun saveSibionicsBleName(name: String) = dataStore.edit { it[KEY_SIBIONICS_BLE_NAME] = name }

    /** Troca o endereço do sensor mantendo código/serial — usado quando a busca por identidade
     *  encontra o mesmo sensor num endereço diferente do memorizado. */
    suspend fun saveSibionicsMac(mac: String) = dataStore.edit { it[KEY_SIBIONICS_MAC] = mac }

    /** Esquece o sensor BLE configurado (MAC + serial + código + calibração + contexto algo). */
    suspend fun clearSibionicsSensor() = dataStore.edit {
        it.remove(KEY_SIBIONICS_MAC)
        it.remove(KEY_SIBIONICS_SERIAL)
        it.remove(KEY_SIBIONICS_CUSTOMER_ID)
        it.remove(KEY_SIBIONICS_CAL_FACTOR)
        it.remove(KEY_SIBIONICS_ALGO_CTX)
        it.remove(KEY_SIBIONICS_ALGO_IDX)
        it.remove(KEY_SIBIONICS_ALGO_CODE)
        it.remove(KEY_SIBIONICS_SENSOR_START)
        it.remove(KEY_SIBIONICS_BLE_NAME)
    }

    // ── FreeStyle Libre 2 Plus ─────────────────────────────────────────────────

    /**
     * Salva a sessão lida na ativação por NFC do Libre 2. Tudo que o streaming BLE headless
     * precisa para conectar, descriptografar e calibrar. Zera o contador de unlock (nova sessão)
     * e a âncora (fator 1.0 = valor de fábrica) só quando é um sensor novo (UID diferente).
     */
    suspend fun saveLibre2Session(
        mac: String,
        serial: String,
        uidHex: String,
        patchInfoHex: String,
        sessionKeyHex: String,
        calibrationJson: String,
        framHex: String = "",
        sensorStartMs: Long
    ) = dataStore.edit {
        val isNewSensor = it[KEY_LIBRE2_UID] != uidHex
        it[KEY_LIBRE2_MAC]          = mac
        it[KEY_LIBRE2_SERIAL]       = serial
        it[KEY_LIBRE2_UID]          = uidHex
        it[KEY_LIBRE2_PATCH_INFO]   = patchInfoHex
        it[KEY_LIBRE2_SESSION_KEY]  = sessionKeyHex
        // A sessão só fica reutilizável depois que o handshake BLE alcançar f002/ready.
        it[KEY_LIBRE2_STREAMING_VALID] = false
        it[KEY_LIBRE2_CALIBRATION]  = calibrationJson
        if (framHex.isNotBlank()) it[KEY_LIBRE2_FRAM] = framHex
        it[KEY_LIBRE2_SENSOR_START] = sensorStartMs
        if (isNewSensor) {
            it[KEY_LIBRE2_UNLOCK_COUNT] = 0
            it[KEY_LIBRE2_CAL_FACTOR]   = 1.0
        }
    }

    /** Persiste o contador de unlock do streaming (incrementa a cada reconexão de sessão). */
    suspend fun saveLibre2UnlockCount(count: Int) =
        dataStore.edit { it[KEY_LIBRE2_UNLOCK_COUNT] = count }

    /** Atualiza a validade observada da sessão GATT persistida. */
    suspend fun saveLibre2StreamingValid(valid: Boolean) =
        dataStore.edit { it[KEY_LIBRE2_STREAMING_VALID] = valid }

    /** Salva o fator de âncora (ponta de dedo) do Libre 2. */
    suspend fun saveLibre2CalFactor(factor: Double) =
        dataStore.edit { it[KEY_LIBRE2_CAL_FACTOR] = factor }

    /** Esquece o sensor Libre 2 configurado (sessão + calibração + âncora). */
    suspend fun clearLibre2Sensor() = dataStore.edit {
        it.remove(KEY_LIBRE2_MAC)
        it.remove(KEY_LIBRE2_SERIAL)
        it.remove(KEY_LIBRE2_UID)
        it.remove(KEY_LIBRE2_PATCH_INFO)
        it.remove(KEY_LIBRE2_SESSION_KEY)
        it.remove(KEY_LIBRE2_STREAMING_VALID)
        it.remove(KEY_LIBRE2_UNLOCK_COUNT)
        it.remove(KEY_LIBRE2_CALIBRATION)
        it.remove(KEY_LIBRE2_FRAM)
        it.remove(KEY_LIBRE2_SENSOR_START)
        it.remove(KEY_LIBRE2_CAL_FACTOR)
    }

    /** Salva o fator de calibração da glicemia (recalculável ao longo da vida do sensor). */
    suspend fun saveSibionicsCalFactor(factor: Double) =
        dataStore.edit { it[KEY_SIBIONICS_CAL_FACTOR] = factor }

    // ── Accu-Chek SmartGuide ───────────────────────────────────────────────────

    /** Endereço do aparelho pareado (o endereço anunciado muda; o do pareamento não). */
    suspend fun saveAccuChekMac(mac: String) =
        dataStore.edit { it[KEY_ACCUCHEK_MAC] = mac }

    /** Serial da embalagem — o sensor anuncia como `AC-<serial>`. */
    suspend fun saveAccuChekSerial(serial: String) =
        dataStore.edit { it[KEY_ACCUCHEK_SERIAL] = serial }

    suspend fun saveAccuChekPin(pin: String) =
        dataStore.edit { it[KEY_ACCUCHEK_PIN] = pin }

    suspend fun saveAccuChekLastMinute(minute: Int) =
        dataStore.edit { it[KEY_ACCUCHEK_LAST_MINUTE] = minute }

    suspend fun saveAccuChekStartMs(startMs: Long) =
        dataStore.edit { it[KEY_ACCUCHEK_START_MS] = startMs }

    /**
     * Novo sensor configurado: grava serial/PIN e zera endereço, cursor e início do anterior.
     * Também apaga a chave do driver antigo.
     */
    suspend fun setAccuChekSensor(serial: String, pin: String) = dataStore.edit {
        it[KEY_ACCUCHEK_SERIAL] = serial
        it[KEY_ACCUCHEK_PIN] = pin
        it.remove(KEY_ACCUCHEK_MAC)
        it.remove(KEY_ACCUCHEK_LAST_MINUTE)
        it.remove(KEY_ACCUCHEK_START_MS)
        it.remove(KEY_ACCUCHEK_LAST_SEQUENCE_OLD)
    }

    /** Esquece o sensor Accu-Chek configurado (não desfaz o pareamento no Android). */
    suspend fun clearAccuChekSensor() = dataStore.edit {
        it.remove(KEY_ACCUCHEK_MAC)
        it.remove(KEY_ACCUCHEK_SERIAL)
        it.remove(KEY_ACCUCHEK_PIN)
        it.remove(KEY_ACCUCHEK_LAST_MINUTE)
        it.remove(KEY_ACCUCHEK_START_MS)
        it.remove(KEY_ACCUCHEK_LAST_SEQUENCE_OLD)
    }

    // ── Smart 2.0 (MedLevensohn) ───────────────────────────────────────────────

    /**
     * Salva o sensor (serial dos 10 últimos caracteres do nome BLE + endereço). Sensor diferente do
     * salvo zera chave-mestra, início e cursor; a chave de sessão antiga é sempre apagada.
     */
    suspend fun saveMedLevensohnSensor(serial: String, mac: String) = dataStore.edit {
        val isNewSensor = it[KEY_MEDLEVENSOHN_SERIAL] != serial
        it[KEY_MEDLEVENSOHN_SERIAL] = serial
        it[KEY_MEDLEVENSOHN_MAC] = mac
        it.remove(KEY_MEDLEVENSOHN_SESSION_KEY)
        if (isNewSensor) {
            it.remove(KEY_MEDLEVENSOHN_MASTER_KEY)
            it.remove(KEY_MEDLEVENSOHN_START_SEC)
            it.remove(KEY_MEDLEVENSOHN_LAST_ID)
            it.remove(KEY_MEDLEVENSOHN_HAS_TIME)
        }
    }

    /** Chave-mestra recebida em f001 ("" = esquecer). */
    suspend fun saveMedLevensohnMasterKey(masterKeyHex: String) = dataStore.edit {
        if (masterKeyHex.isBlank()) it.remove(KEY_MEDLEVENSOHN_MASTER_KEY) else it[KEY_MEDLEVENSOHN_MASTER_KEY] = masterKeyHex
        it.remove(KEY_MEDLEVENSOHN_SESSION_KEY)
    }

    /** Estado que sobrevive entre conexões: início do sensor, último minuto gravado e se a hora é conhecida. */
    suspend fun saveMedLevensohnProgress(startSec: Long, lastId: Int, hasTime: Boolean) = dataStore.edit {
        it[KEY_MEDLEVENSOHN_START_SEC] = startSec
        it[KEY_MEDLEVENSOHN_LAST_ID] = lastId
        it[KEY_MEDLEVENSOHN_HAS_TIME] = hasTime
    }

    /** Esquece o sensor Smart 2.0 configurado. */
    suspend fun clearMedLevensohnSensor() = dataStore.edit {
        it.remove(KEY_MEDLEVENSOHN_SERIAL)
        it.remove(KEY_MEDLEVENSOHN_MAC)
        it.remove(KEY_MEDLEVENSOHN_SESSION_KEY)
        it.remove(KEY_MEDLEVENSOHN_MASTER_KEY)
        it.remove(KEY_MEDLEVENSOHN_START_SEC)
        it.remove(KEY_MEDLEVENSOHN_LAST_ID)
        it.remove(KEY_MEDLEVENSOHN_HAS_TIME)
    }

    // ── Dexcom G6 ──────────────────────────────────────────────────────────────

    /** Salva o Transmitter ID digitado pelo usuário (6 caracteres impressos no transmissor). */
    suspend fun saveDexcomG6TransmitterId(transmitterId: String) =
        dataStore.edit { it[KEY_DEXCOM_G6_TRANSMITTER_ID] = transmitterId }

    /** Salva o MAC do transmissor encontrado por scan. */
    suspend fun saveDexcomG6Mac(mac: String) =
        dataStore.edit { it[KEY_DEXCOM_G6_MAC] = mac }

    /** Esquece o transmissor Dexcom G6 configurado. */
    suspend fun clearDexcomG6Sensor() = dataStore.edit {
        it.remove(KEY_DEXCOM_G6_TRANSMITTER_ID)
        it.remove(KEY_DEXCOM_G6_MAC)
    }

    // ── Pushover ──────────────────────────────────────────────────────────────
    suspend fun savePushover(enabled: Boolean, userKey: String, zonesJson: String) = dataStore.edit {
        it[KEY_PUSHOVER_ENABLED]    = enabled
        it[KEY_PUSHOVER_USER_KEY]   = userKey.trim()
        it[KEY_PUSHOVER_ZONES_JSON] = zonesJson
    }

    // ── Avisar seguidores ─────────────────────────────────────────────────────
    suspend fun saveNotifyFollowers(enabled: Boolean, followerIdsJson: String) = dataStore.edit {
        it[KEY_NOTIFY_FOLLOWERS_ENABLED]  = enabled
        it[KEY_NOTIFY_FOLLOWERS_IDS_JSON] = followerIdsJson
    }

    suspend fun clearSession() {
        withContext(Dispatchers.IO) {
            securePrefs.edit()
                .remove(SECURE_KEY_ACCESS_TOKEN)
                .remove(SECURE_KEY_REFRESH_TOKEN)
                .remove(SECURE_KEY_ACCESS_EXPIRES_AT)
                .remove(SECURE_KEY_PENDING_REFRESH_REQUEST_ID)
                .commit()
        }
        dataStore.edit {
            it.remove(KEY_MY_ROLES)
            it.remove(KEY_ACTIVE_FOLLOWED_PATIENT_ID)
            it.remove(KEY_ACTIVE_FOLLOWED_PATIENT_NAME)
            it.remove(KEY_LOCAL_ONLY_MODE)
            it.remove(KEY_CLOUD_SYNC_STATE)
            it.remove(KEY_DIARY_CLOUD_BLOCKED_UNTIL)
            it.remove(KEY_DIARY_LAST_PULL_MS)
            it.remove(KEY_CLOUD_SYNC_CHECKED_AT)
        }
    }

    // ── Backup ────────────────────────────────────────────────────────────────

    suspend fun toJsonString(): String {
        val p = dataStore.data.first()
        val o = JSONObject()
        o.put("version", 2)
        // Conexão (sem tokens por segurança)
        o.put("base_url",                  p[KEY_BASE_URL]              ?: "")
        // Display
        o.put("polling_interval_minutes",  p[KEY_POLLING_INTERVAL]      ?: 3)
        o.put("glucose_unit",              p[KEY_GLUCOSE_UNIT]          ?: "MG_DL")
        o.put("theme_mode",                p[KEY_THEME_MODE]            ?: "SYSTEM")
        o.put("graph_theme_mode",          p[KEY_GRAPH_THEME]           ?: GraphTheme.FOLLOW_APP)
        o.put("glucose_display_mode",      p[KEY_GLUCOSE_DISPLAY_MODE]  ?: "CONFIRMED_ONLY")
        o.put("time_format",               p[KEY_TIME_FORMAT]           ?: "H24")
        // Faixas
        o.put("threshold_urgent_low",      p[KEY_THRESHOLD_URGENT_LOW]  ?: 55f)
        o.put("threshold_low",             p[KEY_THRESHOLD_LOW]         ?: 70f)
        o.put("threshold_high",            p[KEY_THRESHOLD_HIGH]        ?: 180f)
        o.put("threshold_urgent_high",     p[KEY_THRESHOLD_URGENT_HIGH] ?: 250f)
        // Preditivo + re-alarme
        o.put("predictive_enabled",        p[KEY_PREDICTIVE_ENABLED]    ?: true)
        o.put("predictive_minutes",        p[KEY_PREDICTIVE_MINUTES]    ?: 15)
        o.put("realarm_enabled",           p[KEY_REALARM_ENABLED]       ?: false)
        o.put("realarm_interval",          p[KEY_REALARM_INTERVAL]      ?: 5)
        // TTS + silêncio noturno
        o.put("tts_enabled",               p[KEY_TTS_ENABLED]           ?: false)
        o.put("quiet_period_enabled",      p[KEY_QUIET_PERIOD_ENABLED]  ?: false)
        o.put("quiet_start_hour",          p[KEY_QUIET_START_HOUR]      ?: 23)
        o.put("quiet_end_hour",            p[KEY_QUIET_END_HOUR]        ?: 7)
        // Alerta por ligação
        o.put("call_alert_enabled",        p[KEY_CALL_ALERT_ENABLED]    ?: false)
        o.put("call_alert_phone",          p[KEY_CALL_ALERT_PHONE]      ?: "")
        o.put("call_alert_delay",          p[KEY_CALL_ALERT_DELAY]      ?: 5)
        // SMS
        o.put("sms_alert_enabled",         p[KEY_SMS_ALERT_ENABLED]     ?: false)
        o.put("sms_contact",               p[KEY_SMS_CONTACT]           ?: "")
        o.put("sms_message",               p[KEY_SMS_MESSAGE]           ?: "")
        // Dados + gráficos + logs
        o.put("data_retention_days",       p[KEY_DATA_RETENTION_DAYS]   ?: 100)
        o.put("home_graph_hours",          p[KEY_HOME_GRAPH_HOURS]      ?: 3)
        o.put("widget_graph_hours",        p[KEY_WIDGET_GRAPH_HOURS]    ?: 3)
        o.put("notif_graph_hours",         p[KEY_NOTIF_GRAPH_HOURS]     ?: 3)
        o.put("logs_enabled",              p[KEY_LOGS_ENABLED]          ?: false)
        // Android Auto
        o.put("auto_show_graph",           p[KEY_AUTO_SHOW_GRAPH]       ?: false)
        o.put("auto_graph_hours",          p[KEY_AUTO_GRAPH_HOURS]      ?: 3)
        o.put("auto_graph_theme",          p[KEY_AUTO_GRAPH_THEME]      ?: "AUTO")
        o.put("smart_meal_prompt",         p[KEY_SMART_MEAL_PROMPT]     ?: false)
        o.put("notif_quick_add",           p[KEY_NOTIF_QUICK_ADD]       ?: true)
        // Toggles de alerta (21 chaves)
        val alertZones = listOf(GlucoseZone.URGENT_LOW, GlucoseZone.LOW, GlucoseZone.HIGH,
            GlucoseZone.URGENT_HIGH, GlucoseZone.STALE, GlucoseZone.QUICK_DROP)
        val alertObj = JSONObject()
        for (zone in alertZones) {
            for (type in AlertType.entries) {
                val key = "alert_${type.name.lowercase()}_${zone.name.lowercase()}"
                alertObj.put(key, p[alertKey(zone, type)] ?: defaultAlertEnabled(zone, type))
            }
        }
        o.put("alert_toggles", alertObj)
        return o.toString(2)
    }

    suspend fun fromJsonString(json: String) {
        val o = JSONObject(json)
        dataStore.edit { p ->
            if (o.has("base_url"))                  p[KEY_BASE_URL]              = o.getString("base_url")
            if (o.has("polling_interval_minutes"))  p[KEY_POLLING_INTERVAL]      = o.getInt("polling_interval_minutes")
            if (o.has("glucose_unit"))              p[KEY_GLUCOSE_UNIT]          = o.getString("glucose_unit")
            if (o.has("theme_mode"))                p[KEY_THEME_MODE]            = o.getString("theme_mode")
            if (o.has("graph_theme_mode"))           p[KEY_GRAPH_THEME]           = o.getString("graph_theme_mode")
            if (o.has("glucose_display_mode"))      p[KEY_GLUCOSE_DISPLAY_MODE]  = o.getString("glucose_display_mode")
            if (o.has("time_format"))               p[KEY_TIME_FORMAT]           = o.getString("time_format")
            if (o.has("threshold_urgent_low"))      p[KEY_THRESHOLD_URGENT_LOW]  = o.getDouble("threshold_urgent_low").toFloat()
            if (o.has("threshold_low"))             p[KEY_THRESHOLD_LOW]         = o.getDouble("threshold_low").toFloat()
            if (o.has("threshold_high"))            p[KEY_THRESHOLD_HIGH]        = o.getDouble("threshold_high").toFloat()
            if (o.has("threshold_urgent_high"))     p[KEY_THRESHOLD_URGENT_HIGH] = o.getDouble("threshold_urgent_high").toFloat()
            if (o.has("predictive_enabled"))        p[KEY_PREDICTIVE_ENABLED]    = o.getBoolean("predictive_enabled")
            if (o.has("predictive_minutes"))        p[KEY_PREDICTIVE_MINUTES]    = o.getInt("predictive_minutes")
            if (o.has("realarm_enabled"))           p[KEY_REALARM_ENABLED]       = o.getBoolean("realarm_enabled")
            if (o.has("realarm_interval"))          p[KEY_REALARM_INTERVAL]      = o.getInt("realarm_interval")
            if (o.has("tts_enabled"))               p[KEY_TTS_ENABLED]           = o.getBoolean("tts_enabled")
            if (o.has("quiet_period_enabled"))      p[KEY_QUIET_PERIOD_ENABLED]  = o.getBoolean("quiet_period_enabled")
            if (o.has("quiet_start_hour"))          p[KEY_QUIET_START_HOUR]      = o.getInt("quiet_start_hour")
            if (o.has("quiet_end_hour"))            p[KEY_QUIET_END_HOUR]        = o.getInt("quiet_end_hour")
            if (o.has("call_alert_enabled"))        p[KEY_CALL_ALERT_ENABLED]    = o.getBoolean("call_alert_enabled")
            if (o.has("call_alert_phone"))          p[KEY_CALL_ALERT_PHONE]      = o.getString("call_alert_phone")
            if (o.has("call_alert_delay"))          p[KEY_CALL_ALERT_DELAY]      = o.getInt("call_alert_delay")
            if (o.has("sms_alert_enabled"))         p[KEY_SMS_ALERT_ENABLED]     = o.getBoolean("sms_alert_enabled")
            if (o.has("sms_contact"))               p[KEY_SMS_CONTACT]           = o.getString("sms_contact")
            if (o.has("sms_message"))               p[KEY_SMS_MESSAGE]           = o.getString("sms_message")
            if (o.has("data_retention_days"))       p[KEY_DATA_RETENTION_DAYS]   = o.getInt("data_retention_days")
            if (o.has("home_graph_hours"))          p[KEY_HOME_GRAPH_HOURS]      = o.getInt("home_graph_hours")
            if (o.has("widget_graph_hours"))        p[KEY_WIDGET_GRAPH_HOURS]    = o.getInt("widget_graph_hours")
            if (o.has("notif_graph_hours"))         p[KEY_NOTIF_GRAPH_HOURS]     = o.getInt("notif_graph_hours")
            if (o.has("logs_enabled"))              p[KEY_LOGS_ENABLED]          = o.getBoolean("logs_enabled")
            if (o.has("auto_show_graph"))           p[KEY_AUTO_SHOW_GRAPH]       = o.getBoolean("auto_show_graph")
            if (o.has("auto_graph_hours"))          p[KEY_AUTO_GRAPH_HOURS]      = o.getInt("auto_graph_hours")
            if (o.has("auto_graph_theme"))          p[KEY_AUTO_GRAPH_THEME]      = o.getString("auto_graph_theme")
            if (o.has("smart_meal_prompt"))         p[KEY_SMART_MEAL_PROMPT]     = o.getBoolean("smart_meal_prompt")
            if (o.has("notif_quick_add"))           p[KEY_NOTIF_QUICK_ADD]       = o.getBoolean("notif_quick_add")
            // Toggles de alerta
            if (o.has("alert_toggles")) {
                val at = o.getJSONObject("alert_toggles")
                val alertZones = listOf(GlucoseZone.URGENT_LOW, GlucoseZone.LOW, GlucoseZone.HIGH,
                    GlucoseZone.URGENT_HIGH, GlucoseZone.STALE, GlucoseZone.QUICK_DROP)
                for (zone in alertZones) {
                    for (type in AlertType.entries) {
                        val key = "alert_${type.name.lowercase()}_${zone.name.lowercase()}"
                        if (at.has(key)) p[alertKey(zone, type)] = at.getBoolean(key)
                    }
                }
            }
        }
    }

    // ── Android Auto — gráfico ────────────────────────────────────────────────
    suspend fun saveAutoShowGraph(enabled: Boolean) = dataStore.edit { it[KEY_AUTO_SHOW_GRAPH] = enabled }
    suspend fun saveAutoGraphHours(hours: Int)      = dataStore.edit { it[KEY_AUTO_GRAPH_HOURS] = hours.coerceIn(1, 6) }
    suspend fun saveAutoGraphTheme(theme: String)   = dataStore.edit { it[KEY_AUTO_GRAPH_THEME] = theme }

    // ── IFTTT Webhooks ────────────────────────────────────────────────────────
    suspend fun saveIftttEnabled(enabled: Boolean) = dataStore.edit { it[KEY_IFTTT_ENABLED] = enabled }
    suspend fun saveIftttApiKey(key: String)        = dataStore.edit { it[KEY_IFTTT_API_KEY] = key }
    suspend fun saveIftttEventPrefix(prefix: String) = dataStore.edit { it[KEY_IFTTT_EVENT_PREFIX] = prefix }
    suspend fun setIftttZoneEnabled(zone: GlucoseZone, enabled: Boolean) =
        dataStore.edit { it[iftttZoneKey(zone)] = enabled }
    suspend fun setIftttPredictiveEnabled(enabled: Boolean) =
        dataStore.edit { it[KEY_IFTTT_ZONE_PREDICTIVE] = enabled }
}

/**
 * Idioma do aparelho (não o do app): Resources.getSystem() não é afetado pelo
 * AppCompatDelegate.setApplicationLocales, então reflete o idioma escolhido no celular.
 */
internal fun deviceLanguage(): String =
    if (android.content.res.Resources.getSystem().configuration.locales[0]?.language == "en") "en" else "pt"
