package com.gluview.wear.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

/**
 * A cada 15 min (o mínimo do WorkManager): se nenhum celular está conectado OU a última leitura tem
 * mais de 20 min, tenta o plano B na nuvem. Com o celular por perto mandando leitura, não faz nada
 * (economiza bateria do relógio). Também redesenha complication/tile, para a regra de dado velho
 * valer mesmo sem leitura nova.
 */
class CloudFetchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val now = System.currentTimeMillis()
        val last = SnapshotStore.load(applicationContext)
        val phoneConnected = runCatching {
            withTimeoutOrNull(5_000) { Wearable.getNodeClient(applicationContext).connectedNodes.await() }
                ?.any { it.isNearby } == true
        }.getOrDefault(false)
        val lastIsOld = last == null || now - last.measuredAtMs > STALE_FOR_FETCH_MS
        if (!phoneConnected || lastIsOld) CloudFetcher.fetch(applicationContext, now)
        SnapshotStore.refreshSurfaces(applicationContext)
        return Result.success()
    }

    companion object {
        private const val NAME = "gluview-cloud-fetch"
        private const val STALE_FOR_FETCH_MS = 20 * 60_000L

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<CloudFetchWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
