package com.gluview.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/** "Minhas insulinas" (plano 17, F1.2): nome e tipo, para registrar só a dose. */
@Entity(tableName = "diary_insulin", indices = [Index(value = ["uuid"], unique = true)])
data class InsulinEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String = UUID.randomUUID().toString(),
    val name: String,
    /** rapid | basal | mixed */
    val kind: String,
    val active: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)

/** "Meus remédios" (plano 17, F1.2). */
@Entity(tableName = "diary_medication", indices = [Index(value = ["uuid"], unique = true)])
data class MedicationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String = UUID.randomUUID().toString(),
    val name: String,
    val defaultDose: Double = 0.0,
    /** mg, comprimido, gota… (texto livre) */
    val unit: String = "",
    val active: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * Alimento cadastrado pela pessoa (plano 17, F1.4). Os da base embutida vêm do asset e não ficam
 * aqui. Valores por 100 g; [portionsJson] = porções caseiras ([{"l":"fatia","g":25}]).
 */
@Entity(tableName = "diary_food", indices = [Index(value = ["uuid"], unique = true)])
data class FoodEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String = UUID.randomUUID().toString(),
    val name: String,
    val carbsPer100: Double,
    val kcalPer100: Double = 0.0,
    val proteinPer100: Double = 0.0,
    val fatPer100: Double = 0.0,
    val portionsJson: String = "[]",
    val createdAt: Long = System.currentTimeMillis()
)

/** Quantas vezes cada alimento foi usado — os mais usados aparecem primeiro na busca. */
@Entity(tableName = "diary_food_usage")
data class FoodUsageEntity(
    @PrimaryKey val foodKey: String,
    val useCount: Int = 0,
    val lastUsedAt: Long = 0
)

/** Refeição favorita (plano 17, F1.5): itens prontos para lançar de novo com um toque. */
@Entity(tableName = "diary_meal_favorite", indices = [Index(value = ["uuid"], unique = true)])
data class MealFavoriteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String = UUID.randomUUID().toString(),
    val name: String,
    val itemsJson: String = "",
    val carbsG: Double,
    val kcal: Double = 0.0,
    val useCount: Int = 0,
    val lastUsedAt: Long = 0,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * Lembrete com horário (plano 17, F2.1). Sempre disparado pelo próprio celular.
 * [timesCsv] = minutos do dia separados por vírgula ("480,1320" = 08:00 e 22:00);
 * [daysMask] = bit 0 segunda … bit 6 domingo (127 = todo dia).
 */
@Entity(tableName = "reminder", indices = [Index(value = ["uuid"], unique = true)])
data class ReminderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String = UUID.randomUUID().toString(),
    val title: String,
    /** measure | insulin | medication | custom | basal_check */
    val kind: String,
    val timesCsv: String,
    val daysMask: Int = 127,
    val enabled: Boolean = true,
    /** Insulina ou remédio ligado ao lembrete (uuid do cadastro) — o "Feito" registra a dose. */
    val linkedUuid: String = "",
    val dose: Double = 0.0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/** O que aconteceu com cada disparo (tocou, feito, adiado, pulado) — base dos "avisos do dia". */
@Entity(tableName = "reminder_log", indices = [Index(value = ["reminderId"]), Index(value = ["scheduledAt"])])
data class ReminderLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val reminderId: Long,
    val scheduledAt: Long,
    /** fired | done | snoozed | skipped | suppressed */
    val action: String,
    val at: Long = System.currentTimeMillis()
)
