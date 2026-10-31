package com.gluview.app.ui.components.glucose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gluview.app.R
import com.gluview.app.domain.model.GlucoseThresholds
import com.gluview.app.domain.model.GlucoseZone
import com.gluview.app.ui.components.kit.CappedFontScale
import com.gluview.app.ui.components.kit.graphicColor
import com.gluview.app.ui.theme.GluType
import com.gluview.app.ui.theme.Spacing
import com.gluview.app.ui.theme.glucoseZoneTextColor

/** Teto de escala do número da glicemia: 72sp a 2,0x não cabe numa tela de 360dp. */
private const val GLUCOSE_VALUE_MAX_FONT_SCALE = 1.3f

/** Largura do slot da seta, reservada dos DOIS lados do número para ele ficar no centro real. */
private val ArrowSlotWidth = 64.dp

/** Zona da leitura a partir do valor (seguidor e acompanhado, que só recebem o número). */
fun glucoseZoneFor(sgvMgDl: Double, thresholds: GlucoseThresholds = GlucoseThresholds.DEFAULT, arrow: String = ""): GlucoseZone =
    thresholds.classify(sgvMgDl, arrow)

/** Tendência em palavras para o TalkBack (a seta sozinha é lida como "seta para a direita"). */
@Composable
fun trendLabel(directionOrArrow: String): String? = when (directionOrArrow) {
    "DoubleUp", "↑↑" -> stringResource(R.string.trend_double_up)
    "SingleUp", "↑" -> stringResource(R.string.trend_single_up)
    "FortyFiveUp", "↗" -> stringResource(R.string.trend_forty_five_up)
    "Flat", "→" -> stringResource(R.string.trend_flat)
    "FortyFiveDown", "↘" -> stringResource(R.string.trend_forty_five_down)
    "SingleDown", "↓" -> stringResource(R.string.trend_single_down)
    "DoubleDown", "↓↓" -> stringResource(R.string.trend_double_down)
    else -> null
}

/**
 * Card ÚNICO da leitura atual (redesign visual, Fase 3) — Home, seguidor e acompanhado tinham 3
 * versões. Número e seta na cor de texto da zona (contraste >= 4,5:1), sem quebrar com fonte grande
 * (escala limitada), unidade centralizada, linha de status que quebra em vez de cortar palavra, e
 * uma parada só para o TalkBack, que fala a zona por extenso:
 * "Glicemia 135 mg/dL, estável, no alvo. Medida às 14:36."
 *
 * Os slots [statusStart]/[statusEnd] (conexão e idade do sensor na Home) e [below] (aviso de sem
 * sinal) ficam com quem chama; ações neles continuam focáveis à parte.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GlucoseHeroCard(
    valueText: String,
    arrow: String,
    unitLabel: String,
    zone: GlucoseZone,
    modifier: Modifier = Modifier,
    trendDescription: String? = trendLabel(arrow),
    timeText: String? = null,
    timeIsOld: Boolean = false,
    subtitle: String? = null,
    statusStart: (@Composable () -> Unit)? = null,
    statusEnd: (@Composable () -> Unit)? = null,
    below: (@Composable () -> Unit)? = null
) {
    val color = glucoseZoneTextColor(zone)
    val zoneName = stringResource(zoneLabelRes(zone))
    // Sem a hora (seguidor e acompanhado), não fala "Medida às —": fala a linha de baixo ("+5 · agora").
    val a11y = if (timeText != null) {
        stringResource(R.string.glucose_hero_a11y, valueText, unitLabel, trendDescription ?: arrow, zoneName, timeText)
    } else {
        listOfNotNull(
            stringResource(R.string.glucose_hero_a11y_no_time, valueText, unitLabel, trendDescription ?: arrow, zoneName),
            subtitle
        ).joinToString(" ")
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = zone.graphicColor().copy(alpha = 0.12f)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.sm),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.xxs)
        ) {
            // Leitura principal: uma parada só do TalkBack com a frase completa.
            Column(
                Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = a11y },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CappedFontScale(GLUCOSE_VALUE_MAX_FONT_SCALE) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Spacer(Modifier.width(ArrowSlotWidth))
                        Text(valueText, style = GluType.GlucoseValue, color = color, maxLines = 1, softWrap = false)
                        Box(Modifier.width(ArrowSlotWidth), contentAlignment = Alignment.CenterStart) {
                            Text(arrow, style = GluType.GlucoseArrow, color = color, maxLines = 1, softWrap = false,
                                modifier = Modifier.padding(start = Spacing.xs))
                        }
                    }
                }
                // Só a unidade, centralizada (o selo da zona ao lado ou à direita foi testado e saiu
                // a pedido do mantenedor em 18/10; a zona segue na cor e na leitura do TalkBack).
                Text(unitLabel, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (statusStart != null || timeText != null || statusEnd != null) {
                // Conexão — horário da medição — sensor. Quebra de linha em vez de cortar palavra
                // ("Conecta/do" com fonte grande).
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(top = Spacing.xxs),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalArrangement = Arrangement.spacedBy(Spacing.xxs)
                ) {
                    Box(Modifier.align(Alignment.CenterVertically)) { statusStart?.invoke() }
                    if (timeText != null) {
                        Text(timeText, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Clip,
                            color = if (timeIsOld) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.CenterVertically).clearAndSetSemantics { })
                    }
                    Box(Modifier.align(Alignment.CenterVertically)) { statusEnd?.invoke() }
                }
            }
            below?.invoke()
        }
    }
}

private fun zoneLabelRes(zone: GlucoseZone): Int = when (zone) {
    GlucoseZone.URGENT_LOW -> R.string.zone_urgent_low
    GlucoseZone.LOW -> R.string.zone_low
    GlucoseZone.NORMAL -> R.string.zone_in_range
    GlucoseZone.HIGH -> R.string.zone_high
    GlucoseZone.URGENT_HIGH -> R.string.zone_urgent_high
    GlucoseZone.QUICK_DROP -> R.string.zone_quick_drop
    GlucoseZone.STALE -> R.string.zone_stale
}
