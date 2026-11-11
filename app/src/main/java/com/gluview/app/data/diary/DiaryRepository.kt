package com.gluview.app.data.diary

import com.gluview.app.data.datastore.AppPreferences
import com.gluview.app.data.local.dao.DiaryCatalogDao
import com.gluview.app.data.local.dao.EventLogDao
import com.gluview.app.data.local.dao.FingerstickLogDao
import com.gluview.app.data.local.entity.EventLogEntity
import com.gluview.app.data.local.entity.FingerstickLogEntity
import com.gluview.app.data.local.entity.InsulinEntity
import com.gluview.app.data.local.entity.MedicationEntity
import com.gluview.app.data.repository.GlucoseRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Diário (plano 17, Fase 1) — **GluView first**: tudo é gravado e lido no aparelho. A nuvem
 * (GluSync) só recebe uma cópia quando a conta tem a liberação de nuvem; isso fica no
 * [DiarySyncScheduler], que confere a liberação antes de qualquer envio.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class DiaryRepository @Inject constructor(
    private val dao: EventLogDao,
    private val catalog: DiaryCatalogDao,
    private val fingerstickDao: FingerstickLogDao,
    private val glucose: GlucoseRepository,
    private val preferences: AppPreferences,
    private val syncScheduler: DiarySyncScheduler
) {
    private suspend fun owner(): String = preferences.userEmail.first()

    // ── Leitura ──
    fun observeDay(day: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Flow<List<EventLogEntity>> {
        val (from, to) = dayBounds(day, zone)
        return preferences.userEmail.flatMapLatest { dao.observeBetween(it, from, to) }
    }

    fun observeDaySummary(day: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Flow<DaySummary> =
        observeDay(day, zone).map(DaySummary::of)

    fun observeFrom(fromMs: Long): Flow<List<EventLogEntity>> =
        preferences.userEmail.flatMapLatest { dao.observeFrom(it, fromMs) }

    fun observeRecent(): Flow<List<EventLogEntity>> =
        preferences.userEmail.flatMapLatest { dao.observeRecent(it) }

    suspend fun getBetween(fromMs: Long, toMs: Long): List<EventLogEntity> = dao.getBetween(owner(), fromMs, toMs)

    suspend fun getAll(): List<EventLogEntity> = dao.getAll(owner())

    suspend fun getByUuid(uuid: String): EventLogEntity? = dao.getByUuid(uuid)?.takeIf { !it.deleted }

    // ── Escrita ──
    /**
     * Grava (novo ou edição, casando pelo uuid). Preenche a glicemia do sensor no horário quando
     * não veio, e a ponta de dedo também alimenta a comparação com o sensor (Diagnóstico).
     */
    suspend fun save(entry: EventLogEntity, now: Long = System.currentTimeMillis()): EventLogEntity {
        val owner = owner()
        val existing = dao.getByUuid(entry.uuid)
        val sgv = if (entry.sgvAtEvent > 0) entry.sgvAtEvent else sensorAt(entry.occurredAt)
        val toSave = entry.copy(
            id = existing?.id ?: 0,
            ownerEmail = existing?.ownerEmail?.takeIf { it.isNotEmpty() } ?: owner,
            sgvAtEvent = sgv,
            remoteId = existing?.remoteId ?: entry.remoteId,
            updatedAt = now,
            dirty = true,
            deleted = false
        )
        if (existing == null) dao.insert(toSave) else dao.update(toSave)
        if (existing == null && toSave.type == DiaryType.FINGERSTICK.key && sgv > 0 && toSave.value > 0) {
            recordFingerstickPair(toSave.occurredAt, toSave.value, sgv)
        }
        syncScheduler.requestSync()
        return dao.getByUuid(toSave.uuid) ?: toSave
    }

    /** Apaga. O que já foi à nuvem vira "apagado pendente" até a nuvem saber; o resto sai na hora. */
    suspend fun delete(entry: EventLogEntity, now: Long = System.currentTimeMillis()) {
        val current = dao.getByUuid(entry.uuid) ?: return
        if (current.remoteId == null) dao.hardDelete(current.id)
        else {
            dao.update(current.copy(deleted = true, dirty = true, updatedAt = now))
            syncScheduler.requestSync()
        }
    }

    /** Ponta de dedo vinda da tela Diagnóstico: entra no diário sem duplicar a comparação. */
    suspend fun addFingerstickFromDiagnostic(mgdl: Double, sensorMgDl: Double, at: Long) {
        dao.insert(
            EventLogEntity(
                occurredAt = at, type = DiaryType.FINGERSTICK.key, value = mgdl, sgvAtEvent = sensorMgDl,
                source = EventLogEntity.SOURCE_DIAGNOSTIC, ownerEmail = owner(), updatedAt = at
            )
        )
        syncScheduler.requestSync()
    }

    private suspend fun recordFingerstickPair(at: Long, fingerstick: Double, sensor: Double) {
        val diff = fingerstick - sensor
        fingerstickDao.insert(
            FingerstickLogEntity(ts = at, fingerstickMgDl = fingerstick, sensorMgDl = sensor, diff = diff,
                diffPct = if (fingerstick > 0) diff / fingerstick * 100.0 else 0.0)
        )
    }

    /** Leitura do sensor mais próxima do horário (até 10 min de distância); 0 se não houver. */
    private suspend fun sensorAt(at: Long): Double {
        val window = 10 * 60_000L
        val near = glucose.getReadingsBetween(at - window, at + window)
        return near.minByOrNull { abs(it.dateMs - at) }?.sgvMgDl ?: 0.0
    }

    // ── Cadastros (F1.2) ──
    fun observeInsulins(): Flow<List<InsulinEntity>> = catalog.observeInsulins()
    fun observeMedications(): Flow<List<MedicationEntity>> = catalog.observeMedications()
    suspend fun saveInsulin(insulin: InsulinEntity) { catalog.upsertInsulin(insulin) }
    suspend fun archiveInsulin(uuid: String) = catalog.archiveInsulin(uuid)
    suspend fun saveMedication(medication: MedicationEntity) { catalog.upsertMedication(medication) }
    suspend fun archiveMedication(uuid: String) = catalog.archiveMedication(uuid)
    suspend fun getInsulin(uuid: String) = catalog.getInsulin(uuid)
    suspend fun getMedication(uuid: String) = catalog.getMedication(uuid)

    /** "Basal registrada hoje?" — base do lembrete de basal (F2.4). */
    suspend fun hasBasalOn(day: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Boolean {
        val (from, to) = dayBounds(day, zone)
        return dao.countBasalBetween(owner(), from, to) > 0
    }

    suspend fun hasMealBetween(fromMs: Long, toMs: Long): Boolean = dao.countMealsBetween(owner(), fromMs, toMs) > 0

    companion object {
        fun dayBounds(day: LocalDate, zone: ZoneId): Pair<Long, Long> =
            day.atStartOfDay(zone).toInstant().toEpochMilli() to day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

        fun dayOf(ms: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
    }
}
