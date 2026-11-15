package com.gluview.app.watchface.wff

import com.gluview.app.watchface.ComponentRegistry
import com.gluview.app.watchface.WatchFaceComponent
import com.gluview.app.watchface.WatchFaceDesign
import com.gluview.app.watchface.WatchFacePresets
import com.gluview.app.watchface.WearableCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WffCompilerTest {
    private val opts = WffCompiler.Options(appPackage = "com.gluview.app", unitLabel = "mg/dL", targetLow = 70, targetHigh = 180)
    private val watches = listOf("galaxy_watch_6_44", "pixel_watch_2", "galaxy_watch_4_40")

    private fun comp(typeId: String, x: Float = 100f, y: Float = 100f, w: Float = 200f, h: Float = 80f, text: String? = null) =
        WatchFaceComponent(typeId = typeId, variation = 1, label = typeId, x = x, y = y, width = w, height = h,
            color = 0xFF69F0AEL, font = "Sans", customText = text)

    @Test fun todosOsModelosProntos_geramXmlQuePassaNasRegras_emTodosOsRelogios() {
        // Grava os XML em build/ para o teste ponta a ponta com o validador oficial (plano 16 §7).
        val out = File("build/wff-presets").apply { mkdirs() }
        for (watchId in watches) for (preset in WatchFacePresets.all) {
            val watch = WearableCatalog.find(watchId)
            val design = WatchFaceDesign(name = preset.name, wearable = watch, components = preset.build(watch))
            val r = WffCompiler.compile(design, opts)
            assertEquals("${preset.id}@$watchId: ${WffRules.check(r.xml)}", emptyList<String>(), WffRules.check(r.xml))
            File(out, "${preset.id}__$watchId.xml").writeText(r.xml)
        }
    }

    @Test fun glicemiaViraSlotComProvedorDoGluView_tipoPadraoDentroDosSuportados() {
        val watch = WearableCatalog.find("galaxy_watch_6_44")
        val r = WffCompiler.compile(WatchFaceDesign(wearable = watch, components = listOf(comp("glucose_current"))), opts)
        assertTrue(r.xml.contains("""primaryProvider="com.gluview.app/com.gluview.wear.complication.GlucoseComplicationService""""))
        assertTrue(r.xml.contains("""supportedTypes="SHORT_TEXT""""))
        assertTrue(r.xml.contains("""defaultSystemProviderType="SHORT_TEXT""""))
        assertTrue(r.xml.contains("[COMPLICATION.TEXT]"))
        assertEquals(1, r.slotCount)
    }

    @Test fun todoComponenteDoConstrutor_temEquivalenteNoWearOs() {
        // 14/11/2026: idade da leitura, alertas, sensor, bateria e bomba deixaram de ser "não disponível".
        val semMapa = ComponentRegistry.all.map { it.id }.filterNot { WffCompiler.isSupported(it) }
        assertEquals(emptyList<String>(), semMapa)
    }

    @Test fun componentesDeInformacao_apontamParaAComplicationCerta() {
        val watch = WearableCatalog.find("galaxy_watch_6_44")
        val ids = listOf("reading_age", "hipo_alert", "hiper_alert", "sensor_life", "sensor_connected", "phone_battery")
        val design = WatchFaceDesign(wearable = watch, components = ids.mapIndexed { i, id -> comp(id, y = 20f + i * 60f, h = 50f) })
        val r = WffCompiler.compile(design, opts)
        assertEquals(emptyList<String>(), r.unsupported.map { it.typeId })
        assertEquals(6, r.slotCount)
        for (svc in listOf("ReadingAge", "LowAlert", "HighAlert", "Sensor", "PhoneBattery"))
            assertTrue(svc, r.xml.contains("primaryProvider=\"com.gluview.app/com.gluview.wear.complication.${svc}ComplicationService\""))
        assertTrue(r.xml.contains("<Template>Sensor %s<Parameter expression=\"[COMPLICATION.TEXT]\" />"))
        assertTrue(r.xml.contains("<Template>Sensor %s<Parameter expression=\"[COMPLICATION.TITLE]\" />"))
        assertEquals(emptyList<String>(), WffRules.check(r.xml))
        File("build/wff-presets").apply { mkdirs() }.resolve("informacao__galaxy_watch_6_44.xml").writeText(r.xml)
    }

    @Test fun bombaDeInsulina_viraTracinhosFixos_comoNaMiBand() {
        val watch = WearableCatalog.find("galaxy_watch_6_44")
        val r = WffCompiler.compile(WatchFaceDesign(wearable = watch, components = listOf(comp("insulin_iob"))), opts)
        assertEquals(0, r.slotCount)
        assertTrue(r.xml.contains(">--</Font>"))
        assertEquals(emptyList<String>(), WffRules.check(r.xml))
    }

    @Test fun maisDeOitoSlots_oExcedenteViraNaoSuportado() {
        val watch = WearableCatalog.find("galaxy_watch_6_44")
        val many = (0 until 10).map { comp("glucose_current", y = 10f + it * 40f, h = 30f) }
        val r = WffCompiler.compile(WatchFaceDesign(wearable = watch, components = many), opts)
        assertEquals(8, r.slotCount)
        assertEquals(2, r.unsupported.size)
        assertEquals(emptyList<String>(), WffRules.check(r.xml))
    }

    @Test fun componenteForaDaTela_ePresoDentro() {
        val watch = WearableCatalog.find("pixel_watch_2") // 384x384
        val r = WffCompiler.compile(WatchFaceDesign(wearable = watch, components = listOf(comp("time_hm", x = 350f, y = 370f))), opts)
        assertEquals(emptyList<String>(), WffRules.check(r.xml))
    }

    @Test fun textoFixoEscapado_eCoresNoFormatoDoWff() {
        val watch = WearableCatalog.find("galaxy_watch_6_44")
        val r = WffCompiler.compile(WatchFaceDesign(wearable = watch, components = listOf(comp("fixed_text", text = "A&B <ok>"))), opts)
        assertTrue(r.xml.contains("A&amp;B &lt;ok&gt;"))
        assertEquals("#ff69f0ae", WffCompiler.color(0xFF69F0AEL))
        assertEquals(emptyList<String>(), WffRules.check(r.xml))
    }

    @Test fun regras_pegamOErroQueOValidadorOficialDeixouPassarNoW0() {
        val bad = """<WatchFace width="450" height="450"><Scene>
            <ComplicationSlot slotId="0" x="0" y="0" width="10" height="10" supportedTypes="SHORT_TEXT">
            <DefaultProviderPolicy defaultSystemProvider="EMPTY" defaultSystemProviderType="EMPTY" primaryProviderType="SHORT_TEXT"/>
            <Complication type="SHORT_TEXT"/></ComplicationSlot></Scene></WatchFace>"""
        val problems = WffRules.check(bad)
        assertTrue(problems.toString(), problems.any { "defaultSystemProviderType=EMPTY" in it })
    }

    @Test fun tabelaDeSuporte() {
        listOf("time_hm", "date_dmy", "weekday", "glucose_current", "glucose_delta", "glucose_chart", "fixed_text")
            .forEach { assertTrue(it, WffCompiler.isSupported(it)) }
        listOf("reading_age", "phone_battery", "sensor_connected", "insulin_pump_connected")
            .forEach { assertTrue(it, WffCompiler.isSupported(it)) }
        assertFalse(WffCompiler.isSupported("tipo_que_nao_existe"))
    }
}
