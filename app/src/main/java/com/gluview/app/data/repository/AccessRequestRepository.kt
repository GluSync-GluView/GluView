package com.gluview.app.data.repository

import com.gluview.app.data.datastore.AppPreferences
import com.gluview.app.data.remote.GluSyncApi
import com.gluview.app.data.remote.dto.AccessRequestDto
import com.gluview.app.data.remote.dto.CreateAccessRequestDto
import com.gluview.app.data.remote.dto.CreateAccessRequestResponseDto
import com.gluview.app.data.remote.dto.CreateFeatureAccessRequestDto
import com.gluview.app.data.remote.dto.MyAccessRequestDto
import com.gluview.app.data.remote.dto.VerifyAccessRequestDto
import javax.inject.Inject
import javax.inject.Singleton

/** ADR-0029 — candidatura/interesse. Cobre tanto o pedido de acesso ao app inteiro (público,
 *  usado quando registrationMode=approval no lugar de AuthRepository.register) quanto a
 *  candidatura a uma funcionalidade em beta (autenticado — aba Bomba, sensores comingSoon). */
@Singleton
class AccessRequestRepository @Inject constructor(
    private val api: GluSyncApi,
    private val preferences: AppPreferences
) {
    // Público, antes de existir conta — baseUrl como parâmetro, mesmo padrão de
    // AuthRepository.register/login.
    suspend fun createAccessRequest(
        baseUrl: String, email: String, displayName: String?, roles: List<String>, note: String?
    ): Result<CreateAccessRequestResponseDto> = runCatching {
        preferences.saveBaseUrl(baseUrl)
        api.createAccessRequest(CreateAccessRequestDto(email, displayName, roles, note))
    }

    // Confirma o código de 6 dígitos (unverified -> pending) — não loga, a conta ainda não existe.
    suspend fun verifyAccessRequest(accessRequestId: String, code: String): Result<Unit> = runCatching {
        api.verifyAccessRequest(VerifyAccessRequestDto(accessRequestId, code))
        Unit
    }

    // Autenticado — candidatura a bomba de insulina ou sensor comingSoon.
    suspend fun createFeatureAccessRequest(featureKey: String, note: String? = null): Result<AccessRequestDto> =
        runCatching { api.createFeatureAccessRequest(CreateFeatureAccessRequestDto(featureKey, note)) }

    // Estado consolidado em lote (aba Bomba + os 5 sensores comingSoon numa chamada só).
    // Map<featureKey, state> — chave ausente = "none" (nunca candidatou).
    suspend fun getStatus(): Result<Map<String, String>> = runCatching {
        api.getAccessRequestStatus().associate { it.featureKey to it.state }
    }

    // Histórico completo pra tela "Minhas candidaturas" (Config > Acesso) — inclui o pedido de
    // acesso ao app em si, nota do usuário e comentário do admin (getStatus acima descarta tudo
    // isso, só serve pros badges comingSoon).
    suspend fun getMyRequests(): Result<List<MyAccessRequestDto>> = runCatching { api.getMyAccessRequests() }

    // Público — status por ID, pra quem ainda não tem conta (ver AppPreferences.
    // pendingAppAccessRequestId, gravado em LoginViewModel ao criar/verificar o pedido).
    suspend fun getPublicStatus(accessRequestId: String): Result<MyAccessRequestDto> =
        runCatching { api.getAccessRequestPublicStatus(accessRequestId) }
}
