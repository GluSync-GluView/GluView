package com.gluview.app.data.remote.dto

import com.google.gson.annotations.SerializedName

/** POST /terms/accept — registro do aceite no servidor (versão + hash do texto + idioma + data):
 *  prova do consentimento para dado de saúde (LGPD art. 11). Espelha AcceptTermsRequest do GluSync. */
data class AcceptTermsDto(
    @SerializedName("kind")         val kind: String = "terms_of_use",
    @SerializedName("version")      val version: Int,
    @SerializedName("documentHash") val documentHash: String,
    @SerializedName("locale")       val locale: String
)
