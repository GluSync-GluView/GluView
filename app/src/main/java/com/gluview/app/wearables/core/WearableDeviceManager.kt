package com.gluview.app.wearables.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Núcleo F7.1: gerencia um aparelho (fila, envio, reconexão com recuo, bateria e soneca).
 * Não conhece BLE nem protocolo: opera sobre [WearableTransport] (real ou falso nos testes).
 * Integrável sem alterar o caminho Mi Band 6 existente.
 */
class WearableDeviceManager(
    private val transport: WearableTransport,
    private val scope: CoroutineScope,
    private val backoff: ReconnectBackoff = ReconnectBackoff(),
    private val nowMs: () -> Long = System::currentTimeMillis
) {
    data class State(
        val connected: Boolean = false,
        val battery: BatteryStatus? = null,
        val snoozedUntilMs: Long = 0L,
        val lastError: String? = null
    )

    private val queue = WearableCommandQueue()
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private val _events = MutableSharedFlow<WearableEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<WearableEvent> = _events.asSharedFlow()

    private val running = AtomicBoolean(false)
    private var loopJob: Job? = null
    private var collectJob: Job? = null

    val info: WearableDeviceInfo get() = transport.info

    fun start() {
        if (!running.compareAndSet(false, true)) return
        collectJob = scope.launch {
            transport.events.collect { handleEvent(it) }
        }
        loopJob = scope.launch { loop() }
    }

    fun stop() {
        running.set(false)
        loopJob?.cancel(); loopJob = null
        collectJob?.cancel(); collectJob = null
        scope.launch { runCatching { transport.disconnect() } }
    }

    fun enqueue(command: WearableCommand) {
        queue.enqueue(command)
    }

    fun snoozeFor(minutes: Int) {
        val until = nowMs() + minutes.coerceAtLeast(1) * 60_000L
        _state.value = _state.value.copy(snoozedUntilMs = maxOf(_state.value.snoozedUntilMs, until))
        _events.tryEmit(WearableEvent.SnoozeRequested(info.id))
    }

    fun isSnoozed(): Boolean = nowMs() < _state.value.snoozedUntilMs

    private suspend fun loop() {
        while (running.get() && scope.isActive) {
            if (!transport.isConnected()) {
                val ok = runCatching { transport.connect() }.getOrDefault(false)
                if (!ok) { backoff.waitNext(); continue }
            }
            backoff.reset()
            val cmd = queue.poll()
            if (cmd == null) {
                kotlinx.coroutines.delay(50)
                continue
            }
            if (cmd is WearableCommand.Vibrate && isSnoozed()) continue
            if (!transport.info.capabilities.canVibrate && cmd is WearableCommand.Vibrate) continue
            if (!transport.info.capabilities.canReportBattery && cmd is WearableCommand.RequestBattery) continue
            val sent = runCatching { transport.send(cmd) }.getOrDefault(false)
            if (!sent) {
                // reconecta no próximo ciclo; recoloca comandos que não são glicose obsoleta
                if (cmd !is WearableCommand.SendGlucose) queue.enqueue(cmd)
                runCatching { transport.disconnect() }
            }
        }
    }

    private fun handleEvent(e: WearableEvent) {
        when (e) {
            is WearableEvent.Connected -> _state.value = _state.value.copy(connected = true, lastError = null)
            is WearableEvent.Disconnected -> _state.value = _state.value.copy(connected = false)
            is WearableEvent.Battery -> _state.value = _state.value.copy(battery = e.status)
            is WearableEvent.SnoozeRequested -> snoozeFor(10)
            is WearableEvent.Error -> _state.value = _state.value.copy(lastError = e.message)
        }
        _events.tryEmit(e)
    }
}
