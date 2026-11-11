package com.gluview.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.gluview.app.data.reminder.ReminderRepository
import com.gluview.app.data.reminder.ReminderScheduler
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Roda [block] fora da main, mantendo o receiver vivo até terminar (goAsync). */
private fun BroadcastReceiver.runAsync(block: suspend () -> Unit) {
    val pending = goAsync()
    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
        try { block() } finally { pending.finish() }
    }
}

/** Disparo do lembrete (plano 17, F2.1). Não exportado: só o PendingIntent do próprio app chega aqui. */
@AndroidEntryPoint
class ReminderAlarmReceiver : BroadcastReceiver() {
    @Inject lateinit var reminders: ReminderRepository

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_ID, -1L)
        if (id < 0) return
        val scheduledAt = intent.getLongExtra(EXTRA_SCHEDULED_AT, System.currentTimeMillis())
        runAsync { reminders.onFired(id, scheduledAt, snoozed = intent.action == ACTION_SNOOZED) }
    }

    companion object {
        const val ACTION_FIRE = "com.gluview.app.reminder.FIRE"
        const val ACTION_SNOOZED = "com.gluview.app.reminder.SNOOZED_FIRE"
        const val EXTRA_ID = "reminder_id"
        const val EXTRA_SCHEDULED_AT = "scheduled_at"
    }
}

/** Botões da notificação: Feito, Adiar 10/30 min, Pular (F2.2). */
@AndroidEntryPoint
class ReminderActionReceiver : BroadcastReceiver() {
    @Inject lateinit var reminders: ReminderRepository

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_ID, -1L)
        if (id < 0) return
        val scheduledAt = intent.getLongExtra(EXTRA_SCHEDULED_AT, System.currentTimeMillis())
        runAsync {
            when (intent.action) {
                ACTION_DONE -> reminders.onDone(id, scheduledAt)
                ACTION_SNOOZE_10 -> reminders.onSnooze(id, scheduledAt, 10)
                ACTION_SNOOZE_30 -> reminders.onSnooze(id, scheduledAt, 30)
                ACTION_SKIP -> reminders.onSkip(id, scheduledAt)
            }
        }
    }

    companion object {
        const val ACTION_DONE = "com.gluview.app.reminder.DONE"
        const val ACTION_SNOOZE_10 = "com.gluview.app.reminder.SNOOZE_10"
        const val ACTION_SNOOZE_30 = "com.gluview.app.reminder.SNOOZE_30"
        const val ACTION_SKIP = "com.gluview.app.reminder.SKIP"
        const val EXTRA_ID = "reminder_id"
        const val EXTRA_SCHEDULED_AT = "scheduled_at"
    }
}

/**
 * Remarca todos os lembretes quando o Android esquece os alarmes ou a hora muda: reinício,
 * atualização do app, mudança de hora ou de fuso e permissão de alarme exato concedida.
 */
@AndroidEntryPoint
class ReminderRescheduleReceiver : BroadcastReceiver() {
    @Inject lateinit var scheduler: ReminderScheduler
    @Inject lateinit var reminders: ReminderRepository

    override fun onReceive(context: Context, intent: Intent) {
        runAsync {
            scheduler.rescheduleAll()
            reminders.pruneLog()
        }
    }
}
