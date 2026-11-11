package com.gluview.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.gluview.app.data.local.entity.ReminderEntity
import com.gluview.app.data.local.entity.ReminderLogEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ReminderDao {

    @Query("SELECT * FROM reminder ORDER BY enabled DESC, title")
    fun observeAll(): Flow<List<ReminderEntity>>

    @Query("SELECT * FROM reminder")
    suspend fun getAll(): List<ReminderEntity>

    @Query("SELECT * FROM reminder WHERE enabled = 1")
    suspend fun getEnabled(): List<ReminderEntity>

    @Query("SELECT * FROM reminder WHERE id = :id LIMIT 1")
    suspend fun get(id: Long): ReminderEntity?

    @Query("SELECT * FROM reminder WHERE uuid = :uuid LIMIT 1")
    suspend fun getByUuid(uuid: String): ReminderEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(reminder: ReminderEntity): Long

    @Query("DELETE FROM reminder WHERE id = :id")
    suspend fun delete(id: Long)

    @Insert
    suspend fun log(entry: ReminderLogEntity)

    @Query("SELECT * FROM reminder_log WHERE at >= :fromMs ORDER BY at DESC")
    fun observeLogFrom(fromMs: Long): Flow<List<ReminderLogEntity>>

    @Query("DELETE FROM reminder_log WHERE at < :cutoffMs")
    suspend fun pruneLog(cutoffMs: Long)
}
