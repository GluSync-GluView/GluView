package com.gluview.wearables.domain

/**
 * F7.1 core: modelo mínimo de leitura de glicemia para transporte entre celular e wearables.
 * Independente do domínio do app (sem dependência de com.gluview.app.domain.model).
 */
data class GlucoseReading(
    val sgvMgDl: Double,
    val direction: String,
    val arrow: String,
    val timestamp: String,
    val zone: GlucoseZone,
    val delta: Int,
    val isStale: Boolean,
    val minutesAgo: Int = 0,
    val epochMs: Long = 0L
)