package com.gluview.app.visualaudit

import com.gluview.app.util.GraphReading
import java.time.Instant
import java.time.ZoneId
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Dados de glicemia determinísticos para o harness visual (Fase 0 do redesign). Função pura de
 * (cenário, agora): sem Random e sem relógio, então toda captura sai igual em toda execução.
 *
 * A curva de um dia é uma soma de bumps gaussianos no horário local de São Paulo:
 * hipo noturna (nadir ~48 às 03:10), fenômeno do alvorecer, café, hipo leve (~64 às 10:50),
 * almoço (~230) e jantar (~280, acima do urgente de 250). Dá TIR de ~65–70%, como um dia real.
 */
object GlucoseFixtures {

    /** 2026-10-02 14:37:00 −03:00. Minuto fora da hora cheia de propósito: exercita o 1º tick do eixo X. */
    const val FIXED_NOW: Long = 1_790_962_620_000L

    val ZONE: ZoneId = ZoneId.of("America/Sao_Paulo")

    /** Glicemia (mg/dL) num minuto do dia local; [day] varia levemente a amplitude entre dias. */
    fun profileAt(minuteOfDay: Double, day: Int = 0): Float {
        val amp = 1.0 + 0.15 * sin(day * 1.7)
        val v = 120.0 +
            amp * (
                25.0 * gauss(minuteOfDay, 6 * 60.0, 90.0) +    // alvorecer
                70.0 * gauss(minuteOfDay, 8 * 60.0 + 30, 45.0) + // café
                110.0 * gauss(minuteOfDay, 13 * 60.0, 50.0) +   // almoço
                160.0 * gauss(minuteOfDay, 20 * 60.0 + 15, 55.0) // jantar (> 250)
            ) -
            72.0 * gauss(minuteOfDay, 3 * 60.0 + 10, 35.0) -  // hipo noturna (~48)
            64.0 * gauss(minuteOfDay, 10 * 60.0 + 50, 25.0) + // hipo leve (~64)
            4.0 * sin(minuteOfDay / 7.0) + 3.0 * sin(minuteOfDay / 13.0)
        return v.toFloat()
    }

    private fun gauss(x: Double, mu: Double, sigma: Double): Double {
        // Distância circular no dia (o jantar de ontem conta perto da meia-noite).
        var d = kotlin.math.abs(x - mu)
        if (d > 720) d = 1440 - d
        return exp(-(d * d) / (2 * sigma * sigma))
    }

    /**
     * Série terminando em [nowMs] (inclusive), de [hours] horas, a cada [stepMin] minutos.
     * Valores arredondados para inteiro, como chegam das fontes.
     */
    fun series(nowMs: Long = FIXED_NOW, hours: Int = 24, stepMin: Int = 5): List<GraphReading> {
        val stepMs = stepMin * 60_000L
        val count = (hours * 60) / stepMin
        val out = ArrayList<GraphReading>(count + 1)
        for (i in count downTo 0) {
            val ts = nowMs - i * stepMs
            out += GraphReading(ts, valueAt(ts))
        }
        return out
    }

    /** 24h a 1 leitura/min, como a Sibionics: confirmada a cada 5 min, provisórias entre elas. */
    fun sibionics1Min(nowMs: Long = FIXED_NOW, hours: Int = 24): List<GraphReading> =
        series(nowMs, hours, stepMin = 1).mapIndexed { i, r -> r.copy(confirmed = i % 5 == 0) }

    /** 14 dias a cada 5 min (4.032 pontos). */
    fun days14(nowMs: Long = FIXED_NOW): List<GraphReading> = series(nowMs, hours = 14 * 24, stepMin = 5)

    /** Remove o trecho entre [fromMinAgo] e [toMinAgo] minutos antes de [nowMs] (buraco de sensor). */
    fun withGap(
        readings: List<GraphReading>,
        fromMinAgo: Int = 6 * 60,
        toMinAgo: Int = 4 * 60,
        nowMs: Long = FIXED_NOW
    ): List<GraphReading> {
        val from = nowMs - fromMinAgo * 60_000L
        val to = nowMs - toMinAgo * 60_000L
        return readings.filterNot { it.timestampMs in from..to }
    }

    /** Série com 2 valores fora da escala do gráfico (35 e 340 mg/dL). */
    fun outOfScale(nowMs: Long = FIXED_NOW): List<GraphReading> =
        series(nowMs, hours = 3).map {
            when (it.timestampMs) {
                nowMs - 150 * 60_000L -> it.copy(sgvMgDl = 35f)
                nowMs - 60 * 60_000L -> it.copy(sgvMgDl = 340f)
                else -> it
            }
        }

    /** Leitura mais nova 3 min "no futuro" (âncora Sibionics adiantada) — exercita FUTURE_TOLERANCE_MS. */
    fun newestInFuture(nowMs: Long = FIXED_NOW): List<GraphReading> =
        series(nowMs, hours = 3) + GraphReading(nowMs + 3 * 60_000L, valueAt(nowMs))

    private fun valueAt(ts: Long): Float {
        val local = Instant.ofEpochMilli(ts).atZone(ZONE)
        val minuteOfDay = local.hour * 60.0 + local.minute
        val day = local.toLocalDate().toEpochDay().toInt()
        return profileAt(minuteOfDay, day).roundToInt().toFloat()
    }
}
