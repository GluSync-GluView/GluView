package com.gluview.app.visualaudit

import android.app.Application
import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.gluview.app.domain.model.GlucoseReading
import com.gluview.app.domain.model.GlucoseZone
import com.gluview.app.notification.NotificationHelper
import com.gluview.app.notification.buildPersistentNotificationContent
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import com.gluview.app.util.GlucoseGraphRenderer
import com.gluview.app.util.GlucoseUnit
import com.gluview.app.widget.ChartWidgetBody
import com.gluview.app.widget.ChartWidgetData
import com.gluview.app.widget.WidgetContent
import com.gluview.app.widget.XDripContent
import kotlinx.coroutines.runBlocking
import com.gluview.app.util.TimeFormat
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.TimeZone

/**
 * Superfícies fora do app (redesign visual, Fase 5): a notificação persistente de verdade —
 * o conteúdo do serviço ([buildPersistentNotificationContent]), o [NotificationHelper] e o
 * template do sistema ([Notification.Builder.createContentView]/[createBigContentView]), com o
 * cabeçalho — em claro/escuro, pt/en e fontes 1,0/1,3/2,0, na largura do S23 FE.
 * Só roda com -Pgluview.visualaudit.screens=true; PNGs em build/visual-audit/screens/<run>/notificacao/.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class SurfacesCaptureTest {

    private val run = System.getProperty("gluview.visualaudit.run")?.takeIf { it.isNotBlank() } ?: "local"
    private lateinit var savedZone: TimeZone

    @Before fun setUp() {
        assumeTrue("capturas de tela desligadas", System.getProperty("gluview.visualaudit.screens") == "true")
        savedZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(GlucoseFixtures.ZONE))
    }

    @After fun tearDown() {
        if (::savedZone.isInitialized) TimeZone.setDefault(savedZone)
    }

    private val now = GlucoseFixtures.FIXED_NOW
    private val live = GlucoseReading(165.0, "FortyFiveUp", "↗", "", GlucoseZone.NORMAL, 4, isStale = false, epochMs = now - 60_000L)
    private val stale = live.copy(isStale = true, epochMs = now - 40 * 60_000L)

    @Test fun notification() {
        val out = File("build/visual-audit/screens/$run/notificacao").apply { mkdirs() }
        val cases = listOf(
            Triple("ao-vivo", live, GlucoseUnit.MG_DL),
            Triple("sem-sinal", stale, GlucoseUnit.MG_DL),
            Triple("mmol", live, GlucoseUnit.MMOL_L)
        )
        for ((name, reading, unit) in cases)
            for (night in listOf(false, true))
                for (lang in listOf("pt-rBR", "en-rUS"))
                    for (fs in listOf(1.0f, 1.3f, 2.0f)) {
                        if (name != "ao-vivo" && fs != 1.0f) continue
                        RuntimeEnvironment.setQualifiers("+$lang-${if (night) "night" else "notnight"}")
                        RuntimeEnvironment.setFontScale(fs)
                        val ctx = ApplicationProvider.getApplicationContext<Context>()
                        val time = if (lang.startsWith("en")) TimeFormat.H12 else TimeFormat.H24
                        val readings = GlucoseFixtures.series(now, hours = 3)
                        val content = buildPersistentNotificationContent(ctx, reading, readings, unit, time, 70f, 180f, 3, now)
                        val n = NotificationHelper(ctx).buildPersistentNotification(
                            content.text, content.sgvArrowText, content.detailText, content.graph, content.dots)
                        val themed = ContextThemeWrapper(ctx, android.R.style.Theme_DeviceDefault_DayNight)
                        val builder = Notification.Builder.recoverBuilder(themed, n)
                        val id = "${name}__${if (night) "dark" else "light"}_${lang.take(2)}_fs${(fs * 10).toInt()}"
                        save(builder.createBigContentView().apply(themed, FrameLayout(themed)), night, File(out, "expandida__$id.png"))
                        save(builder.createContentView().apply(themed, FrameLayout(themed)), night, File(out, "recolhida__$id.png"))
                    }
    }

    /** Desenha a view na largura do cartão (tela menos 2×8dp), sobre a cor do cartão do sistema. */
    private fun save(view: View, night: Boolean, file: File) {
        val dm = view.resources.displayMetrics
        val w = dm.widthPixels - (16 * dm.density).toInt()
        view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        val bmp = Bitmap.createBitmap(view.measuredWidth.coerceAtLeast(1), view.measuredHeight.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        Canvas(bmp).apply { drawColor(if (night) 0xFF2B2B2B.toInt() else 0xFFFFFFFF.toInt()); view.draw(this) }
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    // ---- widgets ----

    private data class WidgetCase(val name: String, val zone: String, val sgv: Float, val arrow: String, val stale: Boolean)

    private val widgetCases = listOf(
        WidgetCase("normal", "NORMAL", 132f, "→", false),
        WidgetCase("alta", "HIGH", 214f, "↗", false),
        WidgetCase("queda-rapida", "QUICK_DROP", 96f, "↓↓", false),
        WidgetCase("sem-sinal", "NORMAL", 132f, "→", true)
    )

    /** Tamanhos típicos (dp) de cada widget num celular de 411dp. */
    private val chartSizes = listOf("4x2" to DpSize(330.dp, 180.dp), "3x2" to DpSize(250.dp, 180.dp), "2x1" to DpSize(160.dp, 80.dp),
        // O widget de teste no S23 FE (dumpsys appwidget, 18/10): 2 colunas × 1 linha.
        "2x1-s23" to DpSize(188.dp, 96.dp))

    @OptIn(ExperimentalGlanceRemoteViewsApi::class)
    @Test fun widgets() = runBlocking {
        val out = File("build/visual-audit/screens/$run/widgets").apply { mkdirs() }
        for (night in listOf(false, true)) for (lang in listOf("pt-rBR", "en-rUS")) {
            RuntimeEnvironment.setQualifiers("+$lang-${if (night) "night" else "notnight"}")
            RuntimeEnvironment.setFontScale(1.0f)
            val ctx = ApplicationProvider.getApplicationContext<Context>()
            val time = if (lang.startsWith("en")) TimeFormat.H12 else TimeFormat.H24
            val readings = GlucoseFixtures.series(now, hours = 3)
            val suffix = "${if (night) "dark" else "light"}_${lang.take(2)}"
            for (c in widgetCases) {
                val lastMs = if (c.stale) now - 40 * 60_000L else now - 60_000L
                val data = ChartWidgetData(c.sgv, c.arrow, c.zone, c.stale, GlucoseUnit.MG_DL, "+4", lastMs, readings, 3,
                    night, time, 70f, 180f, null)
                for ((label, size) in chartSizes) {
                    val rv = GlanceRemoteViews().compose(ctx, size) { ChartWidgetBody(data, now) }.remoteViews
                    saveWidget(rv.apply(ctx, FrameLayout(ctx)), size, File(out, "grafico-${label}__${c.name}__$suffix.png"))
                }
                val dots = GlucoseGraphRenderer.renderDots(readings, 160, 220, 3, nowMs = now)
                val xdrip = GlanceRemoteViews().compose(ctx, DpSize(170.dp, 180.dp)) {
                    XDripContent(c.sgv, c.arrow, c.zone, c.stale, "MG_DL", "+4", lastMs, dots, night, time.name, null)
                }.remoteViews
                saveWidget(xdrip.apply(ctx, FrameLayout(ctx)), DpSize(170.dp, 180.dp), File(out, "xdrip__${c.name}__$suffix.png"))
                val simple = GlanceRemoteViews().compose(ctx, DpSize(110.dp, 110.dp)) {
                    WidgetContent(c.sgv, c.arrow, c.zone, c.stale, "MG_DL", lastMs.toString(), time.name)
                }.remoteViews
                saveWidget(simple.apply(ctx, FrameLayout(ctx)), DpSize(110.dp, 110.dp), File(out, "simples__${c.name}__$suffix.png"))
            }
        }
    }

    /** Desenha o widget no tamanho dele, sobre um papel de parede cinza médio. */
    private fun saveWidget(view: View, size: DpSize, file: File) {
        val d = view.resources.displayMetrics.density
        val w = (size.width.value * d).toInt(); val h = (size.height.value * d).toInt()
        view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, w, h)
        val pad = (12 * d).toInt()
        val bmp = Bitmap.createBitmap(w + 2 * pad, h + 2 * pad, Bitmap.Config.ARGB_8888)
        Canvas(bmp).apply { drawColor(0xFF7A8088.toInt()); translate(pad.toFloat(), pad.toFloat()); view.draw(this) }
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    // ---- protetor de tela ----

    /** O layout do Dream em retrato e paisagem, com o gráfico desenhado no tamanho medido da view. */
    @Test fun dream() {
        val out = File("build/visual-audit/screens/$run/dream").apply { mkdirs() }
        for (orientation in listOf("port", "land")) for (fs in listOf(1.0f, 2.0f)) {
            RuntimeEnvironment.setQualifiers(if (orientation == "port") "pt-rBR-w411dp-h891dp-port" else "pt-rBR-w891dp-h411dp-land")
            RuntimeEnvironment.setFontScale(fs)
            val ctx = ApplicationProvider.getApplicationContext<Context>()
            val root = android.view.LayoutInflater.from(ctx).inflate(com.gluview.app.R.layout.dream_gluview, null)
            val sgv = root.findViewById<android.widget.TextView>(com.gluview.app.R.id.tv_dream_sgv)
            val arrow = root.findViewById<android.widget.TextView>(com.gluview.app.R.id.tv_dream_arrow_delta)
            val time = root.findViewById<android.widget.TextView>(com.gluview.app.R.id.tv_dream_time)
            val graph = root.findViewById<android.widget.ImageView>(com.gluview.app.R.id.iv_dream_graph)
            // Mesmo teto de fonte do serviço (1,3).
            listOf(sgv, arrow, time).forEach { tv ->
                val base = tv.textSize / fs
                tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, base * minOf(fs, 1.3f))
            }
            sgv.text = "165"; sgv.setTextColor(0xFF66BB6A.toInt())
            arrow.text = "↗  +4 mg/dL"; time.text = "14:36"
            val dm = ctx.resources.displayMetrics
            fun layoutAll() {
                root.measure(View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, dm.widthPixels, dm.heightPixels)
            }
            layoutAll()
            graph.setImageBitmap(GlucoseGraphRenderer.render(GlucoseFixtures.series(now, hours = 3), com.gluview.app.util.graph.surfaceSpec(
                graph.width, graph.height, 3, now, dark = true, hipo = 70f, hiper = 180f, unit = GlucoseUnit.MG_DL,
                timeFormat = TimeFormat.H24, labelSizePx = com.gluview.app.util.graph.surfaceLabelPx(ctx.resources, 14f))))
            layoutAll()
            val bmp = Bitmap.createBitmap(dm.widthPixels, dm.heightPixels, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bmp))
            File(out, "dream__${orientation}_fs${(fs * 10).toInt()}.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
