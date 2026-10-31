package com.gluview.app.data.remote.dto

import com.google.gson.annotations.SerializedName

data class LoginRequestDto(
    @SerializedName("email")    val email: String,
    @SerializedName("password") val password: String
)

data class RefreshRequestDto(
    @SerializedName("refreshToken") val refreshToken: String,
    // Idempotency key estável entre retries do mesmo refresh token — ver AppPreferences.
    // getOrCreatePendingRefreshRequestId e AuthService.RefreshAsync (janela de graça) no servidor.
    @SerializedName("requestId")    val requestId: String? = null
)

data class TokenResponseDto(
    @SerializedName("accessToken")  val accessToken: String,
    @SerializedName("refreshToken") val refreshToken: String,
    @SerializedName("role")         val role: String = "patient",
    @SerializedName("expiresAt")    val expiresAt: String? = null,
    @SerializedName("roles")        val roles: List<String>? = null
)

/** Epoch ms da expiração do access token — 0L se ausente/ilegível (chamador trata como "não sei quando expira"). */
fun TokenResponseDto.expiresAtEpochMs(): Long =
    expiresAt?.let { runCatching { java.time.OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() } ?: 0L

// F4/T30 — cadastro com papéis combináveis (paciente e/ou seguidor) + nome de exibição.
data class RegisterRequestDto(
    @SerializedName("email")       val email: String,
    @SerializedName("password")    val password: String,
    @SerializedName("role")        val role: String = "patient",
    @SerializedName("displayName") val displayName: String? = null,
    @SerializedName("roles")       val roles: List<String>? = null
)

// Cadastro não retorna mais token direto — exige confirmar o código de e-mail antes de logar.
data class RegisterResponseDto(
    @SerializedName("userId") val userId: String,
    @SerializedName("email")  val email: String
)

data class VerifyEmailRequestDto(
    @SerializedName("email") val email: String,
    @SerializedName("code")  val code: String
)

data class ResendVerificationRequestDto(
    @SerializedName("email") val email: String
)

// Esqueci minha senha — mesmo mecanismo de código de 6 dígitos.
data class ForgotPasswordRequestDto(
    @SerializedName("email") val email: String
)

data class ResetPasswordRequestDto(
    @SerializedName("email")       val email: String,
    @SerializedName("code")        val code: String,
    @SerializedName("newPassword") val newPassword: String
)

// F6/UX — edita o nome de exibição de uma conta já criada (PUT /auth/me)
data class UpdateProfileRequestDto(
    @SerializedName("displayName") val displayName: String?
)

// F4 — perfil do usuário autenticado (GET /auth/me)
data class UserProfileDto(
    @SerializedName("id")          val id: String,
    @SerializedName("email")       val email: String,
    @SerializedName("displayName") val displayName: String? = null,
    @SerializedName("roles")       val roles: List<String> = emptyList(),
    @SerializedName("createdAt")   val createdAt: String? = null
)

// T85 — Passkeys/WebAuthn. CredentialJson trafega o JSON cru devolvido pelo Android
// Credential Manager (registrationResponseJson/authenticationResponseJson) — o app só repassa,
// não interpreta o conteúdo.
data class WebAuthnRegisterVerifyRequestDto(
    @SerializedName("credentialJson") val credentialJson: String,
    @SerializedName("label")          val label: String
)

data class WebAuthnAuthenticateVerifyRequestDto(
    @SerializedName("credentialJson") val credentialJson: String
)

data class PasskeyInfoDto(
    @SerializedName("id")         val id: String,
    @SerializedName("label")      val label: String,
    @SerializedName("createdAt")  val createdAt: String,
    @SerializedName("lastUsedAt") val lastUsedAt: String? = null
)
