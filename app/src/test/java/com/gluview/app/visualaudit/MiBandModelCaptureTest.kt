package com.gluview.app.visualaudit

import android.app.Application
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.gluview.app.ui.screen.miband.MiBandActions
import com.gluview.app.ui.screen.miband.MiBandContent
import com.gluview.app.ui.screen.miband.MiBandUi
import com.gluview.app.ui.screen.miband.XiaomiSection
import com.gluview.app.ui.components.kit.Page
import com.gluview.app.util.TimeFormat
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * F7.2 (22/11/2026): tela da Mi Band com o seletor de modelo, sem login (o app de debug no emulador
 * para no login). Mesmo harness das capturas: só roda com -Pgluview.visualaudit.screens=true.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class)
class MiBandModelCaptureTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var harness: ScreenHarness

    @Before fun setUp() {
        assumeTrue("capturas de tela desligadas", System.getProperty("gluview.visualaudit.screens") == "true")
        harness = ScreenHarness(compose, System.getProperty("gluview.visualaudit.run")?.takeIf { it.isNotBlank() } ?: "miband")
    }

    @Test fun seletorDeModelo() {
        for ((name, model) in listOf("mi_band_6" to "mi_band_6", "amazfit_bip" to "amazfit_bip")) {
            for (v in listOf(Variant(Profiles.S23FE, 1f, false, "pt"), Variant(Profiles.NARROW, 1.3f, true, "en"))) {
                harness.capture("miband", name, v) {
                    MiBandContent(MiBandUi(mac = "F3:22:33:44:66:06", model = model), TimeFormat.H24, MiBandActions())
                }
            }
        }
        harness.writeReport("miband")
    }

    @Test fun secaoXiaomi() {
        val ui = MiBandUi(xiaomiMac = "F3:22:33:44:77:08", xiaomiKey = "00112233445566778899aabbccddeeff", xiaomiAlerts = true,
            xiaomiResult = "a chave de autenticação não confere com a pulseira")
        for (v in listOf(Variant(Profiles.S23FE, 1f, false, "pt"), Variant(Profiles.NARROW, 1.3f, true, "en"))) {
            harness.capture("miband", "xiaomi", v) { Page(title = "Mi Band", onBack = {}) { XiaomiSection(ui, MiBandActions()) } }
        }
        harness.writeReport("miband-xiaomi")
    }
}
