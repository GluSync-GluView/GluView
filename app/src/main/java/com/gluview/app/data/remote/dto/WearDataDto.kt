package com.gluview.app.data.remote.dto

import com.google.gson.annotations.SerializedName

data class WearDataDto(
    @SerializedName("sgv")          val sgv: Double,
    @SerializedName("arrow")        val arrow: String,
    @SerializedName("time")         val time: String,
    @SerializedName("status")       val status: String,
    @SerializedName("delta")        val delta: Int,
    @SerializedName("stale")        val stale: Boolean,
    @SerializedName("minutosAtras") val minutosAtras: Int? = null
)
