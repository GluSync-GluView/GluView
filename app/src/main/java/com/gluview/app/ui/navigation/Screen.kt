package com.gluview.app.ui.navigation

sealed class Screen(val route: String) {
    data object Login         : Screen("login")
    data object Home          : Screen("home")
    data object Settings      : Screen("settings")
    // Aba principal da barra inferior (só paciente) — fonte de dados/BLE/NFC do sensor em si.
    data object SettingsSensor  : Screen("settings_sensor")
    // Submenu de Ajustes — preferências gerais (unidade, formato, idioma, tema, acesso).
    data object SettingsGeneral : Screen("settings_general")
    // Submenu de Ajustes — envio ao GluSync, polling, retenção, horas dos gráficos.
    data object SettingsSync    : Screen("settings_sync")
    data object SettingsSupport : Screen("settings_support")
    // Submenu de Ajustes — servidor/conta/nome de exibição, troca de senha, URL do backend.
    data object SettingsAccount : Screen("settings_account")
    // Plano OTA (E6/E7) — atualização do app sem cabo.
    data object SettingsUpdates : Screen("settings_updates")
    // Doação via Pix (plano 09_doacao_pix_plano.md) — entrada em Ajustes > Ajuda.
    data object Donation        : Screen("donation")
    data object AlertSettings : Screen("alert_settings")
    /** Sub-telas de Alertas (decisão 5.5 #2). */
    data object AlertIfttt    : Screen("alert_ifttt")
    data object AlertPushover : Screen("alert_pushover")
    data object AlertHistory  : Screen("alert_history")
    // Sprint 4 — Histórico unificado: Gráfico/TIR/Padrão viraram abas de uma tela só.
    data object Chart : Screen("chart?tab={tab}") {
        fun createRoute(tab: String = "chart") = "chart?tab=$tab"
    }
    // Plano 17 — diário completo (substitui o "log de eventos"); type = registro rápido de um tipo.
    data object Diary         : Screen("diary?type={type}") {
        fun createRoute(type: String? = null) = if (type.isNullOrBlank()) "diary" else "diary?type=$type"
    }
    data object MealEditor    : Screen("diary_meal?uuid={uuid}") {
        fun createRoute(uuid: String? = null) = if (uuid.isNullOrBlank()) "diary_meal" else "diary_meal?uuid=$uuid"
    }
    data object DiaryCatalog  : Screen("diary_catalog")
    data object Reminders     : Screen("reminders")
    data object Diagnostic    : Screen("diagnostic")
    data object Report        : Screen("report")
    // Sprint 6
    data object Onboarding    : Screen("onboarding")
    // Fluxo guiado de configuração/troca de sensor (caminho principal)
    data object SensorSetup   : Screen("sensor_setup")
    // Fase 3 — BLE Sibionics (Frente B). Caminho avançado: lista de dispositivos, código/serial
    // manuais e calibração. Fica acessível a partir de Ajustes > Sensor e do próprio fluxo guiado.
    data object SensorScanner : Screen("sensor_scanner")
    // Libre 2 Plus — ativação por NFC + calibração
    data object Libre2Activation : Screen("libre2_activation")
    // F7 — Termos de uso
    data object Terms         : Screen("terms")
    // F1/T08 — Logs do app
    data object AppLogs       : Screen("app_logs")
    data object WatchFaces    : Screen("watch_faces")
    // ADR-0029 — aba "Bomba" na barra inferior, no lugar de Wearables (que migrou pra Ajustes).
    data object InsulinPump   : Screen("insulin_pump")
    // F8 — Mi Band (pareamento + teste de conexão/autenticação Huami)
    data object MiBand        : Screen("miband")
    // F5 — API Keys (integração)
    data object ApiKeys       : Screen("api_keys")
    // Integrações externas (Dexcom Share, LibreLinkUp)
    data object ExternalIntegrations : Screen("external_integrations")
    // T85 — Passkeys (beta)
    data object Passkeys     : Screen("passkeys")
    // F6 — Acompanhamento (paciente ↔ seguidor)
    data object Caregiver     : Screen("caregiver")
    data object FollowedPatient : Screen("followed_patient/{patientId}?name={name}") {
        fun createRoute(patientId: String, name: String) =
            "followed_patient/$patientId?name=${android.net.Uri.encode(name)}"
    }
    data object WatchFaceEditor : Screen("watch_face_editor?faceId={faceId}") {
        fun createRoute(faceId: Long = 0L) = "watch_face_editor?faceId=$faceId"
    }
    // Fase 6 — vitrine da loja dentro do app (antes só existia no GluWebView).
    data object WatchfaceStore : Screen("watchface_store")
    // Achado 2026-09-26 — histórico de candidaturas (bomba/sensor beta) pra quem já tem conta.
    data object MyAccessRequests : Screen("my_access_requests")
    // Status do pedido de acesso ao app (ainda sem conta) — reachável direto do Login.
    data object AccessRequestStatus : Screen("access_request_status")
}
