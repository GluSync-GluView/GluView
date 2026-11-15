package com.gluview.app.visualaudit

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.gluview.app.domain.agp.AgpCalculator
import com.gluview.app.ui.screen.agp.AgpChart
import com.gluview.app.ui.screen.agp.AgpContent
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gluview.app.ui.screen.agp.AgpPeriod
import com.gluview.app.ui.screen.agp.AgpUiState
import com.gluview.app.util.GlucoseUnit
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
import kotlin.math.PI
import kotlin.math.sin

/** PT-4 — tela do AGP. Só roda com -Pgluview.visualaudit.screens=true; PNGs em build/visual-audit/screens/<run>/agp/. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class)
class AgpCaptureTest {

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
        for (v in Matrix.REDUCED) harness.capture("agp", name, v, Chrome.NONE) { content(v) }
    }

    @Test fun agp() {
        val day = 86_400_000L
        val to = GlucoseFixtures.FIXED_NOW
        fun report(days: Int, coverDays: Int) = AgpCalculator.compute(
            (0 until coverDays * 288).map { i ->
                val t = to - days * day + i * 300_000L
                val hour = ((t / 3_600_000L) % 24).toDouble()
                val noise = ((i * 7919) % 61) - 30.0
                AgpCalculator.Sample(t, 140 + 45 * sin((hour - 4) / 24 * 2 * PI) + noise + (i / 288 % 3) * 12)
            }, to - days * day, to + 1, GlucoseFixtures.ZONE)
        shoot("agp-14d") { v ->
            AgpContent(AgpUiState(AgpPeriod.D14, report(14, 14), isLoading = false),
                if (v.lang == "en") GlucoseUnit.MMOL_L else GlucoseUnit.MG_DL, {}, {}, {}, {})
        }
        shoot("agp-grafico") { v ->
            AgpChart(report(14, 14), if (v.lang == "en") GlucoseUnit.MMOL_L else GlucoseUnit.MG_DL,
                Modifier.fillMaxWidth().padding(16.dp).height(240.dp))
        }
        shoot("agp-7d-pouco-sensor") { _ ->
            AgpContent(AgpUiState(AgpPeriod.D7, report(7, 4), isLoading = false), GlucoseUnit.MG_DL, {}, {}, {}, {})
        }
    }
}
