package com.gluview.app.data.donation

import com.gluview.app.data.remote.dto.CreateDonationDto
import com.gluview.app.data.remote.dto.CreateDonationRequestDto
import com.gluview.app.data.remote.dto.DonationConfigDto
import com.gluview.app.data.remote.dto.DonationStatusDto
import com.gluview.app.data.remote.dto.StaticPixDto
import com.gluview.app.data.remote.dto.StaticPixRequestDto
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * Doação via Pix. Sempre no GluSync oficial ([com.gluview.app.util.GluSyncConstants.BASE_URL]),
 * por um cliente HTTP ANÔNIMO (ver DonationModule): doar não exige conta, funciona no modo 100%
 * local e nunca leva o JWT de quem usa outro servidor. Só existe em /api/glusync/v1.
 */
interface DonationApi {
    @GET("api/glusync/v1/donations/config")
    suspend fun config(): DonationConfigDto

    /** Modo chave fixa (padrão). */
    @POST("api/glusync/v1/donations/static-pix")
    suspend fun staticPix(@Body request: StaticPixRequestDto): StaticPixDto

    /** Daqui para baixo, só no modo Mercado Pago (guardado, desligado). */
    @POST("api/glusync/v1/donations")
    suspend fun create(@Body request: CreateDonationRequestDto): CreateDonationDto

    @GET("api/glusync/v1/donations/{id}")
    suspend fun status(@Path("id") id: String, @Header(TOKEN_HEADER) token: String): DonationStatusDto

    @POST("api/glusync/v1/donations/{id}/cancel")
    suspend fun cancel(@Path("id") id: String, @Header(TOKEN_HEADER) token: String): DonationStatusDto

    companion object {
        const val TOKEN_HEADER = "X-Donation-Token"
    }
}
