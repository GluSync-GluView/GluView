package com.gluview.app.ui.screen.terms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.gluview.app.R
import com.gluview.app.ui.components.kit.AppTopBar
import com.gluview.app.ui.components.kit.GluCallout
import com.gluview.app.ui.components.kit.ListSkeleton
import com.gluview.app.ui.components.kit.Tone
import com.gluview.app.ui.theme.Spacing

/**
 * F7/T55 — Tela de aceite dos termos. Exibida no onboarding e sempre que a versão dos
 * termos mudar (gate em MainActivity/AppNavigation). Só avança após aceite explícito.
 */
@Composable
fun TermsScreen(
    onAccepted: () -> Unit,
    viewModel: TermsViewModel = hiltViewModel()
) {
    var accepting by remember { mutableStateOf(false) }
    val appLanguage by viewModel.appLanguage.collectAsState()
    TermsContent(appLanguage, accepting, onAccept = {
        accepting = true
        viewModel.accept(onAccepted)
    })
}

/**
 * Termos (redesign visual, Fase 8): título curto na barra (o longo cortava) e o completo no topo
 * do texto; o texto em seções com título e parágrafos em corpo de 14sp (antes um bloco só de 12sp
 * com os títulos em caixa alta corrida) — o texto é o mesmo, palavra por palavra; enquanto o
 * idioma carrega, esqueleto e botão desligado (antes o pt piscava para quem usa en, já com o
 * aceite ativo); aceitando com spinner; versão em cor de texto (antes cor de borda).
 */
@Composable
internal fun TermsContent(appLanguage: String?, accepting: Boolean, onAccept: () -> Unit) {
    val doc = appLanguage?.let { remember(it) { parseTerms(if (it == "en") TERMS_TEXT_EN else TERMS_TEXT) } }
    Scaffold(topBar = { AppTopBar(stringResource(R.string.terms_short_title)) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.gutter, vertical = Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.md)
            ) {
                GluCallout(stringResource(R.string.terms_read_carefully_banner), tone = Tone.Warning)
                if (doc == null) {
                    ListSkeleton(rows = 4)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                        Text(doc.title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                        Text(doc.version, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    doc.intro.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    doc.sections.forEach { section ->
                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                            Text(section.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary, modifier = Modifier.semantics { heading() })
                            section.paragraphs.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                        }
                    }
                }
            }
            HorizontalDivider()
            Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter, vertical = Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                Button(onClick = onAccept, enabled = doc != null && !accepting, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    if (accepting) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
                    else Text(stringResource(R.string.terms_accept_button), textAlign = TextAlign.Center)
                }
                Text(stringResource(R.string.terms_version, TERMS_VERSION), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
