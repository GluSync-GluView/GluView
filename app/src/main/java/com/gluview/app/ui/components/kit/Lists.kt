package com.gluview.app.ui.components.kit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gluview.app.R
import com.gluview.app.ui.theme.Spacing

/*
 * Listas do kit (redesign visual, Fase 1): FactList (rótulo → valor) no lugar das frases
 * "Rótulo: valor", ItemCard no lugar de cards de uma linha com ações espremidas, e StatTile/
 * StatGrid para indicadores — os equivalentes do FactList/.gw-item/StatGrid do portal.
 */

/** Um par rótulo → valor. [valueColor] = null usa onSurface. */
data class Fact(
    val label: String,
    val value: String,
    val valueColor: Color? = null,
    /** true = rótulo em cima e valor com a largura toda (e-mail, URL: longos demais para meia linha). */
    val wide: Boolean = false
)

/**
 * Lista rótulo → valor. Em largura confortável, rótulo à esquerda e valor à direita na mesma
 * linha; com pouca largura ou fonte grande, empilha (rótulo em cima, valor embaixo) — nunca corta.
 */
@Composable
fun FactList(facts: List<Fact>, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val stacked = maxWidth < 280.dp || LocalDensity.current.fontScale >= 1.5f
        Column(Modifier.fillMaxWidth()) {
            facts.forEachIndexed { i, fact ->
                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                FactRow(fact, stacked || fact.wide)
            }
        }
    }
}

@Composable
private fun FactRow(fact: Fact, stacked: Boolean) {
    val valueColor = fact.valueColor ?: MaterialTheme.colorScheme.onSurface
    // O TalkBack lê "rótulo: valor" num toque só.
    val rowModifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xs)
        .semantics(mergeDescendants = true) { contentDescription = "${fact.label}: ${fact.value}" }
    if (stacked) {
        Column(rowModifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(fact.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(fact.value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = valueColor)
        }
    } else {
        Row(rowModifier, horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.Top) {
            Text(fact.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(0.45f))
            Text(fact.value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = valueColor,
                textAlign = TextAlign.End, modifier = Modifier.weight(0.55f))
        }
    }
}

/**
 * Item de lista: corpo (título + meta) e ações. As ações vão para uma linha própria quando não
 * cabem ao lado (FlowRow) — nenhum botão sozinho espremido.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ItemCard(
    title: String,
    modifier: Modifier = Modifier,
    meta: String? = null,
    onClick: (() -> Unit)? = null,
    selected: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null
) {
    GluCard(modifier = modifier, onClick = onClick, selected = selected) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
            if (leading != null) leading()
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (meta != null) Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (actions != null) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(Spacing.xxs)
            ) { actions() }
        }
    }
}

/**
 * Indicador: rótulo, valor grande (com unidade) e sublinha; [tone] colore o valor. "—" se nulo
 * (anunciado como "sem dado"). Uma parada só do TalkBack: "Média, 142, mg/dL, últimos 14 dias".
 */
@Composable
fun StatTile(
    label: String,
    value: String?,
    modifier: Modifier = Modifier,
    unit: String? = null,
    sublabel: String? = null,
    tone: Tone = Tone.Neutral
) {
    val noData = stringResource(R.string.common_no_data)
    GluCard(modifier = modifier.semantics(mergeDescendants = true) {}) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
            Text(value ?: "—", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold,
                color = if (tone == Tone.Neutral) MaterialTheme.colorScheme.onSurface else tone.accent(),
                modifier = if (value == null) Modifier.semantics { contentDescription = noData } else Modifier)
            if (unit != null && value != null) {
                Text(unit, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (sublabel != null) Text(sublabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Grade de indicadores: 2 por linha, mesma altura na linha; com fonte grande ou tela estreita, 1 por linha. */
@Composable
fun StatGrid(tiles: List<@Composable (Modifier) -> Unit>, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val columns = if (maxWidth < 300.dp || LocalDensity.current.fontScale >= 1.5f) 1 else 2
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            tiles.chunked(columns).forEach { row ->
                // IntrinsicSize.Min + fillMaxHeight: os cards da linha esticam até o mais alto.
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    // weight(1f): numa linha incompleta, o último ocupa a largura toda (sem buraco).
                    row.forEach { tile -> tile(Modifier.weight(1f).fillMaxHeight()) }
                }
            }
        }
    }
}
