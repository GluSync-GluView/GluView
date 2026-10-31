package com.gluview.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "fingerstick_log")
data class FingerstickLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts: Long,
    val fingerstickMgDl: Double,
    val sensorMgDl: Double,
    val diff: Double,      // fingerstick - sensor
    val diffPct: Double    // (diff / fingerstick) × 100
)
