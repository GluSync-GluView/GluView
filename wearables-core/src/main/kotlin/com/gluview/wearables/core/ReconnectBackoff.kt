package com.gluview.wearables.core

import kotlinx.coroutines.delay
import kotlin.math.min
import kotlin.math.pow

/**
 * Recuo exponencial com teto, usado na reconexão (F7.1). Determinístico quando [jitterRatio]=0 (útil em testes).
 */
class ReconnectBackoff(
    private val initialMs: Long = 2_000L,
    private val maxMs: Long = 5 * 60_000L,
    private val factor: Double = 2.0,
    private val jitterRatio: Double = 0.0
) {
    private var attempt = 0

    fun reset() { attempt = 0 }
    fun attempts(): Int = attempt

    fun nextDelayMs(): Long {
        val base = min(maxMs.toDouble(), initialMs * factor.pow(attempt.toDouble())).toLong().coerceAtLeast(initialMs)
        attempt++
        if (jitterRatio <= 0.0) return base
        val delta = (base * jitterRatio).toLong()
        val jitter = if (delta == 0L) 0L else (-delta..delta).random()
        return (base + jitter).coerceIn(initialMs, maxMs)
    }

    suspend fun waitNext() {
        delay(nextDelayMs())
    }
}
