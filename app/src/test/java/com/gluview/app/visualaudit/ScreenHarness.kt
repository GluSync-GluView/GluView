package com.gluview.app.visualaudit

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import com.github.takahirom.roborazzi.captureRoboImage
import com.gluview.app.ui.components.CHART_TEST_TAG
import com.gluview.app.ui.components.LocalClock
import com.gluview.app.ui.navigation.MainBottomBar
import com.gluview.app.ui.navigation.Screen
import com.gluview.app.ui.theme.GluViewTheme
import com.gluview.app.ui.theme.ThemeMode
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.util.Locale

/**
 * Perfil de aparelho. O Robolectric não tem barras de sistema, então a moldura as simula com os
 * insets MEDIDOS nos aparelhos em 03/10/2026 (adb shell dumpsys window) — S23 FE e Moto Z2.
 */
data class Profile(
    val name: String,
    val widthDp: Int,
    val heightDp: Int,
    val dpi: Int,
    val statusBarDp: Int,
    val navBarDp: Int
)

object Profiles {
    /** Galaxy S23 FE (aparelho de teste do paciente): 1080×2340 a 450dpi; status 84px (30dp), navegação de
     *  3 botões 135px (48dp) — medidos. */
    val S23FE = Profile("s23fe", 384, 832, 450, 30, 48)
    /** Tela estreita (360×640dp, 480dpi) — mesma geometria do Moto Z2 Play, mas no Android 15. */
    val NARROW = Profile("narrow360", 360, 640, 480, 24, 48)
    /** Pixel 6 (= AVD GluViewHomolog): 1080×2400 a 420dpi; insets aproximados (navegação por gestos). */
    val PIXEL6 = Profile("pixel6", 411, 914, 420, 32, 16)
    /** Moto Z2 Play do seguidor: 1080×1920 a 480dpi, Android 9 (fonte máx. 1,3, linear); status
     *  72px (24dp), navegação 144px (48dp) — medidos. Usado pelo ScreensCaptureMotoTest (SDK 28). */
    val MOTO = Profile("moto", 360, 640, 480, 24, 48)
    val ALL = listOf(S23FE, NARROW, PIXEL6)
}

data class Variant(val profile: Profile, val fontScale: Float, val dark: Boolean, val lang: String) {
    val id: String get() =
        "${profile.name}_fs${(fontScale * 10).toInt()}_${if (dark) "dark" else "light"}_$lang"
}

object Matrix {
    /** 3 perfis × 3 fontes × 2 temas × 2 idiomas = 36 — só para Home, seguidor e Histórico. */
    val FULL: List<Variant> = Profiles.ALL.flatMap { p ->
        listOf(1.0f, 1.3f, 2.0f).flatMap { fs ->
            listOf(false, true).flatMap { dark -> listOf("pt", "en").map { Variant(p, fs, dark, it) } }
        }
    }

    /** Moto Z2 Play (Android 9): fonte 1,0 e 1,3 (o máximo do sistema), claro/escuro, pt; mais 1 en. */
    val MOTO: List<Variant> = listOf(1.0f, 1.3f).flatMap { fs ->
        listOf(false, true).map { dark -> Variant(Profiles.MOTO, fs, dark, "pt") }
    } + Variant(Profiles.MOTO, 1.3f, false, "en")

    /** 6 combinações que cobrem os extremos — para as demais telas. */
    val REDUCED: List<Variant> = listOf(
        Variant(Profiles.S23FE, 1.0f, false, "pt"),
        Variant(Profiles.S23FE, 1.0f, true, "en"),
        Variant(Profiles.NARROW, 1.3f, false, "en"),
        Variant(Profiles.NARROW, 2.0f, true, "pt"),
        Variant(Profiles.PIXEL6, 2.0f, false, "en"),
        Variant(Profiles.S23FE, 1.3f, false, "pt")
    )
}

/** Barra inferior real do app (MainBottomBar), como aparece nas abas principais. */
enum class Chrome(val route: String?, val followerOnly: Boolean) {
    NONE(null, false),
    PATIENT_HOME(Screen.Home.route, false),
    PATIENT_HISTORY(Screen.Chart.route, false),
    FOLLOWER_HOME(Screen.Home.route, true),
    FOLLOWER_HISTORY(Screen.Chart.route, true)
}

/** Barras de sistema simuladas: fora da auditoria. */
const val HARNESS_CHROME_TAG = "harness-chrome"
/** Barra inferior real do app: auditada, mas fora da regra "texto depois do gráfico" (no app o
 *  Scaffold externo a compõe antes do conteúdo). */
const val HARNESS_BOTTOMBAR_TAG = "harness-bottombar"

/**
 * Captura uma tela numa variante (perfil/fonte/tema/idioma), com relógio fixo, e audita.
 * Requer a regra createEmptyComposeRule() e o teste em @GraphicsMode(NATIVE); rodar com
 * -Proborazzi.test.record=true para gravar os PNGs (ver README do harness).
 */
class ScreenHarness(private val compose: ComposeTestRule, run: String) {

    val outDir = File("build/visual-audit/screens/$run").apply { mkdirs() }
    val issues = mutableListOf<AuditIssue>()
    var captures = 0
        private set

    fun capture(
        screen: String,
        scenario: String,
        variant: Variant,
        chrome: Chrome = Chrome.NONE,
        waitForChart: Boolean = false,
        settleMs: Long = 0,
        content: @Composable () -> Unit
    ) {
        val p = variant.profile
        val lang = if (variant.lang == "pt") "pt-rBR" else "en"
        RuntimeEnvironment.setQualifiers(
            "$lang-w${p.widthDp}dp-h${p.heightDp}dp-port-${if (variant.dark) "night" else "notnight"}-${p.dpi}dpi"
        )
        RuntimeEnvironment.setFontScale(variant.fontScale)
        Locale.setDefault(if (variant.lang == "pt") Locale.forLanguageTag("pt-BR") else Locale.US)

        val captureId = "${screen}__${scenario}__${variant.id}"
        ActivityScenario.launch(ComponentActivity::class.java).use { sc ->
            sc.onActivity { activity ->
                activity.setContent {
                    CompositionLocalProvider(LocalClock provides { GlucoseFixtures.FIXED_NOW }) {
                        GluViewTheme(themeMode = if (variant.dark) ThemeMode.DARK else ThemeMode.LIGHT) {
                            HarnessChrome(p, chrome, content)
                        }
                    }
                }
            }
            compose.waitForIdle()
            if (waitForChart) {
                val appeared = runCatching {
                    compose.waitUntil(30_000) {
                        compose.onAllNodesWithTag(CHART_TEST_TAG, useUnmergedTree = true)
                            .fetchSemanticsNodes().isNotEmpty()
                    }
                }.isSuccess
                // Com dados, o gráfico tem que aparecer; se não aparece (ex.: espremido a 0px de
                // altura, ou spinner eterno), é achado — captura assim mesmo para ver o porquê.
                if (!appeared) issues += AuditIssue(captureId, "grafico-ausente", "gráfico não apareceu em 30 s")
                compose.waitForIdle()
            }
            if (settleMs > 0) { Thread.sleep(settleMs); compose.waitForIdle() }
            compose.onRoot().captureRoboImage(File(outDir, "$screen/$captureId.png").path)
            issues += VisualAudit.audit(compose, captureId)
            captures++
        }
    }

    /** Grava audit-<section>.md + .tsv com os achados desta parte da rodada, e o carimbo do
     *  auditor (sha-256 do VisualAudit.kt) para o compare_runs.py só comparar rodadas que usaram
     *  as mesmas regras. */
    fun writeReport(section: String) {
        val auditor = File("src/test/java/com/gluview/app/visualaudit/VisualAudit.kt")
        if (auditor.exists()) {
            val sha = java.security.MessageDigest.getInstance("SHA-256").digest(auditor.readBytes())
                .joinToString("") { "%02x".format(it) }
            File(outDir, "auditor.txt").writeText("VisualAudit.kt sha256 $sha" + System.lineSeparator())
        }
        File(outDir, "audit-$section.tsv").writeText(
            "captura\ttipo\tdetalhe\n" + issues.joinToString("\n") { "${it.capture}\t${it.kind}\t${it.detail}" }
        )
        File(outDir, "audit-$section.md").writeText(buildString {
            appendLine("# Auditoria visual — ${outDir.name} · $section")
            appendLine()
            appendLine("- Capturas: $captures · achados: ${issues.size}")
            issues.groupBy { it.kind }.toSortedMap().forEach { (kind, list) ->
                appendLine("- $kind: ${list.size} (em ${list.map { it.capture }.distinct().size} capturas)")
            }
            appendLine()
            issues.groupBy { it.capture.substringBefore("__") + " · " + it.kind }.toSortedMap()
                .forEach { (key, list) ->
                    appendLine("## $key (${list.size})")
                    list.map { it.detail }.groupingBy { it }.eachCount().entries
                        .sortedByDescending { it.value }.take(15)
                        .forEach { (d, n) -> appendLine("- $d — $n×") }
                    appendLine()
                }
        })
    }
}

/** Moldura do aparelho: barra de status, a tela, a barra inferior do app e a barra de gestos. */
@Composable
private fun HarnessChrome(profile: Profile, chrome: Chrome, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Spacer(Modifier.fillMaxWidth().height(profile.statusBarDp.dp).testTag(HARNESS_CHROME_TAG))
        Box(Modifier.fillMaxWidth().weight(1f)) { content() }
        if (chrome.route != null) {
            Box(Modifier.testTag(HARNESS_BOTTOMBAR_TAG)) {
                MainBottomBar(
                    currentRoute          = chrome.route,
                    isFollowerOnly        = chrome.followerOnly,
                    insulinPumpTabEnabled = !chrome.followerOnly,
                    onNavigate            = {}
                )
            }
        }
        Spacer(Modifier.fillMaxWidth().height(profile.navBarDp.dp).testTag(HARNESS_CHROME_TAG))
    }
}
