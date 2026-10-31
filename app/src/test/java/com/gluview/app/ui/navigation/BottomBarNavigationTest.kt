package com.gluview.app.ui.navigation

import android.app.Application
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Barra inferior + NavHost montados como no AppNavigation (a barra só existe nas abas principais,
 * então nasce com a rota "home"): toda troca de aba, inclusive a volta para Início, tem que chegar
 * à tela da aba. Regressão do bug de 04/10, em que Início não saía de nenhuma outra aba.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "pt-rBR")
class BottomBarNavigationTest {

    @get:Rule val compose = createComposeRule()

    @Composable
    private fun AppLikeTabs() {
        val nav = rememberNavController()
        val backEntry by nav.currentBackStackEntryAsState()
        val currentRoute = backEntry?.destination?.route
        // Como no app: a barra só existe nas abas principais, então ela nasce já com a rota "home".
        val mainRoutes = setOf(Screen.Home.route, Screen.SettingsSensor.route, Screen.InsulinPump.route, Screen.Chart.route, Screen.Settings.route)
        Scaffold(bottomBar = {
            if (currentRoute in mainRoutes) {
                MainBottomBar(currentRoute = currentRoute, isFollowerOnly = false, insulinPumpTabEnabled = true, onNavigate = { nav.navigateToTab(it) })
            }
        }) { p ->
            NavHost(nav, startDestination = Screen.Home.route, modifier = Modifier.padding(p)) {
                composable(Screen.Home.route) { Text("TELA_HOME") }
                composable(Screen.SettingsSensor.route) { Text("TELA_SENSOR") }
                composable(Screen.Chart.route) { Text("TELA_HISTORICO") }
                composable(Screen.InsulinPump.route) { Text("TELA_BOMBA") }
                composable(Screen.Settings.route) { Text("TELA_AJUSTES") }
            }
        }
    }

    private val tela = mapOf("Início" to "TELA_HOME", "Sensor" to "TELA_SENSOR", "Histórico" to "TELA_HISTORICO",
        "Bomba" to "TELA_BOMBA", "Ajustes" to "TELA_AJUSTES")

    /** Toca as abas na ordem e confere, a cada toque, que a tela da aba apareceu. */
    private fun walk(vararg tabs: String) {
        val log = StringBuilder()
        for (t in tabs) {
            compose.onNodeWithText(t).performClick()
            compose.waitForIdle()
            val ok = compose.onAllNodesWithText(tela.getValue(t)).fetchSemanticsNodes().isNotEmpty()
            log.append("$t:${if (ok) "ok" else "FALHOU"} ")
            if (!ok) throw AssertionError("sequência ${tabs.joinToString(">")} → $log")
        }
    }

    @Test fun sensorDepoisInicio() { compose.setContent { AppLikeTabs() }; walk("Sensor", "Início") }
    @Test fun sensorHistoricoInicio() { compose.setContent { AppLikeTabs() }; walk("Sensor", "Histórico", "Início") }
    @Test fun historicoSensorInicio() { compose.setContent { AppLikeTabs() }; walk("Histórico", "Sensor", "Início") }
    @Test fun idaEVoltaDuasVezes() { compose.setContent { AppLikeTabs() }; walk("Sensor", "Início", "Sensor", "Início") }
    @Test fun todasAsAbas() {
        compose.setContent { AppLikeTabs() }
        walk("Sensor", "Histórico", "Bomba", "Ajustes", "Início", "Ajustes", "Sensor", "Início", "Histórico", "Início")
    }
}
