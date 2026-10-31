package com.gluview.app.visualaudit

import android.app.Application
import android.graphics.Bitmap
import com.gluview.app.util.GlucoseGraphRenderer
import com.gluview.app.util.GraphReading
import com.gluview.app.visualaudit.oracle.LegacyGlucoseGraphRenderer
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Locale
import java.util.TimeZone

/**
 * Gate pixel a pixel do motor do gráfico (Fase 2 do redesign): o GlucoseGraphRenderer de produção
 * tem que dar EXATAMENTE o mesmo bitmap que o oráculo congelado ([LegacyGlucoseGraphRenderer]) em
 * todas as chamadas reais do app — Home, seguidor/acompanhado, notificação (expandida e
 * recolhida), widgets (gráfico e xDrip), Android Auto, Dream e Mi Band (envio e editor) —
 * cruzadas com cenários de dado, tema e limiares.
 *
 * Com -Pgluview.visualaudit.dump=true grava cada par em app/build/visual-audit/oracle/ (inspeção).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class)
class RendererOracleTest {

    private val now = GlucoseFixtures.FIXED_NOW
    private val dump = System.getProperty("gluview.visualaudit.dump") == "true"
    private val outDir = File("build/visual-audit/oracle")
    private lateinit var savedZone: TimeZone
    private lateinit var savedLocale: Locale

    @Before fun fix() {
        savedZone = TimeZone.getDefault(); savedLocale = Locale.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(GlucoseFixtures.ZONE))
        Locale.setDefault(Locale.forLanguageTag("pt-BR"))
    }

    @After fun restore() { TimeZone.setDefault(savedZone); Locale.setDefault(savedLocale) }

    private val scenarios: Map<String, List<GraphReading>> = mapOf(
        "24h_5min" to GlucoseFixtures.series(now),
        "sibionics_1min" to GlucoseFixtures.sibionics1Min(now),
        "gap_2h" to GlucoseFixtures.withGap(GlucoseFixtures.series(now)),
        "out_of_scale" to GlucoseFixtures.outOfScale(now),
        "newest_in_future" to GlucoseFixtures.newestInFuture(now),
        "empty" to emptyList()
    )
    private val thresholds = listOf(70f to 180f, 80f to 150f, 100f to 250f)

    /** Uma chamada real do app: nome + como o produto chama o renderer (e o oráculo, igual). */
    private class Call(
        val name: String,
        val hours: List<Int>,
        val themes: List<Boolean>,
        val prod: (List<GraphReading>, Int, Boolean, Float, Float, Long) -> Bitmap,
        val legacy: (List<GraphReading>, Int, Boolean, Float, Float, Long) -> Bitmap
    )

    private val calls = listOf(
        // HomeChartCard: caixa real do S23 FE (fill) e o modo "tight" (aspectRatio 900/400)
        Call("home_fill_990x880", listOf(1, 3, 6, 12, 24), listOf(true, false),
            { r, h, d, lo, hi, n -> GlucoseGraphRenderer.render(r, 990, 880, h, dark = d, hipo = lo, hiper = hi, nowMs = n) },
            { r, h, d, lo, hi, n -> LegacyGlucoseGraphRenderer.render(r, 990, 880, h, dark = d, hipo = lo, hiper = hi, nowMs = n) }),
        Call("home_tight_990x440", listOf(3, 24), listOf(true, false),
            { r, h, d, lo, hi, n -> GlucoseGraphRenderer.render(r, 990, 440, h, dark = d, hipo = lo, hiper = hi, nowMs = n) },
            { r, h, d, lo, hi, n -> LegacyGlucoseGraphRenderer.render(r, 990, 440, h, dark = d, hipo = lo, hiper = hi, nowMs = n) }),
        // Limiar do eixo automático (showAxes = altura >= 200)
        Call("axes_threshold_600x199", listOf(3), listOf(true),
            { r, h, d, lo, hi, n -> GlucoseGraphRenderer.render(r, 600, 199, h, dark = d, hipo = lo, hiper = hi, nowMs = n) },
            { r, h, d, lo, hi, n -> LegacyGlucoseGraphRenderer.render(r, 600, 199, h, dark = d, hipo = lo, hiper = hi, nowMs = n) }),
        Call("axes_threshold_600x200", listOf(3), listOf(true),
            { r, h, d, lo, hi, n -> GlucoseGraphRenderer.render(r, 600, 200, h, dark = d, hipo = lo, hiper = hi, nowMs = n) },
            { r, h, d, lo, hi, n -> LegacyGlucoseGraphRenderer.render(r, 600, 200, h, dark = d, hipo = lo, hiper = hi, nowMs = n) }),
        // FollowerHomeScreen.kt:199 e FollowedPatientScreen.kt:88 (mesmos argumentos)
        Call("follower_900x400", listOf(24), listOf(false),
            { r, h, _, _, _, n -> GlucoseGraphRenderer.render(r, 900, 400, h, dark = false, nowMs = n) },
            { r, h, _, _, _, n -> LegacyGlucoseGraphRenderer.render(r, 900, 400, h, dark = false, nowMs = n) }),
        // GluSyncService.kt:439 e :443
        Call("notification_800x289", listOf(3, 24), listOf(true, false),
            { r, h, d, lo, hi, n -> GlucoseGraphRenderer.render(r, 800, 289, h, dark = d, hipo = lo, hiper = hi, nowMs = n) },
            { r, h, d, lo, hi, n -> LegacyGlucoseGraphRenderer.render(r, 800, 289, h, dark = d, hipo = lo, hiper = hi, nowMs = n) }),
        Call("notification_dots_320x64", listOf(3), listOf(true),
            { r, h, _, lo, hi, n -> GlucoseGraphRenderer.renderDots(r, 320, 64, h, hipo = lo, hiper = hi, nowMs = n) },
            { r, h, _, lo, hi, n -> LegacyGlucoseGraphRenderer.renderDots(r, 320, 64, h, hipo = lo, hiper = hi, nowMs = n) }),
        // GluViewChartWidget.kt:67 e GluViewXDripWidget.kt:55
        Call("widget_800x300", listOf(3, 24), listOf(true, false),
            { r, h, d, lo, hi, n -> GlucoseGraphRenderer.render(r, 800, 300, h, forceAxes = false, dark = d, hipo = lo, hiper = hi, nowMs = n) },
            { r, h, d, lo, hi, n -> LegacyGlucoseGraphRenderer.render(r, 800, 300, h, forceAxes = false, dark = d, hipo = lo, hiper = hi, nowMs = n) }),
        Call("widget_xdrip_dots_160x220", listOf(3), listOf(true),
            { r, h, _, lo, hi, n -> GlucoseGraphRenderer.renderDots(r, 160, 220, h, hipo = lo, hiper = hi, nowMs = n) },
            { r, h, _, lo, hi, n -> LegacyGlucoseGraphRenderer.renderDots(r, 160, 220, h, hipo = lo, hiper = hi, nowMs = n) }),
        // GluAutoService.kt:220 (sem limiares, de propósito: é o comportamento atual)
        Call("auto_500x280", listOf(3), listOf(true, false),
            { r, h, d, _, _, n -> GlucoseGraphRenderer.render(r, 500, 280, h, dark = d, nowMs = n) },
            { r, h, d, _, _, n -> LegacyGlucoseGraphRenderer.render(r, 500, 280, h, dark = d, nowMs = n) }),
        // GluViewDreamService.kt:150 (sem dark: padrão escuro)
        Call("dream_800x300", listOf(3), listOf(true),
            { r, h, _, lo, hi, n -> GlucoseGraphRenderer.render(r, 800, 300, h, forceAxes = false, hipo = lo, hiper = hi, nowMs = n) },
            { r, h, _, lo, hi, n -> LegacyGlucoseGraphRenderer.render(r, 800, 300, h, forceAxes = false, hipo = lo, hiper = hi, nowMs = n) }),
        // MiBandWatchFacePusher.kt:141 (config miband6: 152x136) e MiBandCanvasCompiler.kt:73 (caixa do editor, 3h)
        Call("miband_152x136", listOf(3, 6), listOf(true),
            { r, h, _, lo, hi, n -> GlucoseGraphRenderer.renderWatchface(r, 152, 136, h, hipo = lo, hiper = hi, nowMs = n) },
            { r, h, _, lo, hi, n -> LegacyGlucoseGraphRenderer.renderWatchface(r, 152, 136, h, hipo = lo, hiper = hi, nowMs = n) }),
        Call("miband_editor_152x60", listOf(3), listOf(true),
            { r, h, _, lo, hi, n -> GlucoseGraphRenderer.renderWatchface(r, 152, 60, h, hipo = lo, hiper = hi, nowMs = n) },
            { r, h, _, lo, hi, n -> LegacyGlucoseGraphRenderer.renderWatchface(r, 152, 60, h, hipo = lo, hiper = hi, nowMs = n) }),
        Call("miband5_editor_126x80", listOf(3), listOf(true),
            { r, h, _, lo, hi, n -> GlucoseGraphRenderer.renderWatchface(r, 126, 80, h, hipo = lo, hiper = hi, nowMs = n) },
            { r, h, _, lo, hi, n -> LegacyGlucoseGraphRenderer.renderWatchface(r, 126, 80, h, hipo = lo, hiper = hi, nowMs = n) })
    )

    @Test fun productionRendererMatchesFrozenOracle() {
        if (dump) outDir.mkdirs()
        val mismatches = mutableListOf<String>()
        var compared = 0
        for (call in calls) for ((scenario, readings) in scenarios) for (dark in call.themes)
            for ((lo, hi) in thresholds) for (hours in call.hours) {
                val a = call.prod(readings, hours, dark, lo, hi, now)
                val b = call.legacy(readings, hours, dark, lo, hi, now)
                compared++
                val id = "${call.name}__${scenario}__${hours}h__${if (dark) "dark" else "light"}__${lo.toInt()}-${hi.toInt()}"
                if (!a.sameAs(b)) mismatches += id
                if (dump && lo == 70f) {
                    File(outDir, "$id.png").outputStream().use { a.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
            }
        assertTrue("comparou só $compared", compared > 300)
        assertTrue("${mismatches.size}/$compared bitmaps diferentes do oráculo:\n" + mismatches.joinToString("\n"),
            mismatches.isEmpty())
    }

    /** Idioma inglês: o eixo X usa o Locale padrão (a ordem/forma da hora não pode mudar). */
    @Test fun englishLocaleMatchesOracle() {
        Locale.setDefault(Locale.US)
        val readings = GlucoseFixtures.series(now)
        for (hours in listOf(3, 24)) for (dark in listOf(true, false)) {
            val a = GlucoseGraphRenderer.render(readings, 990, 880, hours, dark = dark, nowMs = now)
            val b = LegacyGlucoseGraphRenderer.render(readings, 990, 880, hours, dark = dark, nowMs = now)
            assertTrue("en ${hours}h dark=$dark difere do oráculo", a.sameAs(b))
        }
    }

    /** Fuso com horário de verão (o de São Paulo não tem desde 2019): ticks do eixo X na virada. */
    @Test fun dstTimeZoneMatchesOracle() {
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
        val dstEnd = 1_793_514_600_000L // 2026-11-01 06:30Z = 01:30 EST, logo após a virada
        val readings = GlucoseFixtures.series(dstEnd, hours = 6)
        for (hours in listOf(3, 6)) {
            val a = GlucoseGraphRenderer.render(readings, 990, 880, hours, nowMs = dstEnd)
            val b = LegacyGlucoseGraphRenderer.render(readings, 990, 880, hours, nowMs = dstEnd)
            assertTrue("DST ${hours}h difere do oráculo", a.sameAs(b))
        }
    }
}
