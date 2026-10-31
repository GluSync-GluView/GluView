package com.gluview.app.di

import com.gluview.app.data.remote.UpdateApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

/** Qualifica o OkHttpClient ANÔNIMO que baixa o APK (plano OTA, seção 5.1): sem AuthInterceptor,
 *  sem Authenticator, sem BaseUrlRewriter e sem logging. Se o download usasse o cliente principal,
 *  o JWT iria para o host da URL assinada do GitHub e o rewriter trocaria o host pelo do GluSync. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class UpdateDownloadHttp

@Module
@InstallIn(SingletonComponent::class)
object UpdateModule {

    private const val PLACEHOLDER_BASE_URL = "https://placeholder.glusync.app/"

    @Provides
    @Singleton
    @UpdateDownloadHttp
    fun provideUpdateDownloadClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        // Redirects seguem só para https (a política de host é conferida no destino final).
        .followSslRedirects(false)
        .build()

    /** Mesmo cliente principal (JWT + rewriter + pool), só com leitura mais longa — ver [UpdateApi]. */
    @Provides
    @Singleton
    fun provideUpdateApi(client: OkHttpClient): UpdateApi = Retrofit.Builder()
        .baseUrl(PLACEHOLDER_BASE_URL)
        .client(client.newBuilder().readTimeout(90, TimeUnit.SECONDS).callTimeout(120, TimeUnit.SECONDS).build())
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(UpdateApi::class.java)
}
