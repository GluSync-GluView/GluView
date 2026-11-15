package com.gluview.wear.complication

import android.app.PendingIntent
import android.content.Context
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationText
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import com.gluview.wear.MainActivity
import com.gluview.wear.R
import com.gluview.wear.data.DiaryStore
import java.text.DateFormat
import java.util.Date

/**
 * Complicação "próximo lembrete" (plano 17, F4.4): a hora do próximo lembrete do GluView em
 * qualquer mostrador. Sem lembrete agendado (ou com o resumo de outro dia), mostra "--".
 */
class NextReminderComplicationService : SuspendingComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        build(this, type, "22:00", getString(R.string.preview_reminder))

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val now = System.currentTimeMillis()
        val day = DiaryStore.loadDay(this)
        val next = day?.takeIf { it.nextReminderAtMs > now }
        val time = next?.let { DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it.nextReminderAtMs)) } ?: "--"
        return build(this, request.complicationType, time, next?.nextReminderTitle ?: getString(R.string.no_reminder))
    }

    companion object {
        private fun text(s: String): ComplicationText = PlainComplicationText.Builder(s).build()

        fun build(context: Context, type: ComplicationType, time: String, title: String): ComplicationData? {
            val tap: PendingIntent = MainActivity.pendingIntent(context)
            val description = text("${context.getString(R.string.next_reminder)}: $time $title")
            return when (type) {
                ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(text(time), description)
                    .setTitle(text(context.getString(R.string.reminder_short))).setTapAction(tap).build()
                ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(text("$time · $title"), description)
                    .setTitle(text(context.getString(R.string.next_reminder))).setTapAction(tap).build()
                else -> null
            }
        }

    }
}
