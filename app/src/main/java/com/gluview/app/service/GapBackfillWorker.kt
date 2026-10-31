package com.gluview.app.service

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.gluview.app.data.repository.GlucoseRepository
import com.gluview.app.data.repository.StatsRepository
import com.gluview.app.util.AppLogger
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * Validador noturno de continuidade: confere se o GluSync recebeu leituras a cada ~5min na janela
 * de lookback. Onde houver buraco, procura no Room local se a leitura existe (gravada pelo BLE mas
 * nunca sincronizada — ex.: serviço derrubado pelo Android, ou histórico/trend do sensor
 * recuperado por [com.gluview.app.data.libre2.Libre2Source.persistHistorical]) e reenvia com o
 * horário ORIGINAL da medição (o servidor não sobrescreve pra "agora" — dateMs vira o timestamp
 * do backfill).
 *
 * Motivação: em 01/07/2026 o upload ficou ~11h42 sem mandar nada (GluSyncUploader.ensureConnected
 * travou num blockingAwait sem timeout — corrigido). Esse worker é a rede de segurança pra
 * qualquer buraco futuro, seja qual for a causa.
 *
 * LOOKBACK ampliado (21/07/2026): antes cobria só 26h — qualquer histórico do sensor
 * mais antigo que isso (ex.: pontos de trend recuperados de um período longo sem BLE conectado)
 * nunca sincronizava, porque os outros 2 caminhos de upload (live/cursor) descartam qualquer
 * timestamp no passado. Agora cobre até a vida útil máxima do sensor (14 dias — limite físico
 * real do FreeStyle Libre 2 Plus, não um número arbitrário), usando
 * [com.gluview.app.data.repository.StatsRepository.getAllTimestamps] (paginado — sem isso, uma
 * janela de dias facilmente passa das 2000 leituras do teto de página e o worker veria "buracos"
 * falsos onde a nuvem já tinha dado, só não visto na 1ª página).
 */
class GapBackfillWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun glucoseRepository(): GlucoseRepository
        fun statsRepository(): StatsRepository
        fun appLogger(): AppLogger
    }

    companion object {
        const val WORK_NAME = "gap_backfill"

        // Alvo do projeto é "pelo menos a cada 5 min"; usamos o mesmo valor como limiar do buraco.
        private val GAP_THRESHOLD_MS = TimeUnit.MINUTES.toMillis(5)

        // Vida útil máxima do FreeStyle Libre 2 Plus (14 dias) + 1 dia de folga — cobre qualquer
        // histórico/trend que o sensor consiga entregar, do início ao fim do seu uso. Roda 1x/dia,
        // então reverificar dias já checados é barato (upsert idempotente no servidor;
        // getAllTimestamps pagina, então não estoura o teto de 2000/página da API).
        private val LOOKBACK = TimeUnit.DAYS.toMillis(15)

        // Não cobra os últimos minutos: pode ser só o upload ao vivo ainda em trânsito.
        private val EDGE_GRACE_MS = TimeUnit.MINUTES.toMillis(15)

        fun schedule(context: Context) {
            val zone = ZoneId.of("America/Sao_Paulo")
            val now = ZonedDateTime.now(zone)
            var next4am = now.withHour(4).withMinute(0).withSecond(0).withNano(0)
            if (!next4am.isAfter(now)) next4am = next4am.plusDays(1)
            val initialDelayMs = Duration.between(now, next4am).toMillis()

            val request = PeriodicWorkRequestBuilder<GapBackfillWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(initialDelayMs, TimeUnit.MILLISECONDS)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }

    override suspend fun doWork(): Result {
        val deps = EntryPointAccessors.fromApplication(applicationContext, Deps::class.java)
        val glucoseRepo = deps.glucoseRepository()
        val statsRepo   = deps.statsRepository()
        val logger      = deps.appLogger()
        val TAG = "GAP_CHECK"

        return try {
            val now          = System.currentTimeMillis()
            val windowStart  = now - LOOKBACK
            val windowEnd    = now - EDGE_GRACE_MS
            if (windowEnd <= windowStart) return Result.success()

            val cloudTimestamps = statsRepo.getAllTimestamps(windowStart, windowEnd).sorted()

            val gaps = findGaps(windowStart, windowEnd, cloudTimestamps)
            if (gaps.isEmpty()) {
                logger.usage(TAG, "Sem buracos nas últimas 26h")
                return Result.success()
            }

            val local = glucoseRepo.getRecentReadings(windowStart)
            var gapsBackfilled = 0
            var gapsSemDadoLocal = 0
            for ((gapStart, gapEnd) in gaps) {
                val toSend = local.filter { it.dateMs in gapStart..gapEnd }
                if (toSend.isEmpty()) {
                    gapsSemDadoLocal++
                    continue
                }
                val ok = glucoseRepo.sendReadingsToCloud(toSend)
                if (ok) {
                    gapsBackfilled++
                    logger.usage(TAG, "Buraco preenchido a partir do banco local", mapOf(
                        "inicio" to gapStart, "fim" to gapEnd, "qtd" to toSend.size
                    ))
                } else {
                    logger.error(TAG, "Falha ao reenviar buraco", mapOf(
                        "inicio" to gapStart, "fim" to gapEnd, "qtd" to toSend.size
                    ))
                }
            }
            logger.usage(TAG, "Verificação concluída", mapOf(
                "buracos" to gaps.size,
                "preenchidos" to gapsBackfilled,
                "semDadoLocal" to gapsSemDadoLocal
            ))
            Result.success()
        } catch (e: Exception) {
            logger.error(TAG, "Erro inesperado na verificação", mapOf("erro" to (e.message ?: "desconhecido")))
            Result.retry()
        }
    }

    /** Varre [windowStart, windowEnd] e retorna os intervalos sem nenhuma leitura na nuvem por mais que o limiar. */
    private fun findGaps(windowStart: Long, windowEnd: Long, cloudTimestamps: List<Long>): List<Pair<Long, Long>> {
        val gaps = mutableListOf<Pair<Long, Long>>()
        var cursor = windowStart
        for (t in cloudTimestamps) {
            if (t - cursor > GAP_THRESHOLD_MS) gaps += cursor to t
            if (t > cursor) cursor = t
        }
        if (windowEnd - cursor > GAP_THRESHOLD_MS) gaps += cursor to windowEnd
        return gaps
    }
}
