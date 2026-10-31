package com.gluview.app.data.repository

import com.gluview.app.data.datastore.AppPreferences
import com.gluview.app.data.remote.GluSyncApi
import com.gluview.app.data.remote.dto.FeatureFlagsDto
import javax.inject.Inject
import javax.inject.Singleton

/** ADR-0029 — flags de exposição de UI (modo de cadastro, aba Bomba, beta de sensores, log de
 *  suporte), lidas do servidor sem exigir rebuild do app. */
@Singleton
class AppConfigRepository @Inject constructor(
    private val api: GluSyncApi,
    private val preferences: AppPreferences
) {
    // Consultado ANTES do login (LoginScreen decide qual formulário mostrar) — baseUrl como
    // parâmetro, mesmo padrão de AuthRepository.register/login.
    suspend fun getFeatureFlags(baseUrl: String): Result<FeatureFlagsDto> = runCatching {
        preferences.saveBaseUrl(baseUrl)
        api.getFeatureFlags()
    }

    // Já logado (aba Bomba, sensores beta, log de suporte) — usa o baseUrl já salvo.
    suspend fun getFeatureFlags(): Result<FeatureFlagsDto> = runCatching { api.getFeatureFlags() }
}
