package com.gluview.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Dado lido do Health Connect, vindo de OUTROS apps (plano 17, Fase 3). Fica no aparelho para os
 * cruzamentos com a glicemia; nunca vai para o GluSync. [hcId] = id do registro no Health Connect.
 *
 * [kind]: glucose (mg/dL em [value]) · exercise ([subtype] = tipo, [title]) · sleep (min dormidos
 * em [value]) · period (menstruação, intervalo) · flow (fluxo, nível em [value]) · nutrition
 * ([value] = carboidrato g, [value2] = kcal).
 */
@Entity(
    tableName = "health_record",
    indices = [Index(value = ["hcId"], unique = true), Index(value = ["kind", "startMs"])]
)
data class HealthRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val hcId: String,
    val kind: String,
    val startMs: Long,
    val endMs: Long,
    val value: Double = 0.0,
    val value2: Double = 0.0,
    val subtype: String = "",
    val title: String = "",
    val sourcePkg: String = "",
    val importedAt: Long = System.currentTimeMillis()
)
