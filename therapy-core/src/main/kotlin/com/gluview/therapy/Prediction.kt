package com.gluview.therapy

/** Um ponto previsto. */
data class PredPoint(val atMs: Long, val mgDl: Double)

data class PredictionResult(
    /** Só insulina ativa (sem carbo). */
    val iob: List<PredPoint>,
    /** Insulina + carbo ainda por absorver. */
    val cob: List<PredPoint>,
    /** Tendência atual da glicemia (UAM, refeição não anunciada) somada à insulina, decaindo. */
    val uam: List<PredPoint>,
    /**
     * Faixa de incerteza: menor e maior valor entre as curvas QUE CONTAM O CARBO (COB e UAM). A curva
     * "só insulina" fica de referência: ela ignora a refeição e assustaria à toa logo depois de comer.
     */
    val low: List<PredPoint>,
    val high: List<PredPoint>
)

/**
 * Curvas de previsão (F4.5) no espírito do oref0 `determine-basal` (IOBpredBGs, COBpredBGs,
 * UAMpredBGs), simplificadas para quem usa caneta:
 *  - impacto da insulina a cada 5 min: −atividade·ISF·5 (BGI do oref);
 *  - desvio atual (variação observada − BGI) decai linearmente em 60 min na curva IOB;
 *  - curva COB soma o carbo absorvido no intervalo × ISF/IC;
 *  - curva UAM mantém o desvio atual decaindo em 3 h.
 * A curva ZT (basal zero) só faz sentido com bomba e fica para a F10.
 * Valores limitados a 39–400 mg/dL, como os sensores. Não há previsão sem glicemia atual válida.
 */
object Prediction {
    private const val STEP_MS = 5 * 60_000L

    fun predict(
        nowMs: Long, bgMgDl: Double, delta5MinMgDl: Double, profile: TherapyProfile, minuteOfDay: Int,
        doses: List<Dose>, carbs: List<Carbs>, horizonMin: Int = 180
    ): PredictionResult {
        val isf = profile.isf.at(minuteOfDay)
        val ic = profile.ic.at(minuteOfDay)
        val curve = profile.curve
        val bgiNow = -OnBoard.iob(doses, nowMs, curve).activityPerMin * isf * 5
        val deviation = delta5MinMgDl - bgiNow
        val n = (horizonMin / 5).coerceAtLeast(1)
        var iobBg = bgMgDl; var cobBg = bgMgDl; var uamBg = bgMgDl
        val iobL = ArrayList<PredPoint>(n); val cobL = ArrayList<PredPoint>(n); val uamL = ArrayList<PredPoint>(n)
        for (i in 1..n) {
            val t = nowMs + i * STEP_MS
            val bgi = -OnBoard.iob(doses, t, curve).activityPerMin * isf * 5
            val devIob = deviation * (1 - minOf(1.0, i / 12.0)).coerceAtLeast(0.0)
            val devUam = deviation * (1 - minOf(1.0, i / 36.0)).coerceAtLeast(0.0)
            val carbImpact = OnBoard.absorbedBetween(carbs, t - STEP_MS, t, profile.carbAbsorptionMin) * isf / ic
            iobBg = clamp(iobBg + bgi + devIob)
            cobBg = clamp(cobBg + bgi + devIob + carbImpact)
            uamBg = clamp(uamBg + bgi + devUam + carbImpact)
            iobL += PredPoint(t, iobBg); cobL += PredPoint(t, cobBg); uamL += PredPoint(t, uamBg)
        }
        val low = iobL.indices.map { PredPoint(iobL[it].atMs, minOf(cobL[it].mgDl, uamL[it].mgDl)) }
        val high = iobL.indices.map { PredPoint(iobL[it].atMs, maxOf(cobL[it].mgDl, uamL[it].mgDl)) }
        return PredictionResult(iobL, cobL, uamL, low, high)
    }

    /** Minutos até a curva mais baixa cruzar [thresholdMgDl], ou null (alerta preditivo opcional). */
    fun minutesUntilBelow(result: PredictionResult, nowMs: Long, thresholdMgDl: Double): Int? =
        result.low.firstOrNull { it.mgDl < thresholdMgDl }?.let { ((it.atMs - nowMs) / 60_000L).toInt() }

    private fun clamp(v: Double) = v.coerceIn(39.0, 400.0)
}
