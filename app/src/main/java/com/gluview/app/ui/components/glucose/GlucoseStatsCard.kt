package com.gluview.app.ui.components.glucose

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gluview.app.ui.theme.Spacing

/** Um indicador do card de estatísticas: rótulo ("Mín") e valor já formatado na unidade. */
data class GlucoseStat(val label: String, val value: String)

/**
 * Card de estatísticas da glicemia (redesign visual, Fase 3): mínima, média e máxima em colunas
 * iguais, legenda do período embaixo. Borda e sem sombra (a linguagem do kit), cada indicador é
 * uma parada só do TalkBack ("Mín, 56") e o conjunto quebra de linha em vez de cortar.
 */
@Composable
fun GlucoseStatsCard(stats: List<GlucoseStat>, caption: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.xs),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                stats.forEach { s ->
                    Column(
                        Modifier.weight(1f).semantics(mergeDescendants = true) {},
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(s.value, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                        Text(s.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center)
                    }
                }
            }
            Text(caption, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center)
        }
    }
}
