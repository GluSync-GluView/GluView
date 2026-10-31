package com.gluview.app.data.repository

import com.gluview.app.data.remote.GluSyncApi
import com.gluview.app.data.remote.dto.CreateSupportReportDto
import com.gluview.app.data.remote.dto.SupportReportCreatedDto
import javax.inject.Inject
import javax.inject.Singleton

/** Botão "Enviar log para o suporte" (Ajustes) — grava no banco + notifica o suporte por e-mail
 *  (sem o log completo, só o aviso — ver EmailService.SendSupportReportNotificationAsync). */
@Singleton
class SupportReportRepository @Inject constructor(
    private val api: GluSyncApi
) {
    suspend fun send(
        logText: String, userMessage: String?, appVersion: String?, deviceInfo: String?
    ): Result<SupportReportCreatedDto> = runCatching {
        api.createSupportReport(CreateSupportReportDto(logText, userMessage, appVersion, deviceInfo))
    }
}
