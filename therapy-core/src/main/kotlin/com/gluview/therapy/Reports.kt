package com.gluview.therapy

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

/** Uma leitura para relatório (hora da medição, mg/dL). */
data class RPoint(val atMs: Long, val mgDl: Double)

/** Limites das faixas, com a regra do app: o valor do limite conta como FORA. */
data class RLimits(val urgentLow: Double = 55.0, val low: Double = 70.0, val high: Double = 180.0, val urgentHigh: Double = 250.0)

data class RStats(
    val n: Int, val mean: Double, val sd: Double, val cv: Double, val min: Double, val max: Double,
    val median: Double, val pUrgentLow: Double, val pLow: Double, val pInRange: Double, val pHigh: Double,
    val pUrgentHigh: Double, val gmi: Double
)

data class DailyRow(val day: LocalDate, val stats: RStats)
data class HourlyRow(val hour: Int, val n: Int, val mean: Double, val p10: Double, val p25: Double, val median: Double, val p75: Double, val p90: Double)
data class DistBucket(val fromMgDl: Int, val toMgDl: Int, val count: Int, val percent: Double)
data class WeekRow(val weekStart: LocalDate, val stats: RStats)
data class SuccessRow(val day: LocalDate, val inRangeOk: Boolean, val lowOk: Boolean, val pInRange: Double, val pBelow: Double)

/**
 * Plano 21, F5.3 — relatórios no estilo do Nightscout (Estatísticas diárias, Distribuição,
 * Estatísticas por hora, Semanal, Metas), calculados no aparelho. Metas padrão do consenso
 * internacional de TIR: ≥ 70% no alvo e < 4% abaixo de 70.
 * GMI (%) = 3,31 + 0,02392 × média (mg/dL).
 */
object Reports {
    const val GOAL_IN_RANGE = 70.0
    const val GOAL_BELOW = 4.0

    fun stats(points: List<RPoint>, l: RLimits): RStats? {
        if (points.isEmpty()) return null
        val v = points.map { it.mgDl }.sorted()
        val n = v.size
        val mean = v.average()
        val sd = if (n > 1) sqrt(v.sumOf { (it - mean) * (it - mean) } / (n - 1)) else 0.0
        fun pct(c: Int) = 100.0 * c / n
        val ul = v.count { it <= l.urgentLow }
        val lo = v.count { it > l.urgentLow && it <= l.low }
        val uh = v.count { it >= l.urgentHigh }
        val hi = v.count { it >= l.high && it < l.urgentHigh }
        return RStats(n, mean, sd, if (mean > 0) 100 * sd / mean else 0.0, v.first(), v.last(), quantile(v, 0.5),
            pct(ul), pct(lo), pct(n - ul - lo - uh - hi), pct(hi), pct(uh), 3.31 + 0.02392 * mean)
    }

    fun daily(points: List<RPoint>, l: RLimits, zone: ZoneId): List<DailyRow> =
        points.groupBy { Instant.ofEpochMilli(it.atMs).atZone(zone).toLocalDate() }
            .mapNotNull { (d, p) -> stats(p, l)?.let { DailyRow(d, it) } }.sortedBy { it.day }

    fun hourly(points: List<RPoint>, zone: ZoneId): List<HourlyRow> =
        (0..23).map { h ->
            val v = points.filter { Instant.ofEpochMilli(it.atMs).atZone(zone).hour == h }.map { it.mgDl }.sorted()
            if (v.isEmpty()) HourlyRow(h, 0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
            else HourlyRow(h, v.size, v.average(), quantile(v, 0.1), quantile(v, 0.25), quantile(v, 0.5), quantile(v, 0.75), quantile(v, 0.9))
        }

    fun distribution(points: List<RPoint>, step: Int = 20, from: Int = 40, to: Int = 400): List<DistBucket> {
        val n = points.size
        val buckets = ArrayList<DistBucket>()
        var a = from
        while (a < to) {
            val b = a + step
            val c = points.count { val x = it.mgDl; (x >= a || a == from) && (x < b || b >= to) }
            buckets += DistBucket(a, b, c, if (n == 0) 0.0 else 100.0 * c / n)
            a = b
        }
        return buckets
    }

    /** Semanas começando na segunda-feira. */
    fun weekly(points: List<RPoint>, l: RLimits, zone: ZoneId): List<WeekRow> =
        points.groupBy { Instant.ofEpochMilli(it.atMs).atZone(zone).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) }
            .mapNotNull { (w, p) -> stats(p, l)?.let { WeekRow(w, it) } }.sortedBy { it.weekStart }

    fun success(daily: List<DailyRow>): List<SuccessRow> = daily.map {
        val below = it.stats.pUrgentLow + it.stats.pLow
        SuccessRow(it.day, it.stats.pInRange >= GOAL_IN_RANGE, below < GOAL_BELOW, it.stats.pInRange, below)
    }

    /** Quantil por interpolação linear (tipo 7). [sorted] precisa estar ordenada. */
    fun quantile(sorted: List<Double>, q: Double): Double {
        if (sorted.isEmpty()) return 0.0
        val pos = (sorted.size - 1) * q
        val lo = floor(pos).toInt(); val hi = ceil(pos).toInt()
        return sorted[lo] + (sorted[hi] - sorted[lo]) * (pos - lo)
    }

    /** Linhas CSV de uma tabela (separador ';', decimal com ponto). */
    fun csv(header: List<String>, rows: List<List<Any>>): String =
        (listOf(header) + rows.map { r -> r.map { if (it is Double) String.format(java.util.Locale.US, "%.1f", it) else it.toString() } })
            .joinToString("\n") { it.joinToString(";") }
}

/** Idade de um item (cânula, insulina, bateria, sensor) e a cor dela. */
enum class AgeLevel { OK, WARN, URGENT, UNKNOWN }

object Ages {
    fun level(sinceMs: Long?, nowMs: Long, warnHours: Int, urgentHours: Int): AgeLevel {
        if (sinceMs == null || sinceMs <= 0L) return AgeLevel.UNKNOWN
        val h = (nowMs - sinceMs) / 3_600_000.0
        return when { h >= urgentHours -> AgeLevel.URGENT; h >= warnHours -> AgeLevel.WARN; else -> AgeLevel.OK }
    }
}
