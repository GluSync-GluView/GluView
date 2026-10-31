package com.gluview.app.visualaudit

import android.app.Application
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.gluview.app.R
import com.gluview.app.ui.components.kit.AppTopBar
import com.gluview.app.ui.screen.report.ExportKind
import com.gluview.app.ui.screen.report.ReportContent
import com.gluview.app.ui.screen.report.ReportUiState
import com.gluview.app.ui.components.chart.ChartAppearance
import com.gluview.app.ui.screen.caregiver.FollowedPatientContent
import com.gluview.app.ui.screen.chart.ChartContent
import com.gluview.app.ui.screen.chart.ChartMode
import com.gluview.app.ui.screen.chart.ChartRange
import com.gluview.app.ui.screen.chart.ChartWindow
import com.gluview.app.ui.screen.dailypattern.DailyPatternContent
import com.gluview.app.ui.screen.dailypattern.PatternPeriod
import com.gluview.app.ui.screen.history.HistoryScreen
import com.gluview.app.ui.screen.home.FollowerHomeContent
import com.gluview.app.ui.screen.home.PatientHomeContent
import com.gluview.app.ui.screen.tir.TirContent
import com.gluview.app.ui.screen.tir.TirUiState
import com.gluview.app.util.GlucoseUnit
import com.gluview.app.util.TimeFormat
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant
import java.util.Locale
import java.util.TimeZone

/**
 * Capturas de tela inteira + auditoria (Fase 0 do redesign: baseline ANTES; nas fases seguintes,
 * o DEPOIS de cada tela). Lento: só roda com -Pgluview.visualaudit.screens=true. Exemplo:
 *
 *   gradlew :app:testDebugUnitTest --tests "*ScreensCaptureTest*" -Pgluview.visualaudit.screens=true
 *     -Pgluview.visualaudit.run=antes -Proborazzi.test.record=true --rerun
 *
 * Saída: PNGs em app/build/visual-audit/screens/<run>/<tela>/, mais audit-<parte>.md e .tsv.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class)
class ScreensCaptureTest {

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

    @Test fun home() {
        for ((name, state) in Scenarios.homeScenarios) {
            val variants = if (name == "normal_sensor") Matrix.FULL else Matrix.REDUCED
            for (v in variants) {
                val s = state(v.dark)
                harness.capture("home", name, v, Chrome.PATIENT_HOME, waitForChart = !s.homeReadings.isNullOrEmpty()) {
                    PatientHomeContent(s, {}, {}, {}, {}, {})
                }
            }
        }
        harness.writeReport("home")
    }

    @Test fun follower() {
        for ((name, ui) in Scenarios.followerScenarios) {
            val variants = if (name == "com_dados") Matrix.FULL else Matrix.REDUCED
            val chart = !ui.loading && ui.error == null && ui.patientId.isNotBlank() && ui.readings.isNotEmpty()
            for (v in variants) harness.capture("seguidor", name, v, Chrome.FOLLOWER_HOME, waitForChart = chart) {
                FollowerHomeContent(ui, alertCount = 1, {}, {}, {}, {})
            }
        }
        harness.writeReport("seguidor")
    }

    @Test fun followedPatient() {
        for ((name, ui) in Scenarios.followedScenarios) {
            val chart = ui.error == null && ui.readings.isNotEmpty()
            for (v in Matrix.REDUCED) harness.capture("acompanhado", name, v, Chrome.NONE, waitForChart = chart) {
                FollowedPatientContent(patientName = "Ana", ui = ui, onBack = {})
            }
        }
        harness.writeReport("acompanhado")
    }

    @Test fun history() {
        val now = GlucoseFixtures.FIXED_NOW
        val readings = GlucoseFixtures.series(now, hours = 3)
        val today = Instant.ofEpochMilli(now).atZone(GlucoseFixtures.ZONE).toLocalDate()

        // Nome da captura → aba aberta, estado do TIR e erro do Gráfico (os estados não escondem o seletor).
        data class Case(val tab: String, val tir: TirUiState = Scenarios.tirState, val chartError: String? = null,
                        val subject: String? = null, val chrome: Chrome = Chrome.PATIENT_HISTORY)
        val cases = linkedMapOf(
            "chart" to Case("chart"), "tir" to Case("tir"), "pattern" to Case("pattern"),
            "tir-carregando" to Case("tir", TirUiState(isLoading = true)),
            "tir-erro" to Case("tir", TirUiState(error = "HTTP 503")),
            "tir-vazio" to Case("tir", TirUiState(report = Scenarios.tirReport(0))),
            "chart-erro" to Case("chart", chartError = "HTTP 503"),
            // Seguidor: a faixa diz de quem é o dado.
            "seguidor-tir" to Case("tir", subject = "Maria Eduarda", chrome = Chrome.FOLLOWER_HISTORY)
        )
        for ((name, case) in cases) for (v in Matrix.REDUCED) {
            // Gráfico novo desenha síncrono no Canvas do Compose: sem espera extra (o Vico precisava de 1,5 s).
            harness.capture("historico", name, v, case.chrome) {
                // Sem onBack, como no app (aba principal não tem seta de voltar).
                HistoryScreen(
                    initialTab = case.tab,
                    subjectName = case.subject,
                    chartTab = {
                        ChartContent(
                            mode = ChartMode.Relative(ChartRange.H3),
                            window = ChartWindow(now - 3 * 3_600_000L, now),
                            isLoading = false, error = case.chartError, isEmpty = false,
                            points = readings,
                            appearance = ChartAppearance(dark = v.dark, unit = GlucoseUnit.MG_DL, timeFormat = TimeFormat.H24),
                            today = { today },
                            onRangeSelected = {}, onShiftDay = {}, onDateSelected = {}
                        )
                    },
                    tirTab = { TirContent(state = case.tir, onPeriodSelected = {}, chartDark = v.dark, onRetry = {}) },
                    patternTab = {
                        DailyPatternContent(
                            period = PatternPeriod.D14, points = Scenarios.patternPoints,
                            isLoading = false, error = null,
                            appearance = ChartAppearance(dark = v.dark), onPeriodSelected = {}
                        )
                    }
                )
            }
        }
        harness.writeReport("historico")
    }

    @Test fun report() {
        val ok = ReportUiState(report = Scenarios.summaryReport(), mes = 10, ano = 2026)
        val cases = linkedMapOf(
            "pronto" to (ok to null),
            "exportando" to (ok.copy(exporting = ExportKind.PDF) to null),
            "carregando" to (ReportUiState(isLoading = true, mes = 10, ano = 2026) to null),
            "erro" to (ReportUiState(error = "HTTP 503", mes = 10, ano = 2026) to null),
            "seguidor" to (ok to "Maria Eduarda")
        )
        for ((name, case) in cases) for (v in Matrix.REDUCED) {
            harness.capture("relatorio", name, v, Chrome.NONE) {
                Scaffold(topBar = { AppTopBar(title = stringResource(R.string.settings_medical_report), onBack = {}) }) { p ->
                    Box(Modifier.padding(p)) {
                        ReportContent(
                            state = case.first, unit = GlucoseUnit.MG_DL, timeFormat = TimeFormat.H24, subjectName = case.second,
                            onMonthChanged = { _, _ -> }, onRetry = {}, onExportPdf = {}, onExportCsv = {}
                        )
                    }
                }
            }
        }
        harness.writeReport("relatorio")
    }
}
