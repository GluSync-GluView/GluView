package com.gluview.app.data.repository

import com.gluview.app.data.datastore.AppPreferences
import com.gluview.app.data.remote.GluSyncApi
import com.gluview.app.data.remote.HistoryBackfillService
import com.gluview.app.data.remote.dto.LoginRequestDto
import com.gluview.app.data.remote.dto.expiresAtEpochMs
import com.gluview.app.data.remote.dto.RefreshRequestDto
import com.gluview.app.data.remote.dto.RegisterRequestDto
import com.gluview.app.data.remote.dto.ForgotPasswordRequestDto
import com.gluview.app.data.remote.dto.PasskeyInfoDto
import com.gluview.app.data.remote.dto.ResendVerificationRequestDto
import com.gluview.app.data.remote.dto.ResetPasswordRequestDto
import com.gluview.app.data.remote.dto.VerifyEmailRequestDto
import com.gluview.app.data.remote.dto.WebAuthnAuthenticateVerifyRequestDto
import com.gluview.app.data.remote.dto.WebAuthnRegisterVerifyRequestDto
import com.gluview.app.util.ApiError
import com.gluview.app.util.AppLogger
import kotlinx.coroutines.flow.first
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * O GluSync trata e-mail como identificador sem distinção entre maiúsculas e minúsculas.
 * Normalizar na fronteira do app evita que qualquer fluxo de autenticação dependa da forma
 * como o usuário digitou o endereço (inclusive espaços inseridos pelo preenchimento automático).
 */
internal fun normalizeAuthEmail(email: String): String = email.trim().lowercase(Locale.ROOT)

@Singleton
class AuthRepository @Inject constructor(
    private val api: GluSyncApi,
    private val preferences: AppPreferences,
    private val appLogger: AppLogger,
    private val historyBackfillService: HistoryBackfillService,
    private val glucoseRepository: GlucoseRepository
) {
    suspend fun login(baseUrl: String, email: String, password: String): Result<Unit> =
        runCatching {
            val normalizedEmail = normalizeAuthEmail(email)
            preferences.saveBaseUrl(baseUrl)
            val response = api.login(LoginRequestDto(normalizedEmail, password))
            preferences.saveTokens(response.accessToken, response.refreshToken, response.expiresAtEpochMs())
            preferences.saveLocalOnlyMode(false) // ADR-0030: com conta, o modo "sem conta" antigo acaba (dados locais ficam)
            preferences.saveUserEmail(normalizedEmail)
            preferences.saveMyRoles(response.roles ?: listOf(response.role))
            appLogger.usage("AUTH", "Login bem-sucedido", mapOf("email" to normalizedEmail))
            glucoseRepository.claimUnownedReadings(normalizedEmail)
            historyBackfillService.backfillIfNeeded(normalizedEmail)
        }.onFailure { e ->
            appLogger.error("AUTH", "Falha no login", mapOf("erro" to (e.message ?: "desconhecido")))
        }.recoverCatching { e -> throw loginApiError(e) }

    // F4/T33-T34 — cadastro com papéis combináveis (paciente e/ou seguidor) e nome.
    // Não loga direto — a conta fica pendente até confirmar o código enviado por e-mail
    // (verifyEmail). Isso é o que impede robôs de criar conta só com um e-mail qualquer.
    suspend fun register(
        baseUrl: String,
        email: String,
        password: String,
        displayName: String?,
        roles: List<String>
    ): Result<Unit> = runCatching {
        val normalizedEmail = normalizeAuthEmail(email)
        preferences.saveBaseUrl(baseUrl)
        val primary = roles.firstOrNull() ?: "patient"
        api.register(
            RegisterRequestDto(
                email = normalizedEmail,
                password = password,
                role = primary,
                displayName = displayName?.takeIf { it.isNotBlank() },
                roles = roles.ifEmpty { null }
            )
        )
        appLogger.usage("AUTH", "Cadastro criado, aguardando verificação", mapOf("email" to normalizedEmail))
    }.onFailure { e ->
        appLogger.error("AUTH", "Falha no cadastro", mapOf("erro" to (e.message ?: "desconhecido")))
    }.recoverCatching { e -> throw apiError(e) }

    /** Confirma o código de 6 dígitos enviado por e-mail — só aqui o login realmente acontece. */
    suspend fun verifyEmail(email: String, code: String, roles: List<String>): Result<Unit> = runCatching {
        val normalizedEmail = normalizeAuthEmail(email)
        val response = api.verifyEmail(VerifyEmailRequestDto(normalizedEmail, code))
        preferences.saveTokens(response.accessToken, response.refreshToken, response.expiresAtEpochMs())
        preferences.saveLocalOnlyMode(false) // ADR-0030: com conta, o modo "sem conta" antigo acaba (dados locais ficam)
        preferences.saveUserEmail(normalizedEmail)
        preferences.saveMyRoles(response.roles ?: roles)
        appLogger.usage("AUTH", "E-mail verificado — login concluído", mapOf("email" to normalizedEmail))
        glucoseRepository.claimUnownedReadings(normalizedEmail)
        historyBackfillService.backfillIfNeeded(normalizedEmail)
    }.onFailure { e ->
        appLogger.error("AUTH", "Falha ao verificar e-mail", mapOf("erro" to (e.message ?: "desconhecido")))
    }.recoverCatching { e -> throw apiError(e) }

    suspend fun resendVerification(email: String): Result<Unit> = runCatching {
        val normalizedEmail = normalizeAuthEmail(email)
        api.resendVerification(ResendVerificationRequestDto(normalizedEmail))
        appLogger.usage("AUTH", "Código de verificação reenviado", mapOf("email" to normalizedEmail))
    }.onFailure { e ->
        appLogger.error("AUTH", "Falha ao reenviar código", mapOf("erro" to (e.message ?: "desconhecido")))
    }.recoverCatching { e -> throw apiError(e) }

    /** "Esqueci minha senha": pede o envio do código. Sempre "sucesso" do ponto de vista da
     *  API (o servidor não revela se o e-mail existe), então basta checar erro de rede/HTTP. */
    suspend fun forgotPassword(email: String): Result<Unit> = runCatching {
        val normalizedEmail = normalizeAuthEmail(email)
        api.forgotPassword(ForgotPasswordRequestDto(normalizedEmail))
        appLogger.usage("AUTH", "Código de redefinição de senha solicitado", mapOf("email" to normalizedEmail))
    }.onFailure { e ->
        appLogger.error("AUTH", "Falha ao pedir redefinição de senha", mapOf("erro" to (e.message ?: "desconhecido")))
    }.recoverCatching { e -> throw apiError(e) }

    /** Confirma código + nova senha — já loga em seguida (mesmo padrão do verifyEmail). */
    suspend fun resetPassword(email: String, code: String, newPassword: String): Result<Unit> = runCatching {
        val normalizedEmail = normalizeAuthEmail(email)
        val response = api.resetPassword(ResetPasswordRequestDto(normalizedEmail, code, newPassword))
        preferences.saveTokens(response.accessToken, response.refreshToken, response.expiresAtEpochMs())
        preferences.saveLocalOnlyMode(false) // ADR-0030: com conta, o modo "sem conta" antigo acaba (dados locais ficam)
        preferences.saveUserEmail(normalizedEmail)
        preferences.saveMyRoles(response.roles ?: listOf(response.role))
        appLogger.usage("AUTH", "Senha redefinida — login concluído", mapOf("email" to normalizedEmail))
        glucoseRepository.claimUnownedReadings(normalizedEmail)
        historyBackfillService.backfillIfNeeded(normalizedEmail)
    }.onFailure { e ->
        appLogger.error("AUTH", "Falha ao redefinir senha", mapOf("erro" to (e.message ?: "desconhecido")))
    }.recoverCatching { e -> throw apiError(e) }

    // Auditoria 2026-07-09 (achado #7) — revoga o refresh token atual no servidor antes de
    // limpar a sessão local. Best-effort: se a chamada falhar (sem rede, servidor fora), o
    // logout local acontece do mesmo jeito — não faz sentido travar o usuário na tela por causa
    // disso, e o token vai expirar sozinho em até 30 dias mesmo sem essa revogação explícita.
    suspend fun logout() {
        runCatching {
            val token = preferences.refreshToken.first()
            if (token.isNotBlank()) api.logout(RefreshRequestDto(token))
        }.onFailure { e ->
            appLogger.error("AUTH", "Falha ao revogar sessão no servidor (logout local prossegue)", mapOf("erro" to (e.message ?: "desconhecido")))
        }
        appLogger.usage("AUTH", "Logout")
        preferences.clearSession()
        glucoseRepository.clearCurrentReadingCache()
    }

    // ── T85 — Passkeys/WebAuthn (atrás da flag) ─────────────────────────────────
    // Só o round-trip com o servidor mora aqui; a interação com o Credential Manager
    // (que precisa de um Context de Activity) é feita pelo PasskeyManager, orquestrada
    // pelo ViewModel — mesma divisão que o app já usa pro BLE do Mi Band.

    /** JSON de opções (Fido2NetLib CredentialCreateOptions) pro Android Credential Manager. */
    suspend fun beginPasskeyRegistration(): Result<String> = runCatching {
        api.beginPasskeyRegistration().use { it.string() }
    }.recoverCatching { e -> throw apiError(e) }

    suspend fun finishPasskeyRegistration(credentialJson: String, label: String): Result<Unit> = runCatching {
        val response = api.finishPasskeyRegistration(WebAuthnRegisterVerifyRequestDto(credentialJson, label))
        if (!response.isSuccessful) throw retrofit2.HttpException(response)
        appLogger.usage("AUTH", "Passkey cadastrada", mapOf("label" to label))
    }.onFailure { e ->
        appLogger.error("AUTH", "Falha ao cadastrar passkey", mapOf("erro" to (e.message ?: "desconhecido")))
    }.recoverCatching { e -> throw apiError(e) }

    suspend fun listPasskeys(): Result<List<PasskeyInfoDto>> = runCatching { api.listPasskeys() }
        .recoverCatching { e -> throw apiError(e) }

    suspend fun revokePasskey(id: String): Result<Unit> = runCatching {
        val response = api.revokePasskey(id)
        if (!response.isSuccessful) throw retrofit2.HttpException(response)
        appLogger.usage("AUTH", "Passkey removida")
    }.onFailure { e ->
        appLogger.error("AUTH", "Falha ao remover passkey", mapOf("erro" to (e.message ?: "desconhecido")))
    }.recoverCatching { e -> throw apiError(e) }

    /** JSON de opções (Fido2NetLib AssertionOptions) — sem e-mail, é o que faz o login "sem senha". */
    suspend fun beginPasskeyLogin(): Result<String> = runCatching {
        api.beginPasskeyLogin().use { it.string() }
    }.recoverCatching { e -> throw apiError(e) }

    suspend fun finishPasskeyLogin(baseUrl: String, credentialJson: String): Result<Unit> = runCatching {
        preferences.saveBaseUrl(baseUrl)
        val response = api.finishPasskeyLogin(WebAuthnAuthenticateVerifyRequestDto(credentialJson))
        preferences.saveTokens(response.accessToken, response.refreshToken, response.expiresAtEpochMs())
        preferences.saveLocalOnlyMode(false) // ADR-0030: com conta, o modo "sem conta" antigo acaba (dados locais ficam)
        // Login sem e-mail: o TokenResponse não traz o e-mail do usuário — busca em /auth/me.
        val profile = api.getMe()
        preferences.saveUserEmail(profile.email)
        preferences.saveMyRoles(response.roles ?: listOf(response.role))
        appLogger.usage("AUTH", "Login por passkey bem-sucedido", mapOf("email" to profile.email))
        glucoseRepository.claimUnownedReadings(profile.email)
        historyBackfillService.backfillIfNeeded(profile.email)
    }.onFailure { e ->
        appLogger.error("AUTH", "Falha no login por passkey", mapOf("erro" to (e.message ?: "desconhecido")))
    }.recoverCatching { e -> throw apiError(e) }

    /** Resume a falha: o campo "erro" do corpo JSON do GluSync quando houver, senão o texto da
     *  exceção (mensagem de sempre, para log e regras); [ApiError.kind] diz à tela o que traduzir. */
    private fun apiError(e: Throwable): ApiError {
        val reason = ApiError.serverReason(e)
        return ApiError(reason ?: e.message ?: "Erro ao conectar ao servidor", ApiError.kindOf(e, reason), e)
    }

    /** O login usa 401 sem corpo para não revelar se uma conta existe ou está verificada. */
    private fun loginApiError(e: Throwable): ApiError {
        val http = e as? retrofit2.HttpException
        if (http?.code() == 401) {
            return ApiError("E-mail ou senha incorretos. Se a conta for nova, confirme o código enviado por e-mail.",
                ApiError.Kind.BAD_CREDENTIALS, e)
        }
        if (http != null && http.code() in 500..599) {
            return ApiError("O GluSync está temporariamente indisponível. Tente novamente em instantes.",
                ApiError.Kind.UNAVAILABLE, e)
        }
        return apiError(e)
    }
}
