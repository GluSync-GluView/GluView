package com.gluview.app.ui.screen.settings

import androidx.annotation.StringRes
import com.gluview.app.R
import com.gluview.app.ui.navigation.Screen
import java.text.Normalizer

/**
 * Busca em Ajustes (22/11/2026) — índice de tudo que dá para ajustar, inclusive o que mora em
 * sub-telas (Ajustes › Alertas › IFTTT…). Cada item guarda o MESMO recurso de texto que a tela usa
 * no título da linha/seção: assim a busca acha pelo texto que a pessoa vê, no idioma do app, e o
 * destaque na tela de destino (Modifier.searchHighlight) acha a linha pelo mesmo texto.
 *
 * Ao criar um ajuste novo numa tela de Ajustes, acrescente a linha aqui (SettingsSearchIndexTest
 * confere que nenhum título se repete na mesma tela e que toda tela tem rota).
 */
internal enum class SearchScreen(
    @StringRes val titleRes: Int,
    val route: String,
    val parent: SearchScreen? = null,
    val gate: SearchGate = SearchGate.ALL
) {
    GENERAL(R.string.settings_general_section, Screen.SettingsGeneral.route),
    HOME(R.string.settings_home_title, Screen.SettingsHome.route, gate = SearchGate.PATIENT),
    FLOATING(R.string.floating_title, Screen.SettingsFloating.route),
    MIRROR(R.string.mirror_title, Screen.Mirror.route),
    ALERTS(R.string.settings_configure_alerts, Screen.AlertSettings.route),
    ALERT_IFTTT(R.string.alertsettings_ifttt_section, Screen.AlertIfttt.route, ALERTS),
    ALERT_PUSHOVER(R.string.alertsettings_pushover_title, Screen.AlertPushover.route, ALERTS),
    WEARABLE_ALERTS(R.string.wearable_alerts_title, Screen.WearableAlerts.route, ALERTS),
    NIGHTSCOUT(R.string.ns_title, Screen.NightscoutHub.route),
    CALIBRATION(R.string.cal_title, Screen.Calibration.route, gate = SearchGate.PATIENT),
    BOLUS(R.string.bolus_title, Screen.Bolus.route, gate = SearchGate.PATIENT),
    THERAPY_PROFILE(R.string.therapy_profile_title, Screen.TherapyProfile.route, BOLUS, SearchGate.PATIENT),
    DIARY(R.string.diary_title, Screen.Diary.createRoute(), gate = SearchGate.PATIENT),
    REMINDERS(R.string.reminders_title, Screen.Reminders.route, gate = SearchGate.PATIENT),
    HEALTH_CONNECT(R.string.hc_title, Screen.HealthConnect.route, gate = SearchGate.PATIENT),
    SYNC(R.string.settings_sync_section_title, Screen.SettingsSync.route, gate = SearchGate.PATIENT),
    WEARABLES(R.string.nav_wearables, Screen.WatchFaces.route),
    MIBAND(R.string.miband_title, Screen.MiBand.route, WEARABLES),
    UPDATES(R.string.settings_updates_title, Screen.SettingsUpdates.route),
    ACCOUNT(R.string.settings_account_section, Screen.SettingsAccount.route, gate = SearchGate.CLOUD),
    PASSKEYS(R.string.passkeys_title, Screen.Passkeys.route, ACCOUNT, SearchGate.CLOUD),
    ACCESS_REQUESTS(R.string.access_requests_title, Screen.MyAccessRequests.route, ACCOUNT, SearchGate.CLOUD),
    CAREGIVER(R.string.settings_caregiver_section, Screen.Caregiver.route, gate = SearchGate.CLOUD),
    INTEGRATIONS(R.string.settings_integrations_title, Screen.ExternalIntegrations.route, gate = SearchGate.CLOUD),
    API_KEYS(R.string.apikeys_title, Screen.ApiKeys.route, INTEGRATIONS, SearchGate.CLOUD),
    SUPPORT(R.string.settings_support_section_title, Screen.SettingsSupport.route),
    DONATE(R.string.settings_donate_title, Screen.Donation.route, gate = SearchGate.DONATE),
    // Aba "Sensor" da barra inferior — entra na busca porque é ajuste também.
    SENSOR(R.string.settings_sensor_section_title, Screen.SettingsSensor.route, gate = SearchGate.PATIENT),
    SENSOR_ADVANCED(R.string.settings_sensor_advanced, Screen.SensorScanner.route, SENSOR, SearchGate.SIBIONICS);

    /** Caminho a partir de Ajustes (sem incluir "Ajustes"): raiz → … → esta tela. */
    val path: List<SearchScreen> get() = generateSequence(this) { it.parent }.toList().asReversed()
}

/** Quando o item aparece — espelha os `if` das próprias telas, para a busca não achar o que está escondido. */
internal enum class SearchGate { ALL, PATIENT, CLOUD, ADMIN, DONATE, SIBIONICS, SIBIONICS_CN, SIBIONICS_GS3 }

internal data class SearchContext(
    val isFollowerOnly: Boolean,
    val isLocalOnly: Boolean,
    val isAdmin: Boolean,
    val showDonate: Boolean,
    val dataSource: String
) {
    fun allows(gate: SearchGate): Boolean = when (gate) {
        SearchGate.ALL           -> true
        SearchGate.PATIENT       -> !isFollowerOnly
        SearchGate.CLOUD         -> !isLocalOnly
        SearchGate.ADMIN         -> isAdmin
        SearchGate.DONATE        -> showDonate
        SearchGate.SIBIONICS     -> !isFollowerOnly && dataSource.startsWith("SIBIONICS")
        SearchGate.SIBIONICS_CN  -> !isFollowerOnly && dataSource == "SIBIONICS_CHINES"
        SearchGate.SIBIONICS_GS3 -> !isFollowerOnly && dataSource == "SIBIONICS_GS3"
    }
}

/**
 * Um ajuste procurável. [titleRes] é o título da linha/seção na tela [screen]; [keywords] são
 * sinônimos (pt e en) para quem procura por outra palavra ("hipo" acha "Baixo").
 */
internal data class SettingsSearchItem(
    @StringRes val titleRes: Int,
    val screen: SearchScreen,
    val gate: SearchGate = SearchGate.ALL,
    val keywords: List<String> = emptyList()
)

internal object SettingsSearchIndex {
    private fun i(res: Int, screen: SearchScreen, gate: SearchGate = SearchGate.ALL, vararg kw: String) =
        SettingsSearchItem(res, screen, gate, kw.toList())

    val items: List<SettingsSearchItem> = listOf(
        // Geral
        i(R.string.settings_display_section, SearchScreen.GENERAL),
        i(R.string.settings_unit, SearchScreen.GENERAL, kw = *arrayOf("mg/dl", "mmol", "unidade", "unit")),
        i(R.string.settings_time_format, SearchScreen.GENERAL, kw = *arrayOf("24h", "12h", "relógio", "hora")),
        i(R.string.settings_language, SearchScreen.GENERAL, kw = *arrayOf("idioma", "língua", "inglês", "português", "language")),
        i(R.string.settings_theme, SearchScreen.GENERAL, kw = *arrayOf("tema", "escuro", "claro", "dark", "light")),
        i(R.string.settings_graphs_section, SearchScreen.GENERAL),
        i(R.string.settings_graphs_widget, SearchScreen.GENERAL, kw = *arrayOf("widget")),
        i(R.string.settings_graphs_notification, SearchScreen.GENERAL, kw = *arrayOf("notificação", "notification")),
        i(R.string.settings_graphs_theme, SearchScreen.GENERAL),
        i(R.string.auto_settings_title, SearchScreen.GENERAL, kw = *arrayOf("android auto", "carro", "car")),
        i(R.string.auto_settings_show_graph, SearchScreen.GENERAL),
        i(R.string.auto_settings_graph_hours, SearchScreen.GENERAL),
        // Home
        i(R.string.settings_home_section, SearchScreen.HOME),
        i(R.string.settings_home_stats, SearchScreen.HOME),
        i(R.string.settings_home_diary, SearchScreen.HOME),
        // Janela flutuante
        i(R.string.floating_enable, SearchScreen.FLOATING, kw = *arrayOf("bolha", "overlay", "sobrepor")),
        i(R.string.floating_section_look, SearchScreen.FLOATING),
        i(R.string.floating_size, SearchScreen.FLOATING),
        i(R.string.floating_opacity, SearchScreen.FLOATING, kw = *arrayOf("transparência")),
        i(R.string.floating_show_arrow, SearchScreen.FLOATING, kw = *arrayOf("seta", "tendência")),
        i(R.string.floating_show_delta, SearchScreen.FLOATING),
        i(R.string.floating_show_age, SearchScreen.FLOATING),
        i(R.string.floating_zone_bg, SearchScreen.FLOATING),
        i(R.string.floating_highlight, SearchScreen.FLOATING),
        i(R.string.floating_section_behavior, SearchScreen.FLOATING),
        i(R.string.floating_locked, SearchScreen.FLOATING),
        i(R.string.floating_hide_locked, SearchScreen.FLOATING),
        i(R.string.floating_hide_minutes, SearchScreen.FLOATING),
        // Espelhamento
        i(R.string.mirror_section_follow, SearchScreen.MIRROR),
        i(R.string.mirror_section_share, SearchScreen.MIRROR),
        i(R.string.mirror_share_enable, SearchScreen.MIRROR),
        i(R.string.mirror_section_http, SearchScreen.MIRROR, kw = *arrayOf("servidor web", "http")),
        i(R.string.mirror_http_enable, SearchScreen.MIRROR),
        i(R.string.mirror_http_wifi, SearchScreen.MIRROR, kw = *arrayOf("wi-fi", "wifi")),
        i(R.string.mirror_stun, SearchScreen.MIRROR),
        i(R.string.mirror_link_timeout, SearchScreen.MIRROR),
        i(R.string.mirror_repeat, SearchScreen.MIRROR),
        // Alertas
        i(R.string.settings_glucose_ranges, SearchScreen.ALERTS, kw = *arrayOf("faixa", "alvo", "limite", "range", "target")),
        i(R.string.settings_range_urgent_low, SearchScreen.ALERTS, kw = *arrayOf("hipo", "hipoglicemia", "urgente")),
        i(R.string.settings_range_low, SearchScreen.ALERTS, kw = *arrayOf("hipo", "hipoglicemia", "low")),
        i(R.string.settings_range_high, SearchScreen.ALERTS, kw = *arrayOf("hiper", "hiperglicemia", "high")),
        i(R.string.settings_range_urgent_high, SearchScreen.ALERTS, kw = *arrayOf("hiper", "hiperglicemia", "urgente")),
        i(R.string.alertsettings_glucose_zones, SearchScreen.ALERTS, kw = *arrayOf("alarme", "som", "vibrar", "não perturbe")),
        i(R.string.alertsettings_behavior_section, SearchScreen.ALERTS),
        i(R.string.alertsettings_predictive_toggle, SearchScreen.ALERTS, kw = *arrayOf("previsão", "preditivo")),
        i(R.string.alertsettings_predictive_minutes_label, SearchScreen.ALERTS),
        i(R.string.alertsettings_realarm_toggle, SearchScreen.ALERTS, kw = *arrayOf("repetir", "soneca")),
        i(R.string.alertsettings_realarm_interval_label, SearchScreen.ALERTS),
        i(R.string.alertsettings_tts_toggle, SearchScreen.ALERTS, kw = *arrayOf("voz", "falar", "tts")),
        i(R.string.alertsettings_quiet_toggle, SearchScreen.ALERTS, kw = *arrayOf("silêncio", "noite", "dormir")),
        i(R.string.alertsettings_quiet_start, SearchScreen.ALERTS),
        i(R.string.alertsettings_quiet_end, SearchScreen.ALERTS),
        i(R.string.alertsettings_emergency_section, SearchScreen.ALERTS, kw = *arrayOf("emergência", "contato")),
        i(R.string.alertsettings_call_toggle, SearchScreen.ALERTS, kw = *arrayOf("ligar", "ligação", "telefone")),
        i(R.string.alertsettings_call_delay_label, SearchScreen.ALERTS),
        i(R.string.alertsettings_sms_toggle, SearchScreen.ALERTS, kw = *arrayOf("mensagem", "sms")),
        i(R.string.alertsettings_cloud_alerts_section, SearchScreen.ALERTS),
        i(R.string.alertsettings_followers_section, SearchScreen.ALERTS),
        i(R.string.alertsettings_followers_toggle, SearchScreen.ALERTS, kw = *arrayOf("seguidor", "cuidador")),
        // Alertas › IFTTT / Pushover / pulso
        i(R.string.alertsettings_ifttt_toggle, SearchScreen.ALERT_IFTTT, kw = *arrayOf("webhook")),
        i(R.string.alertsettings_ifttt_key_section, SearchScreen.ALERT_IFTTT),
        i(R.string.alertsettings_ifttt_zones_label, SearchScreen.ALERT_IFTTT),
        i(R.string.alertsettings_ifttt_zone_predictive, SearchScreen.ALERT_IFTTT),
        i(R.string.alertsettings_pushover_toggle, SearchScreen.ALERT_PUSHOVER),
        i(R.string.alertsettings_pushover_zones_label, SearchScreen.ALERT_PUSHOVER),
        i(R.string.wearable_alerts_enabled, SearchScreen.WEARABLE_ALERTS, kw = *arrayOf("vibração", "pulso", "relógio")),
        i(R.string.wearable_alerts_snooze_section, SearchScreen.WEARABLE_ALERTS, kw = *arrayOf("soneca", "snooze")),
        // Nightscout
        i(R.string.ns_ages, SearchScreen.NIGHTSCOUT),
        i(R.string.ns_pump, SearchScreen.NIGHTSCOUT, kw = *arrayOf("bomba", "alça")),
        // Calibração
        i(R.string.cal_enable, SearchScreen.CALIBRATION, kw = *arrayOf("ponta de dedo", "glicosímetro")),
        i(R.string.cal_section_sensor, SearchScreen.CALIBRATION),
        i(R.string.smooth_title, SearchScreen.CALIBRATION, kw = *arrayOf("suavizar", "filtro")),
        // Bolus e perfil
        i(R.string.bolus_use_bg, SearchScreen.BOLUS),
        i(R.string.bolus_use_iob, SearchScreen.BOLUS, kw = *arrayOf("iob", "insulina ativa")),
        i(R.string.bolus_use_cob, SearchScreen.BOLUS, kw = *arrayOf("cob", "carboidrato")),
        i(R.string.bolus_use_trend, SearchScreen.BOLUS),
        i(R.string.bolus_parts, SearchScreen.BOLUS),
        i(R.string.prediction_title, SearchScreen.BOLUS),
        i(R.string.therapy_person, SearchScreen.THERAPY_PROFILE),
        i(R.string.therapy_insulin, SearchScreen.THERAPY_PROFILE, kw = *arrayOf("fator", "relação", "sensibilidade")),
        i(R.string.therapy_peak, SearchScreen.THERAPY_PROFILE),
        i(R.string.therapy_dia, SearchScreen.THERAPY_PROFILE),
        i(R.string.therapy_absorption, SearchScreen.THERAPY_PROFILE),
        i(R.string.therapy_max_bolus, SearchScreen.THERAPY_PROFILE),
        // Lembretes
        i(R.string.reminders_mine, SearchScreen.REMINDERS),
        i(R.string.reminders_smart, SearchScreen.REMINDERS),
        i(R.string.reminders_smart_meal, SearchScreen.REMINDERS, kw = *arrayOf("refeição", "comida")),
        // Health Connect
        i(R.string.hc_section_write, SearchScreen.HEALTH_CONNECT),
        i(R.string.hc_write_glucose, SearchScreen.HEALTH_CONNECT),
        i(R.string.hc_write_nutrition, SearchScreen.HEALTH_CONNECT),
        i(R.string.hc_section_read, SearchScreen.HEALTH_CONNECT),
        i(R.string.hc_read_activity, SearchScreen.HEALTH_CONNECT, kw = *arrayOf("passos", "steps")),
        i(R.string.hc_read_exercise, SearchScreen.HEALTH_CONNECT),
        i(R.string.hc_read_sleep, SearchScreen.HEALTH_CONNECT),
        i(R.string.hc_read_cycle, SearchScreen.HEALTH_CONNECT),
        i(R.string.hc_read_nutrition, SearchScreen.HEALTH_CONNECT),
        i(R.string.hc_read_glucose, SearchScreen.HEALTH_CONNECT),
        // Sincronização
        i(R.string.settings_glusync_upload, SearchScreen.SYNC, kw = *arrayOf("nuvem", "enviar", "upload")),
        i(R.string.settings_upload_over_mobile_data, SearchScreen.SYNC, kw = *arrayOf("4g", "5g", "dados móveis")),
        i(R.string.settings_mobile_data_interval, SearchScreen.SYNC),
        i(R.string.settings_update_interval, SearchScreen.SYNC, kw = *arrayOf("polling", "atualização")),
        i(R.string.settings_update_interval_row, SearchScreen.SYNC),
        i(R.string.settings_local_retention, SearchScreen.SYNC, kw = *arrayOf("histórico", "guardar", "apagar")),
        i(R.string.settings_retention_unlimited, SearchScreen.SYNC),
        // Wearables / Mi Band
        i(R.string.wf_device_section, SearchScreen.WEARABLES, kw = *arrayOf("visor", "relógio", "watch")),
        i(R.string.wear_row_title, SearchScreen.WEARABLES, kw = *arrayOf("wear os", "galaxy watch")),
        i(R.string.watchface_store_title, SearchScreen.WEARABLES, kw = *arrayOf("loja")),
        i(R.string.miband_pairing_section, SearchScreen.MIBAND, kw = *arrayOf("parear", "auth key")),
        i(R.string.miband_auto_section, SearchScreen.MIBAND),
        i(R.string.miband_auto_update, SearchScreen.MIBAND),
        i(R.string.miband_update_every_reading, SearchScreen.MIBAND),
        i(R.string.xiaomi_band_section, SearchScreen.MIBAND, kw = *arrayOf("mi band 8", "mi band 9", "xiaomi")),
        i(R.string.xiaomi_band_alerts, SearchScreen.MIBAND),
        i(R.string.xiaomi_face_enabled, SearchScreen.MIBAND),
        // Atualizações
        i(R.string.updates_auto_section, SearchScreen.UPDATES),
        i(R.string.updates_auto_check, SearchScreen.UPDATES, kw = *arrayOf("versão", "update")),
        // Conta
        i(R.string.settings_profile_section, SearchScreen.ACCOUNT, kw = *arrayOf("nome", "perfil", "e-mail")),
        i(R.string.settings_change_password, SearchScreen.ACCOUNT, kw = *arrayOf("senha", "password")),
        i(R.string.settings_access_section, SearchScreen.ACCOUNT),
        i(R.string.passkeys_beta_switch_title, SearchScreen.ACCOUNT, kw = *arrayOf("passkey", "biometria")),
        i(R.string.settings_cloud_section, SearchScreen.ACCOUNT, kw = *arrayOf("nuvem", "servidor")),
        // Integrações / chaves de API
        i(R.string.integrations_import_section, SearchScreen.INTEGRATIONS, kw = *arrayOf("dexcom share", "librelinkup", "libreview", "importar")),
        i(R.string.settings_api_keys_section, SearchScreen.INTEGRATIONS),
        i(R.string.settings_api_keys_switch_title, SearchScreen.INTEGRATIONS, kw = *arrayOf("api", "token")),
        i(R.string.apikeys_read_glucose, SearchScreen.API_KEYS),
        i(R.string.apikeys_write_glucose, SearchScreen.API_KEYS),
        // Diagnóstico e suporte
        i(R.string.settings_send_support_log, SearchScreen.SUPPORT, kw = *arrayOf("ajuda", "suporte")),
        i(R.string.settings_event_log, SearchScreen.SUPPORT),
        i(R.string.settings_view_export_logs, SearchScreen.SUPPORT, kw = *arrayOf("log", "logs")),
        i(R.string.settings_medical_report, SearchScreen.SUPPORT, kw = *arrayOf("pdf", "relatório", "médico")),
        i(R.string.settings_service_diagnostic, SearchScreen.SUPPORT, kw = *arrayOf("bateria", "permissão")),
        i(R.string.settings_backup_section, SearchScreen.SUPPORT, kw = *arrayOf("backup", "cópia")),
        i(R.string.settings_export, SearchScreen.SUPPORT),
        i(R.string.settings_import, SearchScreen.SUPPORT, kw = *arrayOf("restaurar")),
        // Sensor (aba) e ajustes avançados
        i(R.string.settings_current_sensor_section, SearchScreen.SENSOR, kw = *arrayOf("serial", "trocar sensor", "parear")),
        i(R.string.settings_sensor_health_warning, SearchScreen.SENSOR, SearchGate.SIBIONICS),
        i(R.string.settings_sensor_health_warning_toggle, SearchScreen.SENSOR, SearchGate.SIBIONICS),
        i(R.string.settings_sensor_aging_after, SearchScreen.SENSOR, SearchGate.SIBIONICS),
        i(R.string.settings_no_signal_after, SearchScreen.SENSOR, SearchGate.SIBIONICS, "sem sinal"),
        i(R.string.settings_glucose_display_mode, SearchScreen.SENSOR, SearchGate.SIBIONICS_CN, "modo de leitura"),
        i(R.string.settings_sibionics_reuse_transmitter_title, SearchScreen.SENSOR, SearchGate.SIBIONICS_CN, "transmissor"),
        i(R.string.settings_sibionics_gs3_account_title, SearchScreen.SENSOR, SearchGate.SIBIONICS_GS3),
        i(R.string.sensorscan_current_section, SearchScreen.SENSOR_ADVANCED, SearchGate.SIBIONICS),
        i(R.string.sensorscan_connect_section, SearchScreen.SENSOR_ADVANCED, SearchGate.SIBIONICS, "bluetooth", "ble"),
        i(R.string.sensorscan_history_title, SearchScreen.SENSOR_ADVANCED, SearchGate.SIBIONICS),
    )

    /** Texto sem acento e em minúsculas — "Calibração" casa com "calibracao". */
    fun normalize(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase().trim()

    /** Todas as palavras da busca precisam aparecer no texto do item (título, caminho ou sinônimos). */
    fun matches(query: String, haystack: String): Boolean {
        val words = normalize(query).split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return false
        val h = normalize(haystack)
        return words.all { h.contains(it) }
    }
}
