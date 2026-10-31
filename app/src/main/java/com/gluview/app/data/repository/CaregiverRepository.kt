package com.gluview.app.data.repository

import com.gluview.app.data.remote.GluSyncApi
import com.gluview.app.data.remote.dto.CaregiverLinkViewDto
import com.gluview.app.data.remote.dto.GlucoseEntryDto
import com.gluview.app.data.remote.dto.InviteRequestDto
import com.gluview.app.data.remote.dto.InviteResponseDto
import com.gluview.app.data.remote.dto.RespondInviteRequestDto
import com.gluview.app.data.remote.dto.UpdateProfileRequestDto
import com.gluview.app.data.remote.dto.UserProfileDto
import com.gluview.app.domain.model.GlucoseThresholds
import javax.inject.Inject
import javax.inject.Singleton

/** F6 — Acompanhamento paciente↔seguidor (consome /follow e /auth/me). */
@Singleton
class CaregiverRepository @Inject constructor(
    private val api: GluSyncApi
) {
    suspend fun me(): UserProfileDto = api.getMe()

    // F6/UX — edita o nome de exibição (cadastro já coletava, faltava editar depois).
    suspend fun updateDisplayName(name: String?): Boolean =
        api.updateMe(UpdateProfileRequestDto(name?.trim()?.ifBlank { null })).isSuccessful
    suspend fun links(): List<CaregiverLinkViewDto> = api.listFollowLinks()

    suspend fun invite(email: String, asRole: String): InviteResponseDto =
        api.createInvite(InviteRequestDto(email.trim(), asRole))

    suspend fun respond(linkId: String, accept: Boolean): Boolean =
        api.respondInvite(RespondInviteRequestDto(linkId, accept)).isSuccessful

    suspend fun revoke(linkId: String): Boolean =
        api.revokeFollowLink(linkId).isSuccessful

    /** Os 4 limites do paciente (L1); null se o GluSync não responder (a tela usa o padrão). */
    suspend fun patientThresholds(patientId: String): GlucoseThresholds? = runCatching {
        api.patientThresholds(patientId).let { GlucoseThresholds(it.urgentLow, it.low, it.high, it.urgentHigh) }
    }.getOrNull()

    suspend fun patientCurrent(patientId: String): GlucoseEntryDto =
        api.followedPatientCurrent(patientId)

    suspend fun patientRange(patientId: String, fromMs: Long, toMs: Long): List<GlucoseEntryDto> =
        api.followedPatientRange(patientId, fromMs, toMs)
}
