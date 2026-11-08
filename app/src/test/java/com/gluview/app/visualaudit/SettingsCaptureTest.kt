package com.gluview.app.visualaudit

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.gluview.app.alert.ALERT_ZONES
import com.gluview.app.alert.AlertType
import com.gluview.app.data.remote.dto.ApiKeyInfoDto
import com.gluview.app.data.remote.dto.CaregiverLinkViewDto
import com.gluview.app.data.remote.dto.IntegrationStatusDto
import com.gluview.app.data.remote.dto.MyAccessRequestDto
import com.gluview.app.data.remote.dto.PasskeyInfoDto
import com.gluview.app.domain.model.GlucoseZone
import com.gluview.app.ui.screen.accessrequests.MyAccessRequestsContent
import com.gluview.app.ui.screen.accessrequests.MyAccessRequestsUiState
import com.gluview.app.ui.screen.alertsettings.AlertActions
import com.gluview.app.ui.screen.alertsettings.AlertContext
import com.gluview.app.ui.screen.alertsettings.AlertSettingsUiState
import com.gluview.app.ui.screen.alertsettings.AlertsContent
import com.gluview.app.ui.screen.alertsettings.IftttActions
import com.gluview.app.ui.screen.alertsettings.IftttContent
import com.gluview.app.ui.screen.alertsettings.PushoverContent
import com.gluview.app.ui.screen.alertsettings.TestResult
import com.gluview.app.ui.screen.apikeys.ApiKeysContent
import com.gluview.app.ui.screen.apikeys.ApiKeysUiState
import com.gluview.app.ui.screen.insulinpump.BetaState
import com.gluview.app.ui.screen.insulinpump.InsulinPumpContent
import com.gluview.app.ui.screen.insulinpump.InsulinPumpUiState
import com.gluview.app.ui.screen.integrations.ExternalIntegrationUiState
import com.gluview.app.ui.screen.integrations.IntegrationsContent
import com.gluview.app.ui.screen.passkeys.PasskeysContent
import com.gluview.app.ui.screen.passkeys.PasskeysUiState
import com.gluview.app.ui.screen.settings.AccountActions
import com.gluview.app.ui.screen.settings.AccountContent
import com.gluview.app.ui.screen.settings.AccountUi
import com.gluview.app.ui.screen.settings.GeneralActions
import com.gluview.app.ui.screen.settings.GeneralContent
import com.gluview.app.ui.screen.settings.GeneralUi
import com.gluview.app.ui.screen.settings.SensorActions
import com.gluview.app.ui.screen.settings.SensorContent
import com.gluview.app.ui.screen.settings.SensorUi
import com.gluview.app.ui.screen.settings.SettingsContent
import com.gluview.app.ui.screen.settings.SettingsNav
import com.gluview.app.ui.screen.settings.SupportContent
import com.gluview.app.ui.screen.settings.SyncActions
import com.gluview.app.ui.screen.settings.SyncContent
import com.gluview.app.ui.screen.settings.SyncUi
import com.gluview.app.ui.theme.ThemeMode
import com.gluview.app.util.GlucoseUnit
import com.gluview.app.util.GraphTheme
import com.gluview.app.util.TimeFormat
import com.gluview.app.util.UiText
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant
import java.util.TimeZone

/**
 * Ajustes e Alertas (redesign visual, Fase 6): as 12 telas (e as sub-telas de IFTTT/Pushover) na
 * matriz reduzida, com dados de propósito difíceis — e-mail longo, integração com erro de
 * autenticação, chaves revogadas, todos os blocos de Alertas abertos. Só roda com
 * -Pgluview.visualaudit.screens=true; PNGs em build/visual-audit/screens/<run>/ajustes/.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class)
class SettingsCaptureTest {

    @get:Rule val compose = createEmptyComposeRule()

    private val run = System.getProperty("gluview.visualaudit.run")?.takeIf { it.isNotBlank() } ?: "local"
    private lateinit var harness: ScreenHarness
    private lateinit var savedZone: TimeZone

    @Before fun setUp() {
        assumeTrue("capturas de tela desligadas", System.getProperty("gluview.visualaudit.screens") == "true")
        savedZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(GlucoseFixtures.ZONE))
        harness = ScreenHarness(compose, run)
    }

    @After fun tearDown() {
        if (::savedZone.isInitialized) TimeZone.setDefault(savedZone)
    }

    private val now = GlucoseFixtures.FIXED_NOW
    private fun iso(minutesAgo: Long) = Instant.ofEpochMilli(now - minutesAgo * 60_000L).toString()

    private fun shoot(name: String, content: @Composable () -> Unit) {
        for (v in Matrix.REDUCED) harness.capture("ajustes", name, v, Chrome.NONE, content = content)
    }

    @Test fun settings() {
        shoot("hub") {
            SettingsContent(isAdmin = true, isFollowerOnly = false, isLocalOnly = false, debugSeedEnabled = false, debugBusy = false,
                nav = SettingsNav({}, {}, {}, {}, {}, {}, {}, {}), onDebugSeedToggle = {}, onDebugBackfill = {}, onLogout = {})
        }
        val general = GeneralUi(GlucoseUnit.MG_DL, TimeFormat.H24, "pt", ThemeMode.SYSTEM, 3, 3, GraphTheme.FOLLOW_APP, true, 3, "AUTO")
        shoot("geral") {
            GeneralContent(general, GeneralActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {}), onBack = {})
        }
        shoot("sensor") {
            SensorContent(SensorUi("SIBIONICS_CHINES", true, 14, 20, "ALL", mapOf("sensor_beta:LIBRE2_PLUS" to "pending"), true),
                SensorActions({}, {}, {}, {}, {}, {}, {}, {}, {}), onBack = null)
        }
        shoot("sensor-medlev") {
            SensorContent(SensorUi("MEDLEVENSOHN_SMART2", true, 14, 20, "ALL",
                mapOf("sensor_beta:MEDLEVENSOHN_SMART2" to "approved"), true, medLevSerial = "22222A2WC8"),
                SensorActions({}, {}, {}, {}, {}, {}, {}, {}, {}), onBack = null)
        }
        shoot("sensor-smartguide") {
            SensorContent(SensorUi("ACCU_CHEK_SMARTGUIDE", true, 14, 20, "ALL",
                mapOf("sensor_beta:ACCU_CHEK_SMARTGUIDE" to "approved"), true, smartGuideSerial = "1R009999990"),
                SensorActions({}, {}, {}, {}, {}, {}, {}, {}, {}), onBack = null)
        }
        shoot("sincronizacao") {
            SyncContent(SyncUi(false, "SIBIONICS_CHINES", true, 15, 3, 365, 60, now - 2 * 86_400_000L),
                SyncActions({}, {}, {}, {}, {}), onBack = {})
        }
        val account = AccountUi("maria.eduarda.albuquerque.cavalcanti@exemplo.com.br",
            "Maria Eduarda", false, false, true, "123", "", false, true)
        shoot("conta") {
            AccountContent(account, AccountActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {}), onBack = {})
        }
        shoot("suporte") {
            SupportContent(true, {}, {}, {}, {}, {}, {}, {}, onBack = {})
        }
        harness.writeReport("ajustes")
    }

    @Test fun alerts() {
        val toggles = ALERT_ZONES.flatMap { z -> AlertType.entries.map { t -> (z to t) to (t != AlertType.DND || z == GlucoseZone.URGENT_LOW) } }.toMap()
        val followers = listOf(
            CaregiverLinkViewDto("1", "p", "eu@exemplo.com", null, "f1", "joao.pedro.silva@exemplo.com.br", "João Pedro", "accepted", "patient"),
            CaregiverLinkViewDto("2", "p", "eu@exemplo.com", null, "f2", "ana@exemplo.com", null, "accepted", "patient")
        )
        val state = AlertSettingsUiState(
            toggles = toggles, predictiveEnabled = true, reAlarmEnabled = true, ttsEnabled = true, quietEnabled = true,
            callEnabled = true, callPhone = "+55 11 98765-4321", smsEnabled = true, smsContact = "+55 11 91234-5678",
            iftttEnabled = true, iftttApiKey = "abcdefghijklmnopqrstuvwxyz", iftttZoneToggles = mapOf(GlucoseZone.URGENT_LOW to true),
            pushoverEnabled = true, pushoverUserKey = "uQiRzpo4DXghDmr9QzzfQu27cmVRsG",
            notifyFollowersEnabled = true, notifyFollowersSpecificIds = setOf("f1"), followers = followers
        )
        val actions = AlertActions({}, {}, {}, {}, { _, _, _ -> }, { _, _ -> }, { _, _ -> }, {}, { _, _, _ -> }, { _, _, _ -> },
            { _, _, _ -> }, {}, {})
        for (v in Matrix.REDUCED) {
            val tf = if (v.lang == "en") TimeFormat.H12 else TimeFormat.H24
            harness.capture("alertas", "alertas", v, Chrome.NONE) {
                AlertsContent(state, AlertContext(GlucoseUnit.MG_DL, tf, false, true), actions, {}, {}, {})
            }
            harness.capture("alertas", "alertas-mmol", v, Chrome.NONE) {
                AlertsContent(state, AlertContext(GlucoseUnit.MMOL_L, tf, false, true), actions, {}, {}, {})
            }
            harness.capture("alertas", "ifttt", v, Chrome.NONE) {
                IftttContent(state, GlucoseUnit.MG_DL, TestResult(true), IftttActions({}, {}, {}, { _, _ -> }, {}, {}), {})
            }
            harness.capture("alertas", "pushover", v, Chrome.NONE) {
                PushoverContent(state, GlucoseUnit.MG_DL, TestResult(false, "HTTP 400: invalid user key"), {}, {}, { _, _ -> }, {}, {})
            }
        }
        harness.writeReport("alertas")
    }

    @Test fun lists() {
        val integrations = ExternalIntegrationUiState(
            integrations = listOf(
                IntegrationStatusDto("dexcom_share", "active", "ous", iso(12), null, iso(9000)),
                IntegrationStatusDto("librelinkup", "auth_error", "eu", iso(300), "401 Unauthorized: invalid credentials", iso(20000))
            ), loading = false
        )
        shoot("integracoes") { IntegrationsContent(integrations, true, {}, {}, {}, {}, {}, {}, nowMs = now) }
        shoot("integracoes-erro") {
            IntegrationsContent(ExternalIntegrationUiState(loading = false, error = UiText.Raw("Sem conexão"), errorDetail = "timeout"),
                false, {}, {}, {}, {}, {}, {}, nowMs = now)
        }
        val keys = listOf(
            ApiKeyInfoDto("1", "Painel da clínica", "gk_7f3a", listOf("glucose:read"), false, iso(60 * 24 * 40), iso(30)),
            ApiKeyInfoDto("2", "Integração do relógio inteligente do quarto", "gk_91bc", listOf("glucose:read", "glucose:write"), false, iso(60 * 24 * 3), null),
            ApiKeyInfoDto("3", "Teste antigo", "gk_0001", emptyList(), true, iso(60 * 24 * 200), null)
        )
        shoot("chaves-api") { ApiKeysContent(ApiKeysUiState(keys = keys, loading = false), {}, {}, {}, {}, nowMs = now) }
        shoot("chaves-api-vazio") { ApiKeysContent(ApiKeysUiState(loading = false), {}, {}, {}, {}, nowMs = now) }
        val passkeys = listOf(PasskeyInfoDto("a", "Celular da Maria", iso(60 * 24 * 10), iso(120)), PasskeyInfoDto("b", "Tablet", iso(60 * 24 * 90)))
        shoot("passkeys") { PasskeysContent(PasskeysUiState(passkeys = passkeys, loading = false), {}, {}, {}, {}, nowMs = now) }
        shoot("bomba") { InsulinPumpContent(InsulinPumpUiState(betaState = BetaState.NONE), onApply = {}) }
        shoot("bomba-pendente") { InsulinPumpContent(InsulinPumpUiState(betaState = BetaState.PENDING), onApply = {}) }
        val requests = listOf(
            MyAccessRequestDto("1", "insulin_pump_beta", "pending", "Uso bomba há 5 anos", null, iso(60 * 24 * 2), null),
            MyAccessRequestDto("2", "sensor_beta:LIBRE2_PLUS", "rejected", null, "Ainda não temos vagas para este sensor na sua região.", iso(60 * 24 * 30), iso(60 * 24 * 20)),
            MyAccessRequestDto("3", "APP", "approved", null, null, iso(60 * 24 * 300), iso(60 * 24 * 299))
        )
        shoot("candidaturas") { MyAccessRequestsContent(MyAccessRequestsUiState(requests, loading = false), TimeFormat.H24, {}, {}) }
        harness.writeReport("listas")
    }
}
