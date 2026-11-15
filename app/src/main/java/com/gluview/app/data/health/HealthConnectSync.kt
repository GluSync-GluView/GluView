package com.gluview.app.data.health

import android.content.Context
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.MenstruationFlowRecord
import androidx.health.connect.client.records.MenstruationPeriodRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.gluview.app.R
import com.gluview.app.data.datastore.AppPreferences
import com.gluview.app.data.diary.DiaryType
import com.gluview.app.data.local.dao.EventLogDao
import com.gluview.app.data.local.dao.HealthRecordDao
import com.gluview.app.data.local.entity.EventLogEntity
import com.gluview.app.data.local.entity.HealthRecordEntity
import com.gluview.app.data.repository.GlucoseRepository
import com.gluview.app.util.AppLogger
import com.gluview.app.util.withAppLanguage
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Troca com o Health Connect (plano 17, F3.1–F3.5). Tudo no aparelho; nada disto passa pelo GluSync.
 *
 * Grava: glicemia do sensor (líquido intersticial), ponta de dedo do diário (capilar) e refeições
 * (nutrição). Lê de OUTROS apps: glicemia (só para o gráfico, nunca dispara alerta), exercício,
 * sono, ciclo e nutrição. Com o app fechado só lê se o Health Connect deixar ler em segundo plano.
 */
@Singleton
class HealthConnectSync @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gateway: HealthConnectGateway,
    private val preferences: AppPreferences,
    private val glucose: GlucoseRepository,
    private val events: EventLogDao,
    private val records: HealthRecordDao,
    private val logger: AppLogger
) {
    private val mutex = Mutex()

    data class Result(val wrote: Int = 0, val read: Int = 0, val skipped: Boolean = false, val error: String = "")

    suspend fun sync(foreground: Boolean, now: Long = System.currentTimeMillis()): Result = mutex.withLock {
        val toggles = preferences.healthConnectToggles.first()
        if (!toggles.any || gateway.status() != HealthConnectStatus.AVAILABLE) return@withLock Result(skipped = true)
        if (preferences.isFollowerOnly.first()) return@withLock Result(skipped = true)
        val granted = gateway.granted()
        val backfillMs = preferences.hcBackfillDays.first() * DAY_MS
        var wrote = 0
        var read = 0
        val result = runCatching {
            // ── Gravar (F3.1 e F3.5) ──
            if (toggles.writeGlucose && HealthPermission.getWritePermission(BloodGlucoseRecord::class) in granted) {
                wrote += writeSensorGlucose(now, backfillMs)
                wrote += writeFingersticks(now, backfillMs)
            }
            if (toggles.writeNutrition && HealthPermission.getWritePermission(NutritionRecord::class) in granted) {
                wrote += writeMeals(now, backfillMs)
            }
            // ── Ler (F3.2, F3.3, F3.5) ──
            val canRead = foreground || HealthConnectGateway.READ_BACKGROUND in granted
            if (canRead && toggles.anyBackgroundRead) {
                val last = preferences.hcLastReadMs.first()
                // Sempre relê pelo menos 7 dias: outro app pode gravar depois um registro com data antiga
                // (sono sincronizado de manhã, treino importado do relógio dele).
                val from = if (last <= 0) now - maxOf(backfillMs, 30 * DAY_MS) else minOf(last - 2 * DAY_MS, now - 7 * DAY_MS)
                val f = Instant.ofEpochMilli(from)
                val t = Instant.ofEpochMilli(now + 60_000)
                val own = gateway.ownPackage
                fun ok(perm: String) = perm in granted
                if (toggles.readGlucose && ok(HealthPermission.getReadPermission(BloodGlucoseRecord::class))) {
                    read += replace(HealthConnectMapper.KIND_GLUCOSE, from, now,
                        gateway.readAll(BloodGlucoseRecord::class, f, t).filter { it.metadata.dataOrigin.packageName != own }
                            .map(HealthConnectMapper::fromGlucose))
                }
                if (toggles.readExercise && ok(HealthPermission.getReadPermission(ExerciseSessionRecord::class))) {
                    val list = gateway.readAll(ExerciseSessionRecord::class, f, t).filter { it.metadata.dataOrigin.packageName != own }
                        .map(HealthConnectMapper::fromExercise)
                    read += replace(HealthConnectMapper.KIND_EXERCISE, from, now, list)
                    importExerciseToDiary(list)
                }
                if (toggles.readSleep && ok(HealthPermission.getReadPermission(SleepSessionRecord::class))) {
                    read += replace(HealthConnectMapper.KIND_SLEEP, from, now,
                        gateway.readAll(SleepSessionRecord::class, f, t).filter { it.metadata.dataOrigin.packageName != own }
                            .map(HealthConnectMapper::fromSleep))
                }
                if (toggles.readCycle && ok(HealthPermission.getReadPermission(MenstruationPeriodRecord::class))) {
                    read += replace(HealthConnectMapper.KIND_PERIOD, from, now,
                        gateway.readAll(MenstruationPeriodRecord::class, f, t).filter { it.metadata.dataOrigin.packageName != own }
                            .map(HealthConnectMapper::fromPeriod))
                }
                if (toggles.readCycle && ok(HealthPermission.getReadPermission(MenstruationFlowRecord::class))) {
                    read += replace(HealthConnectMapper.KIND_FLOW, from, now,
                        gateway.readAll(MenstruationFlowRecord::class, f, t).filter { it.metadata.dataOrigin.packageName != own }
                            .map(HealthConnectMapper::fromFlow))
                }
                if (toggles.readNutrition && ok(HealthPermission.getReadPermission(NutritionRecord::class))) {
                    read += replace(HealthConnectMapper.KIND_NUTRITION, from, now,
                        gateway.readAll(NutritionRecord::class, f, t).filter { it.metadata.dataOrigin.packageName != own }
                            .map(HealthConnectMapper::fromNutrition))
                }
                preferences.saveHcLastReadMs(now)
            }
        }
        val error = result.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName }.orEmpty()
        if (error.isNotEmpty()) logger.usage(LOG, "Falha na troca com o Health Connect", mapOf("erro" to error))
        preferences.saveHcSyncResult(now, error)
        Result(wrote, read, error = error)
    }

    /** Leituras do sensor desde o último envio (na 1ª vez, os dias escolhidos para trás). */
    private suspend fun writeSensorGlucose(now: Long, backfillMs: Long): Int {
        val since = maxOf(preferences.hcGlucoseWrittenUntil.first(), now - backfillMs)
        val readings = glucose.getReadingsBetween(since + 1, now + 60_000).filter { it.sgvMgDl > 0 }
        if (readings.isEmpty()) return 0
        val device = gateway.device
        val recs = readings.mapNotNull { HealthConnectMapper.sensorGlucose(it.dateMs, it.sgvMgDl, device) }
        gateway.insert(recs)
        preferences.saveHcGlucoseWrittenUntil(readings.maxOf { it.dateMs })
        return recs.size
    }

    private suspend fun diaryWindow(now: Long, backfillMs: Long, type: DiaryType): List<EventLogEntity> =
        events.getBetween(preferences.userEmail.first(), now - backfillMs, now + 60_000)
            .filter { it.type == type.key && it.source != SOURCE_HC }

    /** Gravar de novo é seguro: o clientRecordId faz o Health Connect atualizar em vez de duplicar. */
    private suspend fun writeFingersticks(now: Long, backfillMs: Long): Int {
        val recs = diaryWindow(now, backfillMs, DiaryType.FINGERSTICK).mapNotNull { HealthConnectMapper.fingerstick(it) }
        gateway.insert(recs)
        return recs.size
    }

    private suspend fun writeMeals(now: Long, backfillMs: Long): Int {
        val recs = diaryWindow(now, backfillMs, DiaryType.MEAL).mapNotNull { HealthConnectMapper.meal(it) }
        gateway.insert(recs)
        return recs.size
    }

    /** Registro saiu do diário: tira também do Health Connect (melhor esforço). */
    suspend fun onDiaryEntryDeleted(e: EventLogEntity) {
        if (gateway.status() != HealthConnectStatus.AVAILABLE) return
        val toggles = preferences.healthConnectToggles.first()
        runCatching {
            when (e.type) {
                DiaryType.FINGERSTICK.key -> if (toggles.writeGlucose)
                    gateway.deleteByClientIds(BloodGlucoseRecord::class, listOf(HealthConnectMapper.diaryClientId(e.uuid)))
                DiaryType.MEAL.key -> if (toggles.writeNutrition)
                    gateway.deleteByClientIds(NutritionRecord::class, listOf(HealthConnectMapper.diaryClientId(e.uuid)))
            }
        }
    }

    /** Troca o que havia de [kind] na janela pelo que veio agora (o que sumiu foi apagado lá). */
    private suspend fun replace(kind: String, from: Long, now: Long, list: List<HealthRecordEntity>): Int {
        val incoming = list.map { it.hcId }.toSet()
        val gone = records.idsStartingBetween(kind, from, now + 60_000).filter { it !in incoming }
        if (gone.isNotEmpty()) records.deleteIds(gone)
        if (list.isNotEmpty()) records.upsertAll(list)
        return list.size
    }

    /** Exercício de outro app entra no diário marcado com a origem, sem duplicar (uuid fixo por sessão). */
    private suspend fun importExerciseToDiary(list: List<HealthRecordEntity>) {
        if (list.isEmpty()) return
        val owner = preferences.userEmail.first()
        val res = context.withAppLanguage(preferences.appLanguage.first())
        list.forEach { r ->
            val uuid = "hc-" + r.hcId
            if (events.getByUuid(uuid) != null) return@forEach
            events.insert(EventLogEntity(
                occurredAt = r.startMs, type = DiaryType.EXERCISE.key, value = Math.round(r.value).toDouble(),
                itemName = r.title.ifBlank { exerciseLabel(res, r.subtype) }, uuid = uuid,
                notes = res.getString(R.string.hc_from_health_connect), source = SOURCE_HC, ownerEmail = owner,
                updatedAt = System.currentTimeMillis(), dirty = true
            ))
        }
    }

    companion object {
        const val SOURCE_HC = "health_connect"
        private const val LOG = "HEALTH_CONNECT"
        private const val DAY_MS = 24L * 60 * 60 * 1000

        fun exerciseLabel(res: Context, key: String): String = when (key) {
            "walking" -> res.getString(R.string.hc_ex_walking)
            "running", "running_treadmill" -> res.getString(R.string.hc_ex_running)
            "biking", "biking_stationary" -> res.getString(R.string.hc_ex_biking)
            "swimming_pool", "swimming_open_water" -> res.getString(R.string.hc_ex_swimming)
            "strength_training", "weightlifting" -> res.getString(R.string.hc_ex_strength)
            "yoga", "pilates" -> res.getString(R.string.hc_ex_yoga)
            "hiking" -> res.getString(R.string.hc_ex_hiking)
            "dancing" -> res.getString(R.string.hc_ex_dancing)
            "soccer", "basketball", "volleyball", "tennis" -> res.getString(R.string.hc_ex_sport)
            else -> res.getString(R.string.hc_ex_other)
        }
    }
}

/** Agenda a troca: logo depois de mudanças e a cada 30 min (só faz algo se houver opção ligada). */
@Singleton
class HealthConnectScheduler @Inject constructor(@ApplicationContext private val context: Context) {
    fun requestSync() {
        runCatching {
            WorkManager.getInstance(context).enqueueUniqueWork(ONE_SHOT, ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<HealthConnectWorker>().setInitialDelay(15, TimeUnit.SECONDS).build())
        }
    }

    fun schedulePeriodic() {
        runCatching {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<HealthConnectWorker>(30, TimeUnit.MINUTES).setConstraints(Constraints.NONE).build())
        }
    }

    companion object {
        const val ONE_SHOT = "health_connect_now"
        const val PERIODIC = "health_connect_periodic"
    }
}

class HealthConnectWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps { fun healthConnectSync(): HealthConnectSync }

    override suspend fun doWork(): Result {
        EntryPointAccessors.fromApplication(applicationContext, Deps::class.java).healthConnectSync().sync(foreground = false)
        return Result.success()
    }
}
