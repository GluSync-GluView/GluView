package com.gluview.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "event_log")
data class EventLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val occurredAt: Long   = System.currentTimeMillis(),
    val type: String,       // "meal" | "insulin" | "exercise" | "note"
    val notes: String      = "",
    val value: Double      = 0.0,   // carbs (g), insulina (U), duração (min), 0 p/ nota
    val sgvAtEvent: Double = 0.0    // glicemia no momento do registro
)
