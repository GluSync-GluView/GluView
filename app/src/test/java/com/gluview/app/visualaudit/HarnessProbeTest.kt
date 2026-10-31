package com.gluview.app.visualaudit

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.takahirom.roborazzi.captureRoboImage
import com.gluview.app.util.GlucoseGraphRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.TimeZone

/**
 * Teste-sonda da Fase 0: prova que o harness visual funciona NESTA máquina antes de construir
 * qualquer coisa em cima dele. Confere:
 *  - Robolectric com gráficos nativos (RNG) roda no Windows com o JBR 21;
 *  - o bitmap do GlucoseGraphRenderer sai com pixels reais (inclusive o drawText dos eixos);
 *  - a mesma entrada dá o mesmo bitmap (captura determinística);
 *  - o qualifier de densidade do S23 FE (450dpi) é aceito;
 *  - o Compose renderiza e o Roborazzi captura a tela.
 * As imagens vão para app/build/visual-audit/probe/ para inspeção manual.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w384dp-h832dp-450dpi", application = Application::class)
class HarnessProbeTest {

    @get:Rule val compose = createComposeRule()

    private val outDir = File("build/visual-audit/probe").apply { mkdirs() }

    @Before fun fixClockZoneLocale() {
        TimeZone.setDefault(TimeZone.getTimeZone(GlucoseFixtures.ZONE))
        Locale.setDefault(Locale.forLanguageTag("pt-BR"))
    }

    private fun renderHome(dark: Boolean = true): Bitmap =
        GlucoseGraphRenderer.render(
            GlucoseFixtures.series(), 990, 880, 6,
            dark = dark, hipo = 70f, hiper = 180f, nowMs = GlucoseFixtures.FIXED_NOW
        )

    @Test fun rendererProducesRealPixels() {
        val bmp = renderHome()
        bmp.save("renderer_home_6h_dark")
        val colors = HashSet<Int>()
        for (y in 0 until bmp.height step 3) for (x in 0 until bmp.width step 3) colors += bmp.getPixel(x, y)
        assertTrue("bitmap quase uniforme: ${colors.size} cores", colors.size > 20)

        // Rótulos do eixo Y (drawText) ficam na margem esquerda: precisa haver tinta lá.
        val bg = Color.parseColor("#1C1C1E")
        var ink = 0
        for (y in 0 until bmp.height) for (x in 0 until 60) if (bmp.getPixel(x, y) != bg) ink++
        assertTrue("sem tinta de texto na margem do eixo Y ($ink px)", ink > 200)
    }

    @Test fun rendererIsDeterministic() {
        val a = renderHome(dark = false)
        val b = renderHome(dark = false)
        assertTrue("mesma entrada deu bitmaps diferentes", a.sameAs(b))
        assertEquals(sha(a), sha(b))
    }

    @Test fun densityQualifierIsS23Fe() {
        val dm = org.robolectric.RuntimeEnvironment.getApplication().resources.displayMetrics
        assertEquals(450, dm.densityDpi)
        assertEquals(384, (dm.widthPixels / dm.density).toInt())
    }

    @Test fun composeCaptureWorks() {
        val chart = renderHome().asImageBitmap()
        compose.setContent {
            Column(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.White)) {
                Text("Sonda 123 · mg/dL", fontSize = 20.sp)
                Image(chart, contentDescription = null, contentScale = ContentScale.FillBounds,
                    modifier = Modifier.fillMaxWidth().height(300.dp))
            }
        }
        compose.onRoot().captureRoboImage(File(outDir, "compose_text_and_chart.png").path)
    }

    private fun Bitmap.save(name: String) =
        File(outDir, "$name.png").outputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it) }

    private fun sha(bmp: Bitmap): String {
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        val md = MessageDigest.getInstance("SHA-256")
        px.forEach { md.update(byteArrayOf((it ushr 24).toByte(), (it ushr 16).toByte(), (it ushr 8).toByte(), it.toByte())) }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
