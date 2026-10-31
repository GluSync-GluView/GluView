package com.gluview.app.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.gluview.app.data.local.entity.WatchFaceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WatchFaceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(face: WatchFaceEntity): Long

    @Update
    suspend fun update(face: WatchFaceEntity)

    @Delete
    suspend fun delete(face: WatchFaceEntity)

    @Query("SELECT * FROM watch_faces ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<WatchFaceEntity>>

    @Query("SELECT * FROM watch_faces WHERE id = :id LIMIT 1")
    fun observeById(id: Long): Flow<WatchFaceEntity?>

    @Query("SELECT * FROM watch_faces WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): WatchFaceEntity?

    @Query("SELECT * FROM watch_faces WHERE serverId = :serverId LIMIT 1")
    suspend fun getByServerId(serverId: String): WatchFaceEntity?
}
