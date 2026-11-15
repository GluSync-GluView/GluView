package com.gluview.app.data.health

import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.MenstruationFlowRecord
import androidx.health.connect.client.records.MenstruationPeriodRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.BloodGlucose
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Mass
import com.gluview.app.data.local.entity.EventLogEntity
import com.gluview.app.data.local.entity.HealthRecordEntity
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * GluView ↔ Health Connect (plano 17, Fase 3). Puro: testável sem aparelho.
 *
 * Tudo que o GluView grava leva um `clientRecordId` estável ("gluview-…"): gravar de novo
 * atualiza em vez de duplicar, e dá para apagar quando o registro sai do diário.
 */
object HealthConnectMapper {
    const val KIND_GLUCOSE = "glucose"
    const val KIND_EXERCISE = "exercise"
    const val KIND_SLEEP = "sleep"
    const val KIND_PERIOD = "period"
    const val KIND_FLOW = "flow"
    const val KIND_NUTRITION = "nutrition"

    private const val MIN_MGDL = 20.0
    private const val MAX_MGDL = 600.0

    fun sensorClientId(tsMs: Long) = "gluview-sgv-$tsMs"
    fun diaryClientId(uuid: String) = "gluview-diary-$uuid"

    private fun offset(at: Instant, zone: ZoneId): ZoneOffset = zone.rules.getOffset(at)

    /** Leitura do sensor (líquido intersticial). null fora da faixa que o Health Connect aceita. */
    fun sensorGlucose(tsMs: Long, mgdl: Double, device: Device, zone: ZoneId = ZoneId.systemDefault()): BloodGlucoseRecord? {
        if (mgdl < MIN_MGDL || mgdl > MAX_MGDL) return null
        val at = Instant.ofEpochMilli(tsMs)
        return BloodGlucoseRecord(
            time = at, zoneOffset = offset(at, zone),
            metadata = Metadata.autoRecorded(device, sensorClientId(tsMs), 1L),
            level = BloodGlucose.milligramsPerDeciliter(mgdl),
            specimenSource = BloodGlucoseRecord.SPECIMEN_SOURCE_INTERSTITIAL_FLUID
        )
    }

    /** Ponta de dedo do diário (sangue capilar, registrada à mão). */
    fun fingerstick(e: EventLogEntity, zone: ZoneId = ZoneId.systemDefault()): BloodGlucoseRecord? {
        if (e.value < MIN_MGDL || e.value > MAX_MGDL) return null
        val at = Instant.ofEpochMilli(e.occurredAt)
        return BloodGlucoseRecord(
            time = at, zoneOffset = offset(at, zone),
            metadata = Metadata.manualEntry(diaryClientId(e.uuid), e.updatedAt),
            level = BloodGlucose.milligramsPerDeciliter(e.value),
            specimenSource = BloodGlucoseRecord.SPECIMEN_SOURCE_CAPILLARY_BLOOD
        )
    }

    /** Refeição do diário → nutrição (carboidrato e calorias). Insulina não tem tipo no Health Connect. */
    fun meal(e: EventLogEntity, zone: ZoneId = ZoneId.systemDefault()): NutritionRecord? {
        if (e.value <= 0 && e.kcal <= 0) return null
        val start = Instant.ofEpochMilli(e.occurredAt)
        val end = start.plusSeconds(60)
        return NutritionRecord(
            startTime = start, startZoneOffset = offset(start, zone),
            endTime = end, endZoneOffset = offset(end, zone),
            metadata = Metadata.manualEntry(diaryClientId(e.uuid), e.updatedAt),
            totalCarbohydrate = if (e.value > 0) Mass.grams(e.value) else null,
            energy = if (e.kcal > 0) Energy.kilocalories(e.kcal) else null,
            protein = if (e.proteinG > 0) Mass.grams(e.proteinG) else null,
            totalFat = if (e.fatG > 0) Mass.grams(e.fatG) else null,
            name = e.notes.takeIf { it.isNotBlank() }
        )
    }

    // ── Leitura (só de outros apps) ──

    fun fromGlucose(r: BloodGlucoseRecord): HealthRecordEntity = HealthRecordEntity(
        hcId = r.metadata.id, kind = KIND_GLUCOSE, startMs = r.time.toEpochMilli(), endMs = r.time.toEpochMilli(),
        value = r.level.inMilligramsPerDeciliter, subtype = r.specimenSource.toString(), sourcePkg = r.metadata.dataOrigin.packageName
    )

    fun fromExercise(r: ExerciseSessionRecord): HealthRecordEntity = HealthRecordEntity(
        hcId = r.metadata.id, kind = KIND_EXERCISE, startMs = r.startTime.toEpochMilli(), endMs = r.endTime.toEpochMilli(),
        value = (r.endTime.toEpochMilli() - r.startTime.toEpochMilli()) / 60_000.0,
        subtype = ExerciseSessionRecord.EXERCISE_TYPE_INT_TO_STRING_MAP[r.exerciseType] ?: "other_workout",
        title = r.title.orEmpty(), sourcePkg = r.metadata.dataOrigin.packageName
    )

    /** Sono: [value] = minutos dormidos (sem os estágios "acordado"; sem estágios = sessão inteira). */
    fun fromSleep(r: SleepSessionRecord): HealthRecordEntity {
        val awake = setOf(SleepSessionRecord.STAGE_TYPE_AWAKE, SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED, SleepSessionRecord.STAGE_TYPE_OUT_OF_BED)
        val total = (r.endTime.toEpochMilli() - r.startTime.toEpochMilli()) / 60_000.0
        val asleep = if (r.stages.isEmpty()) total
            else r.stages.filter { it.stage !in awake }.sumOf { (it.endTime.toEpochMilli() - it.startTime.toEpochMilli()) / 60_000.0 }
        return HealthRecordEntity(
            hcId = r.metadata.id, kind = KIND_SLEEP, startMs = r.startTime.toEpochMilli(), endMs = r.endTime.toEpochMilli(),
            value = asleep, value2 = total, title = r.title.orEmpty(), sourcePkg = r.metadata.dataOrigin.packageName
        )
    }

    fun fromPeriod(r: MenstruationPeriodRecord): HealthRecordEntity = HealthRecordEntity(
        hcId = r.metadata.id, kind = KIND_PERIOD, startMs = r.startTime.toEpochMilli(), endMs = r.endTime.toEpochMilli(),
        sourcePkg = r.metadata.dataOrigin.packageName
    )

    fun fromFlow(r: MenstruationFlowRecord): HealthRecordEntity = HealthRecordEntity(
        hcId = r.metadata.id, kind = KIND_FLOW, startMs = r.time.toEpochMilli(), endMs = r.time.toEpochMilli(),
        value = r.flow.toDouble(), sourcePkg = r.metadata.dataOrigin.packageName
    )

    fun fromNutrition(r: NutritionRecord): HealthRecordEntity = HealthRecordEntity(
        hcId = r.metadata.id, kind = KIND_NUTRITION, startMs = r.startTime.toEpochMilli(), endMs = r.endTime.toEpochMilli(),
        value = r.totalCarbohydrate?.inGrams ?: 0.0, value2 = r.energy?.inKilocalories ?: 0.0,
        title = r.name.orEmpty(), sourcePkg = r.metadata.dataOrigin.packageName
    )
}
