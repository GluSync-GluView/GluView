package com.gluview.app.data.repository

import com.gluview.app.data.remote.GluSyncApi
import com.gluview.app.data.remote.dto.ApiKeyInfoDto
import com.gluview.app.data.remote.dto.CreateApiKeyRequestDto
import com.gluview.app.data.remote.dto.CreatedApiKeyDto
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ApiKeyRepository @Inject constructor(
    private val api: GluSyncApi
) {
    suspend fun list(): List<ApiKeyInfoDto> = api.listApiKeys()

    suspend fun create(name: String, scopes: List<String>): CreatedApiKeyDto =
        api.createApiKey(CreateApiKeyRequestDto(name = name, scopes = scopes.ifEmpty { null }))

    suspend fun revoke(id: String): Boolean = api.revokeApiKey(id).isSuccessful
}
