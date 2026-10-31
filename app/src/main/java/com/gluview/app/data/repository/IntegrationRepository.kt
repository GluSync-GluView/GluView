package com.gluview.app.data.repository

import com.gluview.app.data.remote.GluSyncApi
import com.gluview.app.data.remote.dto.ConnectIntegrationRequestDto
import com.gluview.app.data.remote.dto.IntegrationStatusDto
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class IntegrationRepository @Inject constructor(
    private val api: GluSyncApi
) {
    suspend fun list(): List<IntegrationStatusDto> = api.listIntegrations()

    suspend fun connect(provider: String, username: String, password: String, region: String?): Boolean {
        val resp = api.connectIntegration(provider, ConnectIntegrationRequestDto(username, password, region?.ifBlank { null }))
        return resp.isSuccessful
    }

    suspend fun disconnect(provider: String): Boolean =
        api.disconnectIntegration(provider).isSuccessful
}
