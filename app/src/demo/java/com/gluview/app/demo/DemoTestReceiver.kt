package com.gluview.app.demo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.MenstruationPeriodRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.BloodGlucose
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Mass
import com.gluview.app.data.datastore.AppPreferences
import com.gluview.app.data.local.dao.GlucoseDao
import com.gluview.app.data.local.entity.GlucoseEntity
import com.gluview.app.data.reminder.SmartMealPrompt
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject

/**
 * SÓ NA VERSÃO DE DEMONSTRAÇÃO (src/demo) — ferramentas de teste do plano 17 por adb:
 *
 * - `SEED_HC`: grava no Health Connect, como ESTE app, exercício, sono, menstruação, refeição e
 *   glicemia de exemplo. Instalado com outro sufixo de pacote, faz o papel de "outro app" para o
 *   GluView de teste ler.
 * - `RAPID_RISE`: põe no banco local uma subida rápida (sem refeição registrada) e chama o aviso
 *   "subindo sem refeição".
 *
 *   adb shell am broadcast -n <pacote>/com.gluview.app.demo.DemoTestReceiver -a com.gluview.app.demo.SEED_HC
 */
@AndroidEntryPoint
class DemoTestReceiver : BroadcastReceiver() {
    @Inject lateinit var glucoseDao: GlucoseDao
    @Inject lateinit var preferences: AppPreferences
    @Inject lateinit var smartMealPrompt: SmartMealPrompt
    @Inject lateinit var glucoseRepo: com.gluview.app.data.repository.GlucoseRepository
    @Inject lateinit var diary: com.gluview.app.data.diary.DiaryRepository

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    ACTION_SEED_HC -> seedHealthConnect(context)
                    ACTION_RAPID_RISE -> rapidRise()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Falhou: ${e.message}", e)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun seedHealthConnect(context: Context) {
        val client = HealthConnectClient.getOrCreate(context)
        val zone = ZoneId.systemDefault()
        val now = Instant.now()
        fun off(i: Instant) = zone.rules.getOffset(i)
        val day = 24L * 3600
        val records = buildList {
            // Corrida e caminhada nos últimos dias.
            for (d in 1..6) {
                val start = now.minusSeconds(d * day + 4 * 3600)
                val end = start.plusSeconds(if (d % 2 == 0) 45 * 60 else 30 * 60)
                add(ExerciseSessionRecord(start, off(start), end, off(end), Metadata.manualEntry(),
                    if (d % 2 == 0) ExerciseSessionRecord.EXERCISE_TYPE_RUNNING else ExerciseSessionRecord.EXERCISE_TYPE_WALKING,
                    title = null))
            }
            // Noites: 5 h e 8 h alternadas.
            for (d in 1..6) {
                val start = now.minusSeconds(d * day + 10 * 3600)
                val end = start.plusSeconds(if (d % 2 == 0) 5 * 3600L else 8 * 3600L)
                add(SleepSessionRecord(start, off(start), end, off(end), Metadata.manualEntry()))
            }
            // Menstruação começando 8 dias atrás, 4 dias.
            val ps = now.minusSeconds(8 * day)
            val pe = ps.plusSeconds(4 * day)
            add(MenstruationPeriodRecord(ps, off(ps), pe, off(pe), Metadata.manualEntry()))
            // Refeição de hoje, 1 h atrás (vira sugestão no diário).
            val ms = now.minusSeconds(3600)
            add(NutritionRecord(startTime = ms, startZoneOffset = off(ms), endTime = ms.plusSeconds(60), endZoneOffset = off(ms),
                metadata = Metadata.manualEntry(), totalCarbohydrate = Mass.grams(52.0), energy = Energy.kilocalories(610.0),
                name = "Almoço (app de teste)"))
            // Passos e calorias ativas de hoje, em blocos de 1 h desde a meia-noite (máx. 6 blocos).
            val midnight = java.time.LocalDate.now(zone).atStartOfDay(zone).toInstant()
            var t0 = maxOf(midnight, now.minusSeconds(6 * 3600))
            while (t0.plusSeconds(3600) <= now) {
                val t1 = t0.plusSeconds(3600)
                add(StepsRecord(t0, off(t0), t1, off(t1), 1234, Metadata.manualEntry()))
                add(ActiveCaloriesBurnedRecord(t0, off(t0), t1, off(t1), Energy.kilocalories(55.5), Metadata.manualEntry()))
                t0 = t1
            }
            // Glicemia de outro aparelho nas últimas 2 h (pontos cinza no gráfico).
            for (m in 0 until 120 step 15) {
                val t = now.minusSeconds(m * 60L)
                add(BloodGlucoseRecord(t, off(t), Metadata.manualEntry(), BloodGlucose.milligramsPerDeciliter(140.0 + m / 3.0),
                    specimenSource = BloodGlucoseRecord.SPECIMEN_SOURCE_CAPILLARY_BLOOD))
            }
        }
        client.insertRecords(records)
        Log.i(TAG, "Health Connect: ${records.size} registros de teste gravados")
    }

    private suspend fun rapidRise() {
        val owner = preferences.userEmail.first()
        val now = System.currentTimeMillis()
        // Limpa a janela (o modo demonstração grava leituras próprias que se misturariam à subida).
        glucoseDao.deleteBetween(owner, now - 35 * 60_000L, now + 60_000L)
        // 30 min subindo de 115 para 175 (5 em 5 min), a última agora.
        val values = listOf(115.0, 122.0, 132.0, 142.0, 152.0, 163.0, 175.0)
        values.forEachIndexed { i, v ->
            val t = now - (values.size - 1 - i) * 5 * 60_000L
            glucoseDao.insertReading(GlucoseEntity(dateMs = t, sgvMgDl = v, direction = "SingleUp", arrow = "↑",
                status = "OK", delta = 10, isStale = false, minutesAgo = 0, ownerEmail = owner))
        }
        val pts = glucoseRepo.getReadingsBetween(now - com.gluview.app.data.reminder.SmartMealRule.WINDOW_MS, now + 60_000L)
            .map { it.dateMs to it.sgvMgDl }
        Log.i(TAG, "Pontos na janela: " + pts.joinToString { "${(it.first - now) / 60_000}min=${it.second}" })
        Log.i(TAG, "Subida? ${com.gluview.app.data.reminder.SmartMealRule.isRapidRise(pts, now)} · " +
            "refeição recente? ${diary.hasMealBetween(now - com.gluview.app.data.reminder.SmartMealRule.MEAL_LOOKBACK_MS, now + 60_000L)}")
        preferences.saveSmartMealPromptLastAt(0L)
        smartMealPrompt.onNewReading(now)
        Log.i(TAG, "Subida rápida gravada e aviso avaliado (ligado=${preferences.smartMealPrompt.first()})")
    }

    companion object {
        private const val TAG = "DemoTest"
        const val ACTION_SEED_HC = "com.gluview.app.demo.SEED_HC"
        const val ACTION_RAPID_RISE = "com.gluview.app.demo.RAPID_RISE"
    }
}
