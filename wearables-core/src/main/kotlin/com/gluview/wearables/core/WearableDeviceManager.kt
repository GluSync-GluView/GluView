package com.gluview.wearables.core

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
    private val nowMs: () -> Long = System::currentTimeMillis,
    /** Minutos de soneca quando a pessoa a pede no próprio aparelho. */
    @Volatile var snoozeMinutes: Int = 30
) {
    data class State(
        val connected: Boolean = false,
        val battery: BatteryStatus? = null,
        val snoozedUntilMs: Long = 0L,
        val lastError: String? = null
    )

    private val queue = WearableCommandQueue()
    /** Acorda o laço quando chega comando (antes ele consultava a fila a cada 50 ms, para sempre). */
    private val wake = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
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
        wake.trySend(Unit)
    }

    /** Soneca vinda de fora (ex.: a pessoa silenciou no celular). */
    fun snoozeUntil(untilMs: Long) {
        _state.value = _state.value.copy(snoozedUntilMs = maxOf(_state.value.snoozedUntilMs, untilMs))
    }

    /** A pessoa desfez a soneca no celular: volta a avisar. */
    fun clearSnooze() {
        _state.value = _state.value.copy(snoozedUntilMs = 0L)
    }

    fun snoozeFor(minutes: Int) {
        applySnooze(minutes)
        _events.tryEmit(WearableEvent.SnoozeRequested(info.id))
    }

    private fun applySnooze(minutes: Int) {
        snoozeUntil(nowMs() + minutes.coerceAtLeast(1) * 60_000L)
    }

    fun isSnoozed(): Boolean = nowMs() < _state.value.snoozedUntilMs

    private suspend fun loop() {
        while (running.get() && scope.isActive) {
            if (queue.isEmpty()) { wake.receive(); continue }
            if (!transport.isConnected()) {
                val ok = runCatching { transport.connect() }.getOrDefault(false)
                if (!ok) { backoff.waitNext(); continue }
            }
            backoff.reset()
            val cmd = queue.poll() ?: continue
            if ((cmd is WearableCommand.Vibrate || cmd is WearableCommand.Alert) && isSnoozed()) continue
            if (!transport.info.capabilities.canVibrate && cmd is WearableCommand.Vibrate) continue
            if (!transport.info.capabilities.canReportBattery && cmd is WearableCommand.RequestBattery) continue
            val sent = runCatching { transport.send(cmd) }.getOrDefault(false)
            if (!sent) {
                // reconecta no próximo ciclo; recoloca comandos que não são glicose obsoleta
                // Alerta tenta no máximo 3 vezes: um aviso velho chegando muito depois engana.
                when {
                    cmd is WearableCommand.Alert -> if (cmd.attempt < 2) queue.enqueue(cmd.copy(attempt = cmd.attempt + 1))
                    cmd is WearableCommand.Find -> Unit   // "achar a pulseira" é na hora; não insiste depois
                    cmd !is WearableCommand.SendGlucose -> queue.enqueue(cmd)
                }
                runCatching { transport.disconnect() }
                // Falhou: espera o recuo antes de reconectar (antes reconectava e reenviava sem pausa).
                backoff.waitNext()
            }
        }
    }

    private fun handleEvent(e: WearableEvent) {
        when (e) {
            is WearableEvent.Connected -> _state.value = _state.value.copy(connected = true, lastError = null)
            is WearableEvent.Disconnected -> _state.value = _state.value.copy(connected = false)
            is WearableEvent.Battery -> _state.value = _state.value.copy(battery = e.status)
            // Soneca pelo aparelho (ex.: recusou a "chamada" na pulseira): o evento segue adiante uma vez só.
            is WearableEvent.SnoozeRequested -> applySnooze(snoozeMinutes)
            is WearableEvent.Error -> _state.value = _state.value.copy(lastError = e.message)
        }
        _events.tryEmit(e)
    }
}
