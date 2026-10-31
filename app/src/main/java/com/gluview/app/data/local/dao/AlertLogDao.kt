package com.gluview.app.data.local.dao

import androidx.room.*
import com.gluview.app.data.local.entity.AlertLogEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AlertLogDao {

    @Insert
    suspend fun insert(alert: AlertLogEntity)

    @Query("UPDATE alert_log SET dismissed = 1, dismissedAt = :at WHERE id = :id")
    suspend fun markDismissed(id: Long, at: Long = System.currentTimeMillis())

    /** Marca TODOS os alertas pendentes como dispensados — zera o badge. */
    @Query("UPDATE alert_log SET dismissed = 1, dismissedAt = :at WHERE dismissed = 0")
    suspend fun dismissAll(at: Long = System.currentTimeMillis())

    /** Dispensa todos os alertas pendentes de um "kind" (ex: auto-resolver o alerta de erro de gravação). */
    @Query("UPDATE alert_log SET dismissed = 1, dismissedAt = :at WHERE kind = :kind AND dismissed = 0")
    suspend fun dismissByKind(kind: String, at: Long = System.currentTimeMillis())

    /** Apaga todo o histórico de alertas. */
    @Query("DELETE FROM alert_log")
    suspend fun deleteAll()

    @Query("SELECT * FROM alert_log ORDER BY triggeredAt DESC LIMIT 100")
    fun observeRecent(): Flow<List<AlertLogEntity>>

    @Query("DELETE FROM alert_log WHERE triggeredAt < :cutoffMs")
    suspend fun deleteOlderThan(cutoffMs: Long)
}
