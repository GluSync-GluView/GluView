package com.gluview.app.data.health

import com.gluview.app.data.datastore.AppPreferences
import com.gluview.app.data.local.dao.HealthRecordDao
import com.gluview.app.data.repository.GlucoseRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/** Resultado dos cruzamentos (tela Padrões e PDFs). */
data class PatternsResult(
    val days: Int,
    val exercise: List<HealthInsights.ExerciseGroup>,
    val sleep: HealthInsights.SleepSummary,
    val cycle: List<HealthInsights.PhaseStats>,
    val hasCycleData: Boolean,
    val readings: Int
) {
    val hasAny: Boolean get() = readings > 0 && (exercise.isNotEmpty() || sleep.nights > 0 || hasCycleData)
}

/** Monta os cruzamentos dos últimos [days] dias até [nowMs] (tela Padrões e PDFs, plano 17). */
@Singleton
class HealthPatterns @Inject constructor(
    private val glucose: GlucoseRepository,
    private val records: HealthRecordDao,
    private val preferences: AppPreferences
) {
    suspend fun compute(days: Int, nowMs: Long = System.currentTimeMillis()): PatternsResult =
        computeBetween(nowMs - days * DAY, nowMs, days)

    suspend fun computeBetween(fromMs: Long, toMs: Long, days: Int = ((toMs - fromMs) / DAY).toInt()): PatternsResult {
        val limits = preferences.glucoseThresholds.first()
        val low = limits.low.toDouble()
        val high = limits.high.toDouble()
        // Exercício precisa das 24 h seguintes; o ciclo, de inícios até 35 dias antes.
        val points = glucose.getReadingsBetween(fromMs, toMs + 60_000).filter { it.sgvMgDl > 0 }
            .map { HealthInsights.Point(it.dateMs, it.sgvMgDl) }
        val ex = records.getBetween(HealthConnectMapper.KIND_EXERCISE, fromMs, toMs)
        val sl = records.getBetween(HealthConnectMapper.KIND_SLEEP, fromMs, toMs)
        val periods = records.getBetween(HealthConnectMapper.KIND_PERIOD, fromMs - 35 * DAY, toMs)
        val flows = records.getBetween(HealthConnectMapper.KIND_FLOW, fromMs - 35 * DAY, toMs)
        val starts = HealthInsights.periodStarts(periods, flows)
        return PatternsResult(
            days = days,
            exercise = HealthInsights.exercise(ex, points, low, high),
            sleep = HealthInsights.sleep(sl, points, low, high),
            cycle = HealthInsights.cycle(starts, points, low, high),
            hasCycleData = starts.isNotEmpty(),
            readings = points.size
        )
    }

    private companion object { const val DAY = 24L * 3_600_000 }
}
