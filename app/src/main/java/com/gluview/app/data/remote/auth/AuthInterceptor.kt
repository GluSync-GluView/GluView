package com.gluview.app.data.remote.auth

import com.gluview.app.data.datastore.AppPreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject

class AuthInterceptor @Inject constructor(
    private val preferences: AppPreferences
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val (baseUrl, token) = runBlocking {
            preferences.baseUrl.first() to preferences.accessToken.first()
        }

        var request = BaseUrlRewriter.rewrite(chain.request(), baseUrl)

        if (token.isNotBlank()) {
            request = request.newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
        }

        return chain.proceed(request)
    }
}
