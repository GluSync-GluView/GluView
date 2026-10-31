package com.gluview.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "watch_faces")
data class WatchFaceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val wearableId: String,
    val wearableName: String,
    val wearableWidthPx: Int,
    val wearableHeightPx: Int,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val componentsJson: String,
    val schemaVersion: Int = 1,
    val applyState: String = "not_applied",
    // Id do projeto no GluSync (Fase 4 do plano 06_watchfaces_v2_arquitetura.md) — null enquanto
    // o projeto nunca foi sincronizado. Preenchido após o primeiro syncToServer() bem-sucedido.
    val serverId: String? = null,
    // Ids de WearableSpec compatíveis ALÉM de `wearableId` (Fase 5 do plano, WatchfaceCompatibility)
    // — JSON array de string, mesmo padrão de componentsJson. "[]" = só o wearable principal.
    val compatibleWearableIdsJson: String = "[]"
)
