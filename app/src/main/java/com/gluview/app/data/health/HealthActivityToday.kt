package com.gluview.app.data.health

import android.util.Log
import com.gluview.app.data.datastore.AppPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Passos e calorias ativas de hoje (desde a meia-noite), lidos do Health Connect para a Home.
 * Cada campo é nulo quando não há dado — e a Home não mostra nada sobre ele (pedido do Felipe,
 * 14/11/2026). Só calorias ATIVAS: o total do Health Connect soma o metabolismo basal estimado e
 * apareceria mesmo sem nenhum app registrando atividade.
 */
data class TodayActivity(val steps: Long? = null, val activeKcal: Double? = null) {
    val isEmpty: Boolean get() = steps == null && activeKcal == null
}

/**
 * Não guarda nada no banco: é um número do dia que o próprio Health Connect já soma (e tira
 * duplicados quando o relógio e o celular contam os mesmos passos). Lido com o app aberto.
 */
@Singleton
class HealthActivityToday @Inject constructor(
    private val gateway: HealthConnectGateway,
    private val preferences: AppPreferences,
) {
    private val _today = MutableStateFlow(TodayActivity())
    val today: StateFlow<TodayActivity> = _today.asStateFlow()

    @Volatile private var lastRefreshMs = 0L

    /** Relê se passou [minIntervalMs] desde a última vez (a Home chama ao abrir e a cada poucos minutos). */
    suspend fun refresh(minIntervalMs: Long = 60_000L, now: Long = System.currentTimeMillis()) {
        if (now - lastRefreshMs < minIntervalMs) return
        lastRefreshMs = now
        if (!preferences.healthConnectToggles.first().readActivity ||
            gateway.status() != HealthConnectStatus.AVAILABLE) {
            _today.value = TodayActivity(); return
        }
        val zone = ZoneId.systemDefault()
        val start = LocalDate.now(zone).atStartOfDay(zone).toInstant()
        _today.value = runCatching { gateway.activityBetween(start, Instant.ofEpochMilli(now)) }
            .onFailure { Log.i("HealthActivity", "Passos/calorias não lidos: ${it.message}") }
            .getOrDefault(TodayActivity())
    }

    /** Ao desligar a opção, some da Home na hora. */
    fun clear() { _today.value = TodayActivity(); lastRefreshMs = 0L }
}
