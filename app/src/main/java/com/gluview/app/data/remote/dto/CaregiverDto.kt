package com.gluview.app.data.remote.dto

import com.google.gson.annotations.SerializedName

// F6 · Acompanhamento (paciente ↔ seguidor) — contrato GluSync /follow

data class InviteRequestDto(
    @SerializedName("email") val email: String,
    // "follower" = quero seguir o e-mail; "patient" = sou paciente e convido o e-mail a me seguir
    @SerializedName("as")    val asRole: String = "follower"
)

data class InviteResponseDto(
    @SerializedName("linkId") val linkId: String? = null,
    @SerializedName("status") val status: String? = null
)

data class RespondInviteRequestDto(
    @SerializedName("linkId") val linkId: String,
    @SerializedName("accept") val accept: Boolean
)

data class CaregiverLinkViewDto(
    @SerializedName("id")            val id: String,
    @SerializedName("patientId")     val patientId: String,
    @SerializedName("patientEmail")  val patientEmail: String,
    @SerializedName("patientName")   val patientName: String? = null,
    @SerializedName("followerId")    val followerId: String,
    @SerializedName("followerEmail") val followerEmail: String,
    @SerializedName("followerName")  val followerName: String? = null,
    @SerializedName("status")        val status: String,
    @SerializedName("initiatedBy")   val initiatedBy: String,
    @SerializedName("createdAt")     val createdAt: String? = null,
    @SerializedName("respondedAt")   val respondedAt: String? = null
) {
    /** true se, do ponto de vista de [myId], eu sou o seguidor nesse vínculo. */
    fun iAmFollower(myId: String): Boolean = followerId == myId

    /** Nome/e-mail de exibição da OUTRA ponta do vínculo, do ponto de vista de [myId]. */
    fun otherPartyName(myId: String): String =
        if (iAmFollower(myId)) (patientName ?: patientEmail) else (followerName ?: followerEmail)
}
