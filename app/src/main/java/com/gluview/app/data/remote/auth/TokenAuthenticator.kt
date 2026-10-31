package com.gluview.app.data.remote.auth

import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import javax.inject.Inject

class TokenAuthenticator @Inject constructor(
    private val coordinator: TokenRefreshCoordinator
) : Authenticator {

    override fun authenticate(route: Route?, response: Response): Request? {
        // Evitar loop infinito (segundo 401 consecutivo = desistir)
        if (response.request.header("X-Refresh-Attempt") != null) return null

        val staleToken = response.request.header("Authorization")?.removePrefix("Bearer ")
        val outcome = runBlocking { coordinator.refresh(staleToken) }
        val newToken = (outcome as? TokenRefreshCoordinator.RefreshOutcome.Success)?.accessToken ?: return null

        return response.request.newBuilder()
            .header("Authorization", "Bearer $newToken")
            .header("X-Refresh-Attempt", "1")
            .build()
    }
}
