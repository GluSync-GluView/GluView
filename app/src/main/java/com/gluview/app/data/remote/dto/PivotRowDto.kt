package com.gluview.app.data.remote.dto

import com.google.gson.annotations.SerializedName

data class PivotRowDto(
    @SerializedName("dia")           val dia: String,
    @SerializedName("ano")           val ano: Int,
    @SerializedName("mes")           val mes: Int,
    @SerializedName("h00")           val h00: Double,
    @SerializedName("h03")           val h03: Double,
    @SerializedName("h06")           val h06: Double,
    @SerializedName("h09")           val h09: Double,
    @SerializedName("h12")           val h12: Double,
    @SerializedName("h15")           val h15: Double,
    @SerializedName("h18")           val h18: Double,
    @SerializedName("h21")           val h21: Double,
    @SerializedName("mediaDia")      val mediaDia: Double,
    @SerializedName("totalLeituras") val totalLeituras: Int,
    @SerializedName("hbA1cEst")      val hbA1cEst: Double
)
