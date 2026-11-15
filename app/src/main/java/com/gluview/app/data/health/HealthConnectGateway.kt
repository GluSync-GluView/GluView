package com.gluview.app.data.health

import android.content.Context
import android.os.Build
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.MenstruationFlowRecord
import androidx.health.connect.client.records.MenstruationPeriodRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.aggregate.AggregateMetric
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.gluview.app.data.datastore.HealthConnectToggles
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.reflect.KClass

/** Estado do Health Connect no aparelho. */
enum class HealthConnectStatus { AVAILABLE, NEEDS_UPDATE, NOT_INSTALLED }

/**
 * Acesso ao Health Connect (plano 17, Fase 3). No Android 14+ ele vem no sistema; do 9 ao 13 é um
 * app separado (pacote [HealthConnectClient.DEFAULT_PROVIDER_PACKAGE_NAME]).
 */
@Singleton
class HealthConnectGateway @Inject constructor(@ApplicationContext private val context: Context) {

    fun status(): HealthConnectStatus = when (HealthConnectClient.getSdkStatus(context)) {
        HealthConnectClient.SDK_AVAILABLE -> HealthConnectStatus.AVAILABLE
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> HealthConnectStatus.NEEDS_UPDATE
        else -> HealthConnectStatus.NOT_INSTALLED
    }

    private val client: HealthConnectClient by lazy { HealthConnectClient.getOrCreate(context) }

    val device: Device get() = Device(Device.TYPE_PHONE, Build.MANUFACTURER, Build.MODEL)

    val ownPackage: String get() = context.packageName

    /** Leitura com o app fechado (worker) — recurso que nem todo Health Connect tem. */
    fun backgroundReadSupported(): Boolean = runCatching {
        client.features.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND) ==
            HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
    }.getOrDefault(false)

    suspend fun granted(): Set<String> = runCatching { client.permissionController.getGrantedPermissions() }.getOrDefault(emptySet())

    suspend fun insert(records: List<Record>) {
        records.chunked(500).forEach { client.insertRecords(it) }
    }

    suspend fun deleteByClientIds(type: KClass<out Record>, clientIds: List<String>) {
        if (clientIds.isNotEmpty()) client.deleteRecords(type, emptyList(), clientIds)
    }

    /** Lê tudo de [type] em [from, to), página por página. */
    suspend fun <T : Record> readAll(type: KClass<T>, from: Instant, to: Instant): List<T> {
        val out = mutableListOf<T>()
        var token: String? = null
        do {
            val resp = client.readRecords(ReadRecordsRequest(type, TimeRangeFilter.between(from, to), pageSize = 1000, pageToken = token))
            out += resp.records
            token = resp.pageToken
        } while (token != null)
        return out
    }

    /**
     * Passos e calorias ativas somados pelo próprio Health Connect em [from, to) — ele junta os apps
     * e tira duplicados. Só pede as métricas com permissão (sem ela o agregado inteiro falharia).
     */
    suspend fun activityBetween(from: Instant, to: Instant): TodayActivity {
        val ok = granted()
        val metrics = buildSet<AggregateMetric<*>> {
            if (HealthPermission.getReadPermission(StepsRecord::class) in ok) add(StepsRecord.COUNT_TOTAL)
            if (HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class) in ok) add(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL)
        }
        if (metrics.isEmpty()) return TodayActivity()
        val r = client.aggregate(AggregateRequest(metrics, TimeRangeFilter.between(from, to)))
        // O agregado só conta os apps da lista de prioridade do Health Connect; um app que grava
        // mas ficou fora dela (acontece) daria "sem dado". Aí vale a fonte que mais contou hoje —
        // nunca a soma das fontes, que duplicaria os passos do celular e do relógio.
        val steps = r[StepsRecord.COUNT_TOTAL]
            ?: if (StepsRecord.COUNT_TOTAL in metrics) bestSource(readAll(StepsRecord::class, from, to)) { it.count.toDouble() }?.toLong() else null
        val kcal = r[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories
            ?: if (ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL in metrics)
                bestSource(readAll(ActiveCaloriesBurnedRecord::class, from, to)) { it.energy.inKilocalories } else null
        return TodayActivity(steps = steps?.takeIf { it > 0 }, activeKcal = kcal?.takeIf { it >= 1.0 })
    }

    private fun <T : Record> bestSource(records: List<T>, value: (T) -> Double): Double? =
        records.groupBy { it.metadata.dataOrigin.packageName }.values.maxOfOrNull { list -> list.sumOf(value) }

    companion object {
        val READ_BACKGROUND: String = HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND

        /** Permissões que cada escolha da tela precisa (pede só o que está ligado). */
        fun permissionsFor(t: HealthConnectToggles, background: Boolean): Set<String> = buildSet {
            if (t.writeGlucose) add(HealthPermission.getWritePermission(BloodGlucoseRecord::class))
            if (t.readGlucose) add(HealthPermission.getReadPermission(BloodGlucoseRecord::class))
            if (t.readExercise) add(HealthPermission.getReadPermission(ExerciseSessionRecord::class))
            if (t.readSleep) add(HealthPermission.getReadPermission(SleepSessionRecord::class))
            if (t.readCycle) {
                add(HealthPermission.getReadPermission(MenstruationPeriodRecord::class))
                add(HealthPermission.getReadPermission(MenstruationFlowRecord::class))
            }
            if (t.writeNutrition) add(HealthPermission.getWritePermission(NutritionRecord::class))
            if (t.readNutrition) add(HealthPermission.getReadPermission(NutritionRecord::class))
            if (t.readActivity) {
                add(HealthPermission.getReadPermission(StepsRecord::class))
                add(HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class))
            }
            if (background && t.anyBackgroundRead) add(READ_BACKGROUND)
        }
    }
}
