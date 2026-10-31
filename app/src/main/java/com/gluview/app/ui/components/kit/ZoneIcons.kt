package com.gluview.app.ui.components.kit

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/*
 * Ícones de zona de glicemia (redesign visual, Fase 1), na lógica do GlucoseStateBadge do portal:
 * a DIREÇÃO diz baixa × alta e a DUPLICAÇÃO diz urgente, para quem não distingue as cores. O
 * portal usa setas; aqui são triângulos cheios, porque o selo fica logo abaixo do número da Home,
 * ao lado da seta de tendência — uma seta no selo podia ser lida como tendência.
 */
internal object ZoneIcons {

    private fun icon(name: String, block: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
            .path(fill = SolidColor(Color.Black), pathBuilder = block)
            .build()

    /** ▼ abaixo da faixa. */
    val Below: ImageVector = icon("ZoneBelow") {
        moveTo(4.5f, 7.5f); lineTo(19.5f, 7.5f); lineTo(12f, 17.5f); close()
    }

    /** ▲ acima da faixa. */
    val Above: ImageVector = icon("ZoneAbove") {
        moveTo(4.5f, 16.5f); lineTo(19.5f, 16.5f); lineTo(12f, 6.5f); close()
    }

    /** ▼▼ muito abaixo (urgente). */
    val FarBelow: ImageVector = icon("ZoneFarBelow") {
        moveTo(5.5f, 3f); lineTo(18.5f, 3f); lineTo(12f, 11f); close()
        moveTo(5.5f, 13f); lineTo(18.5f, 13f); lineTo(12f, 21f); close()
    }

    /** ▲▲ muito acima (urgente). */
    val FarAbove: ImageVector = icon("ZoneFarAbove") {
        moveTo(5.5f, 21f); lineTo(18.5f, 21f); lineTo(12f, 13f); close()
        moveTo(5.5f, 11f); lineTo(18.5f, 11f); lineTo(12f, 3f); close()
    }

    /** ● na faixa. */
    val InRange: ImageVector = icon("ZoneInRange") {
        moveTo(12f, 6f)
        arcTo(6f, 6f, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = 12f, y1 = 18f)
        arcTo(6f, 6f, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = 12f, y1 = 6f)
        close()
    }
}
