package com.gluview.app.visualaudit

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.gluview.app.data.local.entity.SensorHistoryEntity
import com.gluview.app.data.sensor.FailureReason
import com.gluview.app.data.sensor.SensorCode
import com.gluview.app.data.sensor.SetupState
import com.gluview.app.data.source.DataSourceType
import com.gluview.app.ui.screen.libre2.Libre2ActivationContent
import com.gluview.app.ui.screen.libre2.Libre2ActivationUiState
import com.gluview.app.ui.screen.miband.MiBandActions
import com.gluview.app.ui.screen.miband.MiBandContent
import com.gluview.app.ui.screen.miband.MiBandOutcome
import com.gluview.app.ui.screen.miband.MiBandUi
import com.gluview.app.ui.screen.sensorscan.FoundDevice
import com.gluview.app.ui.screen.sensorscan.SensorScanActions
import com.gluview.app.ui.screen.sensorscan.SensorScanUiState
import com.gluview.app.ui.screen.sensorscan.SensorScannerContent
import com.gluview.app.ui.screen.sensorsetup.SensorOption
import com.gluview.app.ui.screen.sensorsetup.SensorSetupContent
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
import java.util.TimeZone

/**
 * Sensor (redesign visual, Fase 8): assistente em todos os passos, ajustes avançados do
 * Sibionics, Libre 2 (NFC) e Mi Band, na matriz reduzida. Só roda com
 * -Pgluview.visualaudit.screens=true; PNGs em build/visual-audit/screens/<run>/sensor/.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class)
class SensorFlowCaptureTest {

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
        for (v in Matrix.REDUCED) harness.capture("sensor", name, v, Chrome.NONE) { content(v) }
    }

    private val now = GlucoseFixtures.FIXED_NOW
    private val day = 86_400_000L

    @Test fun sensor() {
        val options = DataSourceType.entries.filter { it != DataSourceType.GLUSYNC }.map {
            SensorOption(it, isCurrent = it == DataSourceType.SIBIONICS_CHINES, isSupported = !it.comingSoon)
        }.sortedByDescending { it.isCurrent }
        val type = DataSourceType.SIBIONICS_CHINES
        fun setup(state: SetupState) = @Composable { _: Variant ->
            SensorSetupContent(state, options, {}, {}, {}, {}, {}, {}, {})
        }
        shoot("setup-escolher", setup(SetupState.ChoosingSensor))
        shoot("setup-caixa", setup(SetupState.Instructions(type)))
        shoot("setup-buscando", setup(SetupState.Searching(type, SensorCode("1A2B3C4D"))))
        shoot("setup-pronto", setup(SetupState.Ready(type, 60)))
        shoot("setup-falha-bt", setup(SetupState.Failed(FailureReason.BLUETOOTH_UNAVAILABLE, type)))

        // Accu-Chek SmartGuide (F4): caixa com "digitar serial", serial, PIN, pareando, falhas, pronto.
        val sg = DataSourceType.ACCU_CHEK_SMARTGUIDE
        val sgCode = SensorCode("1R009999990", "1R009999990")
        shoot("sg-caixa", setup(SetupState.Instructions(sg)))
        shoot("sg-serial", setup(SetupState.EnteringSerial(sg)))
        shoot("sg-serial-invalido", setup(SetupState.EnteringSerial(sg, invalid = true)))
        shoot("sg-pin", setup(SetupState.EnteringPin(sg, sgCode, java.time.LocalDate.of(2026, 12, 11))))
        shoot("sg-pin-vencido", setup(SetupState.EnteringPin(sg, sgCode, java.time.LocalDate.of(2025, 1, 31), invalid = true)))
        shoot("sg-pareando", setup(SetupState.Pairing(sg, sgCode)))
        shoot("sg-falha-pin", setup(SetupState.Failed(FailureReason.PAIRING_FAILED, sg, sgCode)))
        shoot("sg-falha-tempo", setup(SetupState.Failed(FailureReason.PAIRING_TIMEOUT, sg, sgCode)))
        shoot("sg-pronto-ja-medindo", setup(SetupState.Ready(sg, 0)))

        // Smart 2.0 Medlevensohn: procurar por perto, serial digitado, pareando, pronto.
        val ml = DataSourceType.MEDLEVENSOHN_SMART2
        val mlCode = SensorCode("22222A2WC8", "22222A2WC8")
        shoot("ml-procurar", setup(SetupState.Instructions(ml)))
        shoot("ml-serial-invalido", setup(SetupState.EnteringSerial(ml, invalid = true)))
        shoot("ml-pareando", setup(SetupState.Pairing(ml, mlCode)))
        shoot("ml-pronto", setup(SetupState.Ready(ml, 0)))

        val history = listOf(
            SensorHistoryEntity(1, "C8:2A:7F:10:44:9B", "2509461A2B3C4DEF12", "1A2B3C4D", "SIBIONICS_CHINES", now - 9 * day),
            SensorHistoryEntity(2, "C8:2A:7F:10:12:01", "2508212P3R6S9TVA34", "P3R6S9TV", "SIBIONICS_CHINES", now - 24 * day, now - 9 * day)
        )
        val configured = SensorScanUiState(
            scanning = true,
            devices = listOf(FoundDevice("LT2509461A2B3C4D", "C8:2A:7F:10:44:9B", -58), FoundDevice("LT2508212P3R6S9T", "C8:2A:7F:10:12:01", -84)),
            serial = "2509461A2B3C4DEF12", customerId = "1A2B3C4D",
            savedMac = "C8:2A:7F:10:44:9B", savedSerial = "2509461A2B3C4DEF12", savedCustomerId = "1A2B3C4D",
            lastRawMmol = 7.4, calFactor = 1.042, history = history
        )
        shoot("avancado") { SensorScannerContent(configured, SensorScanActions()) }
        shoot("avancado-novo") { SensorScannerContent(SensorScanUiState(), SensorScanActions()) }
        shoot("avancado-sem-codigo") {
            SensorScannerContent(configured.copy(savedCustomerId = "", scanning = false, devices = emptyList(), lastRawMmol = 0.0, calFactor = 1.0),
                SensorScanActions())
        }

        shoot("libre-aguardando") {
            Libre2ActivationContent(Libre2ActivationUiState(waitingForTag = true), true, {}, {}, {}, {}, {}, {})
        }
        shoot("libre-falha") {
            Libre2ActivationContent(Libre2ActivationUiState(readFailure = "Tag was lost.", savedSerial = "0M00A1B2C3D"),
                true, {}, {}, {}, {}, {}, {})
        }
        shoot("libre-ativado") {
            Libre2ActivationContent(Libre2ActivationUiState(activated = true, serial = "0M00A1B2C3D", savedSerial = "0M00A1B2C3D",
                streamingSupported = false, lastRawMmol = 6.2, calFactor = 1.0), true, {}, {}, {}, {}, {}, {})
        }
        shoot("libre-32bits") { Libre2ActivationContent(Libre2ActivationUiState(), false, {}, {}, {}, {}, {}, {}) }

        fun tf(v: Variant) = if (v.lang == "en") TimeFormat.H12 else TimeFormat.H24
        val band = MiBandUi(mac = "D4:1F:0C:88:21:7A", key = "0a1b2c3d4e5f60718293a4b5c6d7e8f9", autoSend = true,
            outcome = MiBandOutcome.TIMEOUT, log = listOf("Conectando a D4:1F:0C:88:21:7A…", "⏱️ Tempo esgotado (25s)."))
        shoot("miband") { v -> MiBandContent(band, tf(v), MiBandActions()) }
        shoot("miband-erros") { v ->
            MiBandContent(MiBandUi(mac = "D4:1F:0C", key = "123", macError = com.gluview.app.R.string.miband_mac_invalid,
                keyError = com.gluview.app.R.string.miband_key_invalid), tf(v), MiBandActions())
        }
        shoot("miband-agenda") { v -> MiBandContent(band.copy(outcome = MiBandOutcome.SENT, log = emptyList()), tf(v), MiBandActions()) }
        harness.writeReport("sensor")
    }
}
