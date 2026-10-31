package com.gluview.app.data.remote

import com.gluview.app.data.remote.dto.*
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming

interface GluSyncApi {

    @POST("auth/login")
    suspend fun login(@Body request: LoginRequestDto): TokenResponseDto

    // F4 — cadastro de usuário (papéis combináveis) e perfil. Não retorna token — exige
    // confirmar o código enviado por e-mail (verifyEmail) antes de autenticar.
    @POST("auth/register")
    suspend fun register(@Body request: RegisterRequestDto): RegisterResponseDto

    // Anti-robô: confirma o código de 6 dígitos enviado por e-mail no cadastro
    @POST("auth/verify-email")
    suspend fun verifyEmail(@Body request: VerifyEmailRequestDto): TokenResponseDto

    @POST("auth/resend-verification")
    suspend fun resendVerification(@Body request: ResendVerificationRequestDto): Response<Unit>

    // Esqueci minha senha
    @POST("auth/forgot-password")
    suspend fun forgotPassword(@Body request: ForgotPasswordRequestDto): Response<Unit>

    @POST("auth/reset-password")
    suspend fun resetPassword(@Body request: ResetPasswordRequestDto): TokenResponseDto

    @GET("auth/me")
    suspend fun getMe(): UserProfileDto

    @PUT("auth/me")
    suspend fun updateMe(@Body request: UpdateProfileRequestDto): Response<Unit>

    @POST("auth/refresh")
    suspend fun refresh(@Body request: RefreshRequestDto): TokenResponseDto

    // Auditoria 2026-07-09 (achado #7) — revoga o refresh token no servidor no logout
    // (antes só limpava a sessão local; um token já copiado continuava válido por 30 dias).
    @POST("auth/logout")
    suspend fun logout(@Body request: RefreshRequestDto): Response<Unit>

    // T85 — Passkeys/WebAuthn (atrás da flag; servidor devolve 404 se WebAuthn:Enabled=false).
    // Os endpoints de /options devolvem o JSON de opções do Fido2NetLib cru (não um DTO) —
    // é repassado direto pro Android Credential Manager, por isso ResponseBody em vez de um tipo.
    @POST("auth/webauthn/register/options")
    suspend fun beginPasskeyRegistration(): ResponseBody

    @POST("auth/webauthn/register/verify")
    suspend fun finishPasskeyRegistration(@Body request: WebAuthnRegisterVerifyRequestDto): Response<Unit>

    @GET("auth/webauthn/credentials")
    suspend fun listPasskeys(): List<PasskeyInfoDto>

    @DELETE("auth/webauthn/credentials/{id}")
    suspend fun revokePasskey(@Path("id") id: String): Response<Unit>

    @POST("auth/webauthn/authenticate/options")
    suspend fun beginPasskeyLogin(): ResponseBody

    @POST("auth/webauthn/authenticate/verify")
    suspend fun finishPasskeyLogin(@Body request: WebAuthnAuthenticateVerifyRequestDto): TokenResponseDto

    @GET("glucose/wear")
    suspend fun getWearData(): WearDataDto

    // S19 — Gráfico histórico
    @GET("glucose/range")
    suspend fun getRange(
        @Query("from")     from:     String? = null,
        @Query("to")       to:       String? = null,
        @Query("page")     page:     Int     = 1,
        @Query("pageSize") pageSize: Int     = 2000,
        @Query("orderBy")  orderBy:  String  = "date",
        @Query("order")    order:    String  = "asc"
    ): PagedResultDto<GlucoseEntryDto>

    // Reenvio em lote de leituras locais (validador noturno de buracos — GapBackfillWorker).
    // O servidor grava cada entrada com o Date informado (sem sobrescrever pra "agora"), então
    // preserva o horário real da medição mesmo enviado horas/dias depois.
    @POST("glucose/entries")
    suspend fun postEntries(@Body entries: List<GlucoseEntryDto>): Response<Unit>

    // S20 — Padrão diário médio (pivot dia × slot de 3h). patientId: modo seguidor, paciente acompanhado.
    @GET("glucose/pivot")
    suspend fun getPivot(
        @Query("dias")      dias:      Int     = 30,
        @Query("ano")       ano:       Int?    = null,
        @Query("mes")       mes:       Int?    = null,
        @Query("patientId") patientId: String? = null
    ): List<PivotRowDto>

    // S21 — TIR detalhado por dia. patientId: modo seguidor, paciente acompanhado.
    @GET("report/tir")
    suspend fun getTirReport(
        @Query("dias")      dias:      Int     = 30,
        @Query("patientId") patientId: String? = null
    ): TirReportDto

    // S21 — Estatísticas diárias: média, min, max, desvio, TIR, HbA1c. patientId: modo seguidor.
    @GET("glucose/stats/daily")
    suspend fun getDailyStats(
        @Query("dias")      dias:      Int     = 30,
        @Query("patientId") patientId: String? = null
    ): List<DailyStatsDto>

    // S23 — Relatório completo para médico. patientId: modo seguidor, paciente acompanhado.
    @GET("report/summary")
    suspend fun getSummaryReport(
        @Query("mes")       mes:       Int?    = null,
        @Query("ano")       ano:       Int?    = null,
        @Query("patientId") patientId: String? = null
    ): SummaryReportDto

    // F5 — API Keys (exige JWT; o AuthInterceptor injeta o Bearer)
    @POST("apikeys")
    suspend fun createApiKey(@Body request: CreateApiKeyRequestDto): CreatedApiKeyDto

    @GET("apikeys")
    suspend fun listApiKeys(): List<ApiKeyInfoDto>

    @DELETE("apikeys/{id}")
    suspend fun revokeApiKey(@Path("id") id: String): Response<Unit>

    // Fase 4 do plano 06_watchfaces_v2_arquitetura.md — biblioteca de watchfaces sincronizada
    @GET("watchfaces/projects")
    suspend fun listWatchfaceProjects(): List<WatchfaceProjectDto>

    @GET("watchfaces/projects/{id}")
    suspend fun getWatchfaceProject(@Path("id") id: String): WatchfaceProjectDto

    @POST("watchfaces/projects")
    suspend fun createWatchfaceProject(@Body request: SaveWatchfaceProjectRequestDto): WatchfaceProjectDto

    @PUT("watchfaces/projects/{id}")
    suspend fun updateWatchfaceProject(@Path("id") id: String, @Body request: SaveWatchfaceProjectRequestDto): WatchfaceProjectDto

    @DELETE("watchfaces/projects/{id}")
    suspend fun deleteWatchfaceProject(@Path("id") id: String): Response<Unit>

    // Fase 6 — submete pra revisão da loja.
    @POST("watchfaces/projects/{id}/publish")
    suspend fun publishWatchfaceProject(@Path("id") id: String): Response<PublishWatchfaceProjectResponseDto>

    // Fase 6 — vitrine da loja (beta fechado; 403 = conta sem o entitlement "watchface_store",
    // mesmo gate que o GluWebView usa em WatchfaceStorePage.tsx).
    @GET("watchfaces/store")
    suspend fun listWatchfaceStore(@Query("search") search: String? = null): Response<List<WatchfaceStoreListingDto>>

    @Streaming
    @GET("watchfaces/store/{id}/artifact")
    suspend fun downloadWatchfaceStoreArtifact(
        @Path("id") publicationId: String,
        @Query("wearableId") wearableId: String
    ): Response<ResponseBody>

    // F6 — Acompanhamento (paciente ↔ seguidor); exige JWT
    @POST("follow/invite")
    suspend fun createInvite(@Body request: InviteRequestDto): InviteResponseDto

    @GET("follow/links")
    suspend fun listFollowLinks(): List<CaregiverLinkViewDto>

    @POST("follow/respond")
    suspend fun respondInvite(@Body request: RespondInviteRequestDto): Response<Unit>

    @DELETE("follow/{linkId}")
    suspend fun revokeFollowLink(@Path("linkId") linkId: String): Response<Unit>

    @GET("follow/{patientId}/glucose/current")
    suspend fun followedPatientCurrent(@Path("patientId") patientId: String): GlucoseEntryDto

    /** Limites de alerta de um paciente acompanhado (escopo glucose:current; L1). */
    @GET("alerts/thresholds")
    suspend fun patientThresholds(@Query("patientId") patientId: String): AlertThresholdsDto

    @GET("follow/{patientId}/glucose/range")
    suspend fun followedPatientRange(
        @Path("patientId") patientId: String,
        @Query("from") from: Long? = null,
        @Query("to") to: Long? = null
    ): List<GlucoseEntryDto>

    // Histórico de sensores (mesmo modelo das glicemias); exige JWT
    @POST("sensors/history/start")
    suspend fun startSensorHistory(@Body request: SensorHistoryStartRequestDto): Response<Unit>

    @POST("sensors/history/end")
    suspend fun endSensorHistory(): Response<Unit>

    @GET("sensors/history")
    suspend fun getSensorHistory(@Query("limit") limit: Int = 200): List<SensorHistoryDto>

    // Sensor atualmente ativo (ended_at NULL) — usado pra reconciliar calibração após limpeza
    // de dados local. 404 se não houver sensor aberto.
    @GET("sensors/history/active")
    suspend fun getActiveSensorHistory(): Response<SensorHistoryDto>

    @PUT("sensors/history/calibration")
    suspend fun updateSensorCalibration(@Body request: SensorCalibrationRequestDto): Response<Unit>

    // Integrações externas (Dexcom Share, LibreLinkUp) — exige JWT
    @POST("integrations/{provider}/connect")
    suspend fun connectIntegration(
        @Path("provider") provider: String,
        @Body request: ConnectIntegrationRequestDto
    ): Response<Unit>

    @GET("integrations")
    suspend fun listIntegrations(): List<IntegrationStatusDto>

    @DELETE("integrations/{provider}")
    suspend fun disconnectIntegration(@Path("provider") provider: String): Response<Unit>

    // Modo Debug/Admin — gerador de dados sintéticos; servidor também valida o e-mail admin
    @POST("debug/seed/backfill")
    suspend fun debugSeedBackfill(): DebugBackfillResponseDto

    @POST("debug/seed/toggle")
    suspend fun debugSeedToggle(@Body request: DebugToggleRequestDto): Response<Unit>

    @GET("debug/seed/status")
    suspend fun debugSeedStatus(): DebugSeedStatusDto

    // Preferências de alerta + Pushover (exige JWT)
    @GET("alerts/preferences")
    suspend fun getAlertPreferences(): AlertPreferencesDto

    @PUT("alerts/preferences")
    suspend fun putAlertPreferences(@Body request: AlertPreferencesDto): Response<Unit>

    @POST("alerts/preferences/test-pushover")
    suspend fun testPushover(@Body request: TestPushoverRequestDto): TestPushoverResponseDto

    // ADR-0029 — cadastro por convite em camadas + beta da bomba de insulina/sensores.
    @GET("config/features")
    suspend fun getFeatureFlags(): FeatureFlagsDto

    @POST("access-requests")
    suspend fun createAccessRequest(@Body request: CreateAccessRequestDto): CreateAccessRequestResponseDto

    @POST("access-requests/verify")
    suspend fun verifyAccessRequest(@Body request: VerifyAccessRequestDto): Response<Unit>

    @POST("access-requests/feature")
    suspend fun createFeatureAccessRequest(@Body request: CreateFeatureAccessRequestDto): AccessRequestDto

    @GET("access-requests/me/status")
    suspend fun getAccessRequestStatus(): List<AccessRequestFeatureStateDto>

    // Autenticado — histórico completo (nota + comentário do admin), tela "Minhas candidaturas".
    @GET("access-requests/me")
    suspend fun getMyAccessRequests(): List<MyAccessRequestDto>

    // Público — status por ID, pra quem ainda não tem conta (pedido de acesso ao app). Nenhum
    // token é enviado aqui na prática (chamado antes do login existir), mas a rota também não
    // exige RequireAuthorization no servidor, então funciona igual com ou sem sessão.
    @GET("access-requests/{id}/status")
    suspend fun getAccessRequestPublicStatus(@Path("id") id: String): MyAccessRequestDto

    @POST("support/reports")
    suspend fun createSupportReport(@Body request: CreateSupportReportDto): SupportReportCreatedDto
}
