package com.gluview.app.data.update

import android.app.NotificationManager
import android.content.Context
import com.gluview.app.alert.AlertManager
import com.gluview.app.data.datastore.AppPreferences
import com.gluview.app.data.repository.GlucoseRepository
import com.gluview.app.data.sensor.SensorSetupCoordinator
import com.gluview.app.data.sensor.SetupState
import com.gluview.app.domain.model.GlucoseZone
import com.gluview.app.notification.NotificationHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.util.Calendar
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Porteiro de segurança do atualizador (plano OTA, seção 5.5 "Janela segura"). Instalar mata o
 * processo: leitura do sensor, alarmes e envio ao GluSync param de alguns segundos a alguns minutos
 * (no S23 FE, em 24/10/2026, o Bluetooth do Sibionics levou ~7,5 min para voltar).
 *
 * NUNCA bloqueia (decisão do mantenedor, 24/10/2026): cada motivo vira um pedido de confirmação. O
 * usuário escolhe "Atualizar agora" ou "Mais tarde" — mais tarde não tem consequência nenhuma. A
 * avaliação acontece no toque, antes do commit e no pedido de confirmação do sistema; se surgir
 * um motivo que o usuário ainda não confirmou (ex.: a glicemia caiu durante o download), pergunta
 * de novo antes de instalar.
 *
 * Não mexe em leitura, alarme ou sensor; só informa se AGORA é um bom momento para instalar.
 */
object UpdateSafetyRules {

    /** Na ordem em que aparecem para o usuário: o mais sério primeiro. */
    enum class Reason {
        LOW_GLUCOSE,          // URGENT_LOW, LOW ou QUICK_DROP na última leitura
        RECENT_LOW,           // alguma leitura abaixo do limite baixo na janela recente
        FALLING,              // tendência de queda
        ACTIVE_ALERT,         // notificação de alerta/erro do GluView ativa
        SNOOZED,              // alarmes em soneca
        SENSOR_SETUP,         // busca/conexão de sensor em andamento
        NO_RECENT_READING,    // sem leitura recente: estado desconhecido
        NIGHT,                // madrugada
        HIGH_GLUCOSE,
        STALE
    }

    sealed interface Decision {
        data object Free : Decision
        /** Há motivos para talvez esperar: a tela pergunta "Atualizar agora" ou "Mais tarde". */
        data class Confirm(val reasons: List<Reason>) : Decision
    }

    data class Input(
        val nowMs: Long,
        val hourOfDay: Int,
        /** Última leitura conhecida (do sensor próprio ou, no modo seguidor, do paciente). */
        val latestZone: GlucoseZone?,
        val latestEpochMs: Long,
        val latestDirection: String?,
        val latestDelta: Int,
        /** Leituras da janela recente (mg/dL). Vazio no modo seguidor. */
        val recentSgv: List<Double>,
        val lowThreshold: Double,
        val activeAlertNotification: Boolean,
        val snoozed: Boolean,
        val sensorSetupInProgress: Boolean
    )

    /** "Recente" para considerar o estado conhecido. Leituras chegam a cada 1–5 min. */
    val RECENT_READING_MS: Long = TimeUnit.MINUTES.toMillis(15)

    /**
     * Janela em que qualquer leitura baixa entra nos motivos. PROVISÓRIO: o plano pede que esse
     * tempo seja definido com orientação médica (seção 10, item 7); 30 min é só o ponto de partida.
     */
    val RECENT_LOW_WINDOW_MS: Long = TimeUnit.MINUTES.toMillis(30)

    /** Madrugada: 23:00 até 06:59 (lição AAPS #3728). Também segura a notificação de versão nova. */
    fun isNight(hour: Int) = hour >= 23 || hour < 7

    private val FALLING_DIRECTIONS = setOf("DoubleDown", "SingleDown")

    fun evaluate(i: Input): Decision {
        val reasons = sortedSetOf<Reason>()

        val hasRecentReading = i.latestZone != null && i.latestEpochMs > 0 &&
            i.nowMs - i.latestEpochMs <= RECENT_READING_MS

        if (hasRecentReading) {
            when (i.latestZone) {
                GlucoseZone.URGENT_LOW, GlucoseZone.LOW, GlucoseZone.QUICK_DROP -> reasons += Reason.LOW_GLUCOSE
                GlucoseZone.HIGH, GlucoseZone.URGENT_HIGH -> reasons += Reason.HIGH_GLUCOSE
                GlucoseZone.STALE -> reasons += Reason.STALE
                else -> Unit
            }
            // ~-2 mg/dL/min (delta por leitura de 5 min) também conta como queda.
            if (i.latestDirection in FALLING_DIRECTIONS || i.latestDelta <= -10) reasons += Reason.FALLING
        } else {
            reasons += Reason.NO_RECENT_READING
        }

        if (i.recentSgv.any { it < i.lowThreshold } && Reason.LOW_GLUCOSE !in reasons) reasons += Reason.RECENT_LOW
        if (i.activeAlertNotification) reasons += Reason.ACTIVE_ALERT
        if (i.snoozed) reasons += Reason.SNOOZED
        if (i.sensorSetupInProgress) reasons += Reason.SENSOR_SETUP
        if (isNight(i.hourOfDay)) reasons += Reason.NIGHT

        return if (reasons.isEmpty()) Decision.Free else Decision.Confirm(reasons.toList())
    }
}

/** Coleta o estado real do app para o [UpdateSafetyRules]. */
@Singleton
class UpdateSafetyGate @Inject constructor(
    @ApplicationContext private val context: Context,
    private val glucoseRepository: GlucoseRepository,
    private val preferences: AppPreferences,
    private val alertManager: AlertManager,
    private val sensorSetup: SensorSetupCoordinator
) {
    /** Notificações do GluView que significam "algo exige atenção agora". Sobrevivem à troca de
     *  processo, ao contrário do estado em memória do AlertManager. */
    private val alertNotificationIds = setOf(
        NotificationHelper.NOTIF_ID_ALERT_NOTIFICATION, NotificationHelper.NOTIF_ID_ALERT_ALARM,
        NotificationHelper.NOTIF_ID_ALERT_DND, NotificationHelper.NOTIF_ID_PREDICTIVE,
        NotificationHelper.NOTIF_ID_ERROR, NotificationHelper.NOTIF_ID_SENSOR,
        NotificationHelper.NOTIF_ID_UPLOAD_ERROR
    )

    suspend fun evaluate(): UpdateSafetyRules.Decision {
        val now = System.currentTimeMillis()
        val latest = glucoseRepository.currentReading.value ?: glucoseRepository.getLatestOnce()
        val recent = runCatching {
            glucoseRepository.getRecentReadings(now - UpdateSafetyRules.RECENT_LOW_WINDOW_MS).map { it.sgvMgDl }
        }.getOrDefault(emptyList())
        val activeAlert = runCatching {
            context.getSystemService(NotificationManager::class.java).activeNotifications
                .any { it.id in alertNotificationIds }
        }.getOrDefault(false)

        return UpdateSafetyRules.evaluate(
            UpdateSafetyRules.Input(
                nowMs = now,
                hourOfDay = Calendar.getInstance().get(Calendar.HOUR_OF_DAY),
                latestZone = latest?.zone,
                latestEpochMs = latest?.epochMs ?: 0L,
                latestDirection = latest?.direction,
                latestDelta = latest?.delta ?: 0,
                recentSgv = recent,
                lowThreshold = preferences.glucoseThresholds.first().low.toDouble(),
                activeAlertNotification = activeAlert,
                snoozed = alertManager.isSilenced(),
                // Só a busca e a conexão usam o rádio agora; Ready/Failed ficam parados até o
                // assistente ser fechado e não significam nada em andamento.
                sensorSetupInProgress = sensorSetup.state.value.let { it is SetupState.Searching || it is SetupState.Connecting }
            )
        )
    }
}
