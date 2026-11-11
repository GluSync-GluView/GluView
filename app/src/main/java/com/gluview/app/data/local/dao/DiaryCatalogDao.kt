package com.gluview.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.gluview.app.data.local.entity.FoodEntity
import com.gluview.app.data.local.entity.FoodUsageEntity
import com.gluview.app.data.local.entity.InsulinEntity
import com.gluview.app.data.local.entity.MealFavoriteEntity
import com.gluview.app.data.local.entity.MedicationEntity
import kotlinx.coroutines.flow.Flow

/** Cadastros do diário: insulinas, remédios, alimentos próprios, uso dos alimentos e favoritas. */
@Dao
interface DiaryCatalogDao {

    // ── Insulinas ──
    @Query("SELECT * FROM diary_insulin WHERE active = 1 ORDER BY kind, name")
    fun observeInsulins(): Flow<List<InsulinEntity>>

    @Query("SELECT * FROM diary_insulin ORDER BY kind, name")
    suspend fun getAllInsulins(): List<InsulinEntity>

    @Query("SELECT * FROM diary_insulin WHERE uuid = :uuid LIMIT 1")
    suspend fun getInsulin(uuid: String): InsulinEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertInsulin(insulin: InsulinEntity): Long

    @Query("UPDATE diary_insulin SET active = 0 WHERE uuid = :uuid")
    suspend fun archiveInsulin(uuid: String)

    // ── Remédios ──
    @Query("SELECT * FROM diary_medication WHERE active = 1 ORDER BY name")
    fun observeMedications(): Flow<List<MedicationEntity>>

    @Query("SELECT * FROM diary_medication ORDER BY name")
    suspend fun getAllMedications(): List<MedicationEntity>

    @Query("SELECT * FROM diary_medication WHERE uuid = :uuid LIMIT 1")
    suspend fun getMedication(uuid: String): MedicationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMedication(medication: MedicationEntity): Long

    @Query("UPDATE diary_medication SET active = 0 WHERE uuid = :uuid")
    suspend fun archiveMedication(uuid: String)

    // ── Alimentos próprios ──
    @Query("SELECT * FROM diary_food ORDER BY name")
    fun observeFoods(): Flow<List<FoodEntity>>

    @Query("SELECT * FROM diary_food ORDER BY name")
    suspend fun getAllFoods(): List<FoodEntity>

    @Query("SELECT * FROM diary_food WHERE uuid = :uuid LIMIT 1")
    suspend fun getFood(uuid: String): FoodEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFood(food: FoodEntity): Long

    @Query("DELETE FROM diary_food WHERE uuid = :uuid")
    suspend fun deleteFood(uuid: String)

    // ── Uso ──
    @Query("SELECT * FROM diary_food_usage")
    fun observeUsage(): Flow<List<FoodUsageEntity>>

    @Query("SELECT * FROM diary_food_usage WHERE foodKey = :key LIMIT 1")
    suspend fun getUsage(key: String): FoodUsageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertUsage(usage: FoodUsageEntity)

    // ── Favoritas ──
    @Query("SELECT * FROM diary_meal_favorite ORDER BY useCount DESC, lastUsedAt DESC, name")
    fun observeFavorites(): Flow<List<MealFavoriteEntity>>

    @Query("SELECT * FROM diary_meal_favorite ORDER BY name")
    suspend fun getAllFavorites(): List<MealFavoriteEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFavorite(favorite: MealFavoriteEntity): Long

    @Update
    suspend fun updateFavorite(favorite: MealFavoriteEntity)

    @Query("DELETE FROM diary_meal_favorite WHERE uuid = :uuid")
    suspend fun deleteFavorite(uuid: String)

    @Query("SELECT * FROM diary_meal_favorite WHERE uuid = :uuid LIMIT 1")
    suspend fun getFavorite(uuid: String): MealFavoriteEntity?
}
