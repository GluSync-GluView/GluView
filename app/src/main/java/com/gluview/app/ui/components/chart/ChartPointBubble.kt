package com.gluview.app.ui.components.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gluview.app.R
import kotlin.math.roundToInt

/**
 * Balão do ponto tocado, v2 (redesign visual, Fase 2): tamanho MEDIDO (antes estimado em 130×76dp,
 * cortava com fonte grande), corpo preso dentro do gráfico, rabicho que acompanha o ponto mesmo
 * quando o corpo encosta na borda, fechar com 48dp e rótulo, título opcional ("Média 15:00–18:00").
 *
 * Ocupa a caixa do gráfico inteira (fillMaxSize do pai) e se posiciona sozinho em [point].
 */
@Composable
fun ChartPointBubble(
    point: Offset,
    timeText: String,
    valueText: String,
    unitLabel: String,
    accentColor: Color,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null
) {
    val bg = MaterialTheme.colorScheme.surfaceContainerHigh
    Layout(
        modifier = modifier,
        content = {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = bg,
                shadowElevation = 6.dp,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.padding(start = 12.dp, top = 6.dp, bottom = 6.dp)
                ) {
                    Column {
                        if (title != null) {
                            Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(timeText, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(valueText, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = accentColor)
                            Text(unitLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.common_close),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    }
                }
            }
            Canvas(Modifier.size(width = 16.dp, height = 8.dp)) {
                // Desenhado apontando para baixo; o layout gira 180° quando o balão vai abaixo do ponto.
                val path = Path().apply { moveTo(0f, 0f); lineTo(size.width / 2f, size.height); lineTo(size.width, 0f); close() }
                drawPath(path, color = bg)
            }
        }
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val body = measurables[0].measure(loose)
        val tail = measurables[1].measure(loose)
        val w = constraints.maxWidth
        val h = constraints.maxHeight
        val gap = 6.dp.roundToPx()
        // Acima do ponto quando cabe; senão abaixo.
        val above = point.y - gap - tail.height - body.height >= 0
        val bodyX = (point.x - body.width / 2f).roundToInt().coerceIn(0, (w - body.width).coerceAtLeast(0))
        val bodyY = if (above) (point.y - gap - tail.height - body.height).roundToInt()
                    else (point.y + gap + tail.height).roundToInt().coerceAtMost((h - body.height).coerceAtLeast(0))
        val tailMargin = 14.dp.roundToPx()
        val tailLo = bodyX + tailMargin - tail.width / 2
        val tailHi = bodyX + body.width - tailMargin - tail.width / 2
        val tailX = if (tailLo <= tailHi) (point.x - tail.width / 2f).roundToInt().coerceIn(tailLo, tailHi)
                    else bodyX + (body.width - tail.width) / 2
        val tailY = if (above) bodyY + body.height else bodyY - tail.height
        layout(w, h) {
            body.place(bodyX, bodyY)
            if (above) tail.place(tailX, tailY)
            else tail.placeWithLayer(tailX, tailY) { rotationZ = 180f }
        }
    }
}
