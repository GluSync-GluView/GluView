package com.gluview.app.wearables.hub

import com.gluview.app.data.datastore.AppPreferences
import com.gluview.app.watchface.miband.HuamiProfile
import com.gluview.app.wear.WearBridge
import com.gluview.app.wearables.huami.HuamiAlertSender
import com.gluview.app.wearables.huami.HuamiLegacyModel
import com.gluview.app.wearables.xiaomi.XiaomiBandClient
import com.gluview.wear.contract.WearAlert
import com.gluview.wearables.core.VibrationStyle
import com.gluview.wearables.core.WearableCapabilities
import com.gluview.wearables.core.WearableCommand
import com.gluview.wearables.core.WearableDeviceInfo
import com.gluview.wearables.core.WearableEvent
import com.gluview.wearables.core.WearableTransport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first

/**
 * Plano 21, F7.1 — transportes de ALERTA para o núcleo ([com.gluview.wearables.core.WearableDeviceManager]).
 * Pulseiras e relógios aqui não ficam ligados: cada alerta conecta, avisa e solta (como o envio do visor),
 * então "conectado" = configurado. Glicose segue pelos caminhos que já existem (visor, Data Layer, /watch.json).
 */
abstract class AlertTransport(val family: WearableFamily, canSnooze: Boolean) : WearableTransport {
    override val info = WearableDeviceInfo(
        id = "alerts_${family.id}", displayName = family.name, family = family.id,
        capabilities = WearableCapabilities(canVibrate = true, canReceiveSnooze = canSnooze)
    )
    protected val _events = MutableSharedFlow<WearableEvent>(extraBufferCapacity = 8)
    override val events: Flow<WearableEvent> = _events.asSharedFlow()

    abstract suspend fun configured(): Boolean
    protected abstract suspend fun alert(cmd: WearableCommand.Alert): Boolean
    protected open suspend fun find(): Boolean = false

    override suspend fun connect(): Boolean = configured()
    override suspend fun disconnect() {}
    override suspend fun isConnected(): Boolean = configured()
    override suspend fun send(command: WearableCommand): Boolean = when (command) {
        is WearableCommand.Alert -> alert(command)
        is WearableCommand.Find -> find()
        else -> false
    }
}

private fun hexKey(s: String): ByteArray? {
    val k = s.trim().removePrefix("0x").lowercase()
    if (k.length != 32 || !k.all { it in '0'..'9' || it in 'a'..'f' }) return null
    return ByteArray(16) { k.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
}

/** Huami/Amazfit (Mi Band 4–7, Amazfit…): texto + vibração escolhida; "chamada" recusada = soneca. */
class HuamiAlertTransport(
    private val prefs: AppPreferences,
    private val sender: HuamiAlertSender,
    private val log: (String) -> Unit
) : AlertTransport(WearableFamily.HUAMI, canSnooze = true) {
    override suspend fun configured() = prefs.miBandMac.first().isNotBlank() && hexKey(prefs.miBandAuthKey.first()) != null

    private suspend fun profile(): HuamiProfile =
        HuamiLegacyModel.fromId(prefs.miBandModel.first())?.let { HuamiProfile.of(it) } ?: HuamiProfile.MI_BAND_6

    override suspend fun alert(cmd: WearableCommand.Alert): Boolean {
        val key = hexKey(prefs.miBandAuthKey.first()) ?: return false
        val r = sender.alert(prefs.miBandMac.first().trim(), key, profile(), cmd.title, cmd.body, cmd.style, log = log)
        if (r.call == HuamiAlertSender.CallResult.REJECTED) _events.tryEmit(WearableEvent.SnoozeRequested(info.id))
        return r.ok
    }

    override suspend fun find(): Boolean {
        val key = hexKey(prefs.miBandAuthKey.first()) ?: return false
        return sender.find(prefs.miBandMac.first().trim(), key, profile(), log)
    }
}

/** Xiaomi nova (Mi Band 8 = BLE V1, Mi Band 9 Active = BLE V2): notificação + vibração personalizada. */
class XiaomiAlertTransport(
    private val prefs: AppPreferences,
    private val client: XiaomiBandClient,
    private val log: (String) -> Unit
) : AlertTransport(WearableFamily.XIAOMI, canSnooze = false) {
    override suspend fun configured() = prefs.xiaomiBandMac.first().isNotBlank() && hexKey(prefs.xiaomiBandKey.first()) != null

    override suspend fun alert(cmd: WearableCommand.Alert): Boolean =
        client.alert(prefs.xiaomiBandMac.first().trim(), prefs.xiaomiBandKey.first(), cmd.title, cmd.body, cmd.style, log)

    override suspend fun find(): Boolean =
        client.alert(prefs.xiaomiBandMac.first().trim(), prefs.xiaomiBandKey.first(), "GluView", "📍",
            VibrationStyle.INSISTENT, log)
}

/** Relógio Wear OS com o app do GluView: mensagem com o padrão; o relógio vibra e notifica. */
class WearOsAlertTransport(private val bridge: WearBridge) : AlertTransport(WearableFamily.WEAR_OS, canSnooze = false) {
    override suspend fun configured() = true   // sem relógio por perto o envio só falha (e desiste em 3)

    override suspend fun alert(cmd: WearableCommand.Alert): Boolean =
        bridge.sendAlert(WearAlert(System.currentTimeMillis(), cmd.kind.name, cmd.title, cmd.body, cmd.style.segmentsMs.toList()))
}

/** Garmin e Zepp OS: o alerta vai para o quadro e sai no próximo /watch.json daquele app. */
class WatchAppAlertTransport(family: WearableFamily, private val board: WatchAlertBoard) : AlertTransport(family, canSnooze = false) {
    override suspend fun configured() = true

    override suspend fun alert(cmd: WearableCommand.Alert): Boolean {
        val now = System.currentTimeMillis()
        board.post(family.id, WatchAlertBoard.Entry(now, cmd.kind.name, cmd.title, cmd.body, cmd.style.segmentsMs.toList(), now))
        return true
    }
}
