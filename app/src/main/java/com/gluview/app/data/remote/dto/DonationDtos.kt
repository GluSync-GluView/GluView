package com.gluview.app.data.remote.dto

import com.google.gson.annotations.SerializedName

// Doação via Pix (plano 09_doacao_pix_plano.md). Em data/remote/dto/ pelo mesmo
// motivo dos DTOs do OTA: o proguard-rules.pro mantém este pacote inteiro no release com R8.

/** GET /api/glusync/v1/donations/config — anônimo. enabled=false esconde a entrada nos Ajustes. */
data class DonationConfigDto(
    @SerializedName("enabled")     val enabled: Boolean = false,
    @SerializedName("minCents")    val minCents: Int = 0,
    @SerializedName("maxCents")    val maxCents: Int = 0,
    @SerializedName("presetCents") val presetCents: List<Int>? = null,
    /** "static" = chave Pix fixa, sem confirmação (padrão); "mercadopago" = cobrança acompanhada. */
    @SerializedName("mode")        val mode: String? = null
)

data class StaticPixRequestDto(
    @SerializedName("amountCents") val amountCents: Int
)

/** POST /api/glusync/v1/donations/static-pix — copia e cola + QR com a chave fixa. Sem id: não há o que acompanhar. */
data class StaticPixDto(
    @SerializedName("amountCents")  val amountCents: Int,
    @SerializedName("copiaECola")   val copiaECola: String,
    @SerializedName("qrCodeBase64") val qrCodeBase64: String
)

data class CreateDonationRequestDto(
    @SerializedName("amountCents") val amountCents: Int,
    @SerializedName("origin")      val origin: String = "android"
)

/** POST /api/glusync/v1/donations. O [clientToken] só consulta/cancela esta doação. */
data class CreateDonationDto(
    @SerializedName("id")           val id: String,
    @SerializedName("clientToken")  val clientToken: String,
    @SerializedName("amountCents")  val amountCents: Int,
    @SerializedName("qrCodeBase64") val qrCodeBase64: String,
    @SerializedName("copiaECola")   val copiaECola: String,
    @SerializedName("expiresAt")    val expiresAt: String
)

/** GET /api/glusync/v1/donations/{id} e POST …/cancel. status: pending | paid | expired | cancelled | refunded. */
data class DonationStatusDto(
    @SerializedName("id")          val id: String,
    @SerializedName("status")      val status: String,
    @SerializedName("amountCents") val amountCents: Int,
    @SerializedName("expiresAt")   val expiresAt: String? = null,
    @SerializedName("paidAt")      val paidAt: String? = null
)
