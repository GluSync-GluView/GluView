package com.gluview.app.data.reminder

import com.gluview.app.data.diary.DiaryRepository
import com.gluview.app.data.diary.DiaryType
import com.gluview.app.data.diary.InsulinKind
import com.gluview.app.data.local.dao.ReminderDao
import com.gluview.app.data.local.entity.EventLogEntity
import com.gluview.app.data.local.entity.ReminderEntity
import com.gluview.app.data.local.entity.ReminderLogEntity
import com.gluview.app.notification.ReminderNotifier
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lembretes (plano 17, Fase 2). 100% no aparelho: o alarme, a notificação e o "Feito" que vira
 * registro no diário — nenhum passo depende de internet ou do GluSync.
 */
@Singleton
class ReminderRepository @Inject constructor(
    private val dao: ReminderDao,
    private val scheduler: ReminderScheduler,
    private val diary: DiaryRepository,
    private val notifier: ReminderNotifier
) {
    fun observeAll(): Flow<List<ReminderEntity>> = dao.observeAll()
    fun observeLogFrom(fromMs: Long): Flow<List<ReminderLogEntity>> = dao.observeLogFrom(fromMs)
    suspend fun getAll(): List<ReminderEntity> = dao.getAll()

    suspend fun save(r: ReminderEntity, now: Long = System.currentTimeMillis()): ReminderEntity {
        val existing = dao.getByUuid(r.uuid)
        val toSave = r.copy(id = existing?.id ?: r.id, updatedAt = now)
        val id = dao.upsert(toSave)
        val saved = toSave.copy(id = if (toSave.id == 0L) id else toSave.id)
        scheduler.schedule(saved, now)
        return saved
    }

    suspend fun setEnabled(r: ReminderEntity, enabled: Boolean) = save(r.copy(enabled = enabled))

    suspend fun delete(r: ReminderEntity) {
        scheduler.cancel(r.id)
        notifier.cancel(r.id)
        dao.delete(r.id)
    }

    /** O alarme tocou. Remarca o próximo e mostra a notificação (ou não, no lembrete de basal já feita). */
    suspend fun onFired(id: Long, scheduledAt: Long, snoozed: Boolean, now: Long = System.currentTimeMillis()) {
        val r = dao.get(id) ?: return
        if (!snoozed) scheduler.schedule(r, now)
        if (!r.enabled) return
        if (ReminderKind.of(r.kind) == ReminderKind.BASAL_CHECK && diary.hasBasalOn(LocalDate.now(ZoneId.systemDefault()))) {
            dao.log(ReminderLogEntity(reminderId = id, scheduledAt = scheduledAt, action = ACTION_SUPPRESSED, at = now))
            return
        }
        notifier.show(r, scheduledAt, linkedName(r))
        dao.log(ReminderLogEntity(reminderId = id, scheduledAt = scheduledAt, action = ACTION_FIRED, at = now))
    }

    /** "Feito": com insulina/remédio e dose cadastrados, já vira registro no diário. */
    suspend fun onDone(id: Long, scheduledAt: Long, now: Long = System.currentTimeMillis()): Boolean {
        notifier.cancel(id)
        val r = dao.get(id) ?: return false
        dao.log(ReminderLogEntity(reminderId = id, scheduledAt = scheduledAt, action = ACTION_DONE, at = now))
        val entry = diaryEntryFor(r, now) ?: return false
        diary.save(entry, now)
        return true
    }

    suspend fun onSnooze(id: Long, scheduledAt: Long, minutes: Int, now: Long = System.currentTimeMillis()) {
        notifier.cancel(id)
        scheduler.snooze(id, scheduledAt, minutes, now)
        dao.log(ReminderLogEntity(reminderId = id, scheduledAt = scheduledAt, action = ACTION_SNOOZED, at = now))
    }

    suspend fun onSkip(id: Long, scheduledAt: Long, now: Long = System.currentTimeMillis()) {
        notifier.cancel(id)
        dao.log(ReminderLogEntity(reminderId = id, scheduledAt = scheduledAt, action = ACTION_SKIPPED, at = now))
    }

    suspend fun pruneLog(now: Long = System.currentTimeMillis()) = dao.pruneLog(now - 30L * 24 * 3_600_000)

    private suspend fun linkedName(r: ReminderEntity): String = when (ReminderKind.of(r.kind)) {
        ReminderKind.INSULIN, ReminderKind.BASAL_CHECK -> diary.getInsulin(r.linkedUuid)?.name.orEmpty()
        ReminderKind.MEDICATION -> diary.getMedication(r.linkedUuid)?.name.orEmpty()
        else -> ""
    }

    private suspend fun diaryEntryFor(r: ReminderEntity, now: Long): EventLogEntity? {
        if (r.dose <= 0 || r.linkedUuid.isBlank()) return null
        return when (ReminderKind.of(r.kind)) {
            ReminderKind.INSULIN, ReminderKind.BASAL_CHECK -> diary.getInsulin(r.linkedUuid)?.let { ins ->
                EventLogEntity(occurredAt = now, type = DiaryType.INSULIN.key, value = r.dose,
                    subtype = InsulinKind.of(ins.kind).key, itemName = ins.name, source = EventLogEntity.SOURCE_REMINDER)
            }
            ReminderKind.MEDICATION -> diary.getMedication(r.linkedUuid)?.let { m ->
                EventLogEntity(occurredAt = now, type = DiaryType.MEDICATION.key, value = r.dose,
                    itemName = m.name, unit = m.unit, source = EventLogEntity.SOURCE_REMINDER)
            }
            else -> null
        }
    }

    companion object {
        const val ACTION_FIRED = "fired"
        const val ACTION_DONE = "done"
        const val ACTION_SNOOZED = "snoozed"
        const val ACTION_SKIPPED = "skipped"
        const val ACTION_SUPPRESSED = "suppressed"
    }
}
