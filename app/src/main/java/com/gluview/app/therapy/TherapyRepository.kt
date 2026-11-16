package com.gluview.app.therapy

import com.gluview.app.data.datastore.AppPreferences
import com.gluview.app.data.diary.DiaryRepository
import com.gluview.app.data.diary.DiaryType
import com.gluview.app.data.diary.InsulinKind
import com.gluview.app.data.local.entity.EventLogEntity
import com.gluview.app.data.repository.GlucoseRepository
import com.gluview.therapy.Carbs
import com.gluview.therapy.CurrentGlucose
import com.gluview.therapy.Dose
import com.gluview.therapy.OnBoard
import com.gluview.therapy.TherapyProfile
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** Tudo que a calculadora e a previsão precisam, num instante. */
data class TherapySnapshot(
    val nowMs: Long,
    val minuteOfDay: Int,
    val doses: List<Dose>,
    val carbs: List<Carbs>,
    val glucose: CurrentGlucose?,
    /** Variação nos últimos 5 min (mg/dL), para a previsão. */
    val delta5: Double?,
    val iob: Double?,
    val cob: Double?
)

/**
 * Plano 21, F4 — liga o :therapy-core ao diário e à glicemia. Insulina MISTA entra inteira como
 * rápida no IOB (superestima o IOB, então a calculadora sugere MENOS — o lado seguro). Basal de
 * caneta não entra no IOB rápido.
 */
@Singleton
class TherapyRepository @Inject constructor(
    private val preferences: AppPreferences,
    private val diary: DiaryRepository,
    private val glucose: GlucoseRepository
) {
    suspend fun snapshot(nowMs: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): TherapySnapshot {
        val events = diary.getBetween(nowMs - WINDOW_MS, nowMs + 5 * 60_000L).filter { !it.deleted }
        val doses = events.toDoses()
        val carbs = events.filter { it.type == DiaryType.MEAL.key && it.value > 0.0 }.map { Carbs(it.occurredAt, it.value) }
        val recent = glucose.getRecentReadings(nowMs - 25 * 60_000L).filter { !it.isStale && it.dateMs <= nowMs + 60_000L }
            .sortedBy { it.dateMs }
        val last = recent.lastOrNull()
        val current = last?.let {
            val age = ((nowMs - it.dateMs) / 60_000L).toInt().coerceAtLeast(0)
            CurrentGlucose(it.sgvMgDl, age, deltaOver(recent, 15))
        }
        val profile = preferences.therapyProfile.first()
        val minute = Instant.ofEpochMilli(nowMs).atZone(zone).let { it.hour * 60 + it.minute }
        val usable = profile?.takeIf { it.problems().isEmpty() }
        return TherapySnapshot(nowMs, minute, doses, carbs, current, deltaOver(recent, 5),
            usable?.let { OnBoard.iob(doses, nowMs, it.curve).iob },
            usable?.let { OnBoard.cob(carbs, nowMs, it.carbAbsorptionMin) })
    }

    private fun List<EventLogEntity>.toDoses() = filter {
        it.type == DiaryType.INSULIN.key && it.value > 0.0 && InsulinKind.of(it.subtype) != InsulinKind.BASAL
    }.map { Dose(it.occurredAt, it.value) }

    /** Variação da glicemia nos últimos [minutes], ou null sem leitura por perto daquele tempo. */
    private fun deltaOver(sorted: List<com.gluview.app.data.local.entity.GlucoseEntity>, minutes: Int): Double? {
        val last = sorted.lastOrNull() ?: return null
        val target = last.dateMs - minutes * 60_000L
        val before = sorted.minByOrNull { kotlin.math.abs(it.dateMs - target) } ?: return null
        if (kotlin.math.abs(before.dateMs - target) > 3 * 60_000L || before.dateMs >= last.dateMs) return null
        return (last.sgvMgDl - before.sgvMgDl) * minutes * 60_000.0 / (last.dateMs - before.dateMs)
    }

    /** Registra o que a pessoa confirmou: carbo (refeição) e/ou insulina rápida. */
    suspend fun record(profile: TherapyProfile, carbsG: Double, units: Double, atMs: Long = System.currentTimeMillis()) {
        if (carbsG > 0.0) diary.save(EventLogEntity(occurredAt = atMs, type = DiaryType.MEAL.key, value = carbsG,
            unit = "g", notes = NOTE))
        if (units > 0.0) diary.save(EventLogEntity(occurredAt = atMs, type = DiaryType.INSULIN.key, value = units,
            subtype = InsulinKind.RAPID.key, itemName = profile.insulin.label, unit = "U", notes = NOTE))
    }

    companion object {
        /** DIA máximo (10 h) + absorção máxima (8 h) com folga. */
        const val WINDOW_MS = 12 * 3_600_000L
        const val NOTE = "Calculadora de bolus"
    }
}
