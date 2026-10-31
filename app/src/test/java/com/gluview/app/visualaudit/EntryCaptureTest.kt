package com.gluview.app.visualaudit

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.gluview.app.R
import com.gluview.app.data.remote.dto.MyAccessRequestDto
import com.gluview.app.ui.screen.accessrequests.AccessRequestStatusContent
import com.gluview.app.ui.screen.accessrequests.AccessRequestStatusUiState
import com.gluview.app.ui.screen.login.LoginActions
import com.gluview.app.ui.screen.login.LoginContent
import com.gluview.app.ui.screen.login.LoginField
import com.gluview.app.ui.screen.login.LoginUiState
import com.gluview.app.ui.screen.onboarding.NotifPermission
import com.gluview.app.ui.screen.onboarding.OnboardingContent
import com.gluview.app.ui.screen.terms.TermsContent
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
import java.util.TimeZone

/**
 * Entrada (redesign visual, Fase 8): Login em todos os ramos, Status do pedido, Onboarding e
 * Termos, na matriz reduzida. Só roda com -Pgluview.visualaudit.screens=true; PNGs em
 * build/visual-audit/screens/<run>/entrada/.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class)
class EntryCaptureTest {

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

    private fun shoot(name: String, content: @Composable (Variant) -> Unit) {
        for (v in Matrix.REDUCED) harness.capture("entrada", name, v, Chrome.NONE) { content(v) }
    }

    private val email = "maria.eduarda.oliveira@exemplo.com.br"

    @Test fun entry() {
        val login = LoginUiState(email = email, password = "segredo12", passkeysAvailable = true, registrationMode = "approval")
        shoot("login") { LoginContent(login, LoginActions()) }
        shoot("login-erro") {
            LoginContent(login.copy(error = UiText.Res(R.string.login_error_bad_credentials)), LoginActions())
        }
        shoot("login-pedido-enviado") {
            LoginContent(login.copy(password = "", infoMessage = UiText.Res(R.string.login_access_request_sent), accessRequestSent = true),
                LoginActions())
        }
        shoot("cadastro-pedido") {
            LoginContent(login.copy(isRegisterMode = true, isPatient = false, displayName = "Maria Eduarda",
                error = UiText.Res(R.string.login_error_pick_role), errorField = LoginField.ROLES), LoginActions())
        }
        shoot("cadastro-conta") {
            LoginContent(LoginUiState(email = "", isRegisterMode = true, error = UiText.Res(R.string.login_error_fill_email),
                errorField = LoginField.EMAIL), LoginActions())
        }
        shoot("verificacao") {
            LoginContent(login.copy(pendingVerification = true, verificationCode = "1234",
                infoMessage = UiText.Res(R.string.login_code_resent)), LoginActions())
        }
        shoot("esqueci-1") { LoginContent(login.copy(isForgotPasswordMode = true), LoginActions()) }
        shoot("esqueci-2") {
            LoginContent(login.copy(isForgotPasswordMode = true, pendingPasswordReset = true, verificationCode = "482913",
                newPassword = "abc", error = UiText.Res(R.string.login_error_password_short), errorField = LoginField.NEW_PASSWORD),
                LoginActions())
        }

        val req = MyAccessRequestDto("r1", "APP", "pending", null, null, "2026-10-17T13:05:00Z", null)
        fun tf(v: Variant) = if (v.lang == "en") TimeFormat.H12 else TimeFormat.H24
        shoot("status-pendente") { v -> AccessRequestStatusContent(AccessRequestStatusUiState(loading = false, request = req), tf(v), {}, {}) }
        shoot("status-aprovado") { v ->
            AccessRequestStatusContent(AccessRequestStatusUiState(loading = false, request = req.copy(status = "approved",
                adminNote = "Bem-vinda! Qualquer dúvida sobre o sensor, responda este e-mail.", decidedAt = "2026-10-18T10:40:00Z")),
                tf(v), {}, {})
        }
        shoot("status-nenhum") { v -> AccessRequestStatusContent(AccessRequestStatusUiState(loading = false, notFound = true), tf(v), {}, {}) }
        shoot("status-erro") { v ->
            AccessRequestStatusContent(AccessRequestStatusUiState(loading = false, error = "HTTP 503"), tf(v), {}, {})
        }

        shoot("onboarding-1") { OnboardingContent(NotifPermission.NOT_ASKED, {}, {}, initialPage = 0) }
        shoot("onboarding-2") { OnboardingContent(NotifPermission.NOT_ASKED, {}, {}, initialPage = 1) }
        shoot("onboarding-3") { OnboardingContent(NotifPermission.NOT_ASKED, {}, {}, initialPage = 2) }
        shoot("onboarding-3-negada") { OnboardingContent(NotifPermission.DENIED, {}, {}, initialPage = 2) }

        shoot("termos") { v -> TermsContent(v.lang, accepting = false, onAccept = {}) }
        shoot("termos-carregando") { TermsContent(null, accepting = false, onAccept = {}) }
        harness.writeReport("entrada")
    }
}
