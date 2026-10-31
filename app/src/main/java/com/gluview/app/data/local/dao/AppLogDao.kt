package com.gluview.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.gluview.app.data.local.entity.AppLogEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AppLogDao {

    @Insert
    suspend fun insert(entry: AppLogEntity)

    /** Logs mais recentes para a tela de diagnóstico (T08). */
    @Query("SELECT * FROM app_log ORDER BY ts DESC LIMIT :limit")
    fun observeRecent(limit: Int = 500): Flow<List<AppLogEntity>>

    /**
     * Logs de um dia específico (dentro da retenção), opcionalmente só erros — tela de logs (T08).
     * LIMIT alto só como trava de segurança: um dia normal já passa de 6-8 mil linhas (SIBIONICS_BLE
     * grava cada pacote), então um limite baixo cortaria justamente o início do dia (ORDER BY DESC).
     */
    @Query("""
        SELECT * FROM app_log
        WHERE ts BETWEEN :startMs AND :endMs
          AND (:errorsOnly = 0 OR level = 'error')
        ORDER BY ts DESC
        LIMIT :limit
    """)
    fun observeByRange(startMs: Long, endMs: Long, errorsOnly: Boolean, limit: Int = 20000): Flow<List<AppLogEntity>>

    /** F1/T05 — retenção: apaga logs mais antigos que o corte (7 dias). */
    @Query("DELETE FROM app_log WHERE ts < :cutoffMs")
    suspend fun deleteOlderThan(cutoffMs: Long)

    @Query("DELETE FROM app_log")
    suspend fun clear()
}
