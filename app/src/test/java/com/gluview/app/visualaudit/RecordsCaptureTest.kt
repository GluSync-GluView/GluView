package com.gluview.app.visualaudit

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.gluview.app.data.local.entity.AlertLogEntity
import com.gluview.app.data.local.entity.AppLogEntity
import com.gluview.app.data.local.entity.EventLogEntity
import com.gluview.app.ui.screen.alerthistory.AlertHistoryContent
import com.gluview.app.ui.screen.applog.LogContent
import com.gluview.app.ui.screen.diagnostic.DiagnosticContent
import com.gluview.app.ui.screen.diagnostic.DiagnosticUiState
import com.gluview.app.ui.screen.diagnostic.FingerstickPair
import com.gluview.app.ui.screen.eventlog.EventLogContent
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
import java.util.TimeZone

/**
 * Registros e suporte (redesign visual, Fase 7): Eventos, Histórico de alertas, Diagnóstico e
 * Logs, na matriz reduzida, com dados de vários dias e todos os tipos de linha.
 * Só roda com -Pgluview.visualaudit.screens=true; PNGs em build/visual-audit/screens/<run>/registros/.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class)
class RecordsCaptureTest {

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

    private val now = GlucoseFixtures.FIXED_NOW
    private fun ago(min: Long) = now - min * 60_000L

    private fun shoot(name: String, content: @Composable (TimeFormat) -> Unit) {
        for (v in Matrix.REDUCED) {
            val tf = if (v.lang == "en") TimeFormat.H12 else TimeFormat.H24
            harness.capture("registros", name, v, Chrome.NONE) { content(tf) }
        }
    }

    @Test fun records() {
        val events = listOf(
            EventLogEntity(1, ago(40), "insulin", "", 4.5, 182.0),
            EventLogEntity(2, ago(60), "meal", "Almoço: arroz, feijão e salada", 60.0, 168.0),
            EventLogEntity(3, ago(60 * 26), "exercise", "", 30.0, 0.0),
            EventLogEntity(4, ago(60 * 24 * 4), "note", "Troquei o sensor no braço esquerdo", 0.0, 121.0)
        )
        shoot("eventos") { tf -> EventLogContent(events, GlucoseUnit.MG_DL, tf, {}, {}, {}, nowMs = now) }
        shoot("eventos-vazio") { tf -> EventLogContent(emptyList(), GlucoseUnit.MG_DL, tf, {}, {}, {}, nowMs = now) }

        val alerts = listOf(
            AlertLogEntity(1, ago(15), 52.0, "URGENT_LOW", "dnd"),
            AlertLogEntity(2, ago(90), 0.0, "", kind = "upload_error", title = "Erro ao gravar glicemia",
                message = "Há mais de 1 h sem conseguir enviar as leituras ao GluSync."),
            AlertLogEntity(3, ago(60 * 20), 248.0, "HIGH", "notification", dismissed = true),
            AlertLogEntity(4, ago(60 * 22), 75.0, "PREDICTIVE", "alarm", dismissed = true),
            AlertLogEntity(5, ago(60 * 24 * 3), 0.0, "", kind = "caregiver_invite", title = "Novo convite",
                message = "Maria Eduarda quer acompanhar a sua glicemia.")
        )
        shoot("alertas") { tf -> AlertHistoryContent(alerts, GlucoseUnit.MG_DL, tf, {}, {}, {}, {}, {}, nowMs = now) }
        shoot("alertas-mmol") { tf -> AlertHistoryContent(alerts, GlucoseUnit.MMOL_L, tf, {}, {}, {}, {}, {}, nowMs = now) }

        val pairs = listOf(
            FingerstickPair(ago(30), 142.0, 150.0, -8.0, -5.3),
            FingerstickPair(ago(60 * 5), 96.0, 120.0, -24.0, -20.0)
        )
        shoot("diagnostico-sensor") { tf ->
            DiagnosticContent(DiagnosticUiState(serviceRunning = true, isSibionicsActive = true, sensorConnected = true,
                sensorDaysElapsed = 11, sensorLastMin = 1, sensorRssi = -86, sensorBatteryMv = 2750, currentSgvMgDl = 142.0,
                pairs = pairs, mard = 12.6), tf, {}, {}, nowMs = now)
        }
        shoot("diagnostico-nuvem") { tf ->
            DiagnosticContent(DiagnosticUiState(serviceRunning = true, signalRConnected = false, lastSyncAt = ago(47),
                syncError = "Falha ao buscar dados: HTTP 503"), tf, {}, {}, nowMs = now)
        }

        val logs = listOf(
            AppLogEntity(1, ago(2), "usage", "SIBIONICS_SRC", "SibionicsBleSource.onReading:212", "Leitura recebida", "{\"sgv\":142,\"rssi\":-86}"),
            AppLogEntity(2, ago(5), "error", "UPLOAD", "GluSyncUploader.upload:88", "Falha ao enviar lote: HTTP 503", "{\"pending\":12}"),
            AppLogEntity(3, ago(9), "usage", "SERVICE", "", "Fonte já está ativa")
        )
        val day = Instant.ofEpochMilli(now).atZone(GlucoseFixtures.ZONE).toLocalDate()
        shoot("logs") { tf -> LogContent(logs, day, false, 3_400_000, tf, true, false, {}, {}, {}, {}, {}) }
        harness.writeReport("registros")
    }
}
