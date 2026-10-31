package com.gluview.app.data.local.dao

import androidx.room.*
import com.gluview.app.data.local.entity.DayStats
import com.gluview.app.data.local.entity.GlucoseEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface GlucoseDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReading(reading: GlucoseEntity)

    /** Insere sem sobrescrever — para backfill BLE onde o dado existente tem precedência. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfNotExists(reading: GlucoseEntity)

    // Isolamento por conta (ver GlucoseEntity.ownerEmail) — toda leitura filtra pela conta
    // logada no momento; a gravação (insertReading/insertIfNotExists) já vem com o ownerEmail
    // certo no próprio GlucoseEntity, não precisa de parâmetro extra aqui.
    @Query("SELECT * FROM glucose_readings WHERE ownerEmail = :owner ORDER BY dateMs DESC LIMIT 1")
    fun observeLatest(owner: String): Flow<GlucoseEntity?>

    @Query("SELECT * FROM glucose_readings WHERE ownerEmail = :owner ORDER BY dateMs DESC LIMIT 1")
    suspend fun getLatest(owner: String): GlucoseEntity?

    @Query("SELECT * FROM glucose_readings WHERE ownerEmail = :owner AND dateMs >= :fromMs ORDER BY dateMs ASC")
    suspend fun getFrom(owner: String, fromMs: Long): List<GlucoseEntity>

    @Query("SELECT * FROM glucose_readings WHERE ownerEmail = :owner AND dateMs >= :fromMs ORDER BY dateMs ASC")
    fun observeFrom(owner: String, fromMs: Long): Flow<List<GlucoseEntity>>

    @Query("SELECT * FROM glucose_readings WHERE ownerEmail = :owner AND dateMs >= :fromMs AND dateMs < :toMs ORDER BY dateMs ASC")
    suspend fun getBetween(owner: String, fromMs: Long, toMs: Long): List<GlucoseEntity>

    // Retenção local: apaga pela DATA DA MEDIÇÃO (dateMs), não pelo fetchedAt — assim
    // backfill de leituras antigas não estoura a janela local. Hard delete.
    // Global (não filtra por conta) — retenção é preferência de aparelho (AppPreferences.
    // dataRetentionDays), não de conta; não tem relação com o isolamento acima.
    @Query("DELETE FROM glucose_readings WHERE dateMs < :cutoffMs")
    suspend fun deleteOlderThan(cutoffMs: Long)

    // S27 — estatísticas do dia (min, max, avg, contagem) desde :startMs
    @Query("SELECT MIN(sgvMgDl) AS min_sgv, MAX(sgvMgDl) AS max_sgv, AVG(sgvMgDl) AS avg_sgv, COUNT(*) AS count FROM glucose_readings WHERE ownerEmail = :owner AND dateMs >= :startMs")
    fun observeDayStats(owner: String, startMs: Long): Flow<DayStats>

    // Migration 12→13: linhas gravadas antes do isolamento por conta nascem com ownerEmail="".
    // A 1ª conta que logar depois da atualização reclama esse histórico legado — idempotente,
    // não sobra nenhuma linha "" pra reclamar de novo depois da 1ª vez.
    @Query("UPDATE glucose_readings SET ownerEmail = :owner WHERE ownerEmail = ''")
    suspend fun claimUnownedRows(owner: String)
}
