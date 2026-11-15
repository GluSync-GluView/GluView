package com.gluview.therapy

/** Dose de insulina rápida registrada (U). Insulina mista entra inteira como rápida (mais segura: superestima o IOB). */
data class Dose(val atMs: Long, val units: Double)

/** Carboidrato registrado (g), com tempo de absorção próprio ou o do perfil. */
data class Carbs(val atMs: Long, val grams: Double, val absorptionMin: Int? = null)

data class IobResult(val iob: Double, val activityPerMin: Double)

object OnBoard {
    /** Insulina ativa e atividade agora. Doses futuras (relógio adiantado) contam inteiras, sem atividade. */
    fun iob(doses: List<Dose>, nowMs: Long, curve: InsulinCurve): IobResult {
        var iob = 0.0; var act = 0.0
        for (d in doses) {
            if (d.units <= 0.0) continue
            val t = (nowMs - d.atMs) / 60_000.0
            iob += d.units * curve.iobFraction(t)
            act += d.units * curve.activity(t)
        }
        return IobResult(iob, act)
    }

    /**
     * Carboidrato ainda por absorver (g), modelo LINEAR no tempo de absorção. É mais simples que o
     * COB do AAPS (que acompanha os desvios da glicemia); fica documentado como diferença e a tela
     * mostra o tempo de absorção usado.
     */
    fun cob(carbs: List<Carbs>, nowMs: Long, defaultAbsorptionMin: Int): Double = carbs.sumOf { c ->
        if (c.grams <= 0.0) return@sumOf 0.0
        val abs = (c.absorptionMin ?: defaultAbsorptionMin).coerceAtLeast(1)
        val t = (nowMs - c.atMs) / 60_000.0
        when {
            t <= 0.0 -> c.grams
            t >= abs -> 0.0
            else -> c.grams * (1 - t / abs)
        }
    }

    /** Gramas absorvidas entre [fromMs] e [toMs] (para a curva de previsão). */
    fun absorbedBetween(carbs: List<Carbs>, fromMs: Long, toMs: Long, defaultAbsorptionMin: Int): Double =
        cob(carbs, fromMs, defaultAbsorptionMin) - cob(carbs, toMs, defaultAbsorptionMin)
}
