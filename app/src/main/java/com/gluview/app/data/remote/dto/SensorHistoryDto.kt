package com.gluview.app.data.remote.dto

import com.google.gson.annotations.SerializedName

// Histórico de sensores — contrato GluSync /sensors (mesmo modelo das glicemias)

/**
 * startedAt/endedAt em ISO-8601 UTC ("2026-05-10T08:30:00Z"), ambos OPCIONAIS:
 * - omitidos = sensor sendo configurado agora (o servidor usa a hora dele e encerra o anterior);
 * - preenchidos = reenvio de um período que já existe no aparelho mas nunca subiu. Sem eles o
 *   período subiria com a data do reenvio, não com a data real de uso — e, com endedAt, o servidor
 *   sabe que é histórico e não encerra o sensor em uso.
 */
data class SensorHistoryStartRequestDto(
    @SerializedName("mac")        val mac: String,
    @SerializedName("serial")     val serial: String?,
    @SerializedName("customerId") val customerId: String?,
    @SerializedName("source")     val source: String,
    @SerializedName("startedAt")  val startedAt: String? = null,
    @SerializedName("endedAt")    val endedAt: String? = null
)

data class SensorHistoryDto(
    @SerializedName("id")                val id: Long,
    @SerializedName("mac")               val mac: String,
    @SerializedName("serial")            val serial: String?,
    @SerializedName("customerId")        val customerId: String?,
    @SerializedName("source")            val source: String,
    @SerializedName("startedAt")         val startedAt: String,
    @SerializedName("endedAt")           val endedAt: String?,
    @SerializedName("calibrationFactor") val calibrationFactor: Double?,
    @SerializedName("calibratedAt")      val calibratedAt: String?
)

data class SensorCalibrationRequestDto(
    @SerializedName("factor") val factor: Double
)
