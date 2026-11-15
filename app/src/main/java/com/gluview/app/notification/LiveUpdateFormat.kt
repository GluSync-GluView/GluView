package com.gluview.app.notification

import com.gluview.app.util.GlucoseUnit

/**
 * PT-6 — texto e barra da notificação promovida (Live Update do Android 16, que a Samsung One UI 8
 * mostra na barra Now). Função pura e testável: o Android só entra em [NotificationHelper].
 *
 * Regra de sempre: dado velho nunca aparece como atual. Leitura com idade acima do "sem sinal"
 * ([stale]) vira "--" no chip, sem delta e sem barra.
 */
data class LiveUpdateText(
    /** Texto curto do chip da barra de status ("135↗" ou "--"). */
    val chip: String,
    /** Título: "135 ↗ mg/dL" ou "Sem sinal". */
    val title: String,
    /** Linha de baixo: "+3 mg/dL · 14:05" ou "Última leitura 14:05". */
    val text: String,
    /** Posição da glicemia na barra (0..[LiveUpdateFormat.PROGRESS_MAX]); null = sem barra. */
    val progress: Int?,
    /** Segmentos da barra (baixa, alvo, alta), em unidades de [LiveUpdateFormat.PROGRESS_MAX]. */
    val segments: List<LiveSegment>
)

data class LiveSegment(val length: Int, val zone: Zone) {
    enum class Zone { LOW, TARGET, HIGH }
}

object LiveUpdateFormat {

    /** Escala da barra: 40 a 400 mg/dL (o que os sensores costumam medir). */
    const val SCALE_MIN = 40.0
    const val SCALE_MAX = 400.0
    const val PROGRESS_MAX = 360

    /** O chip da barra de status é curto: valor + seta, sem unidade. */
    const val CHIP_MAX_CHARS = 7

    fun format(
        sgvMgDl: Double,
        arrow: String,
        delta: Int,
        stale: Boolean,
        timeText: String?,
        unit: GlucoseUnit,
        low: Float,
        high: Float,
        noSignalText: String,
        lastReadingText: (String) -> String
    ): LiveUpdateText {
        val segments = segments(low.toDouble(), high.toDouble())
        if (stale || sgvMgDl <= 0 || !sgvMgDl.isFinite()) {
            return LiveUpdateText(
                chip = "--",
                title = noSignalText,
                text = timeText?.let(lastReadingText) ?: noSignalText,
                progress = null,
                segments = segments
            )
        }
        return with(unit) {
            val value = sgvMgDl.toDisplay()
            val d = sgvMgDl.deltaToDisplay(delta)
            LiveUpdateText(
                chip = (value + arrow.trim()).take(CHIP_MAX_CHARS),
                title = "$value ${arrow.trim()} ${unit.label}".replace("  ", " "),
                text = listOfNotNull("$d ${unit.label}", timeText).joinToString(" · "),
                progress = position(sgvMgDl),
                segments = segments
            )
        }
    }

    /** Posição de [mgDl] na escala da barra. */
    fun position(mgDl: Double): Int =
        (((mgDl.coerceIn(SCALE_MIN, SCALE_MAX) - SCALE_MIN) / (SCALE_MAX - SCALE_MIN)) * PROGRESS_MAX).toInt()

    /** Três segmentos que somam [PROGRESS_MAX]: abaixo do alvo, alvo e acima do alvo. */
    fun segments(low: Double, high: Double): List<LiveSegment> {
        val lowEnd = position(low).coerceIn(1, PROGRESS_MAX - 2)
        val highEnd = position(high).coerceIn(lowEnd + 1, PROGRESS_MAX - 1)
        return listOf(
            LiveSegment(lowEnd, LiveSegment.Zone.LOW),
            LiveSegment(highEnd - lowEnd, LiveSegment.Zone.TARGET),
            LiveSegment(PROGRESS_MAX - highEnd, LiveSegment.Zone.HIGH)
        )
    }
}
