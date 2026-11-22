package com.gluview.app.wearables.hub

import com.gluview.app.data.datastore.AppPreferences
import com.gluview.app.util.AppLogger
import com.gluview.app.wear.WearBridge
import com.gluview.app.wearables.huami.HuamiAlertSender
import com.gluview.app.wearables.xiaomi.XiaomiBandClient
import com.gluview.wearables.core.ReconnectBackoff
import com.gluview.wearables.core.VibrationStyle
import com.gluview.wearables.core.WearableAlertKind
import com.gluview.wearables.core.WearableAlertRouter
import com.gluview.wearables.core.WearableAlertSettings
import com.gluview.wearables.core.WearableCommand
import com.gluview.wearables.core.WearableDeviceManager
import com.gluview.wearables.core.WearableEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Plano 21, F7.1 — o hub dos vestíveis: cada alerta do GluView vai para todo aparelho configurado e ligado,
 * com a vibração que a pessoa escolheu para aquele tipo de alerta naquele tipo de aparelho. Cada tipo de
 * aparelho tem o seu [WearableDeviceManager] (fila com prioridade, nova tentativa com recuo, soneca).
 *
 * Sempre em paralelo e "melhor esforço": nunca atrasa nem substitui o alerta do celular. A soneca pedida
 * no pulso (recusar a "chamada" na Huami) chega em [snoozeRequests] — o AlertManager silencia o celular.
 */
@Singleton
class WearablesHub @Inject constructor(
    private val prefs: AppPreferences,
    private val huami: HuamiAlertSender,
    private val xiaomi: XiaomiBandClient,
    private val wear: WearBridge,
    private val board: WatchAlertBoard,
    private val appLogger: AppLogger
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val managers = ConcurrentHashMap<WearableFamily, WearableDeviceManager>()
    private val transports = ConcurrentHashMap<WearableFamily, AlertTransport>()
    private val _snoozeRequests = MutableSharedFlow<Int>(extraBufferCapacity = 4)

    /** Minutos de soneca pedidos no pulso. */
    val snoozeRequests: SharedFlow<Int> = _snoozeRequests.asSharedFlow()

    // ---- ajustes

    fun settings(family: WearableFamily): Flow<WearableAlertSettings> =
        combine(prefs.wearableAlerts(family.id), prefs.xiaomiAlertsEnabled) { text, legacyXiaomi -> decode(family, text, legacyXiaomi) }

    suspend fun currentSettings(family: WearableFamily): WearableAlertSettings = settings(family).first()

    suspend fun save(family: WearableFamily, settings: WearableAlertSettings) {
        prefs.saveWearableAlerts(family.id, settings.encode())
        if (family == WearableFamily.XIAOMI) prefs.saveXiaomiAlerts(settings.enabled)   // tela antiga continua coerente
    }

    suspend fun configured(family: WearableFamily): Boolean = transport(family).configured()

    // ---- alertas

    /** Leva o alerta a todos os aparelhos ligados (em paralelo, sem esperar). */
    fun alert(kind: WearableAlertKind, title: String, body: String) {
        scope.launch {
            for (family in WearableFamily.entries) runCatching { route(family, kind, title, body) }
                .onFailure { appLogger.error("WEARABLES", "Falha ao levar o alerta", mapOf("aparelho" to family.id, "erro" to (it.message ?: ""))) }
        }
    }

    private suspend fun route(family: WearableFamily, kind: WearableAlertKind, title: String, body: String) {
        val s = currentSettings(family)
        if (!s.enabled || !configured(family)) return
        val m = manager(family)
        val style = WearableAlertRouter.decide(s, kind, family.supported, m.state.value.snoozedUntilMs, System.currentTimeMillis()) ?: return
        m.snoozeMinutes = prefs.wearableSnoozeMinutes.first()
        m.enqueue(WearableCommand.Alert(kind, title, body, style))
        appLogger.usage("WEARABLES", "Alerta na fila do aparelho", mapOf("aparelho" to family.id, "tipo" to kind.name, "vibração" to style.id))
    }

    /** Botão "Testar" da tela: manda agora, sem fila, e diz se chegou. */
    suspend fun test(family: WearableFamily, style: VibrationStyle, title: String, body: String, log: (String) -> Unit = {}): Boolean {
        val t = transportFor(family, log)
        if (!t.configured()) { log("Aparelho não configurado."); return false }
        return runCatching { t.send(WearableCommand.Alert(WearableAlertKind.LOW, title, body, style)) }.getOrDefault(false)
    }

    /** "Encontrar pulseira": vibra forte, agora. */
    suspend fun find(family: WearableFamily, log: (String) -> Unit = {}): Boolean {
        if (!family.canFind) return false
        val t = transportFor(family, log)
        return t.configured() && runCatching { t.send(WearableCommand.Find) }.getOrDefault(false)
    }

    /** Soneca vinda do celular (ou do próprio pulso): nada vibra no pulso até [untilMs]. */
    fun silenceUntil(untilMs: Long) {
        managers.values.forEach { it.snoozeUntil(untilMs) }
        board.clear()
    }

    fun clearSilence() = managers.values.forEach { it.clearSnooze() }

    // ---- montagem

    private fun manager(family: WearableFamily): WearableDeviceManager = managers.getOrPut(family) {
        WearableDeviceManager(transport(family), scope, ReconnectBackoff(initialMs = 3_000L, maxMs = 60_000L)).also { m ->
            m.start()
            scope.launch {
                m.events.collect { e ->
                    if (e is WearableEvent.SnoozeRequested) {
                        val min = prefs.wearableSnoozeMinutes.first()
                        appLogger.usage("WEARABLES", "Soneca pedida no pulso", mapOf("aparelho" to family.id, "min" to min))
                        silenceUntil(System.currentTimeMillis() + min * 60_000L)
                        _snoozeRequests.tryEmit(min)
                    }
                }
            }
        }
    }

    private fun transport(family: WearableFamily): AlertTransport = transports.getOrPut(family) {
        transportFor(family) { appLogger.usage("WEARABLES", it, mapOf("aparelho" to family.id)) }
    }

    private fun transportFor(family: WearableFamily, log: (String) -> Unit): AlertTransport = when (family) {
        WearableFamily.HUAMI -> HuamiAlertTransport(prefs, huami, log)
        WearableFamily.XIAOMI -> XiaomiAlertTransport(prefs, xiaomi, log)
        WearableFamily.WEAR_OS -> WearOsAlertTransport(wear)
        WearableFamily.GARMIN, WearableFamily.ZEPP -> WatchAppAlertTransport(family, board)
    }

    companion object {
        /** Lê os ajustes salvos; a Xiaomi sem ajuste novo herda o liga/desliga da tela antiga (F7.4). */
        fun decode(family: WearableFamily, text: String?, legacyXiaomiEnabled: Boolean): WearableAlertSettings =
            if (text == null && family == WearableFamily.XIAOMI)
                WearableAlertSettings.defaults(family.supportsCall, enabled = legacyXiaomiEnabled)
            else WearableAlertSettings.decode(text, family.supportsCall)
    }
}
