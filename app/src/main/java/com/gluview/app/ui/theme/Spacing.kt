package com.gluview.app.ui.theme

import androidx.compose.ui.unit.dp

/**
 * Escala de espaçamento de 4dp (redesign visual, Fase 1) — a mesma do portal
 * (tokens-vars.css: --space-1…7). Antes o app tinha 10 valores de spacedBy e gutters de 12, 16 e
 * 24dp conforme a tela; tela nova usa só estes.
 */
object Spacing {
    val xxs = 4.dp
    val xs  = 8.dp
    val sm  = 12.dp
    val md  = 16.dp
    val lg  = 24.dp
    val xl  = 32.dp
    val xxl = 48.dp

    /** Margem lateral das telas. */
    val gutter = md
    /** Espaço entre seções de uma tela. */
    val section = lg
    /** Espaço entre itens de uma seção. */
    val item = sm
}
