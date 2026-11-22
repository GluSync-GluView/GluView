package com.gluview.app.visualaudit

import android.app.Application
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.gluview.app.ui.screen.settings.SearchContext
import com.gluview.app.ui.screen.settings.SensorActions
import com.gluview.app.ui.screen.settings.SensorContent
import com.gluview.app.ui.screen.settings.SensorUi
import com.gluview.app.ui.screen.settings.SettingsContent
import com.gluview.app.ui.screen.settings.SettingsNav
import com.gluview.app.ui.screen.settings.rememberSettingsSearchHits
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Busca em Ajustes e serial no card do sensor (22/11/2026). Só roda com
 * -Pgluview.visualaudit.screens=true; PNGs em build/visual-audit/screens/<run>/ajustes/.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class)
class SettingsSearchCaptureTest {

    @get:Rule val compose = createEmptyComposeRule()
    private val run = System.getProperty("gluview.visualaudit.run")?.takeIf { it.isNotBlank() } ?: "local"
    private lateinit var harness: ScreenHarness

    @Before fun setUp() {
        assumeTrue("capturas de tela desligadas", System.getProperty("gluview.visualaudit.screens") == "true")
        harness = ScreenHarness(compose, run)
    }

    private val variants = listOf(Matrix.REDUCED[0], Matrix.REDUCED[1], Matrix.REDUCED[3])
    private val ctx = SearchContext(isFollowerOnly = false, isLocalOnly = false, isAdmin = false, showDonate = false, dataSource = "SIBIONICS_CHINES")

    private fun hub(name: String, query: String?) {
        for (v in variants) harness.capture("ajustes", name, v) {
            SettingsContent(isAdmin = false, isFollowerOnly = false, isLocalOnly = false, debugSeedEnabled = false, debugBusy = false,
                nav = SettingsNav({}, {}, {}, {}, {}, {}, {}, {}), onDebugSeedToggle = {}, onDebugBackfill = {}, onLogout = {},
                searchQuery = query, searchHits = rememberSettingsSearchHits(ctx))
        }
    }

    @Test fun busca() {
        hub("busca-fechada", null)
        hub("busca-aberta", "")
        hub("busca-alarme", "alarme")
        hub("busca-leitura", "modo leitura")
        hub("busca-nada", "xyzzy")
    }

    @Test fun sensorComSerial() {
        for (v in variants) harness.capture("ajustes", "sensor-serial", v) {
            SensorContent(SensorUi("SIBIONICS_CHINES", true, 14, 20, "ALL", currentSerial = "0M2023CN51"),
                SensorActions({}, {}, {}, {}, {}, {}, {}), onBack = null)
        }
    }
}
