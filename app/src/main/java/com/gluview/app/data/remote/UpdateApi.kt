package com.gluview.app.data.remote

import com.gluview.app.data.remote.dto.UpdateDownloadLinkDto
import com.gluview.app.data.remote.dto.UpdateDownloadLinkRequestDto
import com.gluview.app.data.remote.dto.UpdateLatestDto
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

/**
 * Endpoints de atualização do app (plano OTA, E6/E7). Interface própria, montada sobre o cliente
 * HTTP principal (mesmo JWT e mesmo BaseUrlRewriter), mas com timeout de leitura longo: o Render
 * gratuito leva de ~30 s a ~1 min para acordar e o pedido de link não pode falhar por isso.
 * Só existem em /api/glusync/v1 (sem alias legado no servidor).
 */
interface UpdateApi {
    @GET("api/glusync/v1/app/update/latest")
    suspend fun latest(@Query("versionCode") versionCode: Long): UpdateLatestDto

    @POST("api/glusync/v1/app/update/download-link")
    suspend fun downloadLink(@Body request: UpdateDownloadLinkRequestDto): UpdateDownloadLinkDto
}
