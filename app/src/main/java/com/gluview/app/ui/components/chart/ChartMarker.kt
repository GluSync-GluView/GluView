package com.gluview.app.ui.components.chart

/**
 * Marcador de um registro do diário no gráfico (plano 17, F1.6): bolinha na cor do tipo, na
 * altura da glicemia do momento (ou no rodapé, quando não havia leitura).
 */
data class ChartMarker(
    val timestampMs: Long,
    /** 0 = sem leitura no momento. */
    val sgvMgDl: Double,
    val argb: Int
)
