package com.gluview.wear.contract

/**
 * Credencial que o celular entrega ao relógio em [WearPaths.TOKEN] (por MessageClient, direto
 * entre os dois — nunca por DataItem, que é sincronizado e guardado), para o relógio buscar a
 * glicemia no GluSync sozinho quando o celular some (Fase 7, plano B).
 *
 * Normalmente é a credencial própria do relógio: uma API Key do GluSync só de leitura (prefixo
 * `glsk_`, 30 dias, renovada pelo celular). Com GluSync antigo, é o token de acesso do app do celular
 * (curto, 1 h). O celular reenvia a cada leitura; com o celular desligado o plano B dura até
 * [expiresAtMs]. Em modo só-local (ou sem login) o celular manda [CLEARED] e o relógio apaga o que tinha.
 */
data class WearToken(
    val accessToken: String,
    val baseUrl: String,
    val expiresAtMs: Long,
) {
    fun isUsable(nowMs: Long): Boolean =
        accessToken.isNotBlank() && baseUrl.isNotBlank() && expiresAtMs - nowMs > MIN_REMAINING_MS

    fun toMap(): Map<String, Any> = mapOf(
        K_VERSION to VERSION,
        K_TOKEN to accessToken,
        K_BASE_URL to baseUrl,
        K_EXPIRES to expiresAtMs,
    )

    companion object {
        const val VERSION = 1
        /** Abaixo de 1 min restante não vale a pena usar (a chamada pode chegar já vencida). */
        private const val MIN_REMAINING_MS = 60_000L

        const val K_VERSION = "v"
        const val K_TOKEN = "token"
        const val K_BASE_URL = "baseUrl"
        const val K_EXPIRES = "expiresAt"

        /** Mensagem de "apague o token" (logout, modo só-local, nuvem não liberada). */
        val CLEARED = WearToken("", "", 0)

        fun fromMap(map: Map<String, Any?>): WearToken? {
            val v = map[K_VERSION] as? Int ?: return null
            if (v > VERSION) return null
            val token = map[K_TOKEN] as? String ?: return null
            val baseUrl = (map[K_BASE_URL] as? String)?.trimEnd('/') ?: return null
            val expires = (map[K_EXPIRES] as? Number)?.toLong() ?: return null
            if (token.isNotEmpty() && !(baseUrl.startsWith("https://") || baseUrl.startsWith("http://"))) return null
            return WearToken(token, baseUrl, expires)
        }

        /**
         * Formato binário da mensagem (MessageClient só leva bytes): linhas `chave=valor`, sem
         * dependência de JSON nos dois lados. Valores não contêm quebra de linha (JWT, URL, número).
         */
        fun encode(t: WearToken): ByteArray =
            t.toMap().entries.joinToString("\n") { "${it.key}=${it.value}" }.toByteArray(Charsets.UTF_8)

        fun decode(bytes: ByteArray): WearToken? {
            val pairs = bytes.toString(Charsets.UTF_8).lines()
                .mapNotNull { line -> line.indexOf('=').takeIf { it > 0 }?.let { line.substring(0, it) to line.substring(it + 1) } }
                .toMap()
            return fromMap(mapOf(
                K_VERSION to pairs[K_VERSION]?.toIntOrNull(),
                K_TOKEN to pairs[K_TOKEN],
                K_BASE_URL to pairs[K_BASE_URL],
                K_EXPIRES to pairs[K_EXPIRES]?.toLongOrNull(),
            ))
        }
    }
}
