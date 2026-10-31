package com.gluview.app.visualaudit

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.gluview.app.data.donation.PendingDonation
import com.gluview.app.ui.screen.donation.DonationContent
import com.gluview.app.ui.screen.donation.DonationRange
import com.gluview.app.ui.screen.donation.DonationStep
import com.gluview.app.ui.screen.donation.DonationUi
import com.gluview.app.ui.screen.donation.DonationActions
import com.gluview.app.ui.screen.settings.SettingsContent
import com.gluview.app.ui.screen.settings.SettingsNav
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
 * Doação via Pix (plano 09_doacao_pix_plano.md): valor (com erro de criação),
 * Pix, agradecimento, "agora não", vencido e a entrada nos Ajustes. O aviso é um AppDialog, que abre
 * em outra janela e fica fora da captura (mesma limitação do KitShowcaseTest). Só roda com
 * -Pgluview.visualaudit.screens=true; PNGs em build/visual-audit/screens/<run>/doacao/.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class)
class DonationCaptureTest {

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

    private val range = DonationRange(100, 100_000, listOf(500, 1000, 2000, 5000))

    // QR de verdade (PNG gerado com a lib qrcode), com texto de teste — não é um Pix real.
    private val qr = "iVBORw0KGgoAAAANSUhEUgAAAGwAAABsAQAAAACnHWpgAAAA5ElEQVR4nJ1V22kEQQyTw/7LHWz/ZaUDqQIF78FxIYTFa+bDZkbI76ngQ/z1aQE3pqvKDXuUu8f4LUoUXCfSjhcuAByH6/7xX9NE69/bG/Pb4hMsA5y0L2WFLTXw8reBZa6Y5MJxlG2NJJAUwHWNiicNYdBbXgw3haHf8R6M2TBE73n18lfgticPMJNk9APeiFMekdzGi4SEpiWjLVaESEQD3mFr9sZE2jG9nAXPuuAJGTh32AOBWSnxwey74ICojh7MPssA2b31eeRqam/jfe3JUJqEb3uyADXZWs/gVd+37P6FH0N0f6JuwcuEAAAAAElFTkSuQmCC"

    private val pending = PendingDonation(
        id = "7581a3b4-0796-42d9-a029-f366b8a1c4fe",
        clientToken = "token",
        amountCents = 2000,
        qrCodeBase64 = qr,
        copiaECola = "00020126580014br.gov.bcb.pix0136123e4567-e12b-12d1-a456-4266554400005204000053039865406" +
            "20.005802BR5913GLUVIEW DOACAO6009SAO PAULO62070503***6304ABCD",
        expiresAtMs = GlucoseFixtures.FIXED_NOW + 30 * 60_000L
    )

    private fun shoot(name: String, content: @Composable () -> Unit) {
        for (v in Matrix.REDUCED) harness.capture("doacao", name, v, Chrome.NONE, content = content)
    }

    @Test fun donation() {
        val states = listOf(
            "valor" to DonationUi(DonationStep.Amount, range),
            "valor-erro" to DonationUi(DonationStep.Amount, range, createError = true),
            "pix" to DonationUi(DonationStep.Pix(pending), range.copy(isStatic = false)),
            "pix-fixo" to DonationUi(DonationStep.StaticPix(2000, pending.copiaECola, qr), range),
            "obrigado" to DonationUi(DonationStep.Thanks(2000), range),
            "agora-nao" to DonationUi(DonationStep.Declined, range),
            "vencido" to DonationUi(DonationStep.Expired, range),
            "indisponivel" to DonationUi(DonationStep.Unavailable, null),
            "sem-internet" to DonationUi(DonationStep.LoadError, null)
        )
        for ((name, ui) in states) shoot(name) { DonationContent(ui, DonationActions()) }
        shoot("ajustes-com-doacao") {
            SettingsContent(isAdmin = false, isFollowerOnly = false, isLocalOnly = false, debugSeedEnabled = false, debugBusy = false,
                nav = SettingsNav({}, {}, {}, {}, {}, {}, {}, {}), onDebugSeedToggle = {}, onDebugBackfill = {}, onLogout = {},
                showDonate = true)
        }
        harness.writeReport("doacao")
    }
}
