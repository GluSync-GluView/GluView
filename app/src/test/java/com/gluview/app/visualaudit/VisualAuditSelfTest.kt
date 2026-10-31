package com.gluview.app.visualaudit

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gluview.app.ui.components.CHART_TEST_TAG
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Controle positivo da [VisualAudit]: uma tela com cada defeito de propósito tem que ser acusada,
 *  e uma tela limpa não. Sem isto, um auditor quebrado daria "0 achados" em silêncio. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w384dp-h832dp-450dpi")
class VisualAuditSelfTest {

    @get:Rule val compose = createComposeRule()

    @Test fun flagsEveryKnownDefect() {
        compose.setContent {
            Column {
                Text("Ok, cabe")
                Text("Texto comprido demais que vai ganhar reticencias", maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.width(100.dp))
                Box(Modifier.width(50.dp).clipToBounds()) { Text("Cortado pelo pai, sem quebra", softWrap = false) }
                Text("Vazou da tela", modifier = Modifier.offset(x = 360.dp))
                Text("Conectado", modifier = Modifier.width(40.dp))                // quebra no meio
                Box(Modifier.size(24.dp).clickable {})                       // alvo pequeno e sem rótulo
                // Gráfico em bitmap (Image): o caso da regra do Moto.
                Box(Modifier.fillMaxWidth().height(80.dp).testTag(CHART_TEST_TAG).semantics { role = Role.Image })
                Text("Legenda depois do gráfico")
            }
        }
        val kinds = VisualAudit.audit(compose, "self").groupBy({ it.kind }, { it.detail })
        assertTrue(kinds.toString(), kinds["reticencias"].orEmpty().any { it.startsWith("Texto comprido") })
        assertTrue(kinds.toString(), kinds["texto-cortado"].orEmpty().any { it.startsWith("Cortado pelo pai") })
        assertTrue(kinds.toString(), kinds["fora-da-tela"].orEmpty().any { it.startsWith("Vazou da tela") })
        assertTrue(kinds.toString(), kinds["alvo-pequeno"].orEmpty().isNotEmpty())
        assertTrue(kinds.toString(), kinds["palavra-quebrada"].orEmpty().contains("Conectado"))
        assertTrue(kinds.toString(), kinds["sem-rotulo"].orEmpty().isNotEmpty())
        assertTrue(kinds.toString(), kinds["texto-apos-grafico"].orEmpty().contains("Legenda depois do gráfico"))
        assertTrue(kinds.toString(), kinds.values.flatten().none { it.startsWith("Ok, cabe") })
    }

    @Test fun belowTheFoldSkipsOnlyTheClippedTouchArea() {
        compose.setContent {
            // Tela inteira com uma área rolável de 200dp em cima (como a página acima da barra inferior).
            Column(Modifier.fillMaxSize()) {
                Column(Modifier.height(200.dp).verticalScroll(rememberScrollState())) {
                    Box(Modifier.height(170.dp))
                    Button(onClick = {}) { Text("Botão na dobra") }      // cruza a borda de baixo
                    Box(Modifier.size(24.dp).clickable {})               // inteiro abaixo da dobra
                }
            }
        }
        val issues = VisualAudit.audit(compose, "fold")
        // Botão bom cortado pela borda: o toque recortado não pode virar "alvo-pequeno".
        assertTrue(issues.toString(), issues.none { it.detail.startsWith("Botão na dobra") })
        // Alvo de 24dp sem rótulo continua acusado mesmo abaixo da dobra.
        assertTrue(issues.toString(), issues.any { it.kind == "alvo-pequeno" && it.detail.contains("visível 24×24dp") })
        assertTrue(issues.toString(), issues.any { it.kind == "sem-rotulo" })
    }

    @Test fun cleanScreenHasNoIssues() {
        compose.setContent {
            Column {
                Text("Título")
                Text("Uma frase curta")
                Box(Modifier.fillMaxWidth().height(80.dp).testTag(CHART_TEST_TAG))
            }
        }
        assertEquals(emptyList<AuditIssue>(), VisualAudit.audit(compose, "clean"))
    }
}
