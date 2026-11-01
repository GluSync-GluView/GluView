package com.gluview.app.di

import com.gluview.app.BuildConfig
import com.gluview.app.data.datastore.AppPreferences
import com.gluview.app.data.remote.GluSyncApi
import com.gluview.app.data.remote.auth.AuthInterceptor
import com.gluview.app.data.remote.auth.BaseUrlRewriter
import com.gluview.app.data.remote.auth.TokenAuthenticator
import com.gluview.app.demo.DemoBackend
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    // URL base placeholder — será substituída dinamicamente pelo AuthInterceptor
    private const val PLACEHOLDER_BASE_URL = "https://placeholder.glusync.app/"

    // Auditoria 2026-07-09 (achado #8) — Level.BODY loga o corpo inteiro da requisição/resposta,
    // inclusive senha, refresh token e o código de 6 dígitos de verificação nas rotas /auth/*.
    // Redigir só o header Authorization não bastava (o segredo real estava no BODY, não no
    // header, nas rotas de senha/código). Solução: nas rotas /auth/*, nunca passa de HEADERS
    // (com Authorization redigido); no resto, mantém BODY em debug — só perde detalhe onde
    // haveria segredo pra vazar.
    @Provides
    @Singleton
    fun provideLoggingInterceptor(): Interceptor {
        if (!BuildConfig.DEBUG) return HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.NONE }

        val bodyLogger = HttpLoggingInterceptor().apply {
            redactHeader("Authorization")
            level = HttpLoggingInterceptor.Level.BODY
        }
        val headersOnlyLogger = HttpLoggingInterceptor().apply {
            redactHeader("Authorization")
            level = HttpLoggingInterceptor.Level.HEADERS
        }
        return Interceptor { chain ->
            val isAuthRoute = chain.request().url.encodedPath.contains("/auth/")
            (if (isAuthRoute) headersOnlyLogger else bodyLogger).intercept(chain)
        }
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(
        authInterceptor: AuthInterceptor,
        tokenAuthenticator: TokenAuthenticator,
        loggingInterceptor: Interceptor
    ): OkHttpClient = OkHttpClient.Builder()
        // Build demo: o servidor falso responde antes de tudo (nada vai para a rede).
        .apply { DemoBackend.seAtivo()?.let { addInterceptor(it) } }
        .addInterceptor(authInterceptor)
        .authenticator(tokenAuthenticator)
        .addInterceptor(loggingInterceptor)
        .build()

    @Provides
    @Singleton
    fun provideRetrofit(client: OkHttpClient): Retrofit = Retrofit.Builder()
        .baseUrl(PLACEHOLDER_BASE_URL)
        .client(client)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    @Provides
    @Singleton
    fun provideGluSyncApi(retrofit: Retrofit): GluSyncApi =
        retrofit.create(GluSyncApi::class.java)

    // Cliente/Retrofit/API dedicados à renovação de token (POST /auth/refresh) — instância
    // PRÓPRIA de OkHttpClient (Dispatcher/pool de conexões independente), sem AuthInterceptor
    // nem Authenticator. Ver TokenRefreshCoordinator para o porquê: se o refresh usasse o mesmo
    // cliente/pool do tráfego autenticado normal, uma renovação disparada a partir de um 401
    // teria que disputar vaga de conexão com a própria chamada que a originou — sob concorrência
    // (várias chamadas expirando ao mesmo tempo na inicialização) isso esgota o pool (máx.
    // 5 conexões/host) e trava tudo para sempre, sem nenhum erro.
    @Provides
    @Singleton
    @RefreshApi
    fun provideRefreshOkHttpClient(
        preferences: AppPreferences,
        loggingInterceptor: Interceptor
    ): OkHttpClient = OkHttpClient.Builder()
        .apply { DemoBackend.seAtivo()?.let { addInterceptor(it) } }
        .addInterceptor { chain ->
            val baseUrl = runBlocking { preferences.baseUrl.first() }
            chain.proceed(BaseUrlRewriter.rewrite(chain.request(), baseUrl))
        }
        .addInterceptor(loggingInterceptor)
        .build()

    @Provides
    @Singleton
    @RefreshApi
    fun provideRefreshRetrofit(@RefreshApi client: OkHttpClient): Retrofit = Retrofit.Builder()
        .baseUrl(PLACEHOLDER_BASE_URL)
        .client(client)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    @Provides
    @Singleton
    @RefreshApi
    fun provideRefreshGluSyncApi(@RefreshApi retrofit: Retrofit): GluSyncApi =
        retrofit.create(GluSyncApi::class.java)
}
