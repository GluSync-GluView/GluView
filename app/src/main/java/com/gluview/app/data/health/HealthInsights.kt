package com.gluview.app.data.health

import com.gluview.app.data.local.entity.HealthRecordEntity
import kotlin.math.sqrt

/**
 * Cruzamentos da glicemia com exercício, sono e ciclo (plano 17, F3.4). Só observação: números do
 * que já aconteceu, nunca recomendação de dose. Puro, testável.
 */
object HealthInsights {
    private const val HOUR = 3_600_000L
    private const val DAY = 24 * HOUR

    data class Point(val ts: Long, val mgdl: Double)

    data class Stats(val count: Int, val avg: Double, val tirPct: Double, val belowPct: Double, val cvPct: Double, val min: Double) {
        companion object {
            val EMPTY = Stats(0, 0.0, 0.0, 0.0, 0.0, 0.0)
        }
    }

    fun stats(points: List<Point>, low: Double, high: Double): Stats {
        if (points.isEmpty()) return Stats.EMPTY
        val v = points.map { it.mgdl }
        val avg = v.average()
        val sd = sqrt(v.sumOf { (it - avg) * (it - avg) } / v.size)
        return Stats(
            count = v.size, avg = avg,
            tirPct = v.count { it in low..high } * 100.0 / v.size,
            belowPct = v.count { it < low } * 100.0 / v.size,
            cvPct = if (avg > 0) sd / avg * 100 else 0.0,
            min = v.min()
        )
    }

    private fun between(points: List<Point>, from: Long, to: Long) = points.filter { it.ts in from until to }

    // ── Exercício ──
    data class ExerciseGroup(
        val typeKey: String,
        val sessions: Int,
        val minutes: Double,
        /** Média da glicemia durante o exercício. */
        val during: Stats,
        /** Até 24 h depois. */
        val after: Stats,
        /** Sessões com alguma leitura abaixo do limite nas 24 h seguintes. */
        val sessionsWithHypoAfter: Int
    )

    fun exercise(sessions: List<HealthRecordEntity>, points: List<Point>, low: Double, high: Double): List<ExerciseGroup> =
        sessions.groupBy { it.subtype }.map { (type, list) ->
            val during = list.flatMap { between(points, it.startMs, it.endMs) }
            val after = list.flatMap { between(points, it.endMs, it.endMs + DAY) }
            ExerciseGroup(
                typeKey = type, sessions = list.size, minutes = list.sumOf { it.value },
                during = stats(during, low, high), after = stats(after, low, high),
                sessionsWithHypoAfter = list.count { s -> between(points, s.endMs, s.endMs + DAY).any { it.mgdl < low } }
            )
        }.sortedByDescending { it.sessions }

    // ── Sono ──
    data class SleepSummary(
        val nights: Int,
        val shortNights: Int,
        val shortSleep: Stats,
        val longSleep: Stats,
        val nightsWithHypo: Int,
        val avgAsleepMin: Double
    )

    /** Noites com menos de [shortMin] minutos dormidos contra as demais; glicemia durante o sono. */
    fun sleep(sessions: List<HealthRecordEntity>, points: List<Point>, low: Double, high: Double, shortMin: Double = 360.0): SleepSummary {
        val nights = sessions.filter { it.endMs - it.startMs >= 3 * HOUR }
        val short = nights.filter { it.value < shortMin }
        val long = nights.filter { it.value >= shortMin }
        return SleepSummary(
            nights = nights.size,
            shortNights = short.size,
            shortSleep = stats(short.flatMap { between(points, it.startMs, it.endMs) }, low, high),
            longSleep = stats(long.flatMap { between(points, it.startMs, it.endMs) }, low, high),
            nightsWithHypo = nights.count { n -> between(points, n.startMs, n.endMs).any { it.mgdl < low } },
            avgAsleepMin = if (nights.isEmpty()) 0.0 else nights.map { it.value }.average()
        )
    }

    // ── Ciclo ──
    enum class CyclePhase { MENSTRUAL, FOLLICULAR, LUTEAL }

    data class PhaseStats(val phase: CyclePhase, val days: Int, val stats: Stats)

    /**
     * Fase de cada momento a partir do início das menstruações: dias 1–5 menstrual, 6–13 folicular,
     * 14 em diante lútea (até o próximo início, no máximo 35 dias). Aproximação comum — o ciclo de
     * cada pessoa varia; serve para comparar, não para prever.
     */
    fun phaseAt(ts: Long, periodStarts: List<Long>): CyclePhase? {
        val start = periodStarts.filter { it <= ts }.maxOrNull() ?: return null
        val day = ((ts - start) / DAY).toInt() + 1
        return when {
            day > 35 -> null
            day <= 5 -> CyclePhase.MENSTRUAL
            day <= 13 -> CyclePhase.FOLLICULAR
            else -> CyclePhase.LUTEAL
        }
    }

    /** Inícios de menstruação: períodos + dias de fluxo isolados (um início a cada sequência). */
    fun periodStarts(periods: List<HealthRecordEntity>, flows: List<HealthRecordEntity>): List<Long> {
        val fromPeriods = periods.map { it.startMs }
        val flowDays = flows.filter { it.value > 0 }.map { it.startMs }.sorted()
        val fromFlows = flowDays.filterIndexed { i, t -> i == 0 || t - flowDays[i - 1] > 3 * DAY }
        val all = (fromPeriods + fromFlows).sorted()
        // Junta inícios a menos de 10 dias um do outro (o mesmo ciclo informado dos dois jeitos).
        return all.fold(mutableListOf<Long>()) { acc, t -> if (acc.isEmpty() || t - acc.last() > 10 * DAY) acc.add(t); acc }
    }

    fun cycle(periodStarts: List<Long>, points: List<Point>, low: Double, high: Double): List<PhaseStats> {
        if (periodStarts.isEmpty()) return emptyList()
        val byPhase = points.groupBy { phaseAt(it.ts, periodStarts) }
        return CyclePhase.entries.map { ph ->
            val list = byPhase[ph].orEmpty()
            PhaseStats(ph, list.map { it.ts / DAY }.distinct().size, stats(list, low, high))
        }
    }
}
