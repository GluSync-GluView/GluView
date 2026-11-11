package com.gluview.app.data.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.gluview.app.data.local.dao.ReminderDao
import com.gluview.app.data.local.entity.ReminderEntity
import com.gluview.app.service.ReminderAlarmReceiver
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Põe os lembretes no AlarmManager (F2.1). Exato quando o Android deixa
 * ([AlarmManager.canScheduleExactAlarms]); senão `setAndAllowWhileIdle`, que pode atrasar alguns
 * minutos — a tela avisa. Decisão D3: SCHEDULE_EXACT_ALARM (a Play reserva USE_EXACT_ALARM a apps
 * de despertador e calendário).
 */
@Singleton
class ReminderScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: ReminderDao
) {
    private val alarms: AlarmManager get() = context.getSystemService(AlarmManager::class.java)

    fun canScheduleExact(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()

    /** Remarca todos (boot, atualização do app, mudança de hora/fuso, permissão concedida). */
    suspend fun rescheduleAll(now: Long = System.currentTimeMillis()) {
        dao.getAll().forEach { schedule(it, now) }
    }

    /** Agenda o próximo disparo (ou cancela, se desligado/sem horário). */
    fun schedule(r: ReminderEntity, now: Long = System.currentTimeMillis()) {
        val next = ReminderSchedule.nextTrigger(r, now)
        if (next == null) { cancel(r.id); return }
        setAlarm(next, pending(r.id, next, snooze = false))
    }

    /** Adiar: um disparo avulso daqui a [minutes] (não mexe no próximo horário normal). */
    fun snooze(reminderId: Long, scheduledAt: Long, minutes: Int, now: Long = System.currentTimeMillis()) {
        setAlarm(now + minutes * 60_000L, pending(reminderId, scheduledAt, snooze = true))
    }

    fun cancel(reminderId: Long) {
        alarms.cancel(pending(reminderId, 0, snooze = false))
        alarms.cancel(pending(reminderId, 0, snooze = true))
    }

    private fun setAlarm(at: Long, pi: PendingIntent) {
        try {
            if (canScheduleExact()) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (_: SecurityException) {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    private fun pending(reminderId: Long, scheduledAt: Long, snooze: Boolean): PendingIntent {
        val intent = Intent(context, ReminderAlarmReceiver::class.java)
            .setAction(if (snooze) ReminderAlarmReceiver.ACTION_SNOOZED else ReminderAlarmReceiver.ACTION_FIRE)
            .putExtra(ReminderAlarmReceiver.EXTRA_ID, reminderId)
            .putExtra(ReminderAlarmReceiver.EXTRA_SCHEDULED_AT, scheduledAt)
        val code = requestCode(reminderId, snooze)
        return PendingIntent.getBroadcast(context, code, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    companion object {
        fun requestCode(reminderId: Long, snooze: Boolean): Int = (reminderId * 2 + if (snooze) 1 else 0).toInt() + 50_000
    }
}
