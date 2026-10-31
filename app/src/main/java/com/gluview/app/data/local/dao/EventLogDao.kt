package com.gluview.app.data.local.dao

import androidx.room.*
import com.gluview.app.data.local.entity.EventLogEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface EventLogDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: EventLogEntity)

    @Delete
    suspend fun delete(event: EventLogEntity)

    @Query("SELECT * FROM event_log ORDER BY occurredAt DESC LIMIT 200")
    fun observeRecent(): Flow<List<EventLogEntity>>

    @Query("SELECT * FROM event_log WHERE occurredAt >= :fromMs ORDER BY occurredAt ASC")
    suspend fun getFrom(fromMs: Long): List<EventLogEntity>

    @Query("DELETE FROM event_log WHERE occurredAt < :cutoffMs")
    suspend fun deleteOlderThan(cutoffMs: Long)
}
