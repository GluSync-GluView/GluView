package com.gluview.app.data.diary

import com.gluview.app.data.local.entity.EventLogEntity
import org.json.JSONObject
import java.time.Instant

/**
 * Diário ↔ treatment no formato Nightscout (plano 17, F1.7; decisão D5). Puro, testável.
 *
 * Cada registro vira o evento Nightscout mais próximo (para seguidores e outros clientes verem algo
 * útil) e leva o objeto `gluview` com o registro inteiro, que é o que o GluView lê de volta — assim
 * nada se perde na ida e volta e só treatments do próprio GluView são importados.
 *
 * Insulina basal e mista NÃO vão no campo `insulin`: o Nightscout conta esse campo como bolus
 * (insulina ativa), o que mostraria um valor errado a quem acompanha. Vão como nota.
 */
object DiaryTreatmentMapper {
    const val ENTERED_BY = "GluView"
    const val GLUVIEW_KEY = "gluview"

    fun toTreatment(e: EventLogEntity, readableNote: String): JSONObject {
        val o = JSONObject()
            .put("created_at", Instant.ofEpochMilli(e.occurredAt).toString())
            .put("date", e.occurredAt)
            .put("enteredBy", ENTERED_BY)
            .put("identifier", e.uuid)
        val type = DiaryType.of(e.type)
        when (type) {
            DiaryType.MEAL -> o.put("eventType", "Carb Correction").put("carbs", e.value)
            DiaryType.INSULIN -> if (InsulinKind.of(e.subtype) == InsulinKind.RAPID)
                o.put("eventType", "Correction Bolus").put("insulin", e.value)
            else o.put("eventType", "Note")
            DiaryType.FINGERSTICK -> o.put("eventType", "BG Check").put("glucose", e.value)
                .put("glucoseType", "Finger").put("units", "mg/dl")
            DiaryType.EXERCISE -> o.put("eventType", "Exercise").put("duration", e.value)
            DiaryType.KETONE, DiaryType.MEDICATION, DiaryType.NOTE -> o.put("eventType", "Note")
        }
        if (readableNote.isNotBlank()) o.put("notes", readableNote)
        o.put(GLUVIEW_KEY, JSONObject()
            .put("v", 1)
            .put("uuid", e.uuid)
            .put("type", e.type)
            .put("occurredAt", e.occurredAt)
            .put("updatedAt", e.updatedAt)
            .put("value", e.value)
            .put("notes", e.notes)
            .put("sgvAtEvent", e.sgvAtEvent)
            .put("subtype", e.subtype)
            .put("itemName", e.itemName)
            .put("unit", e.unit)
            .put("kcal", e.kcal)
            .put("proteinG", e.proteinG)
            .put("fatG", e.fatG)
            .put("itemsJson", e.itemsJson)
            .put("source", e.source))
        return o
    }

    /** Treatment do GluView → registro local; null se não veio do GluView (ou veio quebrado). */
    fun fromTreatment(t: JSONObject, owner: String): EventLogEntity? {
        val g = t.optJSONObject(GLUVIEW_KEY) ?: return null
        val uuid = g.optString("uuid").takeIf { it.isNotBlank() } ?: return null
        val type = g.optString("type").takeIf { k -> DiaryType.entries.any { it.key == k } } ?: return null
        val remoteId = t.optString("_id").takeIf { it.isNotBlank() }
        return EventLogEntity(
            occurredAt = g.optLong("occurredAt", t.optLong("date", 0L)),
            type = type,
            notes = g.optString("notes"),
            value = g.optDouble("value", 0.0),
            sgvAtEvent = g.optDouble("sgvAtEvent", 0.0),
            uuid = uuid,
            subtype = g.optString("subtype"),
            itemName = g.optString("itemName"),
            unit = g.optString("unit"),
            kcal = g.optDouble("kcal", 0.0),
            proteinG = g.optDouble("proteinG", 0.0),
            fatG = g.optDouble("fatG", 0.0),
            itemsJson = g.optString("itemsJson"),
            source = EventLogEntity.SOURCE_CLOUD,
            ownerEmail = owner,
            updatedAt = g.optLong("updatedAt", 0L),
            remoteId = remoteId,
            dirty = false
        ).takeIf { it.occurredAt > 0 }
    }
}
