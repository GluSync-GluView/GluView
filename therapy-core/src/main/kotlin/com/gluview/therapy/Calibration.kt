package com.gluview.therapy

import kotlin.math.abs

/** Uma ponta de dedo e o valor do sensor no mesmo momento (mg/dL). */
data class CalPair(val atMs: Long, val fingerstickMgDl: Double, val sensorMgDl: Double)

/** Calibração linear: calibrado = inclinação × sensor + deslocamento. */
data class LinearCalibration(val slope: Double, val offset: Double, val pairs: Int) {
    fun apply(sensorMgDl: Double): Double = (slope * sensorMgDl + offset).coerceIn(39.0, 401.0)
    /** Quanto a calibração muda o valor 100 mg/dL. */
    val correctionAt100: Double get() = slope * 100 + offset - 100
}

enum class CalRejection { WARMUP, FAST_CHANGE, NO_RECENT_SENSOR, OUT_OF_RANGE }
enum class CalFitProblem { TOO_FEW_PAIRS, SLOPE_LIMIT, TOO_LARGE }

/**
 * Plano 21, F3.1 — calibração com glicemia capilar, com as travas do plugin de calibração do
 * AndroidAPS 4.0 (estudo 22, §2), implementadas a partir da descrição (sem código AAPS):
 *  - pelo menos 2 pares; inclinação limitada a 0,55–1,6;
 *  - se a correção em 100 mg/dL passar de ±30, NÃO aplica;
 *  - recusa o par nas 2 h depois da troca de sensor, com glicemia mudando rápido, ou sem leitura do
 *    sensor nos últimos 10 min.
 * Ajuste por mínimos quadrados; com a inclinação fora do limite, refaz com ela presa no limite.
 */
object Calibration {
    const val MIN_PAIRS = 2
    const val MIN_SLOPE = 0.55
    const val MAX_SLOPE = 1.6
    const val MAX_CORRECTION_AT_100 = 30.0
    const val WARMUP_MS = 2 * 3_600_000L
    const val RECENT_SENSOR_MS = 10 * 60_000L
    /** Acima disso (mg/dL por minuto, em 15 min) a glicemia está "mudando rápido". */
    const val FAST_RATE = 2.0

    /** Motivo para recusar o par, ou null se pode usar. [sensor] = leituras do sensor (hora, mg/dL). */
    fun check(atMs: Long, fingerstickMgDl: Double, sensorStartMs: Long?, sensor: List<Pair<Long, Double>>): CalRejection? {
        if (fingerstickMgDl !in 40.0..400.0) return CalRejection.OUT_OF_RANGE
        if (sensorStartMs != null && atMs - sensorStartMs < WARMUP_MS) return CalRejection.WARMUP
        val recent = sensor.filter { it.first in (atMs - RECENT_SENSOR_MS)..(atMs + 60_000L) }
        if (recent.isEmpty()) return CalRejection.NO_RECENT_SENSOR
        val window = sensor.filter { it.first in (atMs - 15 * 60_000L)..(atMs + 60_000L) }.sortedBy { it.first }
        if (window.size >= 2) {
            val minutes = (window.last().first - window.first().first) / 60_000.0
            if (minutes >= 5 && abs(window.last().second - window.first().second) / minutes > FAST_RATE) return CalRejection.FAST_CHANGE
        }
        return null
    }

    /** Valor do sensor mais perto de [atMs] (para formar o par). */
    fun sensorAt(atMs: Long, sensor: List<Pair<Long, Double>>): Double? =
        sensor.filter { abs(it.first - atMs) <= RECENT_SENSOR_MS }.minByOrNull { abs(it.first - atMs) }?.second

    sealed interface Fit {
        data class Ok(val calibration: LinearCalibration) : Fit
        data class Rejected(val problem: CalFitProblem) : Fit
    }

    fun fit(pairs: List<CalPair>): Fit {
        if (pairs.size < MIN_PAIRS) return Fit.Rejected(CalFitProblem.TOO_FEW_PAIRS)
        val xs = pairs.map { it.sensorMgDl }; val ys = pairs.map { it.fingerstickMgDl }
        val mx = xs.average(); val my = ys.average()
        val sxx = xs.sumOf { (it - mx) * (it - mx) }
        var slope = if (sxx < 1e-6) 1.0 else xs.indices.sumOf { (xs[it] - mx) * (ys[it] - my) } / sxx
        var limited = false
        if (slope < MIN_SLOPE) { slope = MIN_SLOPE; limited = true }
        if (slope > MAX_SLOPE) { slope = MAX_SLOPE; limited = true }
        val offset = my - slope * mx
        val cal = LinearCalibration(slope, offset, pairs.size)
        if (abs(cal.correctionAt100) > MAX_CORRECTION_AT_100) return Fit.Rejected(CalFitProblem.TOO_LARGE)
        if (limited && pairs.size == MIN_PAIRS && sxx < 1e-6) return Fit.Rejected(CalFitProblem.SLOPE_LIMIT)
        return Fit.Ok(cal)
    }
}

/** Plano 21, F3.2 — suavização da curva (desligada por padrão; só tela/gráfico). */
enum class SmoothingMethod { OFF, MOVING_AVERAGE, EXPONENTIAL, KALMAN }

object Smoothing {
    /** Suaviza a série em ordem de tempo. Pontos separados por mais de [gapMs] recomeçam o filtro. */
    fun smooth(values: List<Pair<Long, Double>>, method: SmoothingMethod, gapMs: Long = 15 * 60_000L): List<Pair<Long, Double>> {
        if (method == SmoothingMethod.OFF || values.size < 3) return values
        val sorted = values.sortedBy { it.first }
        val out = ArrayList<Pair<Long, Double>>(sorted.size)
        var ema = 0.0
        var kx = 0.0; var kp = 1.0
        for (i in sorted.indices) {
            val (t, v) = sorted[i]
            val restart = i == 0 || t - sorted[i - 1].first > gapMs
            val s = when (method) {
                SmoothingMethod.MOVING_AVERAGE -> {
                    val window = (maxOf(0, i - 2)..i).map { sorted[it] }.filter { t - it.first <= gapMs }
                    window.sumOf { it.second } / window.size
                }
                SmoothingMethod.EXPONENTIAL -> { ema = if (restart) v else 0.4 * v + 0.6 * ema; ema }
                SmoothingMethod.KALMAN -> {
                    // Filtro de Kalman 1-D simples: ruído de processo q, de medição r (mg/dL²).
                    if (restart) { kx = v; kp = 1.0; v } else {
                        val q = 4.0; val r = 25.0
                        kp += q
                        val k = kp / (kp + r)
                        kx += k * (v - kx); kp *= (1 - k); kx
                    }
                }
                SmoothingMethod.OFF -> v
            }
            out += t to s
        }
        return out
    }
}
