package com.gluview.app.util.graph

/**
 * Cores do gráfico de glicemia — a ÚNICA fonte dos hex do motor (redesign visual, Fase 2). Os
 * valores são os do GlucoseGraphRenderer de antes da refatoração, em ARGB literal; o oráculo
 * (RendererOracleTest) prova que o desenho não mudou. O tema do app (GlucoseZoneColors.Graphic)
 * lê daqui; um teste de arquitetura falha se um destes hex aparecer fora de util/graph.
 */
object GraphPalette {

    // Fundo do painel ("tema do gráfico"): escuro na Home/notificação, claro no seguidor.
    const val BG_DARK = 0xFF1C1C1E.toInt()
    const val BG_LIGHT = 0xFFFAFAFA.toInt()

    // Tinta base — clara sobre o escuro, escura sobre o claro.
    const val GRID_DARK = 0x25FFFFFF
    const val GRID_LIGHT = 0x1F000000
    const val LABEL_DARK = 0xAAFFFFFF.toInt()
    const val LABEL_LIGHT = 0x99000000.toInt()
    const val TICK_DARK = 0x30FFFFFF
    const val TICK_LIGHT = 0x26000000
    const val LINE_DARK = 0x70FFFFFF
    const val LINE_LIGHT = 0x80616161.toInt()
    /** Faixa-alvo fixa 100/150 (linhas e rótulos neutros). */
    const val SECONDARY_DARK = 0x80FFFFFF.toInt()
    const val SECONDARY_LIGHT = 0x80000000.toInt()

    // Zonas e limiares (iguais nos dois temas no desenho de hoje).
    const val URGENT_BAND = 0x2AEF5350
    const val HIPO_LINE = 0xCCEF5350.toInt()
    const val HIPER_LINE = 0xCCFFA726.toInt()
    const val ZONE_URGENT = 0xFFEF5350.toInt()
    const val ZONE_OUT_OF_RANGE = 0xFFFF7043.toInt()
    const val ZONE_IN_RANGE = 0xFF66BB6A.toInt()

    // Gráfico claro de alto contraste (decisão 5.2 #8): um tom mais escuro, >= 3:1 contra #FAFAFA
    // e as superfícies claras do app, já com o alfa de 80% das linhas. Só com highContrastLight.
    const val HIPO_LINE_LIGHT_HC = 0xCCD32F2F.toInt()
    const val HIPER_LINE_LIGHT_HC = 0xCCC84300.toInt()
    const val ZONE_URGENT_LIGHT_HC = 0xFFE53935.toInt()
    const val ZONE_OUT_OF_RANGE_LIGHT_HC = 0xFFE64A19.toInt()
    const val ZONE_IN_RANGE_LIGHT_HC = 0xFF388E3C.toInt()
    const val URGENT_BAND_LIGHT_HC = 0x2AE53935

    // Visor da Mi Band: fundo preto do template, limiares opacos e tracejados.
    const val WATCH_BG = 0xFF000000.toInt()
    const val WATCH_URGENT_BAND = 0x33EF5350
    const val WATCH_HIPO = 0xFFEF5350.toInt()
    const val WATCH_HIPER = 0xFFFFA726.toInt()
    const val WATCH_SECONDARY = 0x66FFFFFF
    const val WATCH_LINE = 0x50FFFFFF

    /**
     * Cor do ponto por zona, pela regra única do app (GlucoseThresholds, L2): o valor do limite
     * conta como fora (70 com "Baixo" em 70 sai laranja, como o card diz "baixa"). Antes o ponto
     * usava < / > e o card <= / >=, e com 70 os dois discordavam.
     */
    fun zoneColor(
        sgv: Float, hipo: Float, hiper: Float, highContrastLight: Boolean = false,
        urgentLow: Float = GraphScale.URGENT_LOW, urgentHigh: Float = GraphScale.URGENT_HIGH
    ): Int {
        val urgent = if (highContrastLight) ZONE_URGENT_LIGHT_HC else ZONE_URGENT
        val out = if (highContrastLight) ZONE_OUT_OF_RANGE_LIGHT_HC else ZONE_OUT_OF_RANGE
        return when {
            sgv <= urgentLow  -> urgent
            sgv <= hipo       -> out
            sgv >= urgentHigh -> urgent
            sgv >= hiper      -> out
            else -> if (highContrastLight) ZONE_IN_RANGE_LIGHT_HC else ZONE_IN_RANGE
        }
    }
}

/** Escala fixa do modelo da Home: Y de 40 a 300 mg/dL, urgência abaixo de 55 e acima de 250. */
object GraphScale {
    const val MIN_SGV = 40f
    const val MAX_SGV = 300f
    const val URGENT_LOW = 55f
    const val URGENT_HIGH = 250f
    const val DEFAULT_HIPO = 70f
    const val DEFAULT_HIPER = 180f
}
