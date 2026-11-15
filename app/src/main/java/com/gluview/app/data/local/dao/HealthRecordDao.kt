package com.gluview.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.gluview.app.data.local.entity.HealthRecordEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface HealthRecordDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(records: List<HealthRecordEntity>)

    @Query("SELECT * FROM health_record WHERE kind = :kind AND endMs >= :fromMs AND startMs < :toMs ORDER BY startMs ASC")
    suspend fun getBetween(kind: String, fromMs: Long, toMs: Long): List<HealthRecordEntity>

    @Query("SELECT * FROM health_record WHERE kind = :kind AND endMs >= :fromMs ORDER BY startMs ASC")
    fun observeFrom(kind: String, fromMs: Long): Flow<List<HealthRecordEntity>>

    @Query("SELECT * FROM health_record WHERE kind = :kind AND startMs >= :fromMs AND startMs < :toMs ORDER BY startMs ASC")
    fun observeStartingBetween(kind: String, fromMs: Long, toMs: Long): Flow<List<HealthRecordEntity>>

    @Query("SELECT hcId FROM health_record WHERE kind = :kind AND startMs >= :fromMs AND startMs < :toMs")
    suspend fun idsStartingBetween(kind: String, fromMs: Long, toMs: Long): List<String>

    @Query("DELETE FROM health_record WHERE hcId IN (:ids)")
    suspend fun deleteIds(ids: List<String>)

    @Query("DELETE FROM health_record")
    suspend fun deleteAll()
}
