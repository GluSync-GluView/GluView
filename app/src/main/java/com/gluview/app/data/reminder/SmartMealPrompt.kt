package com.gluview.app.data.reminder

import com.gluview.app.data.datastore.AppPreferences
import com.gluview.app.data.diary.DiaryRepository
import com.gluview.app.data.repository.GlucoseRepository
import com.gluview.app.notification.ReminderNotifier
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Aviso "subindo rápido — registrou a refeição?" (plano 17, F2.4). Desligado por padrão, só no
 * aparelho, e nunca fala de dose: só lembra de registrar.
 */
object SmartMealRule {
    /** Subida mínima (mg/dL) na janela. */
    const val MIN_RISE = 45.0
    const val WINDOW_MS = 30 * 60_000L
    /** Sem refeição registrada nesse tempo para trás. */
    const val MEAL_LOOKBACK_MS = 90 * 60_000L
    /** No máximo um aviso nesse intervalo. */
    const val COOLDOWN_MS = 3 * 60 * 60_000L

    /** [points] = (timestampMs, mg/dL) das leituras recentes. Pura, testável. */
    fun isRapidRise(points: List<Pair<Long, Double>>, now: Long): Boolean {
        val recent = points.filter { it.first in (now - WINDOW_MS)..now }.sortedBy { it.first }
        if (recent.size < 3) return false
        val last = recent.last()
        if (now - last.first > 10 * 60_000L) return false
        val first = recent.first()
        if (last.first - first.first < 15 * 60_000L) return false
        // Subida sustentada: cresceu no total e a última parte também sobe (não é ruído de um ponto).
        val mid = recent[recent.size / 2]
        return last.second - first.second >= MIN_RISE && last.second > mid.second && mid.second >= first.second
    }

    fun shouldPrompt(enabled: Boolean, rapidRise: Boolean, hasRecentMeal: Boolean, lastPromptAt: Long, now: Long): Boolean =
        enabled && rapidRise && !hasRecentMeal && now - lastPromptAt >= COOLDOWN_MS
}

@Singleton
class SmartMealPrompt @Inject constructor(
    private val preferences: AppPreferences,
    private val glucose: GlucoseRepository,
    private val diary: DiaryRepository,
    private val notifier: ReminderNotifier
) {
    private val mutex = Mutex()

    suspend fun onNewReading(now: Long = System.currentTimeMillis()) = mutex.withLock {
        if (!preferences.smartMealPrompt.first()) return@withLock
        val points = glucose.getReadingsBetween(now - SmartMealRule.WINDOW_MS, now + 60_000L).map { it.dateMs to it.sgvMgDl }
        val rise = SmartMealRule.isRapidRise(points, now)
        if (!rise) return@withLock
        val meal = diary.hasMealBetween(now - SmartMealRule.MEAL_LOOKBACK_MS, now + 60_000L)
        if (SmartMealRule.shouldPrompt(true, rise, meal, preferences.smartMealPromptLastAt.first(), now)) {
            preferences.saveSmartMealPromptLastAt(now)
            notifier.showMealPrompt()
        }
    }
}
