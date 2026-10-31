package com.gluview.app.visualaudit

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.gluview.app.data.remote.dto.WatchfaceStoreListingDto
import com.gluview.app.data.sibionics.SibionicsSensorStatus
import com.gluview.app.domain.model.GlucoseReading
import com.gluview.app.domain.model.GlucoseZone
import com.gluview.app.ui.screen.watchface.EditorActions
import com.gluview.app.ui.screen.watchface.ManagerActions
import com.gluview.app.ui.screen.watchface.SimulatedPreviewInput
import com.gluview.app.ui.screen.watchface.StoreLoadError
import com.gluview.app.ui.screen.watchface.StoreUi
import com.gluview.app.ui.screen.watchface.WatchFaceEditorContent
import com.gluview.app.ui.screen.watchface.WatchFaceEditorUiState
import com.gluview.app.ui.screen.watchface.WatchFaceManagerContent
import com.gluview.app.ui.screen.watchface.WatchFaceRuntimeData
import com.gluview.app.ui.screen.watchface.WatchfaceStoreContent
import com.gluview.app.watchface.WatchFaceDesign
import com.gluview.app.watchface.WatchFacePresets
import com.gluview.app.watchface.WearableCatalog
import com.gluview.app.watchface.toEntity
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
 * Visores (redesign visual, Fase 9): gerenciador, editor e loja, na matriz reduzida, com a prévia
 * desenhando o gráfico como a pulseira. Só roda com -Pgluview.visualaudit.screens=true; PNGs em
 * build/visual-audit/screens/<run>/visores/.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class)
class VisorsCaptureTest {

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

    private fun shoot(name: String, content: @Composable () -> Unit) {
        for (v in Matrix.REDUCED) harness.capture("visores", name, v, Chrome.NONE) { content() }
    }

    private val now = GlucoseFixtures.FIXED_NOW
    private val runtime = WatchFaceRuntimeData(
        nowMs = now,
        reading = GlucoseReading(sgvMgDl = 142.0, direction = "flat", arrow = "↗", timestamp = "", zone = GlucoseZone.NORMAL,
            delta = 4, isStale = false, minutesAgo = 0, epochMs = now),
        graphReadings = GlucoseFixtures.series(now, hours = 3),
        sensorStatus = SibionicsSensorStatus(connected = true, sensorStartMs = now - 9 * 86_400_000L, lastReadingMs = now, lastIndex = 0)
    )

    @Test fun visors() {
        val band = WearableCatalog.starter
        val classic = WatchFacePresets.all.first { it.id == "watchdrip_classic" }
        val daily = WatchFacePresets.all.first { it.id == "daily_complete" }
        val designA = WatchFaceDesign(id = 1, name = "Clássico do dia a dia", wearable = band, createdAt = now - 86_400_000L,
            updatedAt = now - 3 * 3_600_000L, components = classic.build(band))
        val designB = WatchFaceDesign(id = 2, name = "Completo com sensor", wearable = band, createdAt = now - 5 * 86_400_000L,
            updatedAt = now - 2 * 86_400_000L, components = daily.build(band))
        val faces = listOf(designA.toEntity().copy(applyState = "success"), designB.toEntity().copy(applyState = "needs_setup"))

        shoot("gerenciador") { WatchFaceManagerContent(faces, runtime, null, null, ManagerActions()) }
        shoot("gerenciador-enviando") { WatchFaceManagerContent(faces, runtime, 1L, null, ManagerActions()) }
        shoot("gerenciador-vazio") { WatchFaceManagerContent(emptyList(), runtime, null, null, ManagerActions()) }

        val selected = designB.components.first { it.typeId == "glucose_current" }
        val editor = WatchFaceEditorUiState(design = designB, selectedId = selected.id, canUndo = true,
            simulation = SimulatedPreviewInput())
        shoot("editor") { WatchFaceEditorContent(editor, runtime, EditorActions(), {}, {}) }
        shoot("editor-vazio") {
            WatchFaceEditorContent(WatchFaceEditorUiState(design = WatchFaceDesign(wearable = band)), runtime, EditorActions(), {}, {})
        }
        shoot("editor-carregando") { WatchFaceEditorContent(WatchFaceEditorUiState(loading = true), runtime, EditorActions(), {}, {}) }

        val listings = listOf(
            WatchfaceStoreListingDto("p1", "pr1", "Noite tranquila", "mi_band_6", "Mi Smart Band 6", 152, 486, "[\"mi_band_5\"]",
                "2026-09-26T14:00:00Z"),
            WatchfaceStoreListingDto("p2", "pr2", "Relógio redondo esportivo", "amazfit_gtr_4", "Amazfit GTR 4", 466, 466, "[]",
                "2026-10-02T09:30:00Z")
        )
        val installable: (WatchfaceStoreListingDto) -> List<String> = { if (it.wearableId == "mi_band_6") listOf("mi_band_6", "mi_band_5") else emptyList() }
        shoot("loja") { WatchfaceStoreContent(StoreUi(listings, installingId = "p1"), installable, {}, {}, { _, _ -> }) }
        shoot("loja-erro") { WatchfaceStoreContent(StoreUi(loadError = StoreLoadError.FAILED), installable, {}, {}, { _, _ -> }) }
        shoot("loja-beta") { WatchfaceStoreContent(StoreUi(loadError = StoreLoadError.CLOSED_BETA), installable, {}, {}, { _, _ -> }) }
        shoot("loja-busca") { WatchfaceStoreContent(StoreUi(lastSearch = "noturno"), installable, {}, {}, { _, _ -> }) }
        harness.writeReport("visores")
    }
}
