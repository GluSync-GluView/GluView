package com.gluview.app.wear

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.gluview.app.R
import com.gluview.app.data.datastore.AppPreferences
import com.gluview.app.data.diary.DiaryRepository
import com.gluview.app.data.diary.FoodRepository
import com.gluview.app.data.local.dao.AlertLogDao
import com.gluview.app.data.local.dao.ReminderDao
import com.gluview.app.data.local.entity.EventLogEntity
import com.gluview.app.data.reminder.ReminderKind
import com.gluview.app.data.reminder.ReminderRepository
import com.gluview.app.data.reminder.ReminderSchedule
import com.gluview.app.util.TimeFormat
import com.gluview.app.util.formatTime
import com.gluview.app.util.withAppLanguage
import com.gluview.wear.contract.WearDayInfo
import com.gluview.wear.contract.WearDiaryEntry
import com.gluview.wear.contract.WearPaths
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Diário e lembretes no relógio — lado do celular (plano 17, Fase 4).
 *
 * - [publishDayInfo]: manda ao relógio o resumo do dia, o próximo lembrete, os avisos de hoje e os
 *   cadastros (insulinas e favoritas) que os seletores do relógio usam.
 * - [receive]: grava no diário (no aparelho — GluView first) o que foi registrado no relógio.
 *
 * Melhor esforço: sem Play Services ou sem relógio, nada quebra no celular.
 */
@Singleton
class WearDiaryBridge @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: AppPreferences,
    private val diary: DiaryRepository,
    private val foods: FoodRepository,
    private val reminders: ReminderDao,
    private val alerts: AlertLogDao,
) {
    private val tag = "WearDiary"

    suspend fun buildDayInfo(now: Long = System.currentTimeMillis()): WearDayInfo {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val res = context.withAppLanguage(preferences.appLanguage.first())
        val tf = TimeFormat.fromString(preferences.timeFormat.first())
        val summary = diary.observeDaySummary(today, zone).first()
        val next = reminders.getEnabled()
            .mapNotNull { r -> ReminderSchedule.nextTrigger(r, now, zone)?.let { r to it } }
            .minByOrNull { it.second }
        val startOfDay = DiaryRepository.dayBounds(today, zone).first
        val notices = alerts.getFrom(startOfDay).map { a ->
            val what = when {
                a.kind != "glucose" -> a.title ?: res.getString(R.string.wear_notice_generic)
                else -> res.getString(R.string.wear_notice_glucose, zoneLabel(res, a.zone), Math.round(a.sgvMgDl).toString())
            }
            formatTime(a.triggeredAt, tf, zone) + " · " + what
        }
        return WearDayInfo(
            carbsG = summary.carbsG,
            rapidU = summary.rapidU + summary.mixedU,
            basalU = summary.basalU,
            nextReminderTitle = next?.first?.let { it.title.ifBlank { kindLabel(res, it.kind) } } ?: "",
            nextReminderAtMs = next?.second ?: 0L,
            notices = notices,
            insulins = diary.observeInsulins().first().map { WearDayInfo.WearItem(it.uuid, it.name, it.kind) },
            favorites = foods.observeFavorites().first().take(10).map {
                WearDayInfo.WearItem(it.uuid, it.name, Math.round(it.carbsG).toString())
            },
            updatedAtMs = now,
        )
    }

    suspend fun publishDayInfo() {
        runCatching {
            val info = buildDayInfo()
            val request = PutDataMapRequest.create(WearPaths.DAY_INFO).apply {
                info.toMap().forEach { (k, v) ->
                    when (v) {
                        is Int -> dataMap.putInt(k, v)
                        is Long -> dataMap.putLong(k, v)
                        is String -> dataMap.putString(k, v)
                    }
                }
            }.asPutDataRequest().setUrgent()
            Wearable.getDataClient(context).putDataItem(request).await()
        }.onFailure { Log.i(tag, "Resumo do dia não enviado ao relógio: ${it.message}") }
    }

    /** Registro vindo do relógio → diário do celular (sem duplicar: o uuid é o do relógio). */
    suspend fun receive(entry: WearDiaryEntry, sourceNode: String?, itemUri: Uri) {
        if (diary.getByUuid(entry.uuid) == null) {
            val res = context.withAppLanguage(preferences.appLanguage.first())
            diary.save(
                EventLogEntity(
                    uuid = entry.uuid, occurredAt = entry.occurredAtMs, type = entry.type, value = entry.value,
                    subtype = entry.subtype, itemName = entry.itemName, source = SOURCE_WATCH,
                    notes = res.getString(R.string.wear_from_watch), kcal = 0.0,
                )
            )
            if (entry.favoriteUuid.isNotBlank()) foods.markFavoriteUsed(entry.favoriteUuid)
        }
        runCatching { Wearable.getDataClient(context).deleteDataItems(itemUri).await() }
        if (sourceNode != null) runCatching {
            Wearable.getMessageClient(context).sendMessage(sourceNode, WearPaths.DIARY_ACK, entry.uuid.toByteArray()).await()
        }
        publishDayInfo()
    }

    companion object {
        const val SOURCE_WATCH = "watch"

        fun zoneLabel(res: Context, zone: String): String = res.getString(when (zone) {
            "URGENT_LOW" -> R.string.zone_urgent_low
            "LOW" -> R.string.zone_low
            "HIGH" -> R.string.zone_high
            "URGENT_HIGH" -> R.string.zone_urgent_high
            "QUICK_DROP" -> R.string.zone_quick_drop
            "STALE" -> R.string.zone_stale
            "PREDICTIVE" -> R.string.wear_notice_predictive
            else -> R.string.zone_in_range
        })

        fun kindLabel(res: Context, kind: String): String = res.getString(when (ReminderKind.of(kind)) {
            ReminderKind.MEASURE -> R.string.reminder_kind_measure
            ReminderKind.INSULIN -> R.string.reminder_kind_insulin
            ReminderKind.MEDICATION -> R.string.reminder_kind_medication
            ReminderKind.BASAL_CHECK -> R.string.reminder_kind_basal_check
            ReminderKind.CUSTOM -> R.string.reminder_kind_custom
        })
    }
}

/** Junta pedidos de "atualizar o relógio" em um envio só, alguns segundos depois. */
@Singleton
class WearDaySync @Inject constructor(@ApplicationContext private val context: Context) {
    fun request(delaySeconds: Long = 3) {
        runCatching {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "wear_day_info", ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<WearDayWorker>().setInitialDelay(delaySeconds, TimeUnit.SECONDS).build()
            )
        }
    }
}

class WearDayWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps { fun bridge(): WearDiaryBridge }

    override suspend fun doWork(): Result {
        EntryPointAccessors.fromApplication(applicationContext, Deps::class.java).bridge().publishDayInfo()
        return Result.success()
    }
}

/** Recebe do relógio os registros do diário (o Play Services acorda o app para isto). */
@AndroidEntryPoint
class WearDiaryListenerService : WearableListenerService() {
    @Inject lateinit var bridge: WearDiaryBridge

    override fun onDataChanged(events: DataEventBuffer) {
        val items = events.filter { it.type == DataEvent.TYPE_CHANGED && it.dataItem.uri.path?.startsWith(WearPaths.DIARY_ENTRY) == true }
            .map { Triple(DataMapItem.fromDataItem(it.dataItem).dataMap.toPlain(), it.dataItem.uri.host, it.dataItem.uri) }
        if (items.isEmpty()) return
        runBlocking {
            withTimeoutOrNull(20_000) {
                items.forEach { (map, node, uri) ->
                    val entry = WearDiaryEntry.fromMap(map)
                    if (entry == null) Log.w("WearDiary", "Registro do relógio recusado (formato inválido)")
                    else bridge.receive(entry, node, uri)
                }
            }
        }
    }
}

private fun DataMap.toPlain(): Map<String, Any?> = keySet().associateWith { key ->
    @Suppress("DEPRECATION")
    when (val v = get<Any?>(key)) {
        is Int, is Long, is Boolean, is String -> v
        else -> null
    }
}
