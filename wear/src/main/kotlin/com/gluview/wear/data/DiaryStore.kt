package com.gluview.wear.data

import android.content.ComponentName
import android.content.Context
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import com.gluview.wear.complication.NextReminderComplicationService
import com.gluview.wear.contract.WearDayInfo
import com.gluview.wear.contract.WearDiaryEntry
import com.gluview.wear.contract.WearPaths
import com.gluview.wear.tile.GlucoseTileService
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.tasks.await

/**
 * Diário no relógio (plano 17, Fase 4): o resumo do dia que veio do celular ([WearDayInfo]) e a fila
 * dos registros feitos aqui que o celular ainda não confirmou.
 *
 * A fila é o próprio Data Layer: cada registro vira um DataItem em `DIARY_ENTRY/<uuid>`, que o
 * Google Play Services guarda e entrega quando o celular estiver ao alcance. Aqui só se guarda
 * quais uuids ainda esperam a confirmação, para mostrar "aguardando o celular".
 */
object DiaryStore {
    private const val PREFS_DAY = "gluview_day"
    private const val PREFS_PENDING = "gluview_diary_pending"

    private val _day = MutableStateFlow<WearDayInfo?>(null)
    private val _pending = MutableStateFlow(0)
    @Volatile private var loaded = false

    fun day(context: Context): StateFlow<WearDayInfo?> { ensureLoaded(context); return _day }
    fun pendingCount(context: Context): StateFlow<Int> { ensureLoaded(context); return _pending }
    fun loadDay(context: Context): WearDayInfo? { ensureLoaded(context); return _day.value }

    @Synchronized
    private fun ensureLoaded(context: Context) {
        if (loaded) return
        val app = context.applicationContext
        val all = app.getSharedPreferences(PREFS_DAY, Context.MODE_PRIVATE).all
        _day.value = if (all.isEmpty()) null else WearDayInfo.fromMap(all)
        _pending.value = app.getSharedPreferences(PREFS_PENDING, Context.MODE_PRIVATE).all.size
        loaded = true
    }

    @Synchronized
    fun saveDay(context: Context, info: WearDayInfo) {
        val app = context.applicationContext
        app.getSharedPreferences(PREFS_DAY, Context.MODE_PRIVATE).edit().apply {
            clear()
            info.toMap().forEach { (k, v) ->
                when (v) {
                    is Int -> putInt(k, v)
                    is Long -> putLong(k, v)
                    is String -> putString(k, v)
                }
            }
        }.commit()
        _day.value = info
        loaded = true
        refreshSurfaces(app)
    }

    /** Põe o registro na fila do Data Layer (urgente: vai na hora se o celular estiver perto). */
    suspend fun send(context: Context, entry: WearDiaryEntry) {
        val app = context.applicationContext
        app.getSharedPreferences(PREFS_PENDING, Context.MODE_PRIVATE).edit().putLong(entry.uuid, entry.occurredAtMs).commit()
        ensureLoaded(app)
        _pending.value = app.getSharedPreferences(PREFS_PENDING, Context.MODE_PRIVATE).all.size
        val request = PutDataMapRequest.create("${WearPaths.DIARY_ENTRY}/${entry.uuid}").apply {
            entry.toMap().forEach { (k, v) ->
                when (v) {
                    is Int -> dataMap.putInt(k, v)
                    is Long -> dataMap.putLong(k, v)
                    is String -> dataMap.putString(k, v)
                }
            }
        }.asPutDataRequest().setUrgent()
        Wearable.getDataClient(app).putDataItem(request).await()
    }

    /** O celular gravou: sai da fila. */
    @Synchronized
    fun acked(context: Context, uuid: String) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS_PENDING, Context.MODE_PRIVATE)
        prefs.edit().remove(uuid).commit()
        _pending.value = prefs.all.size
    }

    fun refreshSurfaces(context: Context) {
        runCatching { TileService.getUpdater(context).requestUpdate(GlucoseTileService::class.java) }
        runCatching {
            ComplicationDataSourceUpdateRequester.create(
                context, ComponentName(context, NextReminderComplicationService::class.java)
            ).requestUpdateAll()
        }
    }
}
