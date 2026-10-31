package com.gluview.app.visualaudit

import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.gluview.app.domain.model.GlucoseZone
import com.gluview.app.ui.components.kit.Page
import com.gluview.app.ui.components.kit.PeriodSelector
import com.gluview.app.ui.components.kit.graphicColor
import com.gluview.app.ui.components.kit.ChoiceChips
import com.gluview.app.ui.components.kit.EmptyState
import com.gluview.app.ui.components.kit.ErrorState
import com.gluview.app.ui.components.kit.Fact
import com.gluview.app.ui.components.kit.FactList
import com.gluview.app.ui.components.kit.GluCallout
import com.gluview.app.ui.components.kit.GluCard
import com.gluview.app.ui.components.kit.GluSection
import com.gluview.app.ui.components.kit.GlucoseZoneChip
import com.gluview.app.ui.components.kit.ItemCard
import com.gluview.app.ui.components.kit.ListSkeleton
import com.gluview.app.ui.components.kit.ScreenHeader
import com.gluview.app.ui.components.kit.SliderRow
import com.gluview.app.ui.components.kit.StatGrid
import com.gluview.app.ui.components.kit.StatTile
import com.gluview.app.ui.components.kit.StatusBadge
import com.gluview.app.ui.components.kit.SwitchRow
import com.gluview.app.ui.components.kit.Tone
import com.gluview.app.ui.theme.Spacing
import com.gluview.app.util.breakableEmail
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale
import java.util.TimeZone

/**
 * Vitrine do kit da Fase 1 do redesign visual: cada página junta componentes do kit, é
 * capturada nas 6 variantes extremas (Matrix.REDUCED, em perfis ALTOS — mesma largura e
 * densidade, 1600dp de altura — para nenhum componente ficar abaixo da dobra sem auditoria) e
 * auditada. O kit é a base de todas as
 * telas, então aqui a auditoria tem que dar ZERO achados — corte, quebra no meio da palavra,
 * alvo pequeno ou clicável sem rótulo num componente do kit se repetiria em toda tela.
 *
 * Roda na suíte normal (são poucas capturas). PNGs em app/build/visual-audit/screens/kit/.
 * Os diálogos (AppDialog/ConfirmDialog) abrem em outra janela e ficam fora da captura.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class)
class KitShowcaseTest {

    @get:Rule val compose = createEmptyComposeRule()

    private lateinit var harness: ScreenHarness
    private lateinit var savedZone: TimeZone
    private lateinit var savedLocale: Locale

    @Before fun setUp() {
        savedZone = TimeZone.getDefault(); savedLocale = Locale.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(GlucoseFixtures.ZONE))
        harness = ScreenHarness(compose, "kit")
    }

    @After fun tearDown() {
        TimeZone.setDefault(savedZone); Locale.setDefault(savedLocale)
    }

    // Surface como nas telas reais (Scaffold): dá a cor de conteúdo (onBackground) aos Text sem cor.
    private val tallVariants = Matrix.REDUCED.map {
        it.copy(profile = it.profile.copy(name = it.profile.name + "alto", heightDp = 1600))
    }

    private fun page(name: String, content: @Composable () -> Unit) {
        for (v in tallVariants) harness.capture("kit", name, v) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() }
        }
    }

    @Test fun kitSemAchados() {
        page("estrutura") {
            Page(title = "Sincronização", onBack = {}, actions = {
                IconButton(onClick = {}) { Icon(Icons.Outlined.History, contentDescription = "Histórico") }
            }) {
                ScreenHeader(subtitle = "Envie e receba leituras da nuvem, com a conta ${breakableEmail("rafael.oliveira@exemplo.com.br")}.")
                GluSection(title = "Conexão", description = "Como o app fala com o GluSync", count = 3, onHelp = {}) {
                    GluCard { Text("Conteúdo da seção") }
                }
                // Título longo + contagem: o selo não pode sumir espremido.
                GluSection(title = "Pessoas que acompanham a sua glicemia", count = 12, onHelp = {}) {
                    GluCard(onClick = {}, selected = true) { Text("Card selecionado") }
                }
                GluCallout("Sem internet há 12 minutos. As leituras ficam guardadas e sobem quando voltar.",
                    tone = Tone.Warning, title = "Sincronização pausada", actionLabel = "Tentar novamente", onAction = {})
                GluCallout("Leitura recebida do sensor.", tone = Tone.Success)
                GluCallout("Falha ao autenticar no servidor.", tone = Tone.Danger)
            }
        }
        page("glicemia") {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                GlucoseZone.entries.forEach { zone ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        // Ponto na cor de gráfico da zona (paleta por tema) ao lado do selo.
                        Box(Modifier.size(12.dp).background(zone.graphicColor(), CircleShape))
                        GlucoseZoneChip(zone)
                    }
                }
                StatusBadge("Conectado", tone = Tone.Success)
                StatusBadge("Sem sinal", tone = Tone.Danger)
            }
        }
        page("listas") {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                GluCard {
                    FactList(listOf(
                        Fact("Sensor", "Libre 2 Plus"),
                        Fact("Última leitura", "há 3 min"),
                        Fact("Conta", breakableEmail("rafael.oliveira@exemplo.com.br")),
                        Fact("Estado", "Conectado", com.gluview.app.ui.theme.glucoseZoneTextColor(GlucoseZone.NORMAL))
                    ))
                }
                ItemCard(title = "Pixel 6 de teste", meta = "Último acesso há 2 dias", actions = {
                    TextButton(onClick = {}) { Text("Renomear") }
                    TextButton(onClick = {}) { Text("Remover acesso") }
                })
                StatGrid(listOf(
                    { m -> StatTile("Média", "142", m, unit = "mg/dL", sublabel = "últimos 14 dias") },
                    { m -> StatTile("No alvo", "71%", m, tone = Tone.Success) },
                    { m -> StatTile("GMI", null, m) }
                ))
            }
        }
        page("controles") {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                SwitchRow("Sincronizar automaticamente com o GluSync", checked = true, onCheckedChange = {},
                    description = "Envia cada leitura assim que chega do sensor")
                SwitchRow("Modo escuro", checked = false, onCheckedChange = {})
                SliderRow("Intervalo de envio", value = 5f, valueRange = 1f..60f, step = 1f,
                    valueText = { "${it.toInt()} min" }, onValueChange = {})
                ChoiceChips(listOf("1h", "3h", "6h", "12h", "24h"), selected = "6h", onSelect = {}, label = { it })
                PeriodSelector(listOf(7, 14, 30, 90), selected = 14, onSelect = {}, label = { "$it dias" }, title = "Período")
            }
        }
        page("estados") {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                ErrorState("Não foi possível buscar as leituras do GluSync.", onRetry = {}, detail = "HTTP 503")
                EmptyState("Nenhum alerta ainda", "Quando um alerta disparar, ele aparece aqui com a hora e o valor.",
                    Icons.Outlined.History, actionLabel = "Configurar alertas", onAction = {})
                ListSkeleton(rows = 2)
            }
        }
        harness.writeReport("kit")
        val detail = harness.issues.joinToString("\n") { "${it.capture} · ${it.kind} · ${it.detail}" }
        assertTrue("achados no kit:\n$detail", harness.issues.isEmpty())
    }
}
