package com.gluview.app.data.remote.auth

import com.gluview.app.data.datastore.AppPreferences
import com.gluview.app.notification.NotificationHelper
import com.gluview.app.util.AppLogger
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Renova o access token (via refresh token) para as conexões SignalR (upload e recepção).
 * Diferente do REST (via [TokenAuthenticator], plugado no OkHttp), o cliente SignalR não tem
 * retry automático de token — se o access token (validade de 1h) expirar sem nenhuma chamada
 * REST acontecendo nesse meio tempo (comum no modo sensor BLE, sem polling), a conexão passa a
 * falhar com "negotiate: 401" para sempre. Os pontos que usam SignalR chamam isto explicitamente.
 *
 * Delega a [TokenRefreshCoordinator] em vez de chamar a API diretamente — assim uma renovação
 * disparada por aqui e outra disparada por um 401 do REST (ver [TokenAuthenticator]) ao mesmo
 * tempo colapsam numa única chamada de rede, em vez de brigarem pelo mesmo pool de conexões.
 */
@Singleton
class TokenRefresher @Inject constructor(
    private val preferences: AppPreferences,
    private val coordinator: TokenRefreshCoordinator,
    private val appLogger: AppLogger,
    private val notificationHelper: NotificationHelper
) {
    // Evita re-notificar (com som) a cada tentativa enquanto a sessão continuar inválida —
    // reseta assim que um refresh voltar a funcionar (ex.: usuário logou de novo).
    @Volatile private var sessionExpiredNotified = false

    /** true = token renovado com sucesso (accessToken novo já salvo nas preferências). */
    suspend fun refresh(): Boolean {
        val staleToken = preferences.accessToken.first()
        return when (val outcome = coordinator.refresh(staleToken)) {
            is TokenRefreshCoordinator.RefreshOutcome.Success -> {
                if (sessionExpiredNotified) {
                    sessionExpiredNotified = false
                    notificationHelper.cancelError()
                }
                true
            }
            TokenRefreshCoordinator.RefreshOutcome.NoRefreshToken -> {
                appLogger.error("AUTH", "Falha ao renovar token — sem refresh token salvo (nunca logou ou já deslogou)")
                false
            }
            TokenRefreshCoordinator.RefreshOutcome.Invalid -> {
                appLogger.error("AUTH", "Falha ao renovar token — refresh token inválido/revogado/expirado; login necessário")
                // Falha DEFINITIVA (não é rede) — avisa na hora em vez de esperar o alerta de
                // upload de 1h (fireUploadErrorAlert), que só cobre o caminho de envio de leitura.
                if (!sessionExpiredNotified) {
                    sessionExpiredNotified = true
                    notificationHelper.showErrorNotification(sessionExpired = true)
                }
                false
            }
            is TokenRefreshCoordinator.RefreshOutcome.NetworkError -> {
                appLogger.error("AUTH", "Falha ao renovar token — erro de rede, tentará de novo", mapOf("erro" to (outcome.cause.message ?: "desconhecido")))
                false
            }
        }
    }

    /** Heurística: a mensagem de erro do SignalR indica falha de autenticação (401)? */
    fun isAuthFailure(message: String?): Boolean = message?.contains("401") == true
}
