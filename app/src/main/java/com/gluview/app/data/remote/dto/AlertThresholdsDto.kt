package com.gluview.app.data.remote.dto

import com.google.gson.annotations.SerializedName

/** GET /alerts/thresholds?patientId= — só os 4 limites (mg/dL) do paciente, sem credenciais (L1). */
data class AlertThresholdsDto(
    @SerializedName("urgentLow")  val urgentLow: Float,
    @SerializedName("low")        val low: Float,
    @SerializedName("high")       val high: Float,
    @SerializedName("urgentHigh") val urgentHigh: Float
)
