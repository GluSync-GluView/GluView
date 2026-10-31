package com.gluview.app.data.remote.dto

import com.google.gson.annotations.SerializedName

data class GlucoseEntryDto(
    // Aceita "date" (rota de escrita /glucose/entries e Nightscout) e "measuredAtMs" (resposta
    // de /glucose/range desde Etapa 4 do GluSync — GlucoseRangeEntryDto.MeasuredAtMs). O backend
    // também expõe "date" via [JsonPropertyName] por compatibilidade, mas o cliente já fica
    // resiliente a qualquer um dos dois nomes.
    @SerializedName(value = "date", alternate = ["measuredAtMs"]) val date: Long,
    @SerializedName("sgv")       val sgv: Double,
    @SerializedName("direction") val direction: String,
    @SerializedName("device")    val device: String?,
    @SerializedName("type")      val type: String = "sgv"
)
