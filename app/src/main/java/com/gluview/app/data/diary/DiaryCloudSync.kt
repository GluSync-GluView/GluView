package com.gluview.app.data.diary

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.gluview.app.data.datastore.AppPreferences
import com.gluview.app.data.local.dao.EventLogDao
import com.gluview.app.data.repository.CloudAccessRepository
import com.gluview.app.util.AppLogger
import com.gluview.app.util.DiaryText
import com.gluview.app.util.withAppLanguage
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import org.json.JSONArray
import org.json.JSONObject
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Rotas Nightscout de treatments do GluSync (caminho absoluto: ficam fora de /api/glusync/v1). */
interface DiaryTreatmentsApi {
    @POST("/api/v1/treatments")
    suspend fun create(@Body body: RequestBody): Response<ResponseBody>

    @PUT("/api/v1/treatments/{id}")
    suspend fun update(@Path("id") id: String, @Body body: RequestBody): Response<ResponseBody>

    @DELETE("/api/v1/treatments/{id}")
    suspend fun delete(@Path("id") id: String): Response<ResponseBody>

    @GET("/api/v1/treatments")
    suspend fun list(
        @Query("count") count: Int,
        @Query("find[date][\$gte]") fromMs: Long
    ): Response<ResponseBody>
}

/**
 * Regra única de "posso usar o GluSync para o diário?" (pedido do Felipe, 10/11/2026: GluView
 * first — sem liberação de nuvem, 100% no aparelho). Pura, testável.
 */
object DiaryCloudGate {
    fun allowed(hasAccount: Boolean, localOnly: Boolean, cloudSyncState: String, blockedUntil: Long, now: Long): Boolean =
        hasAccount && !localOnly && cloudSyncState == CloudAccessRepository.STATE_APPROVED && now >= blockedUntil
}

/** Agenda a sincronização do diário. Não faz nada de rede: o worker confere a liberação antes. */
@Singleton
class DiarySyncScheduler @Inject constructor(@ApplicationContext private val context: Context) {
    private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Depois de uma mudança: junta as mudanças de alguns segundos num envio só. */
    fun requestSync() {
        runCatching {
            WorkManager.getInstance(context).enqueueUniqueWork(
                ONE_SHOT, ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<DiarySyncWorker>()
                    .setConstraints(constraints)
                    .setInitialDelay(20, TimeUnit.SECONDS)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                    .build()
            )
        }
    }

    /** Volta periódica (pega o que veio de outro aparelho e reenvia o que falhou). */
    fun schedulePeriodic() {
        runCatching {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<DiarySyncWorker>(6, TimeUnit.HOURS).setConstraints(constraints).build()
            )
        }
    }

    companion object {
        const val ONE_SHOT = "diary_sync_now"
        const val PERIODIC = "diary_sync_periodic"
    }
}

/**
 * Envia e recebe o diário do GluSync — só quando [DiaryCloudGate] permite. Se o servidor responder
 * que a conta não tem a liberação (403), para de tentar por um dia.
 */
@Singleton
class DiaryCloudSync @Inject constructor(
    @ApplicationContext private val context: Context,
    retrofit: Retrofit,
    private val dao: EventLogDao,
    private val preferences: AppPreferences,
    private val logger: AppLogger
) {
    private val api = retrofit.create(DiaryTreatmentsApi::class.java)
    private val json = "application/json; charset=utf-8".toMediaType()

    enum class Result { SKIPPED, OK, BLOCKED, FAILED }

    suspend fun isAllowed(now: Long = System.currentTimeMillis()): Boolean = DiaryCloudGate.allowed(
        preferences.hasAccount.first(), preferences.localOnlyMode.first(), preferences.cloudSyncState.first(),
        preferences.diaryCloudBlockedUntil.first(), now
    )

    suspend fun sync(now: Long = System.currentTimeMillis()): Result {
        if (!isAllowed(now)) return Result.SKIPPED
        val owner = preferences.userEmail.first()
        if (owner.isBlank()) return Result.SKIPPED
        return try {
            val text = DiaryText(context.withAppLanguage(preferences.appLanguage.first()))
            // Recebe antes de enviar: um registro que já existe na nuvem (backup restaurado, outro
            // aparelho) adota o _id de lá em vez de ser criado de novo.
            pull(owner, now) ?: push(owner, text) ?: run {
                preferences.saveDiaryLastSyncMs(now)
                Result.OK
            }
        } catch (e: Exception) {
            logger.usage(LOG, "Falha ao sincronizar o diário", mapOf("erro" to (e.message ?: e.javaClass.simpleName)))
            Result.FAILED
        }
    }

    /** null = seguiu; senão o resultado que encerra a rodada. */
    private suspend fun push(owner: String, text: DiaryText): Result? {
        for (e in dao.getDirty(owner)) {
            val remote = e.remoteId
            val resp: Response<ResponseBody> = when {
                e.deleted && remote == null -> { dao.hardDelete(e.id); continue }
                e.deleted -> api.delete(remote!!)
                remote == null -> api.create(body(e, text))
                else -> api.update(remote, body(e, text))
            }
            if (resp.code() == 403) return blocked()
            if (resp.code() == 401) return Result.FAILED
            when {
                e.deleted && (resp.isSuccessful || resp.code() == 404) -> dao.hardDelete(e.id)
                resp.isSuccessful && remote == null -> {
                    val id = resp.body()?.string()?.let(::firstId)
                    dao.update(e.copy(remoteId = id, dirty = id == null))
                }
                resp.isSuccessful -> dao.update(e.copy(dirty = false))
                // Sumiu no servidor (apagado por outro cliente): cria de novo na próxima rodada.
                resp.code() == 404 -> dao.update(e.copy(remoteId = null))
                else -> return Result.FAILED
            }
        }
        return null
    }

    private suspend fun pull(owner: String, now: Long): Result? {
        val lastPull = preferences.diaryLastPullMs.first()
        val from = if (lastPull <= 0) now - INITIAL_PULL_MS else lastPull - OVERLAP_MS
        val resp = api.list(PULL_LIMIT, from)
        if (resp.code() == 403) return blocked()
        if (!resp.isSuccessful) return Result.FAILED
        val arr = JSONArray(resp.body()?.string() ?: "[]")
        val remoteUuids = HashSet<String>()
        for (i in 0 until arr.length()) {
            val incoming = DiaryTreatmentMapper.fromTreatment(arr.getJSONObject(i), owner) ?: continue
            remoteUuids += incoming.uuid
            val local = dao.getByUuid(incoming.uuid)
            when {
                local == null -> dao.insert(incoming)
                local.dirty && local.remoteId == null && incoming.remoteId != null ->
                    dao.update(local.copy(remoteId = incoming.remoteId)) // segue sujo: vai como PUT
                local.dirty -> Unit // a mudança local vence até ser enviada
                incoming.updatedAt > local.updatedAt -> dao.update(incoming.copy(id = local.id, ownerEmail = local.ownerEmail))
                local.remoteId == null -> dao.update(local.copy(remoteId = incoming.remoteId))
            }
        }
        // Apagado em outro aparelho: só dá para concluir quando a lista veio inteira.
        if (arr.length() < PULL_LIMIT) {
            dao.getSyncedFrom(owner, from).filter { !it.dirty && it.uuid !in remoteUuids }.forEach { dao.hardDelete(it.id) }
        }
        preferences.saveDiaryLastPullMs(now)
        return null
    }

    private suspend fun blocked(): Result {
        preferences.saveDiaryCloudBlockedUntil(System.currentTimeMillis() + BLOCK_MS)
        logger.usage(LOG, "GluSync recusou o diário (sem liberação) — fica só no aparelho por 24 h")
        return Result.BLOCKED
    }

    private fun body(e: com.gluview.app.data.local.entity.EventLogEntity, text: DiaryText): RequestBody =
        DiaryTreatmentMapper.toTreatment(e, text.describe(e)).toString().toRequestBody(json)

    private fun firstId(raw: String): String? = runCatching {
        val t = raw.trim()
        if (t.startsWith("[")) JSONArray(t).getJSONObject(0).optString("_id")
        else JSONObject(t).optString("_id")
    }.getOrNull()?.takeIf { it.isNotBlank() }

    companion object {
        private const val LOG = "DIARY_SYNC"
        const val PULL_LIMIT = 2000
        private const val INITIAL_PULL_MS = 120L * 24 * 60 * 60 * 1000
        private const val OVERLAP_MS = 24L * 60 * 60 * 1000
        private const val BLOCK_MS = 24L * 60 * 60 * 1000
    }
}

class DiarySyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps { fun diaryCloudSync(): DiaryCloudSync }

    override suspend fun doWork(): Result {
        val sync = EntryPointAccessors.fromApplication(applicationContext, Deps::class.java).diaryCloudSync()
        return when (sync.sync()) {
            DiaryCloudSync.Result.FAILED -> if (runAttemptCount < 3) Result.retry() else Result.success()
            else -> Result.success()
        }
    }
}
