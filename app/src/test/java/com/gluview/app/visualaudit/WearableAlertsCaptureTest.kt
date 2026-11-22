package com.gluview.app.visualaudit

import android.app.Application
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.gluview.app.ui.screen.wearables.WearableAlertsActions
import com.gluview.app.ui.screen.wearables.WearableAlertsContent
import com.gluview.app.ui.screen.wearables.WearableAlertsUi
import com.gluview.app.ui.screen.wearables.WearableFamilyUi
import com.gluview.app.wearables.hub.WearableFamily
import com.gluview.wearables.core.AlertRule
import com.gluview.wearables.core.VibrationStyle
import com.gluview.wearables.core.WearableAlertKind
import com.gluview.wearables.core.WearableAlertSettings
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Plano 21, F7.1 (22/11/2026): tela "Alertas no pulso e no relógio" — Huami ligada com "Chamada" na hipo
 * urgente e o resultado do teste, Xiaomi ligada sem pareamento, os outros desligados. Mesmo harness das
 * capturas: só roda com -Pgluview.visualaudit.screens=true.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class)
class WearableAlertsCaptureTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var harness: ScreenHarness

    @Before fun setUp() {
        assumeTrue("capturas de tela desligadas", System.getProperty("gluview.visualaudit.screens") == "true")
        harness = ScreenHarness(compose, System.getProperty("gluview.visualaudit.run")?.takeIf { it.isNotBlank() } ?: "wearables")
    }

    @Test fun alertasNoPulso() {
        val huami = WearableAlertSettings.defaults(supportsCall = true, enabled = true)
            .with(WearableAlertKind.HIGH, AlertRule(false, VibrationStyle.DOUBLE))
        val ui = WearableAlertsUi(listOf(
            WearableFamilyUi(WearableFamily.HUAMI, huami, configured = true, result = "ok"),
            WearableFamilyUi(WearableFamily.XIAOMI, WearableAlertSettings.defaults(false, enabled = true), configured = false),
            WearableFamilyUi(WearableFamily.WEAR_OS, WearableAlertSettings.defaults(false), configured = true),
            WearableFamilyUi(WearableFamily.GARMIN, WearableAlertSettings.defaults(false), configured = true),
            WearableFamilyUi(WearableFamily.ZEPP, WearableAlertSettings.defaults(false), configured = true),
        ), snoozeMinutes = 30)
        val actions = WearableAlertsActions({}, { _, _ -> }, { _, _, _ -> }, {}, { _, _, _ -> }, {})
        for (v in listOf(Variant(Profiles.S23FE, 1f, false, "pt"), Variant(Profiles.S23FE, 1f, true, "pt"),
            Variant(Profiles.NARROW, 1.3f, true, "en"))) {
            harness.capture("wearables", "alertas-pulso", v) { WearableAlertsContent(ui, actions) }
        }
        harness.writeReport("wearables")
    }
}
