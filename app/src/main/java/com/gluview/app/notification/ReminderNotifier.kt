package com.gluview.app.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import com.gluview.app.MainActivity
import com.gluview.app.R
import com.gluview.app.data.datastore.AppPreferences
import com.gluview.app.data.local.entity.ReminderEntity
import com.gluview.app.data.reminder.ReminderKind
import com.gluview.app.service.ReminderActionReceiver
import com.gluview.app.util.withAppLanguage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.text.DecimalFormat
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Notificação dos lembretes (F2.2) — canal próprio, separado dos alertas de glicemia, para a
 * pessoa poder silenciar um sem perder o outro. Botões: Feito, Adiar 10 min, Adiar 30 min, Pular.
 */
@Singleton
class ReminderNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: AppPreferences
) {
    private val manager get() = context.getSystemService(NotificationManager::class.java)

    fun createChannels(res: Context = context) {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_REMINDER, res.getString(R.string.notif_channel_reminder_name), NotificationManager.IMPORTANCE_HIGH)
                .apply { description = res.getString(R.string.notif_channel_reminder_desc) }
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_DIARY_PROMPT, res.getString(R.string.notif_channel_diary_prompt_name), NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = res.getString(R.string.notif_channel_diary_prompt_desc) }
        )
    }

    private suspend fun localized(): Context = context.withAppLanguage(preferences.appLanguage.first())

    suspend fun show(r: ReminderEntity, scheduledAt: Long, linkedName: String) {
        val res = localized()
        createChannels(res)
        val kind = ReminderKind.of(r.kind)
        val dose = if (r.dose > 0) DecimalFormat("0.##").format(r.dose) else ""
        val body = when (kind) {
            ReminderKind.MEASURE -> res.getString(R.string.reminder_notif_measure)
            ReminderKind.INSULIN -> if (linkedName.isNotBlank() && dose.isNotBlank())
                res.getString(R.string.reminder_notif_insulin_dose, linkedName, dose) else res.getString(R.string.reminder_notif_insulin)
            ReminderKind.MEDICATION -> if (linkedName.isNotBlank())
                res.getString(R.string.reminder_notif_medication_named, linkedName) else res.getString(R.string.reminder_notif_medication)
            ReminderKind.BASAL_CHECK -> res.getString(R.string.reminder_notif_basal_check)
            ReminderKind.CUSTOM -> res.getString(R.string.reminder_notif_custom)
        }
        val canRegister = r.dose > 0 && r.linkedUuid.isNotBlank() &&
            kind in setOf(ReminderKind.INSULIN, ReminderKind.MEDICATION, ReminderKind.BASAL_CHECK)
        val diaryType = when (kind) {
            ReminderKind.MEASURE -> "fingerstick"
            ReminderKind.INSULIN, ReminderKind.BASAL_CHECK -> "insulin"
            ReminderKind.MEDICATION -> "medication"
            ReminderKind.CUSTOM -> "note"
        }
        val open = PendingIntent.getActivity(
            context, requestCode(r.id, 3),
            Intent(Intent.ACTION_VIEW, Uri.parse("gluview://diary?type=$diaryType"), context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(context, CHANNEL_REMINDER)
            .setSmallIcon(R.drawable.ic_glucose)
            .setContentTitle(r.title.ifBlank { res.getString(R.string.reminders_title) })
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setWhen(scheduledAt)
            .setShowWhen(true)
            .setAutoCancel(true)
            .setContentIntent(open)
            // Até 3 botões cabem na notificação; descartar (arrastar para o lado) conta como "pular".
            .setDeleteIntent(action(r.id, scheduledAt, ReminderActionReceiver.ACTION_SKIP))
            .addAction(0, res.getString(if (canRegister) R.string.reminder_action_done_register else R.string.reminder_action_done),
                action(r.id, scheduledAt, ReminderActionReceiver.ACTION_DONE))
            .addAction(0, res.getString(R.string.reminder_action_snooze10), action(r.id, scheduledAt, ReminderActionReceiver.ACTION_SNOOZE_10))
            .addAction(0, res.getString(R.string.reminder_action_snooze30), action(r.id, scheduledAt, ReminderActionReceiver.ACTION_SNOOZE_30))
            .build()
        runCatching { manager.notify(notificationId(r.id), n) }
    }

    /** F2.4 — "subindo rápido sem refeição registrada". */
    suspend fun showMealPrompt() {
        val res = localized()
        createChannels(res)
        val open = PendingIntent.getActivity(
            context, 49_999,
            Intent(Intent.ACTION_VIEW, Uri.parse("gluview://diary?type=meal"), context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(context, CHANNEL_DIARY_PROMPT)
            .setSmallIcon(R.drawable.ic_glucose)
            .setContentTitle(res.getString(R.string.smart_meal_prompt_title))
            .setContentText(res.getString(R.string.smart_meal_prompt_body))
            .setStyle(NotificationCompat.BigTextStyle().bigText(res.getString(R.string.smart_meal_prompt_body)))
            .setAutoCancel(true)
            .setContentIntent(open)
            .addAction(0, res.getString(R.string.smart_meal_prompt_action), open)
            .build()
        runCatching { manager.notify(NOTIF_ID_MEAL_PROMPT, n) }
    }

    fun cancel(reminderId: Long) = manager.cancel(notificationId(reminderId))

    private fun action(id: Long, scheduledAt: Long, action: String): PendingIntent {
        val code = when (action) {
            ReminderActionReceiver.ACTION_DONE -> 0
            ReminderActionReceiver.ACTION_SNOOZE_10 -> 1
            ReminderActionReceiver.ACTION_SNOOZE_30 -> 2
            else -> 4
        }
        return PendingIntent.getBroadcast(
            context, requestCode(id, code),
            Intent(context, ReminderActionReceiver::class.java).setAction(action)
                .putExtra(ReminderActionReceiver.EXTRA_ID, id)
                .putExtra(ReminderActionReceiver.EXTRA_SCHEDULED_AT, scheduledAt),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun requestCode(id: Long, slot: Int): Int = (id * 8 + slot).toInt() + 60_000

    companion object {
        const val CHANNEL_REMINDER = "gluview_reminders"
        const val CHANNEL_DIARY_PROMPT = "gluview_diary_prompt"
        const val NOTIF_ID_MEAL_PROMPT = 4_000
        fun notificationId(reminderId: Long): Int = 5_000 + reminderId.toInt()
    }
}
