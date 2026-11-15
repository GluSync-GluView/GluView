package com.gluview.therapy

import kotlin.math.exp
import kotlin.math.pow

/**
 * Curva de ação da insulina rápida — modelo exponencial do oref0 (`lib/iob/calculate.js`,
 * `iobCalcExponential`), o mesmo usado pelo AndroidAPS para todas as insulinas desde a 4.0
 * (estudo 22, §3). Parâmetros: pico (min) e duração de ação, DIA (h). Implementação própria a
 * partir da fórmula publicada (sem copiar código AGPL).
 *
 *   tau = pico·(1 − pico/fim) / (1 − 2·pico/fim)
 *   a   = 2·tau/fim
 *   S   = 1 / (1 − a + (1 + a)·e^(−fim/tau))
 *   atividade(t) = dose · (S/tau²) · t · (1 − t/fim) · e^(−t/tau)          [U/min]
 *   IOB(t)       = dose · (1 − S·(1−a)·((t²/(tau·fim·(1−a)) − t/tau − 1)·e^(−t/tau) + 1))
 */
data class InsulinCurve(val peakMin: Double, val diaHours: Double) {
    init {
        require(peakMin in 35.0..120.0) { "pico fora de 35–120 min" }
        require(diaHours in 5.0..10.0) { "DIA fora de 5–10 h" }
    }

    private val end = diaHours * 60.0
    private val tau = peakMin * (1 - peakMin / end) / (1 - 2 * peakMin / end)
    private val a = 2 * tau / end
    private val s = 1 / (1 - a + (1 + a) * exp(-end / tau))

    /** Fração da dose ainda ativa depois de [minutes] (1 no início, 0 depois do DIA). */
    fun iobFraction(minutes: Double): Double = when {
        minutes <= 0.0 -> 1.0
        minutes >= end -> 0.0
        else -> (1 - s * (1 - a) * ((minutes.pow(2) / (tau * end * (1 - a)) - minutes / tau - 1) * exp(-minutes / tau) + 1))
            .coerceIn(0.0, 1.0)
    }

    /** Atividade por unidade (U/min por U de dose) depois de [minutes]. */
    fun activity(minutes: Double): Double = when {
        minutes <= 0.0 || minutes >= end -> 0.0
        else -> (s / tau.pow(2)) * minutes * (1 - minutes / end) * exp(-minutes / tau)
    }
}

/** Insulinas do catálogo (pico do AAPS 4.0; personalizada usa os valores do perfil). */
enum class InsulinPreset(val peakMin: Double, val label: String) {
    NOVORAPID(75.0, "Novorapid / Humalog / Apidra"),
    FIASP(55.0, "Fiasp"),
    LYUMJEV(45.0, "Lyumjev"),
    CUSTOM(75.0, "Personalizada")
}
