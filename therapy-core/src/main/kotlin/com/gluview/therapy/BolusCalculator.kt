package com.gluview.therapy

import kotlin.math.floor

/** Glicemia atual para a calculadora. [ageMin] = minutos desde a medição. */
data class CurrentGlucose(val mgDl: Double, val ageMin: Int, val delta15MinMgDl: Double? = null)

data class BolusInput(
    val carbsG: Double,
    val glucose: CurrentGlucose?,
    val useBgCorrection: Boolean = true,
    val useIob: Boolean = true,
    /**
     * Padrão DESLIGADO, como no AAPS (`WizardIncludeCob = false`, conferido no código 4.0.0): ligado,
     * a refeição já anotada no diário e digitada de novo aqui contaria duas vezes (dose dobrada).
     */
    val useCob: Boolean = false,
    val useTrend: Boolean = false,
    /** Sensor em aquecimento (as primeiras horas): a correção pela glicemia fica de fora. */
    val sensorWarmup: Boolean = false
)

enum class BolusWarning {
    PROFILE_INVALID, NO_GLUCOSE, GLUCOSE_OLD, LOW_GLUCOSE_NO_INSULIN, SENSOR_WARMUP,
    CAPPED_AT_MAX, MORE_CARBS_SUGGESTED, HIGH_IOB, ROUNDED_DOWN
}

/** Cada parcela em U; [suggestedUnits] já arredondada e limitada. */
data class BolusResult(
    val fromCarbs: Double,
    val fromCorrection: Double,
    val fromIob: Double,
    val fromCob: Double,
    val fromTrend: Double,
    val rawTotal: Double,
    val suggestedUnits: Double,
    /** Quando o total dá negativo: carboidrato que falta para não cair (g). */
    val carbsNeededG: Double,
    val iob: Double,
    val cob: Double,
    val isf: Double,
    val ic: Double,
    val target: Double,
    val warnings: Set<BolusWarning>
)

/**
 * Calculadora de bolus (F4.4), mesmo desenho da do AndroidAPS (BolusWizard): carbo/IC + correção
 * para fora da faixa-alvo − IOB (+ COB e tendência, opcionais). Regras do GluView, conservadoras:
 *  - glicemia com mais de 15 min, ausente ou em aquecimento → sem correção pela glicemia;
 *  - glicemia baixa (≤ [LOW_MGDL]) → NUNCA sugere insulina, só o carboidrato que falta;
 *  - arredonda PARA BAIXO no passo da caneta; nunca passa do bolus máximo (o menor entre o da
 *    pessoa e o limite fixo do AAPS).
 * Sempre sugere: quem aplica e confere é a pessoa.
 */
object BolusCalculator {
    const val LOW_MGDL = 70.0
    const val MAX_GLUCOSE_AGE_MIN = 15

    fun calculate(input: BolusInput, profile: TherapyProfile, doses: List<Dose>, carbs: List<Carbs>,
                  nowMs: Long, minuteOfDay: Int): BolusResult {
        val w = mutableSetOf<BolusWarning>()
        if (profile.problems().isNotEmpty()) {
            return BolusResult(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, setOf(BolusWarning.PROFILE_INVALID))
        }
        val isf = profile.isf.at(minuteOfDay)
        val ic = profile.ic.at(minuteOfDay)
        val tLow = profile.targetLow.at(minuteOfDay)
        val tHigh = profile.targetHigh.at(minuteOfDay)
        val iob = OnBoard.iob(doses, nowMs, profile.curve).iob
        val cob = OnBoard.cob(carbs, nowMs, profile.carbAbsorptionMin)

        val fromCarbs = input.carbsG.coerceAtLeast(0.0) / ic
        val g = input.glucose
        val glucoseUsable = when {
            g == null -> { w += BolusWarning.NO_GLUCOSE; false }
            g.ageMin > MAX_GLUCOSE_AGE_MIN -> { w += BolusWarning.GLUCOSE_OLD; false }
            input.sensorWarmup -> { w += BolusWarning.SENSOR_WARMUP; false }
            else -> true
        }
        var target = (tLow + tHigh) / 2
        val fromCorrection = if (glucoseUsable && input.useBgCorrection) {
            val bg = g!!.mgDl
            when {
                bg <= tLow -> { target = tLow; (bg - tLow) / isf }
                bg >= tHigh -> { target = tHigh; (bg - tHigh) / isf }
                else -> 0.0
            }
        } else 0.0
        val fromIob = if (input.useIob) -iob else 0.0
        val fromCob = if (input.useCob) cob / ic else 0.0
        val fromTrend = if (glucoseUsable && input.useTrend) (g!!.delta15MinMgDl ?: 0.0) / isf else 0.0
        val raw = fromCarbs + fromCorrection + fromIob + fromCob + fromTrend

        val low = glucoseUsable && g!!.mgDl <= LOW_MGDL
        var units = if (raw > 0.0 && !low) raw else 0.0
        if (low) w += BolusWarning.LOW_GLUCOSE_NO_INSULIN
        val max = profile.effectiveMaxBolus
        if (units > max) { units = max; w += BolusWarning.CAPPED_AT_MAX }
        val rounded = floor(units / profile.penStep + 1e-9) * profile.penStep
        if (rounded < units - 1e-9) w += BolusWarning.ROUNDED_DOWN
        // Carbo que falta: desconta o carbo ainda ativo mesmo com o COB fora da dose — logo depois
        // de comer e aplicar, a insulina ativa é "coberta" pela refeição e não falta nada (antes a
        // tela pedia "faltam 45 g" à toa). Isso só mexe no aviso, nunca soma insulina.
        val rawWithCob = if (input.useCob) raw else raw + cob / ic
        val carbsNeeded = if (rawWithCob < 0.0) -rawWithCob * ic else 0.0
        if (carbsNeeded >= 1.0) w += BolusWarning.MORE_CARBS_SUGGESTED
        if (iob > profile.effectiveMaxBolus) w += BolusWarning.HIGH_IOB

        return BolusResult(fromCarbs, fromCorrection, fromIob, fromCob, fromTrend, raw,
            (rounded * 100).let { Math.round(it) / 100.0 }, carbsNeeded, iob, cob, isf, ic, target, w)
    }
}
