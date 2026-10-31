package com.gluview.app.visualaudit

import android.app.Application
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.gluview.app.ui.screen.caregiver.FollowedPatientContent
import com.gluview.app.ui.screen.home.FollowerHomeContent
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale
import java.util.TimeZone

/**
 * As telas do seguidor no perfil do Moto Z2 Play (Android 9 = SDK 28, 360×640dp, fonte linear até
 * 1,3). Mesma chave das outras capturas: -Pgluview.visualaudit.screens=true. Saída em
 * screens/<run>/seguidor-moto e acompanhado-moto.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [28], application = Application::class)
class ScreensCaptureMotoTest {

    @get:Rule val compose = createEmptyComposeRule()

    private val run = System.getProperty("gluview.visualaudit.run")?.takeIf { it.isNotBlank() } ?: "local"
    private lateinit var harness: ScreenHarness
    private lateinit var savedZone: TimeZone
    private lateinit var savedLocale: Locale

    @Before fun setUp() {
        assumeTrue("capturas de tela desligadas", System.getProperty("gluview.visualaudit.screens") == "true")
        savedZone = TimeZone.getDefault(); savedLocale = Locale.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(GlucoseFixtures.ZONE))
        harness = ScreenHarness(compose, run)
    }

    @After fun tearDown() {
        if (::savedZone.isInitialized) { TimeZone.setDefault(savedZone); Locale.setDefault(savedLocale) }
    }

    @Test fun followerOnMoto() {
        for ((name, ui) in Scenarios.followerScenarios) {
            val chart = !ui.loading && ui.error == null && ui.patientId.isNotBlank() && ui.readings.isNotEmpty()
            for (v in Matrix.MOTO) harness.capture("seguidor-moto", name, v, Chrome.FOLLOWER_HOME, waitForChart = chart) {
                FollowerHomeContent(ui, alertCount = 1, {}, {}, {}, {})
            }
        }
        for ((name, ui) in Scenarios.followedScenarios) {
            val chart = ui.error == null && ui.readings.isNotEmpty()
            for (v in Matrix.MOTO) harness.capture("acompanhado-moto", name, v, Chrome.NONE, waitForChart = chart) {
                FollowedPatientContent(patientName = "Ana", ui = ui, onBack = {})
            }
        }
        harness.writeReport("moto")
    }
}
