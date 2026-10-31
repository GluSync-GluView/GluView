package com.gluview.app.data.repository

import com.gluview.app.data.remote.GluSyncApi
import com.gluview.app.data.remote.dto.*
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

data class ChartPoint(val index: Int, val timestampMs: Long, val sgvMgDl: Double)

data class DailyPatternPoint(val slotHour: Int, val avgSgv: Double, val label: String)

@Singleton
class StatsRepository @Inject constructor(private val api: GluSyncApi) {

    // DateTimeFormatter é thread-safe; SimpleDateFormat NÃO é — singleton não pode compartilhá-lo
    private val isoFmt: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)

    // S19 — dados brutos para o gráfico histórico (intervalo explícito em ms).
    // patientId != null → modo seguidor: usa a mesma rota de /follow/{patientId}/glucose/range
    // já usada pela tela do paciente acompanhado, pra ter o período completo vindo do servidor
    // (não só o que existe localmente).
    suspend fun getChartData(fromMs: Long, toMs: Long, patientId: String? = null): List<ChartPoint> {
        if (patientId != null) {
            val entries = api.followedPatientRange(patientId, from = fromMs, to = toMs)
            return entries.mapIndexed { idx, entry -> ChartPoint(index = idx, timestampMs = entry.date, sgvMgDl = entry.sgv) }
        }
        val result = api.getRange(
            from     = isoFmt.format(Instant.ofEpochMilli(fromMs)),
            to       = isoFmt.format(Instant.ofEpochMilli(toMs)),
            pageSize = 2000
        )
        return result.data.mapIndexed { idx, entry ->
            ChartPoint(index = idx, timestampMs = entry.date, sgvMgDl = entry.sgv)
        }
    }

    /**
     * Timestamps da nuvem num intervalo, SEM o teto de 2000 do [getChartData] — percorre todas as
     * páginas (`totalPages`). Uso: [com.gluview.app.service.GapBackfillWorker], que agora cobre até
     * a vida útil inteira do sensor (21/07/2026) e facilmente passa de 2000 leituras
     * (~1/min) numa janela de dias. `getChartData` continua com 1 página só — é usado pela tela de
     * gráfico, que não precisa (nem quer) buscar dias inteiros de uma vez.
     */
    suspend fun getAllTimestamps(fromMs: Long, toMs: Long): List<Long> {
        val out = mutableListOf<Long>()
        var page = 1
        while (true) {
            val result = api.getRange(
                from     = isoFmt.format(Instant.ofEpochMilli(fromMs)),
                to       = isoFmt.format(Instant.ofEpochMilli(toMs)),
                page     = page,
                pageSize = 2000,
                orderBy  = "date",
                order    = "asc"
            )
            out += result.data.map { it.date }
            if (page >= result.totalPages || result.data.isEmpty()) break
            page++
        }
        return out
    }

    // S20 — curva média por slot de 3h (8 slots: 00, 03, 06, 09, 12, 15, 18, 21)
    suspend fun getDailyPattern(days: Int, patientId: String? = null): List<DailyPatternPoint> {
        val rows = api.getPivot(dias = days, patientId = patientId)
        return listOf(0, 3, 6, 9, 12, 15, 18, 21).map { hour ->
            val values = rows.mapNotNull { row ->
                val v = when (hour) {
                    0  -> row.h00;  3 -> row.h03;  6 -> row.h06;  9 -> row.h09
                    12 -> row.h12; 15 -> row.h15; 18 -> row.h18; 21 -> row.h21
                    else -> 0.0
                }
                if (v > 0.0) v else null
            }
            DailyPatternPoint(
                slotHour = hour,
                avgSgv   = if (values.isEmpty()) 0.0 else values.average(),
                label    = String.format("%02d:00", hour)
            )
        }
    }

    // S21 — TIR detalhado (inclui MediaTIR, MediaHbA1c e per-day breakdown)
    suspend fun getTirReport(days: Int, patientId: String? = null): TirReportDto =
        api.getTirReport(dias = days, patientId = patientId)

    // S21 — Stats diárias para cards de resumo
    suspend fun getDailyStats(days: Int, patientId: String? = null): List<DailyStatsDto> =
        api.getDailyStats(dias = days, patientId = patientId)

    // S23 — Relatório completo para médico
    suspend fun getSummaryReport(mes: Int? = null, ano: Int? = null, patientId: String? = null): SummaryReportDto =
        api.getSummaryReport(mes = mes, ano = ano, patientId = patientId)
}
