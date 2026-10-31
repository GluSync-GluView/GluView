package com.gluview.app.ui.components.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gluview.app.R
import com.gluview.app.ui.theme.Spacing
import com.gluview.app.util.UiText

/*
 * Estados honestos (redesign visual, Fase 1) — os critérios do portal:
 *  - carregando no FORMATO do conteúdo (skeleton), sem spinner solto;
 *  - vazio que explica o que aparece ali;
 *  - erro nunca vira vazio nem zero, e sempre tem "Tentar novamente".
 * Hoje há spinner solto em 16 telas e erro sem retry em 9.
 */

/**
 * Bloco de placeholder (skeleton) no tamanho do conteúdo que vai chegar. Estático de propósito:
 * sem animação infinita (que o compose-test congela e que pesa no Moto Z2).
 */
@Composable
fun SkeletonBlock(modifier: Modifier = Modifier, height: Dp = 16.dp) {
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            // outlineVariant: 1,5:1 (claro) e 1,8:1 (escuro) sobre o surfaceContainer do GluCard, o
            // tom do skeleton do portal; surfaceContainerHigh dava 1,05:1 e o bloco sumia.
            .background(MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.extraSmall)
    )
}

/** Skeleton de uma lista de [rows] itens (cards fantasmas), anunciado como "Carregando". */
@Composable
fun ListSkeleton(modifier: Modifier = Modifier, rows: Int = 3) {
    val loading = stringResource(R.string.common_loading)
    Column(
        modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = loading },
        verticalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        repeat(rows) {
            GluCard {
                SkeletonBlock(Modifier.fillMaxWidth(0.6f), height = 18.dp)
                SkeletonBlock(Modifier.fillMaxWidth(0.4f), height = 14.dp)
            }
        }
    }
}

/**
 * Vazio: ícone, título, frase que diz o que vai aparecer ali e ação principal opcional em
 * largura toda (o EmptyPatientState do seguidor era o único bem resolvido; este é o padrão dele).
 */
@Composable
fun EmptyState(
    title: String,
    body: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Column(
        modifier.fillMaxWidth().padding(vertical = Spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(48.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() })
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center)
        if (actionLabel != null && onAction != null) {
            Button(onClick = onAction, modifier = Modifier.fillMaxWidth()) { Text(actionLabel) }
        }
    }
}

/**
 * Erro de carregamento: "Não foi possível carregar", a explicação, o detalhe técnico rotulado
 * (opcional) e "Tentar novamente" em largura toda. Nunca mostre o vazio no lugar disto.
 */
@Composable
fun ErrorState(
    message: String,
    onRetry: (() -> Unit)?,
    modifier: Modifier = Modifier,
    detail: String? = null
) {
    Column(
        modifier.fillMaxWidth().padding(vertical = Spacing.md),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        Icon(Icons.Outlined.CloudOff, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(40.dp))
        Text(stringResource(R.string.common_load_error_title), style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center)
        if (detail != null) {
            Text(stringResource(R.string.common_load_error_detail, detail), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        if (onRetry != null) {
            OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.common_retry)) }
        }
    }
}

/**
 * Estado de carregamento de uma tela ou seção. O ViewModel expõe um destes em vez de
 * isLoading/error/lista soltos, e [LoadStateContent] garante os três estados honestos.
 */
sealed interface LoadState<out T> {
    data object Loading : LoadState<Nothing>
    data object Empty : LoadState<Nothing>
    data class Error(val message: UiText, val detail: String? = null) : LoadState<Nothing>
    data class Ready<T>(val data: T) : LoadState<T>
}

/** Desenha o estado: skeleton, vazio, erro com "Tentar novamente" ou o conteúdo. */
@Composable
fun <T> LoadStateContent(
    state: LoadState<T>,
    onRetry: (() -> Unit)?,
    empty: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    loading: @Composable () -> Unit = { ListSkeleton() },
    content: @Composable (T) -> Unit
) {
    Box(modifier.fillMaxWidth()) {
        when (state) {
            LoadState.Loading  -> loading()
            LoadState.Empty    -> empty()
            is LoadState.Error -> ErrorState(state.message.asString(), onRetry, detail = state.detail)
            is LoadState.Ready -> content(state.data)
        }
    }
}
