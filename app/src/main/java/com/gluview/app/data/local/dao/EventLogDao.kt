package com.gluview.app.data.local.dao

import androidx.room.*
import com.gluview.app.data.local.entity.EventLogEntity
import kotlinx.coroutines.flow.Flow

/**
 * Diário (plano 17). Toda leitura filtra pela conta dona ([EventLogEntity.ownerEmail]) e esconde os
 * apagados que só esperam a nuvem ([EventLogEntity.deleted]).
 */
@Dao
interface EventLogDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: EventLogEntity): Long

    @Update
    suspend fun update(event: EventLogEntity)

    @Delete
    suspend fun delete(event: EventLogEntity)

    @Query("SELECT * FROM event_log WHERE uuid = :uuid LIMIT 1")
    suspend fun getByUuid(uuid: String): EventLogEntity?

    @Query("SELECT * FROM event_log WHERE ownerEmail = :owner AND deleted = 0 ORDER BY occurredAt DESC LIMIT 200")
    fun observeRecent(owner: String): Flow<List<EventLogEntity>>

    @Query("""SELECT * FROM event_log WHERE ownerEmail = :owner AND deleted = 0
              AND occurredAt >= :fromMs AND occurredAt < :toMs ORDER BY occurredAt DESC""")
    fun observeBetween(owner: String, fromMs: Long, toMs: Long): Flow<List<EventLogEntity>>

    @Query("""SELECT * FROM event_log WHERE ownerEmail = :owner AND deleted = 0
              AND occurredAt >= :fromMs AND occurredAt < :toMs ORDER BY occurredAt ASC""")
    suspend fun getBetween(owner: String, fromMs: Long, toMs: Long): List<EventLogEntity>

    @Query("SELECT * FROM event_log WHERE ownerEmail = :owner AND deleted = 0 AND occurredAt >= :fromMs ORDER BY occurredAt ASC")
    fun observeFrom(owner: String, fromMs: Long): Flow<List<EventLogEntity>>

    @Query("SELECT * FROM event_log WHERE ownerEmail = :owner AND deleted = 0 ORDER BY occurredAt ASC")
    suspend fun getAll(owner: String): List<EventLogEntity>

    /** Pendentes de envio à nuvem (inclui os apagados que já tinham ido). */
    @Query("SELECT * FROM event_log WHERE ownerEmail = :owner AND dirty = 1 ORDER BY occurredAt ASC LIMIT :limit")
    suspend fun getDirty(owner: String, limit: Int = 200): List<EventLogEntity>

    @Query("SELECT * FROM event_log WHERE ownerEmail = :owner AND remoteId IS NOT NULL AND deleted = 0 AND occurredAt >= :fromMs")
    suspend fun getSyncedFrom(owner: String, fromMs: Long): List<EventLogEntity>

    @Query("UPDATE event_log SET ownerEmail = :owner WHERE ownerEmail = ''")
    suspend fun claimUnownedRows(owner: String)

    @Query("DELETE FROM event_log WHERE id = :id")
    suspend fun hardDelete(id: Long)

    /** Insulina basal registrada no intervalo? (lembrete "basal ainda não registrada", F2.4) */
    @Query("""SELECT COUNT(*) FROM event_log WHERE ownerEmail = :owner AND deleted = 0 AND type = 'insulin'
              AND subtype = 'basal' AND occurredAt >= :fromMs AND occurredAt < :toMs""")
    suspend fun countBasalBetween(owner: String, fromMs: Long, toMs: Long): Int

    /** Refeição registrada no intervalo? (aviso "subindo sem refeição", F2.4) */
    @Query("""SELECT COUNT(*) FROM event_log WHERE ownerEmail = :owner AND deleted = 0 AND type = 'meal'
              AND occurredAt >= :fromMs AND occurredAt < :toMs""")
    suspend fun countMealsBetween(owner: String, fromMs: Long, toMs: Long): Int
}
