package com.gluview.app.nightscout

import com.gluview.app.data.datastore.AppPreferences
import com.gluview.app.data.diary.CareEvent
import com.gluview.app.data.diary.DiaryRepository
import com.gluview.app.data.diary.DiaryType
import com.gluview.app.data.local.dao.SensorHistoryDao
import com.gluview.app.data.local.entity.EventLogEntity
import com.gluview.app.data.remote.GluSyncApi
import com.gluview.app.data.repository.GlucoseRepository
import com.gluview.therapy.RLimits
import com.gluview.therapy.RPoint
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/** Desde quando está em uso cada item (null = não registrado). */
data class CareAges(val sensorSince: Long?, val cannulaSince: Long?, val insulinSince: Long?, val batterySince: Long?)

/**
 * Plano 21, F5 — dados das telas no estilo Nightscout: idades (do diário e do histórico de
 * sensores), status do AndroidAPS (devicestatus no GluSync) e leituras para os relatórios.
 */
@Singleton
class NightscoutRepository @Inject constructor(
    private val diary: DiaryRepository,
    private val sensorHistoryDao: SensorHistoryDao,
    private val glucose: GlucoseRepository,
    private val preferences: AppPreferences,
    private val api: GluSyncApi
) {
    suspend fun ages(nowMs: Long = System.currentTimeMillis()): CareAges {
        val events = diary.getBetween(nowMs - 60L * 86_400_000L, nowMs + 60_000L).filter { !it.deleted && it.type == DiaryType.NOTE.key }
        fun last(e: CareEvent) = events.filter { it.subtype == e.subtype }.maxOfOrNull { it.occurredAt }
        return CareAges(sensorHistoryDao.observeActive().first()?.startedAt, last(CareEvent.SITE_CHANGE),
            last(CareEvent.INSULIN_CHANGE), last(CareEvent.PUMP_BATTERY))
    }

    /** Registra a troca agora (vai ao GluSync como evento Nightscout, ver DiaryTreatmentMapper). */
    suspend fun recordChange(e: CareEvent, note: String, atMs: Long = System.currentTimeMillis()) {
        diary.save(EventLogEntity(occurredAt = atMs, type = DiaryType.NOTE.key, subtype = e.subtype, notes = note))
    }

    /** Status mais novo do AAPS guardado no GluSync, ou null (sem conta, sem dados ou sem rede). */
    suspend fun aapsStatus(): AapsStatus? {
        if (preferences.localOnlyMode.first()) return null
        val r = runCatching { api.getDeviceStatus(5) }.getOrNull() ?: return null
        if (!r.isSuccessful) return null
        return r.body()?.string()?.let(AapsDeviceStatus::parseLatest)
    }

    suspend fun points(fromMs: Long, toMs: Long): List<RPoint> =
        glucose.getReadingsBetween(fromMs, toMs).filter { !it.isStale && it.sgvMgDl in 20.0..600.0 }.map { RPoint(it.dateMs, it.sgvMgDl) }

    suspend fun limits(): RLimits = preferences.glucoseThresholds.first().let {
        RLimits(it.urgentLow.toDouble(), it.low.toDouble(), it.high.toDouble(), it.urgentHigh.toDouble())
    }
}
