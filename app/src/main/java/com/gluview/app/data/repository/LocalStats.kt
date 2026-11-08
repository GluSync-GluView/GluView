package com.gluview.app.data.repository

import com.gluview.app.data.remote.dto.DailyStatsDto
import com.gluview.app.data.remote.dto.HbA1cResultDto
import com.gluview.app.data.remote.dto.MonthlyStatsDto
import com.gluview.app.data.remote.dto.PivotRowDto
import com.gluview.app.data.remote.dto.SummaryCabecalhoDto
import com.gluview.app.data.remote.dto.SummaryReportDto
import com.gluview.app.data.remote.dto.TirDayDto
import com.gluview.app.data.remote.dto.TirReportDto
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.sqrt

/**
 * Estatísticas calculadas no aparelho, com as MESMAS regras do GluSync (GlucoseService.cs e
 * ReportEndpoints.cs): dia do calendário no fuso do aparelho, faixa de TIR 70–180 (padrão do perfil
 * no servidor, independente dos limites de alerta — ADR-0005), HbA1c = (média + 46,7) / 28,7 e a
 * leitura das 23h contando no slot 00h do dia seguinte no pivô.
 *
 * Usado quando os dados da própria pessoa não estão na nuvem (sem conta ou nuvem não liberada,
 * ADR-0030) — antes Tempo no alvo, Padrão diário e Relatório médico iam sempre ao servidor e
 * apareciam vazios ou com erro. Funções puras: recebem as leituras e o "agora", sem banco.
 */
object LocalStats {

    const val TARGET_LOW = 70.0
    const val TARGET_HIGH = 180.0

    data class Sample(val dateMs: Long, val sgv: Double)

    private val DIA: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
    private val SLOTS = listOf(0, 3, 6, 9, 12, 15, 18, 21)

    private fun Double.round1(): Double = Math.round(this * 10.0) / 10.0
    private fun Double.round0(): Double = Math.round(this).toDouble()
    private fun hba1c(avg: Double): Double = (avg + 46.7) / 28.7

    private fun localDate(ms: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
    private fun localHour(ms: Long, zone: ZoneId): Int = Instant.ofEpochMilli(ms).atZone(zone).hour

    /** Início da janela "últimos N dias" igual ao servidor: agora no fuso − N dias. */
    fun daysAgoMs(nowMs: Long, days: Int, zone: ZoneId): Long =
        Instant.ofEpochMilli(nowMs).atZone(zone).minusDays(days.toLong()).toInstant().toEpochMilli()

    private fun valid(samples: List<Sample>) = samples.filter { it.sgv > 0 }

    private fun sampleStdDev(values: List<Double>): Double {
        if (values.size < 2) return 0.0
        val avg = values.average()
        return sqrt(values.sumOf { (it - avg) * (it - avg) } / (values.size - 1))
    }

    private fun pctInRange(values: List<Double>): Double =
        if (values.isEmpty()) 0.0 else (100.0 * values.count { it in TARGET_LOW..TARGET_HIGH } / values.size).round1()

    /** GET /glucose/stats/daily — um item por dia do calendário, em ordem crescente. */
    fun dailyStats(samples: List<Sample>, zone: ZoneId): List<DailyStatsDto> =
        valid(samples).groupBy { localDate(it.dateMs, zone) }.toSortedMap().map { (day, list) ->
            val g = list.map { it.sgv }
            val avg = g.average()
            DailyStatsDto(
                dia = day.format(DIA),
                media = avg.round0(),
                minimo = g.min().round0(),
                maximo = g.max().round0(),
                desvio = sampleStdDev(g).round1(),
                totalLeituras = g.size,
                leiturasBaixas = g.count { it < TARGET_LOW },
                leiturasAltas = g.count { it > TARGET_HIGH },
                percentNoAlvo = pctInRange(g),
                hbA1cEst = hba1c(avg).round1()
            )
        }

    /** GET /report/tir — [samples] já recortadas em [agora − dias, agora). */
    fun tirReport(samples: List<Sample>, days: Int, zone: ZoneId): TirReportDto {
        val list = dailyStats(samples, zone)
        val periodo = "últimos $days dias"
        if (list.isEmpty()) return TirReportDto(periodo, 0.0, 0.0, emptyList())
        return TirReportDto(
            periodo = periodo,
            mediaTir = list.map { it.percentNoAlvo }.average().round1(),
            mediaHbA1c = list.map { it.hbA1cEst }.average().round1(),
            dias = list.map { s ->
                TirDayDto(
                    dia = s.dia,
                    tir = s.percentNoAlvo,
                    hbA1cEst = s.hbA1cEst,
                    hipoglicemia = if (s.leiturasBaixas > 0) (100.0 * s.leiturasBaixas / s.totalLeituras).round1() else 0.0,
                    hiperglicemia = if (s.leiturasAltas > 0) (100.0 * s.leiturasAltas / s.totalLeituras).round1() else 0.0
                )
            }
        )
    }

    private fun slotOf(hour: Int): Int = when (hour) {
        23, 0, 1 -> 0
        else -> ((hour + 1) / 3) * 3
    }

    /**
     * GET /glucose/pivot — média por faixa de 3 h (±1 h) por dia. A leitura das 23h vai para o
     * slot 00h do dia SEGUINTE; média/total do dia são do dia do calendário. [keepDay] recorta os
     * dias devolvidos (relatório do mês).
     */
    fun pivot(samples: List<Sample>, zone: ZoneId, keepDay: (LocalDate) -> Boolean = { true }): List<PivotRowDto> {
        val v = valid(samples)
        val byCalendarDay = v.groupBy { localDate(it.dateMs, zone) }
        val bySlottedDay = v.groupBy {
            val d = localDate(it.dateMs, zone)
            if (localHour(it.dateMs, zone) == 23) d.plusDays(1) else d
        }
        return bySlottedDay.toSortedMap().filterKeys(keepDay).map { (day, list) ->
            val slotAvg = SLOTS.associateWith { slot ->
                list.filter { slotOf(localHour(it.dateMs, zone)) == slot }.map { it.sgv }
                    .takeIf { it.isNotEmpty() }?.average()?.round0() ?: 0.0
            }
            val calendar = byCalendarDay[day].orEmpty().map { it.sgv }
            val dayAvg = calendar.takeIf { it.isNotEmpty() }?.average()
            PivotRowDto(
                dia = day.format(DIA), ano = day.year, mes = day.monthValue,
                h00 = slotAvg.getValue(0), h03 = slotAvg.getValue(3), h06 = slotAvg.getValue(6), h09 = slotAvg.getValue(9),
                h12 = slotAvg.getValue(12), h15 = slotAvg.getValue(15), h18 = slotAvg.getValue(18), h21 = slotAvg.getValue(21),
                mediaDia = dayAvg?.round0() ?: 0.0,
                totalLeituras = calendar.size,
                hbA1cEst = dayAvg?.let { hba1c(it).round1() } ?: 0.0
            )
        }
    }

    /** Curva média por slot (tela Padrão diário) a partir das linhas do pivô — slot sem dado = 0. */
    fun dailyPattern(rows: List<PivotRowDto>): List<DailyPatternPoint> = SLOTS.map { hour ->
        val values = rows.mapNotNull { row ->
            val v = when (hour) {
                0 -> row.h00; 3 -> row.h03; 6 -> row.h06; 9 -> row.h09
                12 -> row.h12; 15 -> row.h15; 18 -> row.h18; else -> row.h21
            }
            if (v > 0.0) v else null
        }
        DailyPatternPoint(
            slotHour = hour,
            avgSgv = if (values.isEmpty()) 0.0 else values.average(),
            label = String.format("%02d:00", hour)
        )
    }

    private fun classificacao(h: Double) = when {
        h < 5.7 -> "Normal" to "normal"
        h < 6.5 -> "Ótimo" to "optimal"
        h < 7.0 -> "Bom" to "good"
        h < 8.0 -> "Atenção" to "attention"
        else -> "Alto" to "high"
    }

    /** HbA1c estimada das leituras de [samples] (já recortadas na janela de [days] dias). */
    fun hbA1c(samples: List<Sample>, days: Int): HbA1cResultDto? {
        val g = valid(samples).map { it.sgv }
        if (g.isEmpty()) return null
        val avg = g.average()
        val h = hba1c(avg)
        val (texto, code) = classificacao(h)
        return HbA1cResultDto(
            periodo = "últimos $days dias", mediaGlicemia = avg.round1(), hbA1cEst = h.round1(),
            classificacao = texto, totalLeituras = g.size, classificacaoCode = code
        )
    }

    /** Janela [início do mês, min(fim do mês, agora)) no fuso; null se o mês é no futuro. */
    fun monthWindow(mes: Int, ano: Int, nowMs: Long, zone: ZoneId): Pair<Long, Long>? {
        val ym = YearMonth.of(ano, mes)
        val from = ym.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = ym.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        if (from > nowMs) return null
        return from to minOf(end, nowMs)
    }

    /**
     * GET /report/summary. [monthSamples] cobre [início do mês − 1 h, fim) (as 23h do último dia do
     * mês anterior caem no 1º dia); [hbSamples] cobre os 90 dias que terminam no fim da janela.
     */
    fun summary(
        mes: Int, ano: Int, window: Pair<Long, Long>, monthSamples: List<Sample>, hbSamples: List<Sample>,
        zone: ZoneId, generatedAtMs: Long
    ): SummaryReportDto {
        val (fromMs, toMs) = window
        val firstDay = localDate(fromMs, zone)
        val lastDay = localDate(toMs - 1, zone)
        val pivotRows = pivot(monthSamples.filter { it.dateMs < toMs }, zone) { it in firstDay..lastDay }
        val inMonth = valid(monthSamples).filter { it.dateMs in fromMs until toMs }
        val monthly = if (inMonth.isEmpty()) emptyList() else {
            val g = inMonth.map { it.sgv }
            val avg = g.average()
            listOf(
                MonthlyStatsDto(
                    ano = ano, mes = mes,
                    mediaMensal = avg.round0(),
                    hbA1cEstMensal = hba1c(avg).round1(),
                    tirPercent = pctInRange(g),
                    totalDias = inMonth.map { localDate(it.dateMs, zone) }.distinct().size,
                    totalLeituras = g.size
                )
            )
        }
        return SummaryReportDto(
            cabecalho = SummaryCabecalhoDto(
                periodo = String.format("%02d/%d", mes, ano),
                gerado = Instant.ofEpochMilli(generatedAtMs).toString(),
                totalDias = pivotRows.size,
                hbA1c90dias = hbA1c(hbSamples, 90)
            ),
            tabelaPivot = pivotRows,
            resumoMensal = monthly
        )
    }
}
