package com.gluview.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Um período de uso de um sensor. `endedAt == null` = sensor atualmente ativo.
 *  Retenção local: 3 meses (ver SensorHistoryDao.deleteOlderThan). */
@Entity(tableName = "sensor_history")
data class SensorHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mac: String,
    val serial: String?,
    val customerId: String?,
    val source: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    val syncedToCloud: Boolean = false,
    val calibrationFactor: Double? = null
)
