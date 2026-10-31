package com.gluview.app.util

import org.json.JSONObject
import java.util.Base64

/**
 * Leitura (sem verificar assinatura) das claims do access token do GluSync. Só serve para decidir
 * o que a UI mostra: quem autoriza de verdade é o servidor, que valida a assinatura em cada
 * chamada. Por isso não há segredo nem e-mail fixo no app — o código é público.
 */
object JwtClaims {

    /** Claim "admin"="true", emitida pelo GluSync só para a conta administradora com passkey. */
    fun isAdmin(accessToken: String?): Boolean =
        payload(accessToken)?.optString("admin")?.equals("true", ignoreCase = true) == true

    private fun payload(token: String?): JSONObject? {
        val part = token?.split('.')?.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { JSONObject(String(Base64.getUrlDecoder().decode(part), Charsets.UTF_8)) }.getOrNull()
    }
}
