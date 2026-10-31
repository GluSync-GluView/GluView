package com.gluview.app.data.remote.dto

import com.google.gson.annotations.SerializedName

// Integrações de fonte de dados externa (Dexcom Share, LibreLinkUp) — DTOs do contrato
// GluSync (/integrations). JSON em camelCase (ASP.NET default).

data class ConnectIntegrationRequestDto(
    @SerializedName("username") val username: String,
    @SerializedName("password") val password: String,
    @SerializedName("region")   val region:   String? = null
)

/** Status de uma integração conectada — nunca expõe usuário/senha. */
data class IntegrationStatusDto(
    @SerializedName("provider")    val provider:   String,   // dexcom_share | librelinkup
    @SerializedName("status")      val status:     String,   // active | auth_error | disabled
    @SerializedName("region")      val region:     String?,
    @SerializedName("lastSyncAt")  val lastSyncAt: String?,
    @SerializedName("lastError")   val lastError:  String?,
    @SerializedName("createdAt")   val createdAt:  String
)
