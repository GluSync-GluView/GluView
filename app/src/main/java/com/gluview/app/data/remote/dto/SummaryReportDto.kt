package com.gluview.app.data.remote.dto

import com.google.gson.annotations.SerializedName

data class HbA1cResultDto(
    @SerializedName("periodo")       val periodo: String,
    @SerializedName("mediaGlicemia") val mediaGlicemia: Double,
    @SerializedName("hbA1cEst")      val hbA1cEst: Double,
    @SerializedName("classificacao") val classificacao: String,
    @SerializedName("totalLeituras") val totalLeituras: Int,
    /** Código estável da classificação ("normal", "optimal", "good", "attention", "high");
     *  null em GluSync antigo — aí vale o texto em português de [classificacao]. */
    @SerializedName("classificacaoCode") val classificacaoCode: String? = null
)

data class MonthlyStatsDto(
    @SerializedName("ano")            val ano: Int,
    @SerializedName("mes")            val mes: Int,
    @SerializedName("mediaMensal")    val mediaMensal: Double,
    @SerializedName("hbA1cEstMensal") val hbA1cEstMensal: Double,
    @SerializedName("tirPercent")     val tirPercent: Double,
    @SerializedName("totalDias")      val totalDias: Int,
    @SerializedName("totalLeituras")  val totalLeituras: Int
)

data class SummaryCabecalhoDto(
    @SerializedName("periodo")     val periodo: String,
    @SerializedName("gerado")      val gerado: String,
    @SerializedName("totalDias")   val totalDias: Int,
    @SerializedName("hbA1c90dias") val hbA1c90dias: HbA1cResultDto?
)

data class SummaryReportDto(
    @SerializedName("cabecalho")    val cabecalho: SummaryCabecalhoDto,
    @SerializedName("tabelaPivot")  val tabelaPivot: List<PivotRowDto>,
    @SerializedName("resumoMensal") val resumoMensal: List<MonthlyStatsDto>
)
