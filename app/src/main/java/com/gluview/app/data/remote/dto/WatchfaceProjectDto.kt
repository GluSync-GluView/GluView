package com.gluview.app.data.remote.dto

import com.google.gson.annotations.SerializedName

// Fase 4 do plano 06_watchfaces_v2_arquitetura.md (GluView) — biblioteca de watchfaces
// sincronizada. DTOs do contrato GluSync (/watchfaces/projects). JSON em camelCase (ASP.NET default).

data class WatchfaceProjectDto(
    @SerializedName("id")               val id: String,
    @SerializedName("userId")           val userId: String,
    @SerializedName("name")             val name: String,
    @SerializedName("wearableId")       val wearableId: String,
    @SerializedName("wearableName")     val wearableName: String,
    @SerializedName("wearableWidthPx")  val wearableWidthPx: Int,
    @SerializedName("wearableHeightPx") val wearableHeightPx: Int,
    @SerializedName("componentsJson")   val componentsJson: String,
    @SerializedName("schemaVersion")    val schemaVersion: Int,
    @SerializedName("visibility")       val visibility: String,
    @SerializedName("createdAt")        val createdAt: String,
    @SerializedName("updatedAt")        val updatedAt: String,
    // Fase 5 (WatchfaceCompatibility) — ausente = servidor antigo/campo omitido; trata como "[]".
    @SerializedName("compatibleWearableIdsJson") val compatibleWearableIdsJson: String? = null
)

data class SaveWatchfaceProjectRequestDto(
    @SerializedName("id")               val id: String? = null,
    @SerializedName("name")             val name: String,
    @SerializedName("wearableId")       val wearableId: String,
    @SerializedName("wearableName")     val wearableName: String,
    @SerializedName("wearableWidthPx")  val wearableWidthPx: Int,
    @SerializedName("wearableHeightPx") val wearableHeightPx: Int,
    @SerializedName("componentsJson")   val componentsJson: String,
    @SerializedName("schemaVersion")    val schemaVersion: Int,
    @SerializedName("compatibleWearableIdsJson") val compatibleWearableIdsJson: String
)

data class PublishWatchfaceProjectResponseDto(
    @SerializedName("publicationId") val publicationId: String
)

// Fase 6 — vitrine da loja (GET /watchfaces/store). Mesma forma de WatchfaceStoreListingItem no
// GluSync (Models/WatchfaceModels.cs).
data class WatchfaceStoreListingDto(
    @SerializedName("publicationId")            val publicationId: String,
    @SerializedName("projectId")                val projectId: String,
    @SerializedName("name")                     val name: String,
    @SerializedName("wearableId")               val wearableId: String,
    @SerializedName("wearableName")             val wearableName: String,
    @SerializedName("wearableWidthPx")          val wearableWidthPx: Int,
    @SerializedName("wearableHeightPx")         val wearableHeightPx: Int,
    @SerializedName("compatibleWearableIdsJson") val compatibleWearableIdsJson: String,
    @SerializedName("publishedAt")              val publishedAt: String
)
