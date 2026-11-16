package com.gluview.therapy

import kotlin.math.abs
import kotlin.math.exp

/** Uma ponta de dedo e o valor do sensor no mesmo momento (mg/dL). */
data class CalPair(val atMs: Long, val fingerstickMgDl: Double, val sensorMgDl: Double)

/** Calibração linear: calibrado = inclinação × sensor + deslocamento. */
data class LinearCalibration(val slope: Double, val offset: Double, val pairs: Int) {
    fun apply(sensorMgDl: Double): Double = (slope * sensorMgDl + offset).coerceIn(39.0, 401.0)
    /** Quanto a calibração muda o valor 100 mg/dL. */
    val correctionAt100: Double get() = slope * 100 + offset - 100
}

enum class CalRejection { NO_SESSION, WARMUP, FAST_CHANGE, NO_RECENT_SENSOR, OUT_OF_RANGE }
enum class CalFitProblem { TOO_FEW_PAIRS, SLOPE_LIMIT, TOO_LARGE }

/**
 * Plano 21, F3.1 — calibração com glicemia capilar. Regras CONFERIDAS no código do AndroidAPS 4.0.0
 * (`plugins/calibration/.../LinearCalibrationPlugin.kt` e `CalibrationMath.kt`, 16/11/2026),
 * reimplementadas (sem copiar código AGPL):
 *  - sem início de sensor conhecido: recusa; nas 2 h depois da troca: recusa;
 *  - glicemia variando mais de 5 mg/dL a cada 5 min (média curta): recusa;
 *  - par = leitura do sensor mais recente ANTES da ponta de dedo, até 10 min antes;
 *  - mínimos quadrados PONDERADOS pela idade do par (peso = e^(−idade/2 dias));
 *  - valores do sensor espalhados por menos de 54 mg/dL: só deslocamento (inclinação 1);
 *  - inclinação fora de 0,55–1,6: presa no limite e deslocamento refeito;
 *  - correção em 100 mg/dL fora de ±30: NÃO aplica.
 */
object Calibration {
    const val MIN_PAIRS = 2
    const val MIN_SLOPE = 0.55
    const val MAX_SLOPE = 1.6
    const val MAX_CORRECTION_AT_100 = 30.0
    const val WARMUP_MS = 2 * 3_600_000L
    const val PAIR_LOOKBACK_MS = 10 * 60_000L
    /** Variação máxima aceita, em mg/dL a cada 5 min (média curta de ~15 min). */
    const val DELTA_GATE_PER_5MIN = 5.0
    const val MIN_SENSOR_RANGE_FOR_SLOPE = 54.0
    const val DECAY_TAU_MS = 2 * 86_400_000L

    /** Motivo para recusar o par, ou null se pode usar. [sensor] = leituras do sensor (hora, mg/dL). */
    fun check(atMs: Long, fingerstickMgDl: Double, sensorStartMs: Long?, sensor: List<Pair<Long, Double>>): CalRejection? {
        if (fingerstickMgDl !in 40.0..400.0) return CalRejection.OUT_OF_RANGE
        if (sensorStartMs == null) return CalRejection.NO_SESSION
        if (atMs - sensorStartMs < WARMUP_MS) return CalRejection.WARMUP
        shortAvgDelta(atMs, sensor)?.let { if (abs(it) > DELTA_GATE_PER_5MIN) return CalRejection.FAST_CHANGE }
        if (sensorAt(atMs, sensor) == null) return CalRejection.NO_RECENT_SENSOR
        return null
    }

    /** Variação média por 5 min nos ~15 min antes de [atMs] (como o shortAvgDelta), ou null. */
    fun shortAvgDelta(atMs: Long, sensor: List<Pair<Long, Double>>): Double? {
        val w = sensor.filter { it.first in (atMs - 17 * 60_000L)..atMs }.sortedBy { it.first }
        if (w.size < 2) return null
        val minutes = (w.last().first - w.first().first) / 60_000.0
        if (minutes < 4.0) return null
        return (w.last().second - w.first().second) / minutes * 5.0
    }

    /** Leitura do sensor mais recente até 10 min ANTES de [atMs] (o par da ponta de dedo). */
    fun sensorAt(atMs: Long, sensor: List<Pair<Long, Double>>): Double? =
        sensor.filter { it.first in (atMs - PAIR_LOOKBACK_MS)..atMs }.maxByOrNull { it.first }?.second

    sealed interface Fit {
        data class Ok(val calibration: LinearCalibration) : Fit
        data class Rejected(val problem: CalFitProblem) : Fit
    }

    private fun weight(atMs: Long, nowMs: Long) = if (atMs >= nowMs) 1.0 else exp(-(nowMs - atMs).toDouble() / DECAY_TAU_MS)

    fun fit(pairs: List<CalPair>, nowMs: Long = pairs.maxOfOrNull { it.atMs } ?: 0L): Fit {
        if (pairs.size < MIN_PAIRS) return Fit.Rejected(CalFitProblem.TOO_FEW_PAIRS)
        val range = pairs.maxOf { it.sensorMgDl } - pairs.minOf { it.sensorMgDl }
        val cal = if (range < MIN_SENSOR_RANGE_FOR_SLOPE) {
            var sw = 0.0; var swd = 0.0
            for (p in pairs) { val w = weight(p.atMs, nowMs); sw += w; swd += w * (p.fingerstickMgDl - p.sensorMgDl) }
            LinearCalibration(1.0, swd / sw, pairs.size)
        } else {
            var sw = 0.0; var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0
            for (p in pairs) {
                val w = weight(p.atMs, nowMs); val x = p.sensorMgDl; val y = p.fingerstickMgDl
                sw += w; sx += w * x; sy += w * y; sxx += w * x * x; sxy += w * x * y
            }
            val den = sw * sxx - sx * sx
            if (den == 0.0) return Fit.Rejected(CalFitProblem.SLOPE_LIMIT)
            val rawSlope = (sw * sxy - sx * sy) / den
            val rawOffset = (sxx * sy - sx * sxy) / den
            val slope = rawSlope.coerceIn(MIN_SLOPE, MAX_SLOPE)
            if (slope == rawSlope) LinearCalibration(rawSlope, rawOffset, pairs.size)
            else LinearCalibration(slope, (sy - slope * sx) / sw, pairs.size)
        }
        if (abs(cal.correctionAt100) > MAX_CORRECTION_AT_100) return Fit.Rejected(CalFitProblem.TOO_LARGE)
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
