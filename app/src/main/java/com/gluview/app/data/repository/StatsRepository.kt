package com.gluview.app.data.repository

import com.gluview.app.data.datastore.AppPreferences
import com.gluview.app.data.remote.GluSyncApi
import com.gluview.app.data.remote.dto.*
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

data class ChartPoint(val index: Int, val timestampMs: Long, val sgvMgDl: Double)

data class DailyPatternPoint(val slotHour: Int, val avgSgv: Double, val label: String)

@Singleton
class StatsRepository @Inject constructor(
    private val api: GluSyncApi,
    private val glucose: GlucoseRepository,
    private val preferences: AppPreferences,
    private val cloudAccess: CloudAccessRepository
) {

    /**
     * ADR-0030 — os dados da PRÓPRIA pessoa só estão na nuvem com conta e envio liberado. Sem conta
     * (modo antigo 100% local) ou com o pedido não liberado/em análise/recusado/revogado, as telas de
     * estatística calculam no aparelho ([LocalStats]) em vez de mostrar vazio ou erro. Estado ainda
     * não consultado ("", ex.: sem rede) continua indo à nuvem, como antes.
     */
    suspend fun ownDataIsLocal(): Boolean {
        if (!preferences.hasAccount.first()) return true
        runCatching { cloudAccess.refreshIfStale() }
        val state = preferences.cloudSyncState.first()
        return state.isNotEmpty() && state != CloudAccessRepository.STATE_APPROVED
    }

    private suspend fun useLocal(patientId: String?) = patientId == null && ownDataIsLocal()

    /** Leituras locais válidas (sem as "sem sinal" que o polling grava com a hora atual). */
    private suspend fun localSamples(fromMs: Long, toMs: Long): List<LocalStats.Sample> =
        glucose.getReadingsBetween(fromMs, toMs)
            .filter { !it.isStale && it.sgvMgDl > 0 }
            .map { LocalStats.Sample(it.dateMs, it.sgvMgDl) }

    private val zone: ZoneId get() = ZoneId.systemDefault()

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
        val rows = if (useLocal(patientId)) {
            val now = System.currentTimeMillis()
            LocalStats.pivot(localSamples(LocalStats.daysAgoMs(now, days, zone), now + 1), zone)
        } else api.getPivot(dias = days, patientId = patientId)
        return LocalStats.dailyPattern(rows)
    }

    // S21 — TIR detalhado (inclui MediaTIR, MediaHbA1c e per-day breakdown)
    suspend fun getTirReport(days: Int, patientId: String? = null): TirReportDto {
        if (!useLocal(patientId)) return api.getTirReport(dias = days, patientId = patientId)
        val now = System.currentTimeMillis()
        return LocalStats.tirReport(localSamples(LocalStats.daysAgoMs(now, days, zone), now), days, zone)
    }

    // S21 — Stats diárias para cards de resumo
    suspend fun getDailyStats(days: Int, patientId: String? = null): List<DailyStatsDto> {
        if (!useLocal(patientId)) return api.getDailyStats(dias = days, patientId = patientId)
        val now = System.currentTimeMillis()
        return LocalStats.dailyStats(localSamples(LocalStats.daysAgoMs(now, days, zone), now), zone)
    }

    // S23 — Relatório completo para médico
    suspend fun getSummaryReport(mes: Int? = null, ano: Int? = null, patientId: String? = null): SummaryReportDto {
        if (!useLocal(patientId)) return api.getSummaryReport(mes = mes, ano = ano, patientId = patientId)
        val now = System.currentTimeMillis()
        val today = java.time.LocalDate.now(zone)
        val m = mes ?: today.monthValue
        val a = ano ?: today.year
        val window = LocalStats.monthWindow(m, a, now, zone) ?: error("mes/ano no futuro")
        val monthSamples = localSamples(window.first - 3_600_000L, window.second)
        val hbSamples = localSamples(LocalStats.daysAgoMs(window.second, 90, zone), window.second)
        return LocalStats.summary(m, a, window, monthSamples, hbSamples, zone, now)
    }
}
