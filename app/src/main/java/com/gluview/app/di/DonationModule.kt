package com.gluview.app.di

import com.gluview.app.data.donation.DonationApi
import com.gluview.app.demo.DemoBackend
import com.gluview.app.util.GluSyncConstants
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DonationModule {

    /** Cliente próprio, sem AuthInterceptor nem BaseUrlRewriter (ver [DonationApi]). Leitura longa
     *  pelo mesmo motivo do OTA: o Render gratuito leva até ~1 min para acordar. */
    @Provides
    @Singleton
    fun provideDonationApi(): DonationApi = Retrofit.Builder()
        .baseUrl("${GluSyncConstants.BASE_URL}/")
        .client(
            OkHttpClient.Builder()
                .apply { DemoBackend.seAtivo()?.let { addInterceptor(it) } }
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(90, TimeUnit.SECONDS)
                .callTimeout(120, TimeUnit.SECONDS)
                .build()
        )
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(DonationApi::class.java)
}
