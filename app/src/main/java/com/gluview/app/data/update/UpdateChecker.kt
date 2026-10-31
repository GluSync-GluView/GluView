package com.gluview.app.data.update

import android.os.Build
import com.gluview.app.BuildConfig
import com.gluview.app.data.datastore.AppPreferences
import com.gluview.app.data.remote.UpdateApi
import com.gluview.app.util.AppLogger
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pergunta ao GluSync se há versão nova (plano OTA, E6). Só metadados: a URL de download é pedida
 * na hora de baixar, pelo [UpdateInstaller]. Chamado pelo [com.gluview.app.service.UpdateCheckWorker]
 * (periódico), ao abrir o app (com intervalo mínimo) e pelo botão "Verificar agora".
 */
@Singleton
class UpdateChecker @Inject constructor(
    private val api: UpdateApi,
    private val preferences: AppPreferences,
    private val updatePreferences: UpdatePreferences,
    private val appLogger: AppLogger
) {
    sealed interface Result {
        data class Available(val latest: UpdatePreferences.KnownLatest) : Result
        data object UpToDate : Result
        /** Versão nova exige um Android mais recente que o deste aparelho. */
        data class UnsupportedAndroid(val latest: UpdatePreferences.KnownLatest) : Result
        /** Modo 100% local ou sem servidor configurado: sem como consultar (plano, seção 5.6). */
        data object NoServer : Result
        data object Skipped : Result
        data class Failed(val error: String) : Result
    }

    private companion object {
        val MIN_INTERVAL_MS = TimeUnit.HOURS.toMillis(1)
    }

    suspend fun check(force: Boolean): Result {
        if (preferences.localOnlyMode.first() || preferences.baseUrl.first().isBlank()) return Result.NoServer
        val now = System.currentTimeMillis()
        if (!force && now - updatePreferences.lastCheckMs.first() < MIN_INTERVAL_MS) return Result.Skipped

        val dto = try {
            api.latest(BuildConfig.VERSION_CODE.toLong())
        } catch (e: Exception) {
            appLogger.usage("UPDATE", "Checagem falhou", mapOf("erro" to e.javaClass.simpleName))
            return Result.Failed(e.javaClass.simpleName)
        }

        val code = dto.versionCode
        if (!dto.updateAvailable || code == null || code <= BuildConfig.VERSION_CODE) {
            updatePreferences.saveCheck(now, null)
            return Result.UpToDate
        }
        val latest = UpdatePreferences.KnownLatest(
            versionCode = code,
            versionName = dto.versionName ?: code.toString(),
            sizeBytes = dto.sizeBytes ?: 0L,
            minSdk = dto.minSdk ?: 0,
            security = dto.security
        )
        updatePreferences.saveCheck(now, latest)
        appLogger.usage("UPDATE", "Versão nova disponível", mapOf(
            "versionCode" to code, "versionName" to latest.versionName, "seguranca" to latest.security
        ))
        return if (latest.minSdk > Build.VERSION.SDK_INT) Result.UnsupportedAndroid(latest) else Result.Available(latest)
    }
}
