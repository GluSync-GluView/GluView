package com.gluview.app.wearables.xiaomi

import com.gluview.app.data.datastore.AppPreferences
import com.gluview.app.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Plano 21, F7.1/F7.4 — leva os alertas do GluView (hipo, hiper, urgentes, previsão de hipo) à pulseira
 * Xiaomi nova como notificação: ela vibra no pulso. Desligado até a pessoa ligar na tela da Mi Band.
 * Sempre em paralelo e "melhor esforço": nunca atrasa nem substitui o alerta do celular. O padrão de
 * vibração é o da própria pulseira (o protocolo de notificação não o escolhe).
 */
@Singleton
class WristbandAlertForwarder @Inject constructor(
    private val preferences: AppPreferences,
    private val xiaomi: XiaomiBandClient,
    private val appLogger: AppLogger
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun forward(title: String, message: String) {
        scope.launch {
            runCatching {
                if (!preferences.xiaomiAlertsEnabled.first()) return@launch
                val mac = preferences.xiaomiBandMac.first().trim()
                val key = preferences.xiaomiBandKey.first().trim()
                if (mac.isBlank() || key.isBlank()) return@launch
                val ok = xiaomi.sendNotification(mac, key, title, message) { }
                appLogger.usage("WRISTBAND", "Alerta levado à pulseira", mapOf("ok" to ok))
            }.onFailure { appLogger.error("WRISTBAND", "Falha ao levar o alerta à pulseira", mapOf("erro" to (it.message ?: ""))) }
        }
    }
}
