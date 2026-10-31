package com.gluview.app.data.remote.dto

import com.google.gson.annotations.SerializedName

data class TirDayDto(
    @SerializedName("dia")           val dia: String,
    @SerializedName("tir")           val tir: Double,
    @SerializedName("hbA1cEst")      val hbA1cEst: Double,
    @SerializedName("hipoglicemia")  val hipoglicemia: Double,
    @SerializedName("hiperglicemia") val hiperglicemia: Double
)

data class TirReportDto(
    @SerializedName("periodo")    val periodo: String,
    @SerializedName("mediaTIR")   val mediaTir: Double,
    @SerializedName("mediaHbA1c") val mediaHbA1c: Double,
    @SerializedName("dias")       val dias: List<TirDayDto>
)
