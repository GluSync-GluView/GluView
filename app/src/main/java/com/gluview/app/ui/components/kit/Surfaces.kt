package com.gluview.app.ui.components.kit

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.SensorsOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.gluview.app.R
import com.gluview.app.domain.model.GlucoseZone
import com.gluview.app.ui.theme.Spacing
import com.gluview.app.ui.theme.glucoseGraphicPalette
import com.gluview.app.ui.theme.glucoseZoneTextColor

/*
 * Superfícies do kit (redesign visual, Fase 1): UMA linguagem de elevação — card com borda e sem
 * sombra (a sombra fica só no que flutua: balão, FAB, diálogo), como no portal.
 */

/** Tom de mensagens e selos: o mesmo conjunto do Callout do portal. */
enum class Tone { Info, Success, Warning, Danger, Neutral }

@Composable
@ReadOnlyComposable
fun Tone.accent(): Color = when (this) {
    Tone.Info    -> MaterialTheme.colorScheme.primary
    Tone.Success -> glucoseZoneTextColor(GlucoseZone.NORMAL)
    Tone.Warning -> glucoseZoneTextColor(GlucoseZone.LOW)
    Tone.Danger  -> MaterialTheme.colorScheme.error
    Tone.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun Tone.icon(): ImageVector = when (this) {
    Tone.Info, Tone.Neutral -> Icons.Filled.Info
    Tone.Success            -> Icons.Filled.CheckCircle
    Tone.Warning            -> Icons.Filled.Warning
    Tone.Danger             -> Icons.Filled.Error
}

/**
 * Card único do app: surfaceContainer, borda de 1dp outlineVariant, raio medium (16dp), sem
 * sombra. [selected] troca a borda por 2dp primary e é anunciado pelo TalkBack ("selecionado"),
 * não só visto. Substitui Card/ElevatedCard/Surface tingida.
 */
@Composable
fun GluCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    selected: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    val border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                 else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    val inner: @Composable () -> Unit = {
        Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.xs), content = content)
    }
    // Clicável: o nó do clique já junta os filhos, basta marcar o estado. Não clicável: junta os
    // filhos para o card selecionado ser uma parada só do TalkBack.
    val selectedSemantics = if (selected) Modifier.semantics(mergeDescendants = onClick == null) { this.selected = true }
                            else Modifier
    if (onClick != null) {
        Surface(onClick = onClick, modifier = modifier.fillMaxWidth().then(selectedSemantics), shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainer, border = border, content = inner)
    } else {
        Surface(modifier = modifier.fillMaxWidth().then(selectedSemantics), shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainer, border = border, content = inner)
    }
}

/**
 * Aviso dentro da tela: faixa de 4dp no início + fundo com 10% do tom + texto onSurface (o Callout
 * do portal). [announce] = true só quando o aviso surge de uma ação (live region para o TalkBack).
 * A ação vai em onSurface negrito e sublinhado: primary sobre o fundo tingido dá só 4,1:1 no claro.
 */
@Composable
fun GluCallout(
    message: String,
    modifier: Modifier = Modifier,
    tone: Tone = Tone.Info,
    title: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    announce: Boolean = false
) {
    val accent = tone.accent()
    Surface(
        modifier = modifier.fillMaxWidth()
            .then(if (announce) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier),
        shape = MaterialTheme.shapes.small,
        color = accent.copy(alpha = 0.10f)
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(4.dp).fillMaxHeight().background(accent))
            Row(
                Modifier.padding(start = Spacing.sm, end = Spacing.sm, top = Spacing.sm, bottom = Spacing.sm),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                Icon(tone.icon(), contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                    if (title != null) Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    if (actionLabel != null && onAction != null) {
                        TextButton(
                            onClick = onAction,
                            contentPadding = PaddingValues(0.dp),
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)
                        ) {
                            Text(actionLabel, fontWeight = FontWeight.SemiBold, textDecoration = TextDecoration.Underline)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Selo de status NÃO clicável: texto (+ ícone opcional) no tom, sobre o tom a 8% (≥ 4,6:1 sobre
 * surface e surfaceContainer/High nos dois temas). Info usa primaryContainer, como no portal.
 */
@Composable
fun StatusBadge(text: String, modifier: Modifier = Modifier, tone: Tone = Tone.Neutral, icon: ImageVector? = null) {
    val info = tone == Tone.Info
    val accent = if (info) MaterialTheme.colorScheme.onPrimaryContainer else tone.accent()
    val fill = if (info) MaterialTheme.colorScheme.primaryContainer else accent.copy(alpha = 0.08f)
    Surface(modifier = modifier, shape = CircleShape, color = fill) {
        Row(
            Modifier.padding(horizontal = Spacing.xs, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(14.dp))
            Text(text, style = MaterialTheme.typography.labelMedium, color = accent)
        }
    }
}

/**
 * Selo da zona de glicemia com TEXTO e ícone de FORMA própria — a cor nunca é a única pista (o
 * GlucoseStateBadge do portal): ▼ baixa, ▲ alta, ▼▼/▲▲ urgente, ● no alvo, linha caindo para queda
 * rápida, sensor cortado para sem sinal. Triângulos, não setas, para não confundir com a seta de
 * tendência ao lado do número. Fundo transparente e borda no tom: o texto mantém ≥ 4,5:1 sobre
 * surface e surfaceContainer/High (um fundo tingido derrubava "Baixa urgente" no claro).
 */
@Composable
fun GlucoseZoneChip(zone: GlucoseZone, modifier: Modifier = Modifier) {
    val (label, icon) = when (zone) {
        GlucoseZone.URGENT_LOW  -> R.string.zone_urgent_low to ZoneIcons.FarBelow
        GlucoseZone.LOW         -> R.string.zone_low to ZoneIcons.Below
        GlucoseZone.NORMAL      -> R.string.zone_in_range to ZoneIcons.InRange
        GlucoseZone.HIGH        -> R.string.zone_high to ZoneIcons.Above
        GlucoseZone.URGENT_HIGH -> R.string.zone_urgent_high to ZoneIcons.FarAbove
        GlucoseZone.QUICK_DROP  -> R.string.zone_quick_drop to Icons.AutoMirrored.Filled.TrendingDown
        GlucoseZone.STALE       -> R.string.zone_stale to Icons.Filled.SensorsOff
    }
    val color = glucoseZoneTextColor(zone)
    Surface(modifier = modifier, shape = CircleShape, color = Color.Transparent, border = BorderStroke(1.5.dp, color)) {
        Row(
            Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xxs),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
            Text(stringResource(label), style = MaterialTheme.typography.labelLarge, color = color)
        }
    }
}

/** Cor de gráfico da zona no tema atual, para quem precisa dela fora do renderer (ex.: legenda). */
@Composable
@ReadOnlyComposable
fun GlucoseZone.graphicColor(): Color {
    val p = glucoseGraphicPalette()
    return when (this) {
        GlucoseZone.URGENT_LOW, GlucoseZone.URGENT_HIGH -> p.urgent
        GlucoseZone.LOW, GlucoseZone.HIGH, GlucoseZone.QUICK_DROP -> p.outOfRange
        GlucoseZone.NORMAL -> p.inRange
        GlucoseZone.STALE -> MaterialTheme.colorScheme.outline
    }
}
