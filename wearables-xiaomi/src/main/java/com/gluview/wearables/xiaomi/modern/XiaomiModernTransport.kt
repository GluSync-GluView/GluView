package com.gluview.wearables.xiaomi.modern

import com.gluview.wearables.core.BatteryStatus
import com.gluview.wearables.core.GlucosePayload
import com.gluview.wearables.core.WearableCapabilities
import com.gluview.wearables.core.WearableCommand
import com.gluview.wearables.core.WearableDeviceInfo
import com.gluview.wearables.core.WearableEvent
import com.gluview.wearables.core.WearableTransport
import com.gluview.wearables.xiaomi.XiaomiGlucoseUpdate
import com.gluview.wearables.xiaomi.XiaomiModernModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * F7.4 (plano 21): Transporte para dispositivos Xiaomi modernos (Mi Band 7/8/9).
 * Protocolo protobuf + autenticação derivada do Gadgetbridge (AGPLv3).
 * Cabeçalhos originais preservados nos arquivos copiados.
 *
 * SEM TESTE COM HARDWARE REAL — necessita aparelhos físicos e vetores de captura.
 *
 * Auditoria 21/11/2026 (Claude): o protocolo NÃO está implementado. Antes, connect() fingia conectar,
 * o envio fingia sucesso e a bateria devolvia 85% inventado. Agora tudo responde "não implementado"
 * (false / sem eventos) até existir o porte real (BLE + autenticação + protobuf do Gadgetbridge).
 */
class XiaomiModernTransport(
    private val model: XiaomiModernModel,
    private val scope: CoroutineScope
) : WearableTransport {

    override val info: WearableDeviceInfo = model.deviceInfo()

    private val _events = MutableSharedFlow<WearableEvent>(extraBufferCapacity = 32)
    override val events = _events.asSharedFlow()

    private val connected = AtomicBoolean(false)
    private val commandChannel = Channel<WearableCommand>(64)
    private var authCompleted = false

    override suspend fun connect(): Boolean {
        if (connected.get()) return true
        // Não implementado: nunca finge conexão.
        return false
    }

    override suspend fun disconnect() {
        if (connected.compareAndSet(true, false)) {
            authCompleted = false
            _events.tryEmit(WearableEvent.Disconnected(info.id))
        }
    }

    override suspend fun isConnected(): Boolean = connected.get()

    override suspend fun send(command: WearableCommand): Boolean {
        if (!connected.get()) return false
        return when (command) {
            is WearableCommand.SendGlucose -> sendGlucose(command.payload)
            is WearableCommand.Vibrate -> sendVibration(command.patternMs)
            is WearableCommand.RequestBattery -> requestBattery()
            is WearableCommand.SnoozeAlerts -> { snooze(); true }
            else -> false
        }
    }

    private suspend fun sendGlucose(payload: GlucosePayload): Boolean {
        // Não implementado (antes devolvia true sem enviar nada).
        return false
    }

    private suspend fun sendVibration(patternMs: LongArray): Boolean {
        // Não implementado.
        return false
    }

    private suspend fun requestBattery(): Boolean {
        // Não implementado (antes emitia uma bateria de 85% inventada).
        return false
    }

    private fun snooze() {
        _events.tryEmit(WearableEvent.SnoozeRequested(info.id))
    }
}