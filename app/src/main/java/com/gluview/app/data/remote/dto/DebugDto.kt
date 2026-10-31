package com.gluview.app.data.remote.dto

import com.google.gson.annotations.SerializedName

// Modo Debug/Admin — gerador de dados sintéticos (contrato GluSync /debug/seed)

data class DebugToggleRequestDto(
    @SerializedName("enabled") val enabled: Boolean
)

data class DebugSeedStatusDto(
    @SerializedName("enabled") val enabled: Boolean
)

data class DebugBackfillResponseDto(
    @SerializedName("geradas") val geradas: Int
)
