package com.gluview.app.ui.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import com.gluview.app.R
import com.gluview.app.ui.components.kit.EmptyState
import com.gluview.app.ui.components.kit.GluCard
import com.gluview.app.ui.components.kit.NavRow
import com.gluview.app.ui.components.kit.RowGroup
import com.gluview.app.ui.theme.Spacing

/** Um resultado já traduzido: título, caminho legível ("Configurar alertas › IFTTT Webhooks") e destino. */
internal data class SettingsSearchHit(
    val title: String,
    val pathText: String,
    val route: String,
    /** null = o resultado é a própria tela (não há linha para destacar). */
    val highlight: String?,
    val haystack: String
)

/** Monta todos os resultados possíveis para o contexto (gates) no idioma atual do app. */
@Composable
internal fun rememberSettingsSearchHits(ctx: SearchContext): List<SettingsSearchHit> {
    val res = LocalContext.current.resources
    val locale = LocalConfiguration.current.locales[0]
    return remember(ctx, locale) {
        val root = res.getString(R.string.settings_title)
        fun visible(s: SearchScreen) = s.path.all { ctx.allows(it.gate) }
        fun pathOf(s: SearchScreen) = (listOf(root) + s.path.map { res.getString(it.titleRes) }).joinToString(" › ")
        val screens = SearchScreen.entries.filter(::visible).map { s ->
            val title = res.getString(s.titleRes)
            val parentPath = s.parent?.let(::pathOf) ?: root
            SettingsSearchHit(title, parentPath, s.route, null, "$title $parentPath")
        }
        val items = SettingsSearchIndex.items
            .filter { visible(it.screen) && ctx.allows(it.gate) }
            .map { item ->
                val title = res.getString(item.titleRes)
                val path = pathOf(item.screen)
                SettingsSearchHit(title, path, item.screen.route, title, "$title $path ${item.keywordsRes?.let(res::getString).orEmpty()}")
            }
        screens + items
    }
}

/**
 * Campo de busca + resultados no topo do hub de Ajustes. Com a busca vazia mostra só o campo (o
 * hub continua embaixo); com texto, o chamador esconde o hub e só os resultados aparecem.
 */
@Composable
internal fun SettingsSearchBox(
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
    hits: List<SettingsSearchHit>,
    onHit: (SettingsSearchHit) -> Unit
) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    // Teclado só ao ABRIR a busca (vazia); voltando de um resultado, a lista fica à vista sem teclado.
    LaunchedEffect(Unit) { if (query.isEmpty()) runCatching { focus.requestFocus() } }

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            placeholder = { Text(stringResource(R.string.settings_search_hint)) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.settings_search_close))
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
            modifier = Modifier.fillMaxWidth().focusRequester(focus)
        )
        if (query.isNotBlank()) {
            val found = remember(query, hits) {
                hits.filter { SettingsSearchIndex.matches(query, it.haystack) }
                    // Título que casa primeiro; telas antes de linhas soltas com o mesmo peso.
                    .sortedWith(compareBy<SettingsSearchHit>(
                        { if (SettingsSearchIndex.matches(query, it.title)) 0 else 1 },
                        { if (it.highlight == null) 0 else 1 }
                    ))
                    .distinctBy { it.route to it.title }
            }
            if (found.isEmpty()) {
                EmptyState(
                    title = stringResource(R.string.settings_search_empty, query),
                    body = stringResource(R.string.settings_search_empty_body),
                    icon = Icons.Default.Search
                )
            } else {
                Text(
                    pluralStringResource(R.plurals.settings_search_count, found.size, found.size),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                GluCard {
                    RowGroup(found.map { hit ->
                        { NavRow(hit.title, onClick = { keyboard?.hide(); onHit(hit) }, subtitle = hit.pathText) }
                    })
                }
            }
        }
    }
}
