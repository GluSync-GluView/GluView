package com.gluview.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.gluview.app.data.local.entity.SensorHistoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SensorHistoryDao {

    @Insert
    suspend fun insert(entry: SensorHistoryEntity): Long

    /** Fecha qualquer período em aberto (novo sensor substitui o anterior, ou "esquecer"). */
    @Query("UPDATE sensor_history SET endedAt = :endedAt WHERE endedAt IS NULL")
    suspend fun endOpenEntries(endedAt: Long)

    @Query("SELECT * FROM sensor_history WHERE endedAt IS NULL ORDER BY startedAt DESC LIMIT 1")
    fun observeActive(): Flow<SensorHistoryEntity?>

    @Query("SELECT * FROM sensor_history ORDER BY startedAt DESC")
    fun observeAll(): Flow<List<SensorHistoryEntity>>

    // Retenção local: 3 meses. Nunca apaga o período ativo (endedAt NULL).
    @Query("DELETE FROM sensor_history WHERE startedAt < :cutoffMs AND endedAt IS NOT NULL")
    suspend fun deleteOlderThan(cutoffMs: Long)

    @Query("UPDATE sensor_history SET syncedToCloud = 1 WHERE id = :id")
    suspend fun markSynced(id: Long)

    @Query("SELECT * FROM sensor_history WHERE syncedToCloud = 0 ORDER BY startedAt ASC")
    suspend fun getUnsynced(): List<SensorHistoryEntity>

    /** Todos os períodos, inclusive os marcados como sincronizados — usado pela reconciliação, que
     *  precisa reenviar registros marcados como enviados por engano (ver
     *  SensorHistoryRepository.reconcileWithServer). */
    @Query("SELECT * FROM sensor_history ORDER BY startedAt ASC")
    suspend fun getAll(): List<SensorHistoryEntity>

    /** Atualiza o fator de calibração do período atualmente aberto (se houver). */
    @Query("UPDATE sensor_history SET calibrationFactor = :factor WHERE endedAt IS NULL")
    suspend fun updateActiveCalibration(factor: Double)
}
