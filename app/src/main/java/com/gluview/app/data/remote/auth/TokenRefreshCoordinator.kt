package com.gluview.app.data.remote.auth

import com.gluview.app.data.datastore.AppPreferences
import com.gluview.app.data.remote.GluSyncApi
import com.gluview.app.data.remote.dto.RefreshRequestDto
import com.gluview.app.data.remote.dto.expiresAtEpochMs
import com.gluview.app.di.RefreshApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Serializa a renovação do access token entre TODOS os chamadores (REST via [TokenAuthenticator]
 * e SignalR via [TokenRefresher]).
 *
 * Sem isso: quando o access token expira, várias chamadas simultâneas (comum na inicialização,
 * com várias telas/serviços batendo na API em paralelo) recebem 401 ao mesmo tempo e cada uma
 * dispara sua PRÓPRIA renovação — cada renovação é uma chamada Retrofit nova, disputando o mesmo
 * limite de conexões por host (5) do OkHttpClient principal. Como as chamadas ORIGINAIS que
 * geraram o 401 continuam "rodando" nesse mesmo pool enquanto esperam o Authenticator, e cada
 * renovação extra também precisa de uma vaga do MESMO pool, o pool esgota e nada mais progride
 * (nem as chamadas originais nem as renovações) — foi exatamente isso que travou o gráfico de
 * histórico para sempre, sem nenhum erro.
 *
 * Com o Mutex, só a primeira corrotina faz a chamada de rede de fato — e faz isso num cliente
 * HTTP dedicado ([RefreshApi], sem Authenticator/pool compartilhado), então nunca briga com o
 * próprio tráfego que está tentando desbloquear. As demais chamadas concorrentes reaproveitam o
 * token que essa primeira obteve.
 */
@Singleton
class TokenRefreshCoordinator @Inject constructor(
    private val preferences: AppPreferences,
    @RefreshApi private val refreshApi: GluSyncApi
) {
    private val mutex = Mutex()

    /** Resultado tipado — diferencia "não vai funcionar de novo sem login" (Invalid) de
     *  "tenta de novo mais tarde" (NetworkError), pra quem chama decidir o que fazer. */
    sealed class RefreshOutcome {
        data class Success(val accessToken: String) : RefreshOutcome()
        object NoRefreshToken : RefreshOutcome()
        /** Servidor respondeu (4xx) recusando o refresh token — inválido, revogado ou expirado. */
        object Invalid : RefreshOutcome()
        data class NetworkError(val cause: Throwable) : RefreshOutcome()
    }

    /** [staleAccessToken] é o token que falhou (ou o token corrente, se a chamada for proativa).
     * Se outra corrotina já tiver renovado enquanto esperávamos o lock, o token corrente já é
     * diferente do stale e reaproveitamos sem bater na rede de novo. */
    suspend fun refresh(staleAccessToken: String?): RefreshOutcome = mutex.withLock {
        val current = preferences.accessToken.first()
        if (current.isNotBlank() && current != staleAccessToken) return@withLock RefreshOutcome.Success(current)

        val refreshToken = preferences.refreshToken.first()
        if (refreshToken.isBlank()) return@withLock RefreshOutcome.NoRefreshToken

        // Idempotency key estável enquanto refreshToken não mudar — se esta chamada tiver sucesso
        // no servidor mas a resposta se perder na rede, o próximo retry reenvia o MESMO id e o
        // servidor reconhece como o mesmo client tentando de novo (janela de graça), em vez de
        // tratar a reapresentação do token antigo como reuso malicioso.
        val requestId = preferences.getOrCreatePendingRefreshRequestId()

        try {
            val result = refreshApi.refresh(RefreshRequestDto(refreshToken, requestId))
            preferences.saveTokens(result.accessToken, result.refreshToken, result.expiresAtEpochMs())
            RefreshOutcome.Success(result.accessToken)
        } catch (e: retrofit2.HttpException) {
            // 4xx = o servidor processou e recusou de propósito (token inválido/revogado/expirado).
            // 5xx cai como NetworkError — pode ser instabilidade passageira do servidor.
            if (e.code() in 400..499) RefreshOutcome.Invalid else RefreshOutcome.NetworkError(e)
        } catch (e: Exception) {
            RefreshOutcome.NetworkError(e)
        }
    }
}
