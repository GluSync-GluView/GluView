package com.gluview.app.domain.model

data class GlucoseReading(
    val sgvMgDl: Double,
    val direction: String,
    val arrow: String,
    val timestamp: String,
    val zone: GlucoseZone,
    val delta: Int,
    val isStale: Boolean,
    val minutesAgo: Int = 0,
    /** Epoch ms do instante da MEDIÇÃO. Base do "há X min" dinâmico (home + widgets). 0 = n/d. */
    val epochMs: Long = 0L
)
