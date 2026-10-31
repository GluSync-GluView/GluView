package com.gluview.app.visualaudit

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import kotlin.math.roundToInt
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import org.robolectric.RuntimeEnvironment
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.gluview.app.ui.components.CHART_TEST_TAG
import com.gluview.app.ui.components.LocalClock
import com.gluview.app.ui.components.chart.ChartAppearance
import com.gluview.app.ui.components.chart.GlucoseChart
import com.gluview.app.ui.components.chart.GlucoseChartPanel
import com.gluview.app.ui.components.chart.appChartSpec
import com.gluview.app.ui.components.chart.chartLabelPx
import com.gluview.app.ui.components.kit.LoadState
import com.gluview.app.ui.theme.GluViewTheme
import com.gluview.app.ui.theme.Spacing
import com.gluview.app.util.GlucoseGraphRenderer
import com.gluview.app.util.GlucoseUnit
import com.gluview.app.util.GraphReading
import com.gluview.app.util.TimeFormat
import com.gluview.app.util.UiText
import com.gluview.app.util.graph.GraphWindow
import com.gluview.app.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
 * Componentes do gráfico da Fase 2 (ui/components/chart): vitrine capturada e auditada nas 6
 * variantes extremas (0 achados) e a prova de que o app desenha o MESMO gráfico das superfícies.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class)
class ChartComponentsTest {

    @get:Rule val compose = createEmptyComposeRule()

    private val now = GlucoseFixtures.FIXED_NOW
    private lateinit var savedZone: TimeZone
    private lateinit var savedLocale: Locale

    @Before fun setUp() {
        savedZone = TimeZone.getDefault(); savedLocale = Locale.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(GlucoseFixtures.ZONE))
    }

    @After fun tearDown() { TimeZone.setDefault(savedZone); Locale.setDefault(savedLocale) }

    private val readings = GlucoseFixtures.withGap(GlucoseFixtures.outOfScale(now), fromMinAgo = 120, toMinAgo = 80)

    @Test fun vitrineDoGraficoSemAchados() {
        val harness = ScreenHarness(compose, "chart")
        val tall = Matrix.REDUCED.map { it.copy(profile = it.profile.copy(name = it.profile.name + "alto", heightDp = 1800)) }
        for (v in tall) harness.capture("chart", "estados", v) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.md),
                    verticalArrangement = Arrangement.spacedBy(Spacing.lg)
                ) {
                    GlucoseChartPanel(title = "Histórico", periods = listOf(1, 3, 6, 12, 24), selectedPeriod = 3) {
                        GlucoseChart(LoadState.Ready(readings), GraphWindow.Trailing(3), ChartAppearance(dark = true),
                            Modifier.height(260.dp))
                    }
                    GlucoseChartPanel(title = "Seguidor (claro, mmol/L, 12h)") {
                        GlucoseChart(LoadState.Ready(readings), GraphWindow.Trailing(3),
                            ChartAppearance(dark = false, unit = GlucoseUnit.MMOL_L, timeFormat = TimeFormat.H12),
                            Modifier.height(260.dp))
                    }
                    GlucoseChart(LoadState.Loading, GraphWindow.Trailing(3), ChartAppearance(dark = true))
                    GlucoseChart(LoadState.Empty, GraphWindow.Trailing(3), ChartAppearance(dark = false))
                    GlucoseChart(LoadState.Error(UiText.Res(R.string.common_load_error_title), "HTTP 503"),
                        GraphWindow.Trailing(3), ChartAppearance(dark = true), onRetry = {})
                }
            }
        }
        harness.writeReport("chart")
        val detail = harness.issues.joinToString("\n") { "${it.capture} · ${it.kind} · ${it.detail}" }
        assertTrue("achados nos componentes do gráfico:\n$detail", harness.issues.isEmpty())
    }

    @Test fun toqueAbreBalaoComRotuloDeFechar() {
        Locale.setDefault(Locale.forLanguageTag("pt-BR"))
        RuntimeEnvironment.setQualifiers("pt-rBR-w384dp-h832dp-450dpi")
        ActivityScenario.launch(ComponentActivity::class.java).use { sc ->
            sc.onActivity { a ->
                a.setContent {
                    CompositionLocalProvider(LocalClock provides { now }) {
                        GluViewTheme {
                            Box(Modifier.padding(Spacing.md)) {
                                GlucoseChart(LoadState.Ready(GlucoseFixtures.series(now, hours = 3)), GraphWindow.Trailing(3),
                                    ChartAppearance(dark = true), Modifier.height(300.dp))
                            }
                        }
                    }
                }
            }
            compose.waitForIdle()
            // Toque perto da borda direita: o balão encosta na borda e o rabicho segue o ponto.
            compose.onNodeWithTag(CHART_TEST_TAG).performTouchInput { click(Offset(width - 20f, centerY)) }
            compose.waitForIdle()
            compose.onNodeWithContentDescription("Fechar").assertExists()
            val issues = VisualAudit.audit(compose, "chart__balao")
            assertTrue("achados com o balão aberto: $issues", issues.isEmpty())
        }
    }

    /** O app (Canvas do Compose) e as superfícies (bitmap) desenham o mesmo gráfico, pixel a pixel. */
    @Test fun appDesenhaOMesmoGraficoDoBitmap() {
        Locale.setDefault(Locale.forLanguageTag("pt-BR"))
        val sizeDp = 360
        val heightDp = 240
        val appearance = ChartAppearance(dark = true)
        val data = GlucoseFixtures.series(now, hours = 3)
        var labelPx = 0f
        var density = 1f
        RuntimeEnvironment.setQualifiers("w400dp-h800dp-450dpi")
        ActivityScenario.launch(ComponentActivity::class.java).use { sc ->
            sc.onActivity { a ->
                a.setContent {
                    CompositionLocalProvider(LocalClock provides { now }) {
                        GluViewTheme {
                            val d = LocalDensity.current
                            labelPx = chartLabelPx(d); density = d.density
                            Box(Modifier.size(sizeDp.dp, heightDp.dp)) {
                                GlucoseChart(LoadState.Ready(data), GraphWindow.Trailing(3), appearance,
                                    Modifier.fillMaxSize(), interactive = false)
                            }
                        }
                    }
                }
            }
            compose.waitForIdle()
            // Desenha a janela inteira num bitmap e recorta a caixa do gráfico (o captureToImage
            // espera um quadro que não vem sob o Robolectric).
            val b = compose.onNodeWithTag(CHART_TEST_TAG).fetchSemanticsNode().boundsInWindow
            lateinit var app: Bitmap
            sc.onActivity { a ->
                val v = a.window.decorView
                val full = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
                v.draw(Canvas(full))
                app = Bitmap.createBitmap(full, b.left.roundToInt(), b.top.roundToInt(), b.width.roundToInt(), b.height.roundToInt())
            }
            val surface = GlucoseGraphRenderer.render(data, appChartSpec(app.width, app.height, GraphWindow.Trailing(3), now, appearance, labelPx))
            assertEquals(surface.width, app.width)
            // Cantos de 12dp recortados pelo painel ficam de fora. O resto é o MESMO desenho (mesmas
            // DrawOp): a diferença tolerada é só a suavização de borda, que arredonda diferente no
            // Canvas do Compose e no do bitmap — 1–2 níveis nos ticks finos, até 8 na borda dos
            // pontos e da curva (medido em 17/10/2026). Deslocamento, cor ou elemento faltando dão
            // diferenças muito maiores e em muitos pixels.
            val corner = (12 * density).toInt() + 1
            var compared = 0
            var soft = 0
            var hard = 0
            for (y in 0 until app.height) for (x in 0 until app.width) {
                val inCorner = (x < corner || x >= app.width - corner) && (y < corner || y >= app.height - corner)
                if (inCorner) continue
                compared++
                val p = app.getPixel(x, y); val q = surface.getPixel(x, y)
                val delta = intArrayOf(0, 8, 16, 24).maxOf { sh -> kotlin.math.abs(((p shr sh) and 255) - ((q shr sh) and 255)) }
                if (delta > 16) hard++ else if (delta > 2) soft++
            }
            assertTrue("comparou só $compared pixels", compared > 500_000)
            assertEquals("pixels do app muito diferentes do bitmap", 0, hard)
            assertTrue("suavização diferente em pixels demais: $soft de $compared", soft * 1000 <= compared)
        }
    }
}

