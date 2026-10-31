package com.gluview.app.util

import com.gluview.app.R
import java.io.IOException

/**
 * Falha de chamada ao GluSync já resumida. [message] é o texto de sempre (vai para o log e para as
 * regras que leem a mensagem do servidor, ex.: "verificado" no login); [kind] diz à tela qual frase
 * traduzida mostrar. Antes a tela mostrava a mensagem crua: em pt fixo ou o texto Java da exceção
 * ("Unable to resolve host…").
 */
class ApiError(message: String, val kind: Kind, cause: Throwable? = null) : Exception(message, cause) {
    enum class Kind {
        /** Sem internet, DNS, tempo esgotado. */
        NETWORK,
        /** HTTP 5xx: servidor fora do ar ou acordando. */
        UNAVAILABLE,
        /** Login 401 (não revela se a conta existe). */
        BAD_CREDENTIALS,
        /** O servidor mandou o motivo no campo "erro": ele é a mensagem. */
        SERVER,
        UNKNOWN
    }

    companion object {
        /** Corpo `{"erro": "..."}` de uma resposta de erro do GluSync, quando houver (lê o corpo uma vez). */
        fun serverReason(e: Throwable): String? =
            (e as? retrofit2.HttpException)?.response()?.errorBody()?.string()
                ?.let { runCatching { org.json.JSONObject(it).optString("erro").ifBlank { null } }.getOrNull() }

        fun kindOf(e: Throwable, serverReason: String?): Kind = when {
            e is ApiError -> e.kind
            e is IOException -> Kind.NETWORK
            (e as? retrofit2.HttpException)?.code() in 500..599 -> Kind.UNAVAILABLE
            serverReason != null -> Kind.SERVER
            else -> Kind.UNKNOWN
        }
    }
}

/**
 * Frase para a tela, no idioma do app. O motivo mandado pelo servidor aparece como veio (o GluSync
 * responde em pt); os demais casos viram frase traduzida.
 */
fun Throwable.toUiText(): UiText {
    val reason = (this as? ApiError)?.takeIf { it.kind == ApiError.Kind.SERVER }?.message
        ?: ApiError.serverReason(this)
    return when (ApiError.kindOf(this, reason)) {
        ApiError.Kind.NETWORK -> UiText.Res(R.string.error_network)
        ApiError.Kind.UNAVAILABLE -> UiText.Res(R.string.error_server_unavailable)
        ApiError.Kind.BAD_CREDENTIALS -> UiText.Res(R.string.login_error_bad_credentials)
        ApiError.Kind.SERVER -> UiText.Raw(reason ?: message.orEmpty())
        ApiError.Kind.UNKNOWN -> UiText.Res(R.string.error_generic)
    }
}
