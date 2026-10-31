package com.gluview.app.service

import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

class ServiceWatchdogWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    companion object {
        const val WORK_NAME = "service_watchdog"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<ServiceWatchdogWorker>(15, TimeUnit.MINUTES)
                .setInitialDelay(15, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }

    override suspend fun doWork(): Result {
        // No Android 12+, startForegroundService() de background lança
        // ForegroundServiceStartNotAllowedException se o app não estiver isento de bateria.
        // Nesse caso o START_STICKY já garante o restart; apenas logamos e retornamos sucesso.
        try {
            applicationContext.startForegroundService(
                Intent(applicationContext, GluSyncService::class.java)
            )
        } catch (e: Exception) {
            // Silencioso: o serviço será reiniciado via START_STICKY ou na próxima ciclo do watchdog
        }
        return Result.success()
    }
}
