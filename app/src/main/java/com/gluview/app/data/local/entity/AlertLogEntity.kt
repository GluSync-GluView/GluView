package com.gluview.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "alert_log")
data class AlertLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val triggeredAt: Long = System.currentTimeMillis(),
    val sgvMgDl: Double = 0.0,
    val zone: String = "",
    val alertType: String = "notification",   // "notification" | "alarm" | "dnd"
    val dismissed: Boolean = false,
    val dismissedAt: Long? = null,
    // "glucose" (default, usa sgvMgDl/zone acima) | "caregiver_invite" | "caregiver_accepted"
    val kind: String = "glucose",
    val title: String? = null,
    val message: String? = null
)
