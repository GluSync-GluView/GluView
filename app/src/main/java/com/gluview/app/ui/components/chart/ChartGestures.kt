package com.gluview.app.ui.components.chart

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import kotlin.math.abs

/**
 * Rolagem e zoom do gráfico como no xDrip (pedido do Felipe, 14/11/2026): arrastar na horizontal
 * anda no tempo, pinçar muda o tamanho da janela. Arrasto vertical não é consumido (a tela pode
 * rolar) e um toque curto continua abrindo o balão da leitura.
 */
class ChartGestures(
    /** dx em fração da largura do gráfico (positivo = arrastou para a direita = passado). */
    val onPan: (Float) -> Unit,
    /** Fator do pinçar desde o último evento (>1 = afastou os dedos = zoom in). */
    val onZoom: (Float) -> Unit,
    val onGestureEnd: () -> Unit = {}
)

fun Modifier.chartGestures(g: ChartGestures?, slopPx: Float): Modifier = if (g == null) this else pointerInput(g) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var dragging = false
        var acc = 0f
        do {
            val event = awaitPointerEvent()
            val pressed = event.changes.count { it.pressed }
            if (pressed >= 2) {
                val zoom = event.calculateZoom()
                if (zoom != 1f && event.calculateCentroidSize() > 0f) {
                    g.onZoom(zoom)
                    dragging = true
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                }
            } else if (pressed == 1) {
                val pan = event.calculatePan()
                if (!dragging) {
                    acc += pan.x
                    if (abs(acc) > slopPx && abs(pan.x) >= abs(pan.y)) dragging = true
                }
                if (dragging) {
                    g.onPan(pan.x / size.width.coerceAtLeast(1))
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                }
            }
        } while (event.changes.any { it.pressed })
        if (dragging) g.onGestureEnd()
    }
}
