package com.gluview.app.data.diary

import com.gluview.app.data.datastore.AppPreferences
import com.gluview.app.data.local.dao.DiaryCatalogDao
import com.gluview.app.data.local.dao.EventLogDao
import com.gluview.app.data.local.dao.ReminderDao
import com.gluview.app.data.local.entity.EventLogEntity
import com.gluview.app.data.local.entity.FoodEntity
import com.gluview.app.data.local.entity.FoodUsageEntity
import com.gluview.app.data.local.entity.InsulinEntity
import com.gluview.app.data.local.entity.MealFavoriteEntity
import com.gluview.app.data.local.entity.MedicationEntity
import com.gluview.app.data.local.entity.ReminderEntity
import com.gluview.app.data.reminder.ReminderScheduler
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Diário, cadastros e lembretes no arquivo de backup (plano 17, F1.7 e F2.5). Vai como a chave
 * "diary" dentro do mesmo JSON das preferências — versões antigas do app só ignoram a chave.
 * Restaurar junta pelo uuid: não duplica e não apaga o que já existe no aparelho.
 */
@Singleton
class DiaryBackup @Inject constructor(
    private val events: EventLogDao,
    private val catalog: DiaryCatalogDao,
    private val reminders: ReminderDao,
    private val scheduler: ReminderScheduler,
    private val preferences: AppPreferences
) {
    suspend fun export(): JSONObject {
        val owner = preferences.userEmail.first()
        return JSONObject()
            .put("version", 1)
            .put("entries", JSONArray().apply { events.getAll(owner).forEach { put(entryJson(it)) } })
            .put("insulins", JSONArray().apply { catalog.getAllInsulins().forEach { put(JSONObject()
                .put("uuid", it.uuid).put("name", it.name).put("kind", it.kind).put("active", it.active).put("createdAt", it.createdAt)) } })
            .put("medications", JSONArray().apply { catalog.getAllMedications().forEach { put(JSONObject()
                .put("uuid", it.uuid).put("name", it.name).put("defaultDose", it.defaultDose).put("unit", it.unit)
                .put("active", it.active).put("createdAt", it.createdAt)) } })
            .put("foods", JSONArray().apply { catalog.getAllFoods().forEach { put(JSONObject()
                .put("uuid", it.uuid).put("name", it.name).put("carbsPer100", it.carbsPer100).put("kcalPer100", it.kcalPer100)
                .put("proteinPer100", it.proteinPer100).put("fatPer100", it.fatPer100).put("portionsJson", it.portionsJson)
                .put("createdAt", it.createdAt)) } })
            .put("favorites", JSONArray().apply { catalog.getAllFavorites().forEach { put(JSONObject()
                .put("uuid", it.uuid).put("name", it.name).put("itemsJson", it.itemsJson).put("carbsG", it.carbsG)
                .put("kcal", it.kcal).put("useCount", it.useCount).put("lastUsedAt", it.lastUsedAt).put("createdAt", it.createdAt)) } })
            .put("reminders", JSONArray().apply { reminders.getAll().forEach { put(JSONObject()
                .put("uuid", it.uuid).put("title", it.title).put("kind", it.kind).put("timesCsv", it.timesCsv)
                .put("daysMask", it.daysMask).put("enabled", it.enabled).put("linkedUuid", it.linkedUuid).put("dose", it.dose)
                .put("createdAt", it.createdAt).put("updatedAt", it.updatedAt)) } })
    }

    /** Devolve quantos registros do diário entraram ou foram atualizados. */
    suspend fun import(o: JSONObject, now: Long = System.currentTimeMillis()): Int {
        val owner = preferences.userEmail.first()
        var count = 0
        o.optJSONArray("entries")?.forEachObject { j ->
            val incoming = entryFrom(j, owner) ?: return@forEachObject
            val local = events.getByUuid(incoming.uuid)
            when {
                local == null -> { events.insert(incoming); count++ }
                incoming.updatedAt > local.updatedAt -> {
                    events.update(incoming.copy(id = local.id, remoteId = local.remoteId, ownerEmail = local.ownerEmail)); count++
                }
            }
        }
        o.optJSONArray("insulins")?.forEachObject { j ->
            if (catalog.getInsulin(j.getString("uuid")) == null) catalog.upsertInsulin(InsulinEntity(
                uuid = j.getString("uuid"), name = j.getString("name"), kind = j.optString("kind", "rapid"),
                active = j.optBoolean("active", true), createdAt = j.optLong("createdAt", now)))
        }
        o.optJSONArray("medications")?.forEachObject { j ->
            if (catalog.getMedication(j.getString("uuid")) == null) catalog.upsertMedication(MedicationEntity(
                uuid = j.getString("uuid"), name = j.getString("name"), defaultDose = j.optDouble("defaultDose", 0.0),
                unit = j.optString("unit"), active = j.optBoolean("active", true), createdAt = j.optLong("createdAt", now)))
        }
        o.optJSONArray("foods")?.forEachObject { j ->
            if (catalog.getFood(j.getString("uuid")) == null) catalog.upsertFood(FoodEntity(
                uuid = j.getString("uuid"), name = j.getString("name"), carbsPer100 = j.optDouble("carbsPer100", 0.0),
                kcalPer100 = j.optDouble("kcalPer100", 0.0), proteinPer100 = j.optDouble("proteinPer100", 0.0),
                fatPer100 = j.optDouble("fatPer100", 0.0), portionsJson = j.optString("portionsJson", "[]"),
                createdAt = j.optLong("createdAt", now)))
        }
        o.optJSONArray("favorites")?.forEachObject { j ->
            if (catalog.getFavorite(j.getString("uuid")) == null) catalog.upsertFavorite(MealFavoriteEntity(
                uuid = j.getString("uuid"), name = j.getString("name"), itemsJson = j.optString("itemsJson"),
                carbsG = j.optDouble("carbsG", 0.0), kcal = j.optDouble("kcal", 0.0), useCount = j.optInt("useCount", 0),
                lastUsedAt = j.optLong("lastUsedAt", 0L), createdAt = j.optLong("createdAt", now)))
        }
        o.optJSONArray("reminders")?.forEachObject { j ->
            val local = reminders.getByUuid(j.getString("uuid"))
            val r = ReminderEntity(
                id = local?.id ?: 0, uuid = j.getString("uuid"), title = j.optString("title"), kind = j.optString("kind", "custom"),
                timesCsv = j.optString("timesCsv"), daysMask = j.optInt("daysMask", 127), enabled = j.optBoolean("enabled", true),
                linkedUuid = j.optString("linkedUuid"), dose = j.optDouble("dose", 0.0),
                createdAt = j.optLong("createdAt", now), updatedAt = j.optLong("updatedAt", now))
            if (local == null || r.updatedAt > local.updatedAt) {
                val id = reminders.upsert(r)
                scheduler.schedule(r.copy(id = if (r.id == 0L) id else r.id), now)
            }
        }
        return count
    }

    companion object {
        fun entryJson(e: EventLogEntity): JSONObject = JSONObject()
            .put("uuid", e.uuid).put("occurredAt", e.occurredAt).put("type", e.type).put("notes", e.notes)
            .put("value", e.value).put("sgvAtEvent", e.sgvAtEvent).put("subtype", e.subtype).put("itemName", e.itemName)
            .put("unit", e.unit).put("kcal", e.kcal).put("proteinG", e.proteinG).put("fatG", e.fatG)
            .put("itemsJson", e.itemsJson).put("source", e.source).put("updatedAt", e.updatedAt)

        fun entryFrom(j: JSONObject, owner: String): EventLogEntity? {
            val uuid = j.optString("uuid").takeIf { it.isNotBlank() } ?: return null
            val type = j.optString("type").takeIf { k -> DiaryType.entries.any { it.key == k } } ?: return null
            return EventLogEntity(
                occurredAt = j.optLong("occurredAt", 0L), type = type, notes = j.optString("notes"),
                value = j.optDouble("value", 0.0), sgvAtEvent = j.optDouble("sgvAtEvent", 0.0), uuid = uuid,
                subtype = j.optString("subtype"), itemName = j.optString("itemName"), unit = j.optString("unit"),
                kcal = j.optDouble("kcal", 0.0), proteinG = j.optDouble("proteinG", 0.0), fatG = j.optDouble("fatG", 0.0),
                itemsJson = j.optString("itemsJson"), source = j.optString("source", EventLogEntity.SOURCE_BACKUP),
                ownerEmail = owner, updatedAt = j.optLong("updatedAt", 0L), dirty = true
            ).takeIf { it.occurredAt > 0 }
        }

        private inline fun JSONArray.forEachObject(block: (JSONObject) -> Unit) {
            for (i in 0 until length()) optJSONObject(i)?.let(block)
        }
    }
}
