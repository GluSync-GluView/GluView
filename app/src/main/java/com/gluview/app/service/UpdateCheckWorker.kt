package com.gluview.app.service

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.gluview.app.R
import com.gluview.app.data.datastore.AppPreferences
import com.gluview.app.data.update.UpdateChecker
import com.gluview.app.data.update.UpdatePreferences
import com.gluview.app.data.update.UpdateSafetyRules
import com.gluview.app.notification.NotificationHelper
import com.gluview.app.util.withAppLanguage
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Checagem periódica de versão nova (plano OTA, E6). Só consulta e avisa: nunca baixa nem instala.
 *
 * Agendado na criação da aplicação com KEEP — fora do ciclo do GluSyncService e do logout, que só
 * cancelam os próprios workers. A cada 6 h (o plano sugeria 12 h; 6 h dá mais chances de cair
 * fora da madrugada, quando o aviso é segurado). Uma notificação por versão, sem som.
 */
class UpdateCheckWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun updateChecker(): UpdateChecker
        fun updatePreferences(): UpdatePreferences
        fun preferences(): AppPreferences
        fun notificationHelper(): NotificationHelper
    }

    companion object {
        const val WORK_NAME = "update_check"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(6, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request
            )
        }
    }

    override suspend fun doWork(): Result {
        val deps = EntryPointAccessors.fromApplication(applicationContext, Deps::class.java)
        val updatePrefs = deps.updatePreferences()
        if (!updatePrefs.autoCheck.first()) return Result.success()

        val result = deps.updateChecker().check(force = true)
        if (result is UpdateChecker.Result.Failed) return if (runAttemptCount < 3) Result.retry() else Result.success()
        if (result !is UpdateChecker.Result.Available) return Result.success()

        val latest = result.latest
        // Uma vez por versão, e nunca de madrugada (o próximo ciclo avisa).
        if (latest.versionCode == updatePrefs.notifiedCode()) return Result.success()
        if (UpdateSafetyRules.isNight(Calendar.getInstance().get(Calendar.HOUR_OF_DAY))) return Result.success()

        val ctx = applicationContext.withAppLanguage(deps.preferences().appLanguage.first())
        val helper = deps.notificationHelper()
        helper.createChannels() // sem o canal criado a notificação some em silêncio
        helper.showUpdateAvailable(
            title = ctx.getString(if (latest.security) R.string.notif_update_security_title else R.string.notif_update_title),
            message = ctx.getString(R.string.notif_update_text, latest.versionName),
            security = latest.security
        )
        updatePrefs.setNotifiedCode(latest.versionCode)
        return Result.success()
    }
}
