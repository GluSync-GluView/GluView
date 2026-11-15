package com.gluview.app.domain.agp

import java.time.Instant
import java.time.ZoneId
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * PT-4 — Perfil Ambulatorial de Glicose (AGP), Kotlin puro (sem Android).
 *
 * Referência: consenso internacional de métricas de CGM (Battelino et al., Diabetes Care 2019) e o
 * modelo de relatório AGP:
 * - percentis 5, 25, 50, 75 e 95 por hora do dia, sobre todos os dias do período;
 * - tempo com sensor ativo e suficiência de dados (≥ 70% em ≥ 14 dias);
 * - glicose média, GMI = 3,31 + 0,02392 × média (mg/dL), CV = DP / média × 100;
 * - tempo nas faixas: muito baixa (< 54), baixa (54–69), no alvo (70–180), alta (181–250) e muito
 *   alta (> 250) mg/dL. A faixa-alvo pode ser a do usuário ([AgpRanges]); os limites de "muito
 *   baixa" e "muito alta" seguem o consenso.
 *
 * Tudo em mg/dL; a tela converte para a unidade da pessoa. Não é diagnóstico.
 */
object AgpCalculator {

    /** Uma leitura de glicose válida (sem "sem sinal"). */
    data class Sample(val timestampMs: Long, val mgDl: Double)

    const val MIN_DAYS_RECOMMENDED = 14
    const val MIN_ACTIVE_PERCENT = 70.0

    /** Janela usada para medir "sensor ativo": cada bloco de 5 min com ao menos uma leitura. */
    private const val SLOT_MS = 5 * 60_000L

    /**
     * @param samples leituras do período (qualquer ordem); fora de [fromMs, toMs) são ignoradas.
     * @param fromMs início do período (inclusive).
     * @param toMs fim do período (exclusive).
     */
    fun compute(
        samples: List<Sample>,
        fromMs: Long,
        toMs: Long,
        zone: ZoneId,
        ranges: AgpRanges = AgpRanges.CONSENSUS
    ): AgpReport {
        require(toMs > fromMs) { "período vazio" }
        val inPeriod = samples.filter { it.timestampMs in fromMs until toMs && it.mgDl > 0 && it.mgDl.isFinite() }
        val periodDays = (toMs - fromMs) / 86_400_000.0
        val totalSlots = ceil((toMs - fromMs).toDouble() / SLOT_MS).toLong().coerceAtLeast(1)
        val activeSlots = inPeriod.map { (it.timestampMs - fromMs) / SLOT_MS }.toSet().size
        val activePercent = activeSlots * 100.0 / totalSlots

        val dataDays = inPeriod.map { Instant.ofEpochMilli(it.timestampMs).atZone(zone).toLocalDate() }.toSet().size

        val values = inPeriod.map { it.mgDl }
        val mean = if (values.isEmpty()) null else values.average()
        val sd = if (values.size < 2 || mean == null) null
            else sqrt(values.sumOf { (it - mean) * (it - mean) } / (values.size - 1))
        val cv = if (mean != null && sd != null && mean > 0) sd / mean * 100 else null
        val gmi = mean?.let { gmiPercent(it) }

        val hourly = (0 until 24).map { hour ->
            val inHour = inPeriod.filter { Instant.ofEpochMilli(it.timestampMs).atZone(zone).hour == hour }
                .map { it.mgDl }.sorted()
            if (inHour.isEmpty()) HourlyPercentiles(hour, null, null, null, null, null, 0)
            else HourlyPercentiles(
                hour = hour,
                p5  = percentile(inHour, 5.0),
                p25 = percentile(inHour, 25.0),
                p50 = percentile(inHour, 50.0),
                p75 = percentile(inHour, 75.0),
                p95 = percentile(inHour, 95.0),
                count = inHour.size
            )
        }

        return AgpReport(
            fromMs = fromMs,
            toMs = toMs,
            periodDays = periodDays,
            dataDays = dataDays,
            readings = inPeriod.size,
            activePercent = activePercent,
            meanMgDl = mean,
            gmiPercent = gmi,
            cvPercent = cv,
            timeInRanges = timeInRanges(values, ranges),
            ranges = ranges,
            hourly = hourly
        )
    }

    /** GMI (%) = 3,31 + 0,02392 × glicose média em mg/dL (Bergenstal et al., 2018). */
    fun gmiPercent(meanMgDl: Double): Double = 3.31 + 0.02392 * meanMgDl

    /**
     * Percentil com interpolação linear entre vizinhos (o "tipo 7" de Hyndman & Fan, o padrão de
     * R/NumPy). [sorted] precisa estar em ordem crescente e não vazio.
     */
    fun percentile(sorted: List<Double>, p: Double): Double {
        require(sorted.isNotEmpty())
        if (sorted.size == 1) return sorted[0]
        val rank = p / 100.0 * (sorted.size - 1)
        val lo = floor(rank).toInt()
        val hi = ceil(rank).toInt()
        val frac = rank - lo
        return sorted[lo] + (sorted[hi] - sorted[lo]) * frac
    }

    /** Percentual de leituras em cada faixa (soma 100 quando há leituras; zeros quando não há). */
    fun timeInRanges(values: List<Double>, ranges: AgpRanges = AgpRanges.CONSENSUS): TimeInRanges {
        if (values.isEmpty()) return TimeInRanges(0.0, 0.0, 0.0, 0.0, 0.0)
        val n = values.size.toDouble()
        // Limites inteiros como no consenso: "< 54", "54–69", "70–180", "181–250", "> 250".
        val veryLow  = values.count { it < ranges.veryLowBelow }
        val low      = values.count { it >= ranges.veryLowBelow && it < ranges.targetLow }
        val target   = values.count { it >= ranges.targetLow && it <= ranges.targetHigh }
        val high     = values.count { it > ranges.targetHigh && it <= ranges.veryHighAbove }
        val veryHigh = values.count { it > ranges.veryHighAbove }
        return TimeInRanges(
            veryLowPercent  = veryLow * 100 / n,
            lowPercent      = low * 100 / n,
            targetPercent   = target * 100 / n,
            highPercent     = high * 100 / n,
            veryHighPercent = veryHigh * 100 / n
        )
    }
}

/**
 * Limites das faixas em mg/dL. Padrão do consenso: muito baixa < 54, baixa 54–69, alvo 70–180,
 * alta 181–250, muito alta > 250. Com faixa-alvo personalizada, só [targetLow]/[targetHigh] mudam.
 */
data class AgpRanges(
    val veryLowBelow: Double = 54.0,
    val targetLow: Double = 70.0,
    val targetHigh: Double = 180.0,
    val veryHighAbove: Double = 250.0
) {
    val isConsensus: Boolean get() = this == CONSENSUS

    companion object {
        val CONSENSUS = AgpRanges()

        /** Faixa-alvo do usuário, mantida dentro dos limites de "muito baixa"/"muito alta". */
        fun withTarget(low: Double, high: Double): AgpRanges {
            val l = low.coerceIn(55.0, 120.0)
            val h = high.coerceIn(l + 10, 249.0)
            return AgpRanges(targetLow = l, targetHigh = h)
        }
    }
}

data class TimeInRanges(
    val veryLowPercent: Double,
    val lowPercent: Double,
    val targetPercent: Double,
    val highPercent: Double,
    val veryHighPercent: Double
)

data class HourlyPercentiles(
    val hour: Int,
    val p5: Double?,
    val p25: Double?,
    val p50: Double?,
    val p75: Double?,
    val p95: Double?,
    val count: Int
)

data class AgpReport(
    val fromMs: Long,
    val toMs: Long,
    val periodDays: Double,
    /** Dias de calendário com ao menos uma leitura. */
    val dataDays: Int,
    val readings: Int,
    /** % do período com sensor enviando leitura (blocos de 5 min com leitura). */
    val activePercent: Double,
    val meanMgDl: Double?,
    val gmiPercent: Double?,
    val cvPercent: Double?,
    val timeInRanges: TimeInRanges,
    val ranges: AgpRanges,
    val hourly: List<HourlyPercentiles>
) {
    /** Período curto (< 14 dias): o AGP aparece, com o aviso de dados insuficientes. */
    val periodTooShort: Boolean get() = periodDays < AgpCalculator.MIN_DAYS_RECOMMENDED - 0.01

    /** Menos de 70% de tempo com sensor ativo. */
    val lowSensorTime: Boolean get() = activePercent < AgpCalculator.MIN_ACTIVE_PERCENT

    /** Dados suficientes pelo consenso: ≥ 14 dias e ≥ 70% de sensor ativo. */
    val sufficient: Boolean get() = !periodTooShort && !lowSensorTime

    val hasData: Boolean get() = readings > 0

    /** CV ≤ 36% = glicemia estável pelo consenso. */
    val stable: Boolean? get() = cvPercent?.let { it <= 36.0 }
}
