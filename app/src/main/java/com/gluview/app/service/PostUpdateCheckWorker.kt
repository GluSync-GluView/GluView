package com.gluview.app.service

import android.app.ActivityManager
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.gluview.app.R
import com.gluview.app.data.datastore.AppPreferences
import com.gluview.app.data.repository.GlucoseRepository
import com.gluview.app.data.update.PostUpdateVerifier
import com.gluview.app.notification.NotificationHelper
import com.gluview.app.util.AppLogger
import com.gluview.app.util.withAppLanguage
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Verificação ativa ~10 min depois de uma atualização (plano OTA, E7). Enfileirado ANTES do commit
 * da sessão, sobrevive à morte do processo na instalação. Confere se a versão mudou e se o
 * monitoramento voltou — serviço rodando e leitura recente. Se não, avisa com prioridade alta:
 * cobre a cota diária do dataSync (Android 15+) e o Bluetooth que não reconecta.
 */
class PostUpdateCheckWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun postUpdateVerifier(): PostUpdateVerifier
        fun updatePreferences(): com.gluview.app.data.update.UpdatePreferences
        fun glucoseRepository(): GlucoseRepository
        fun preferences(): AppPreferences
        fun notificationHelper(): NotificationHelper
        fun appLogger(): AppLogger
    }

    companion object {
        const val WORK_NAME = "post_update_check"
        private val RECENT_READING_MS = TimeUnit.MINUTES.toMillis(15)
        private val CONFIRMED_WINDOW_MS = TimeUnit.MINUTES.toMillis(30)
    }

    override suspend fun doWork(): Result {
        val deps = EntryPointAccessors.fromApplication(applicationContext, Deps::class.java)
        // A abertura do processo novo normalmente já reconciliou; aqui vale a confirmação recente.
        val outcome = deps.postUpdateVerifier().reconcile()
        val confirmedRecently = System.currentTimeMillis() - deps.updatePreferences().confirmedAtMs() <= CONFIRMED_WINDOW_MS
        if (outcome != PostUpdateVerifier.Outcome.UPDATED && !confirmedRecently) return Result.success()
        val prefs = deps.preferences()
        if (!prefs.isConfigured.first()) return Result.success()

        val serviceRunning = isServiceRunning()
        val repo = deps.glucoseRepository()
        val latest = repo.currentReading.value ?: repo.getLatestOnce()
        val hasRecentReading = latest != null && latest.epochMs > 0 &&
            System.currentTimeMillis() - latest.epochMs <= RECENT_READING_MS

        val appLogger = deps.appLogger()
        appLogger.usage("UPDATE", "Verificação pós-atualização", mapOf(
            "servico" to serviceRunning, "leituraRecente" to hasRecentReading
        ))
        if (!serviceRunning || !hasRecentReading) {
            val ctx = applicationContext.withAppLanguage(prefs.appLanguage.first())
            deps.notificationHelper().showUpdateProblem(
                ctx.getString(R.string.notif_update_problem_title),
                ctx.getString(R.string.notif_update_problem_text)
            )
        }
        return Result.success()
    }

    /** getRunningServices é deprecado, mas continua devolvendo os serviços do próprio app. */
    @Suppress("DEPRECATION")
    private fun isServiceRunning(): Boolean =
        applicationContext.getSystemService(ActivityManager::class.java)
            ?.getRunningServices(Int.MAX_VALUE)
            ?.any { it.service.className == GluSyncService::class.java.name && it.service.packageName == applicationContext.packageName }
            ?: false
}
