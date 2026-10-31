package com.gluview.app.data.remote.auth

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request

/** Reescreve host/scheme/porta do request pela URL do servidor configurada em Ajustes. */
object BaseUrlRewriter {
    fun rewrite(request: Request, baseUrl: String): Request {
        if (baseUrl.isBlank()) return request
        val parsed = baseUrl.trimEnd('/').toHttpUrlOrNull() ?: return request
        val newUrl = request.url.newBuilder()
            .scheme(parsed.scheme)
            .host(parsed.host)
            .port(parsed.port)
            .build()
        return request.newBuilder().url(newUrl).build()
    }
}
