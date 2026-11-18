package com.gluview.app.wearables.huami

import android.content.Context
import com.gluview.app.data.datastore.AppPreferences
import com.gluview.app.data.repository.GlucoseRepository
import com.gluview.app.watchface.miband.MiBandWatchFacePusher
import com.gluview.app.wearables.core.GlucosePayload
import com.gluview.app.wearables.core.WearableAlertKind
import com.gluview.app.wearables.core.WearableCapabilities
import com.gluview.app.wearables.core.WearableCommand
import com.gluview.app.wearables.core.WearableDeviceInfo
import com.gluview.app.wearables.core.WearableDeviceManager
import com.gluview.app.wearables.core.WearableEvent
import com.gluview.app.wearables.core.WearableTransport
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Adaptador F7.1 para a Mi Band 6/Huami que JÁ funciona: reutiliza o [MiBandWatchFacePusher]
 * e o pareamento existente sem alterar o comportamento/protocolo deles. O envio de glicose
 * segue sendo por visor (pusher); vibração remota e bateria são capacidades não expostas aqui
 * (mantidas conservadoras) para não inventar comportamento.
 */
class MiBand6HuamiTransport(
    private val pusher: MiBandWatchFacePusher,
    private val preferences: AppPreferences
) : WearableTransport {

    override val info = WearableDeviceInfo(
        id = "mi_band_6_huami",
        displayName = "Mi Band 6 (Huami)",
        family = "huami_legacy",
        capabilities = WearableCapabilities(
            canShowGlucose = true, canShowGraph = true, canVibrate = false,
            canReceiveSnooze = false, canReportBattery = false, canSendWatchface = true
        ),
        maturityLabel = "Funciona (Mi Band 6 do Felipe)" // caminho existente, não quebrar
    )

    private val _events = MutableSharedFlow<WearableEvent>(extraBufferCapacity = 16)
    override val events: Flow<WearableEvent> = _events.asSharedFlow()
    @Volatile private var connected = false

    override suspend fun connect(): Boolean {
        val mac = preferences.miBandMac.first().trim()
        val ok = mac.isNotBlank()
        connected = ok
        if (ok) _events.tryEmit(WearableEvent.Connected(info.id))
        return ok
    }

    override suspend fun disconnect() {
        connected = false
        _events.tryEmit(WearableEvent.Disconnected(info.id, "desconectado"))
    }

    override suspend fun isConnected(): Boolean = connected

    override suspend fun send(command: WearableCommand): Boolean = when (command) {
        is WearableCommand.SendGlucose -> {
            // Reusa exatamente o pusher atual (gera+envia visor); sem mudança de protocolo.
            val ok = pusher.send(staleOverrideText = if (command.payload.stale) command.payload.deltaText else null) { }
            connected = ok || connected
            ok
        }
        is WearableCommand.Vibrate, is WearableCommand.SnoozeAlerts, is WearableCommand.RequestBattery, is WearableCommand.Ping -> false
    }
}

/** Registro mínimo para encaixar a Mi Band 6 no núcleo sem alterar o serviço atual. */
@Singleton
class HuamiWearableRegistry @Inject constructor(
    private val pusher: MiBandWatchFacePusher,
    private val preferences: AppPreferences,
    @ApplicationContext private val context: Context
) {
    fun miBand6Transport(): WearableTransport = MiBand6HuamiTransport(pusher, preferences)
}
