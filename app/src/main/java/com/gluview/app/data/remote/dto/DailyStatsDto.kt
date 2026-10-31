package com.gluview.app.data.remote.dto

import com.google.gson.annotations.SerializedName

data class DailyStatsDto(
    @SerializedName("dia")            val dia: String,
    @SerializedName("media")          val media: Double,
    @SerializedName("minimo")         val minimo: Double,
    @SerializedName("maximo")         val maximo: Double,
    @SerializedName("desvio")         val desvio: Double,
    @SerializedName("totalLeituras")  val totalLeituras: Int,
    @SerializedName("leiturasBaixas") val leiturasBaixas: Int,
    @SerializedName("leiturasAltas")  val leiturasAltas: Int,
    @SerializedName("percentNoAlvo")  val percentNoAlvo: Double,
    @SerializedName("hbA1cEst")       val hbA1cEst: Double
)
