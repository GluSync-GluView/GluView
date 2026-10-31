package com.gluview.app.ui.components.kit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import com.gluview.app.R
import com.gluview.app.ui.theme.Spacing

/*
 * Kit de estrutura de tela (redesign visual, Fase 1) — equivalentes Compose do Page/PageHeader/
 * Section do portal (GluWebView/packages/ui).
 */

/**
 * Barra superior padrão: título de 1 linha (reticências se não couber — o subtítulo explicativo
 * fica no corpo, em [ScreenHeader]) e seta de voltar só quando [onBack] é passado (abas
 * principais não têm seta).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    TopAppBar(
        // Teto de escala no título: a 2,0× nem "Sincronização" cabe ao lado da seta e das ações;
        // o conteúdo da tela continua escalando inteiro. Reticências só como último recurso.
        title = {
            CappedFontScale(TOP_BAR_TITLE_MAX_FONT_SCALE) {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        navigationIcon = {
            if (onBack != null) IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.settings_back))
            }
        },
        actions = actions
    )
}

private const val TOP_BAR_TITLE_MAX_FONT_SCALE = 1.3f

/**
 * Limita a escala de fonte do sistema em [content] a [max]. Só para rótulos de moldura que vivem
 * numa altura fixa (barra superior, barra de navegação) — nunca para conteúdo.
 */
@Composable
fun CappedFontScale(max: Float, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(density.density, density.fontScale.coerceAtMost(max)),
        content = content
    )
}

/** Abertura da tela: frase que explica o que aparece ali (o "lede" do portal). */
@Composable
fun ScreenHeader(subtitle: String, modifier: Modifier = Modifier, title: String? = null) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
        if (title != null) {
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        }
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Seção de uma tela: título (heading para o TalkBack), contagem opcional ao lado, descrição
 * opcional e botão de ajuda de 48dp com rótulo. Substitui SettingsSection/AlertSection/Section.
 */
@Composable
fun GluSection(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    count: Int? = null,
    onHelp: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // weight(fill = false): o selo da contagem é medido primeiro e fica colado ao título
                // curto; título longo (ou fonte grande) quebra no espaço que sobra, sem esmagar o selo.
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f, fill = false).semantics { heading() }
                )
                if (count != null) StatusBadge(text = count.toString(), tone = Tone.Neutral)
            }
            if (onHelp != null) IconButton(onClick = onHelp) {
                Icon(
                    Icons.AutoMirrored.Outlined.HelpOutline,
                    contentDescription = "${stringResource(R.string.common_help)}: $title",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (description != null) {
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        content()
    }
}

/**
 * Moldura de tela secundária: [AppTopBar], conteúdo rolável com gutter de 16dp e espaço de
 * seção entre os blocos, imePadding (o teclado não cobre o campo) e SnackbarHost opcional.
 * [scrollable] = false para telas que já têm a própria lista (LazyColumn).
 */
@Composable
fun Page(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    snackbarHostState: SnackbarHostState? = null,
    scrollable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    Scaffold(
        modifier = modifier,
        topBar = { AppTopBar(title, onBack, actions) },
        snackbarHost = { if (snackbarHostState != null) SnackbarHost(snackbarHostState) }
    ) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .consumeWindowInsets(pad)
                .imePadding()
                .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                .padding(horizontal = Spacing.gutter, vertical = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.section),
            content = content
        )
    }
}
