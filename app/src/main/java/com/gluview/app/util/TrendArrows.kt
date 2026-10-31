package com.gluview.app.util

private val ARROWS = setOf("↑↑", "↑", "↗", "→", "↘", "↓", "↓↓")

/**
 * Direção Nightscout ("Flat", "SingleUp"...) -> seta; o que já for seta passa igual. Desconhecida
 * ("NOT COMPUTABLE", "RATE OUT OF RANGE") vira vazio — antes o texto aparecia no lugar da seta.
 */
fun directionToArrow(direction: String): String = when (direction.lowercase()) {
    "doubleup" -> "↑↑"; "singleup" -> "↑"; "fortyfiveup" -> "↗"
    "flat" -> "→"; "fortyfivedown" -> "↘"; "singledown" -> "↓"; "doubledown" -> "↓↓"
    else -> direction.takeIf { it in ARROWS }.orEmpty()
}

/**
 * Seta de uma leitura ao vivo (NovaMedicao): o campo "arrow" quando vier; senão a direção
 * convertida (L30 — o GluSync manda a GlucoseEntry crua, só com "direction").
 */
fun liveArrow(arrow: String?, direction: String?): String =
    arrow?.takeIf { it.isNotBlank() } ?: directionToArrow(direction.orEmpty())
