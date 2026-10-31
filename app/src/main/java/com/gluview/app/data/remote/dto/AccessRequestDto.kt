package com.gluview.app.data.remote.dto

import com.google.gson.annotations.SerializedName

// ADR-0029 · Cadastro por convite em camadas — DTOs do contrato GluSync
// (access-requests / activation-invites / feature-entitlements / config/features).

/** Espelha Models/AccessRequestModels.cs (AccessRequestFeatures) do GluSync. */
object AccessRequestFeatures {
    const val APP = "APP"
    const val INSULIN_PUMP_BETA = "insulin_pump_beta"

    /** ADR-0030 — pedido único "glicemia na nuvem e acompanhamento" (o servidor expande em
     *  [CLOUD_UPLOAD_GLUCOSE] + `cloud_follow` ao aprovar). */
    const val CLOUD_SYNC = "cloud_sync"
    const val CLOUD_UPLOAD_GLUCOSE = "cloud_upload:glucose"

    fun sensorBeta(dataSourceTypeId: String): String = "sensor_beta:$dataSourceTypeId"
}

/** GET /config/features — flags de exposição de UI, independentes entre si. */
data class FeatureFlagsDto(
    @SerializedName("registrationMode")      val registrationMode: String = "open",
    @SerializedName("insulinPumpTabEnabled") val insulinPumpTabEnabled: Boolean = false,
    @SerializedName("sensorBetaEnabled")     val sensorBetaEnabled: Boolean = false,
    @SerializedName("supportLogEnabled")     val supportLogEnabled: Boolean = false
)

/** POST /access-requests (público) — pedido de acesso ao app inteiro. */
data class CreateAccessRequestDto(
    @SerializedName("email")       val email: String,
    @SerializedName("displayName") val displayName: String? = null,
    @SerializedName("roles")       val roles: List<String>? = null,
    @SerializedName("note")        val note: String? = null
)

data class CreateAccessRequestResponseDto(
    @SerializedName("id")     val id: String,
    @SerializedName("status") val status: String
)

/** POST /access-requests/verify — confirma o código de 6 dígitos da candidatura de acesso. */
data class VerifyAccessRequestDto(
    @SerializedName("accessRequestId") val accessRequestId: String,
    @SerializedName("code")            val code: String
)

/** POST /access-requests/feature (autenticado) — candidatura a uma feature em beta (bomba,
 *  sensor comingSoon). featureKey: "insulin_pump_beta" ou "sensor_beta:<DataSourceType.id>". */
data class CreateFeatureAccessRequestDto(
    @SerializedName("featureKey") val featureKey: String,
    @SerializedName("note")       val note: String? = null
)

data class AccessRequestDto(
    @SerializedName("id")         val id: String,
    @SerializedName("featureKey") val featureKey: String,
    @SerializedName("status")     val status: String
)

/** GET /access-requests/me/status — estado consolidado em lote (não 1 chamada por sensor). */
data class AccessRequestFeatureStateDto(
    @SerializedName("featureKey") val featureKey: String,
    @SerializedName("state")      val state: String
)

/** GET /access-requests/me (autenticado, histórico completo) e GET
 *  /access-requests/{id}/status (público, por ID) — mesmo shape do MyAccessRequestView do
 *  GluSync. adminNote/decidedAt só vêm preenchidos depois de decidido. */
data class MyAccessRequestDto(
    @SerializedName("id")         val id: String,
    @SerializedName("featureKey") val featureKey: String,
    @SerializedName("status")     val status: String,
    @SerializedName("note")       val note: String?,
    @SerializedName("adminNote")  val adminNote: String?,
    @SerializedName("createdAt")  val createdAt: String,
    @SerializedName("decidedAt")  val decidedAt: String?
)

/** POST /support/reports — botão "Enviar log para o suporte" (Ajustes). */
data class CreateSupportReportDto(
    @SerializedName("logText")     val logText: String,
    @SerializedName("userMessage") val userMessage: String? = null,
    @SerializedName("appVersion")  val appVersion: String? = null,
    @SerializedName("deviceInfo")  val deviceInfo: String? = null
)

data class SupportReportCreatedDto(
    @SerializedName("id") val id: String
)
