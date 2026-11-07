package com.gluview.app.data.accuchek.cgm

import kotlin.math.roundToInt

/** Problemas que o SmartGuide anuncia e que viram aviso ao usuário. */
enum class SmartGuideAlert {
    /** Sessão encerrada (fim dos 14 dias ou sensor parado) — trocar o sensor. */
    SESSION_ENDED,
    /** Defeito do sensor/aparelho persistente (ver [MalfunctionStreak]). */
    MALFUNCTION,
    BATTERY_LOW,
    /** Temperatura do sensor fora da faixa — o sensor pode pausar as leituras. */
    TEMPERATURE,
    /** O sensor pede calibração (o GluView ainda não calibra o SmartGuide). */
    CALIBRATION_REQUIRED
}

/**
 * Regras do SmartGuide sobre o que gravar e o que avisar (puras, testáveis).
 */
object SmartGuideRules {

    /** Valor gravado para "LO" — o mesmo do Juggluco (`cpp/accu/java.cpp`, `mgdL = 39`). */
    const val LOW_MGDL = 39
    /** Valor gravado para "HI" — simétrico ao LO (39/401 é a convenção de LO/HI em CGM). */
    const val HIGH_MGDL = 401

    /** Leitura anterior usada para validar LO/HI. */
    data class Previous(val mgdl: Int, val minute: Int)

    /**
     * Glicemia a gravar (mg/dL inteiro) ou null para não gravar.
     *
     * - Valor numérico: arredondado.
     * - LO (−∞): o registro "LO" real vem também com o bit de defeito ligado, então o Juggluco só o
     *   aceita como 39 quando é coerente — leitura anterior < 120 mg/dL entre 5 e 29 min antes.
     *   Mesma regra aqui.
     * - HI (+∞): regra simétrica — anterior > 250 mg/dL entre 5 e 29 min antes. [HIP] O Juggluco não
     *   trata HI e nenhum registro HI real foi visto ainda; validar na F5.
     * - NaN (erro do sensor): não grava.
     */
    fun glucoseToStore(m: CgmMeasurement, previous: Previous?): Int? {
        if (m.hasValue) return m.glucoseMgDl.roundToInt()
        if (previous == null) return null
        val gap = m.timeOffsetMinutes - previous.minute
        if (gap !in 5..29) return null
        return when {
            m.isLow && previous.mgdl < 120 -> LOW_MGDL
            m.isHigh && previous.mgdl > 250 -> HIGH_MGDL
            else -> null
        }
    }

    /**
     * Avisos de uma anunciação, SEM o defeito (que depende de persistência — ver
     * [MalfunctionStreak]): defeito aparece junto com o "LO" legítimo e não pode alarmar sozinho.
     */
    fun alertsFor(a: SensorAnnunciation): Set<SmartGuideAlert> = buildSet {
        if (a.sessionStopped) add(SmartGuideAlert.SESSION_ENDED)
        if (a.batteryLow) add(SmartGuideAlert.BATTERY_LOW)
        if (a.temperatureTooHigh || a.temperatureTooLow) add(SmartGuideAlert.TEMPERATURE)
        if (a.calibrationRequired) add(SmartGuideAlert.CALIBRATION_REQUIRED)
    }

    /** O registro indica defeito do sensor/aparelho? */
    fun isMalfunction(a: SensorAnnunciation): Boolean =
        a.sensorMalfunction || a.generalDeviceFault || a.sensorTypeIncorrect

    /**
     * Seta pela tendência do próprio sensor (mg/dL/min), com os cortes EXATOS do Juggluco
     * (`dexcom/java.cpp` `rate2changeindex`, usado também para o SmartGuide): ≤ −2 desce rápido,
     * ≤ −1 desce, ≤ +1 estável, ≤ +2 sobe, acima sobe rápido. Cinco níveis, sem seta dupla.
     * null quando o sensor não mandou tendência.
     */
    fun arrowFor(trendMgDlPerMin: Float?): String? {
        val t = trendMgDlPerMin ?: return null
        if (t.isNaN() || t.isInfinite()) return null
        return when {
            t <= -2f -> "↓"
            t <= -1f -> "↘"
            t <= 1f  -> "→"
            t <= 2f  -> "↗"
            else     -> "↑"
        }
    }
}

/**
 * Defeito persistente: só vira aviso depois de [threshold] leituras AO VIVO seguidas sem valor e
 * com o bit de defeito (3 = 15 min). Uma leitura gravada zera a contagem. O Juggluco marca erro a
 * cada registro desses, mas mostrar notificação a cada um seria alarme falso no "LO" e em falhas
 * de um registro só.
 */
class MalfunctionStreak(private val threshold: Int = 3) {
    var count = 0
        private set

    /** Registra uma leitura ao vivo; devolve true quando o defeito acabou de ficar persistente. */
    fun onLiveRecord(stored: Boolean, malfunction: Boolean): Boolean {
        if (stored || !malfunction) { count = 0; return false }
        count++
        return count == threshold
    }

    val isActive: Boolean get() = count >= threshold

    fun reset() { count = 0 }
}
