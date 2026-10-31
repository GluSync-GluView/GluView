package com.gluview.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.gluview.app.data.local.entity.FingerstickLogEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FingerstickLogDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: FingerstickLogEntity)

    @Query("SELECT * FROM fingerstick_log ORDER BY ts DESC LIMIT 10")
    fun observeRecent(): Flow<List<FingerstickLogEntity>>

    @Query("SELECT * FROM fingerstick_log ORDER BY ts DESC LIMIT :n")
    suspend fun getRecent(n: Int): List<FingerstickLogEntity>
}
