package com.gluview.app.util.graph

/**
 * Geometria de um gráfico: converte dado (timestamp/sgv) <-> pixel. A mesma instância serve ao
 * desenho, ao toque e ao balão — antes o toque recalculava com o relógio do momento e o balão
 * podia deslizar até a leitura vizinha.
 */
class GraphGeometry(
    val fromMs: Long,
    /** Borda direita do eixo X (agora, a leitura mais nova um pouco à frente, ou o fim da janela fixa). */
    val nowMs: Long,
    val padL: Float,
    val padT: Float,
    val chartW: Float,
    val chartH: Float
) {
    fun yOf(sgv: Float): Float {
        val ratio = (sgv.coerceIn(GraphScale.MIN_SGV, GraphScale.MAX_SGV) - GraphScale.MIN_SGV) /
            (GraphScale.MAX_SGV - GraphScale.MIN_SGV)
        return padT + chartH * (1f - ratio)
    }

    fun xOf(ts: Long): Float {
        val ratio = ((ts - fromMs).toFloat() / (nowMs - fromMs).coerceAtLeast(1L)).coerceIn(0f, 1f)
        return padL + ratio * chartW
    }

    fun timestampAt(xPx: Float): Long {
        val ratio = ((xPx - padL) / chartW).coerceIn(0f, 1f)
        return fromMs + (ratio * (nowMs - fromMs)).toLong()
    }
}
