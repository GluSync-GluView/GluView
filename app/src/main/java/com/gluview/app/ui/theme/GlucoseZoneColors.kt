package com.gluview.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.material3.MaterialTheme
import com.gluview.app.domain.model.GlucoseZone
import com.gluview.app.util.graph.GraphPalette

/**
 * Fonte única das cores de zona de glicemia (redesign visual, Fase 1), no espelho do portal
 * (GluWebView/packages/ui/src/tokens-vars.css: --color-glucose-* e --color-glucose-*-strong).
 *
 * Dois papéis, porque a mesma cor não serve para tudo:
 *  - [text]: número, seta, rótulo de zona e selo — tons por tema com contraste ≥ 4,5:1 contra
 *    background, surface e surfaceContainer/High (os tons antigos, ex. #FF6F00 sobre claro, davam
 *    ≈2,4:1). Vale para texto sobre essas superfícies; o GlucoseZoneChip por isso não tinge o fundo;
 *  - [Graphic]: pontos, linhas e faixas do gráfico — a paleta do modelo da Home
 *    (GlucoseGraphRenderer), com 3:1 (contraste de elemento gráfico) nos dois temas.
 *
 * As cores antigas do card da Home (GlucoseUrgentLow… em Color.kt) continuam existindo até a
 * Fase 3 migrar a Home; tela nova usa só daqui.
 */
object GlucoseZoneColors {

    /** Tons de texto/ícone por zona. [dark] = tema escuro do app. */
    fun text(zone: GlucoseZone, dark: Boolean): Color = if (dark) when (zone) {
        GlucoseZone.URGENT_LOW  -> Color(0xFFFF6659)
        GlucoseZone.LOW         -> Color(0xFFFFB74D)
        GlucoseZone.NORMAL      -> Color(0xFF66BB6A)
        GlucoseZone.HIGH        -> Color(0xFFFFA726)
        GlucoseZone.URGENT_HIGH -> Color(0xFFFF5449)
        GlucoseZone.QUICK_DROP  -> Color(0xFFFFB74D)
        GlucoseZone.STALE       -> Color(0xFFC4C6D0)   // onSurfaceVariant escuro
    } else when (zone) {
        GlucoseZone.URGENT_LOW  -> Color(0xFFC62828)
        GlucoseZone.LOW         -> Color(0xFF8A4300)
        GlucoseZone.NORMAL      -> Color(0xFF1B5E20)
        GlucoseZone.HIGH        -> Color(0xFF7A3300)
        GlucoseZone.URGENT_HIGH -> Color(0xFFB71C1C)
        GlucoseZone.QUICK_DROP  -> Color(0xFF8A4300)
        GlucoseZone.STALE       -> Color(0xFF44474F)   // onSurfaceVariant claro
    }

    /**
     * Paleta do gráfico. [Dark] é a do modelo da Home hoje (GlucoseGraphRenderer.zoneColor e as
     * linhas de limiar, que o renderer desenha a 80% de alfa — o alfa está na própria cor);
     * [Light] tem as mesmas cores um tom mais escuras, para chegar a 3:1 contra #FAFAFA, surface e
     * surfaceContainer/High claros já com o alfa (as atuais dão 1,6–3,0:1 no claro). O renderer
     * passa a usar daqui na Fase 2; até lá nada muda no gráfico.
     */
    data class GraphicPalette(
        val urgent: Color,      // < 55 e > 250
        val outOfRange: Color,  // < hipo e > hiper
        val inRange: Color,
        val hipoLine: Color,
        val hiperLine: Color,
        val urgentBand: Color   // faixa < 55
    )

    /** Lê do GraphPalette (util/graph), a única fonte dos hex do gráfico (Fase 2). */
    object Graphic {
        val Dark = GraphicPalette(
            urgent     = Color(GraphPalette.ZONE_URGENT),
            outOfRange = Color(GraphPalette.ZONE_OUT_OF_RANGE),
            inRange    = Color(GraphPalette.ZONE_IN_RANGE),
            hipoLine   = Color(GraphPalette.HIPO_LINE),
            hiperLine  = Color(GraphPalette.HIPER_LINE),
            urgentBand = Color(GraphPalette.URGENT_BAND)
        )
        val Light = GraphicPalette(
            urgent     = Color(GraphPalette.ZONE_URGENT_LIGHT_HC),        // 3,5:1 sobre surfaceContainerHigh
            outOfRange = Color(GraphPalette.ZONE_OUT_OF_RANGE_LIGHT_HC),  // 3,2:1
            inRange    = Color(GraphPalette.ZONE_IN_RANGE_LIGHT_HC),      // 3,4:1
            hipoLine   = Color(GraphPalette.HIPO_LINE_LIGHT_HC),          // 3,2:1 com o alfa de 80%
            hiperLine  = Color(GraphPalette.HIPER_LINE_LIGHT_HC),         // 3,1:1 com o alfa de 80%
            urgentBand = Color(GraphPalette.URGENT_BAND_LIGHT_HC)
        )
        fun of(dark: Boolean): GraphicPalette = if (dark) Dark else Light
    }
}

/** Tom de texto da zona no tema atual (escuro = fundo com luminância baixa). */
@Composable
@ReadOnlyComposable
fun glucoseZoneTextColor(zone: GlucoseZone): Color =
    GlucoseZoneColors.text(zone, dark = MaterialTheme.colorScheme.background.luminance() < 0.5f)

/** Paleta do gráfico no tema atual. */
@Composable
@ReadOnlyComposable
fun glucoseGraphicPalette(): GlucoseZoneColors.GraphicPalette =
    GlucoseZoneColors.Graphic.of(dark = MaterialTheme.colorScheme.background.luminance() < 0.5f)
