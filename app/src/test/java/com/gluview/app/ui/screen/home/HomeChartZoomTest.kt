package com.gluview.app.ui.screen.home

import android.app.Application
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.unit.dp
import com.gluview.app.ui.components.CHART_TEST_TAG
import com.gluview.app.ui.components.chart.ChartAppearance
import com.gluview.app.util.GraphReading
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pinçar o gráfico da Home salva a nova janela (22/11/2026). Antes o pointerInput reiniciava no
 * meio do gesto e o "fim do gesto" (que salvava) nunca chegava: ao sair e voltar, 3 h de novo.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class HomeChartZoomTest {

    @get:Rule val compose = createComposeRule()

    private val readings = (0 until 48 * 12).map { i ->
        GraphReading(System.currentTimeMillis() - i * 300_000L, 120f + (i % 20))
    }

    @Test fun `pincar para fora salva 4 h`() {
        val saved = mutableListOf<Int>()
        compose.setContent {
            HomeChart(readings, selectedHours = 3, onHoursChange = { saved += it },
                appearance = ChartAppearance(dark = false), modifier = Modifier.fillMaxWidth().height(300.dp))
        }
        val node = compose.onNodeWithTag(CHART_TEST_TAG, useUnmergedTree = true)
        // Como no aparelho: a pinça dura vários quadros e a tela recompõe NO MEIO do gesto
        // (o pinch() pronto injeta tudo de uma vez e não pegava o defeito).
        node.performTouchInput {
            down(0, Offset(width * 0.1f, center.y)); down(1, Offset(width * 0.9f, center.y))
        }
        for (step in 1..10) {
            node.performTouchInput {
                val d = width * 0.01f * step   // fecha até 0,6 da largura (fator 0,75: um passo)
                moveTo(0, Offset(width * 0.1f + d, center.y)); moveTo(1, Offset(width * 0.9f - d, center.y))
            }
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
        node.performTouchInput { up(0); up(1) }
        compose.waitForIdle()
        val desc = compose.onNodeWithTag(CHART_TEST_TAG, useUnmergedTree = true).fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.ContentDescription].joinToString()
        assertEquals("desc=$desc saved=$saved", 4, saved.lastOrNull())
    }
}
