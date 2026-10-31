package com.gluview.app.data.remote.dto

import com.google.gson.annotations.SerializedName

// Atualização sem cabo (plano OTA, 08_atualizacao_ota_plano.md, E6/E7).
// Ficam em data/remote/dto/ de propósito: o proguard-rules.pro mantém este pacote inteiro. Um DTO
// fora dele teria os campos renomeados pelo R8 no release e o parse falharia sem crash visível.

/** GET /api/glusync/v1/app/update/latest — anônimo, só metadados do canal stable. */
data class UpdateLatestDto(
    @SerializedName("updateAvailable") val updateAvailable: Boolean = false,
    @SerializedName("recommended")     val recommended: Boolean = false,
    @SerializedName("security")        val security: Boolean = false,
    @SerializedName("versionCode")     val versionCode: Long? = null,
    @SerializedName("versionName")     val versionName: String? = null,
    @SerializedName("minSdk")          val minSdk: Int? = null,
    @SerializedName("sizeBytes")       val sizeBytes: Long? = null,
    @SerializedName("sha256")          val sha256: String? = null,
    @SerializedName("publishedAt")     val publishedAt: String? = null
)

data class UpdateDownloadLinkRequestDto(
    @SerializedName("versionCode") val versionCode: Long,
    @SerializedName("channel")     val channel: String? = null
)

/** POST /api/glusync/v1/app/update/download-link. A [url] vale poucos minutos: nunca guardar. */
data class UpdateDownloadLinkDto(
    @SerializedName("url")         val url: String,
    @SerializedName("expiresAt")   val expiresAt: String? = null,
    @SerializedName("versionCode") val versionCode: Long,
    @SerializedName("versionName") val versionName: String,
    @SerializedName("sha256")      val sha256: String,
    @SerializedName("sizeBytes")   val sizeBytes: Long,
    @SerializedName("notes")       val notes: UpdateNotesDto? = null
)

data class UpdateNotesDto(
    @SerializedName("pt") val pt: String? = null,
    @SerializedName("en") val en: String? = null
)
