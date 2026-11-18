package com.gluview.app.wearables.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** Transporte falso para testes de unidade do núcleo F7.1 (sem BLE/hardware). */
class FakeWearableTransport(
    override val info: WearableDeviceInfo = WearableDeviceInfo(
        "fake", "Fake", "test", WearableCapabilities(
            canShowGlucose = true, canShowGraph = false, canVibrate = true,
            canReceiveSnooze = true, canReportBattery = true, canSendWatchface = false
        ), "teste"
    ),
    var connectOk: Boolean = true,
    var sendOk: Boolean = true
) : WearableTransport {
    private val _events = MutableSharedFlow<WearableEvent>(extraBufferCapacity = 32)
    override val events = _events
    val sent = CopyOnWriteArrayList<WearableCommand>()
    @Volatile var connected = false
    var connectCalls = 0

    override suspend fun connect(): Boolean { connectCalls++; connected = connectOk; if (connected) _events.emit(WearableEvent.Connected(info.id)); return connected }
    override suspend fun disconnect() { connected = false; _events.emit(WearableEvent.Disconnected(info.id)) }
    override suspend fun isConnected(): Boolean = connected
    override suspend fun send(command: WearableCommand): Boolean { if (!sendOk) return false; sent += command; return true }
    suspend fun emit(e: WearableEvent) { _events.emit(e) }
}

class WearableCoreTest {

    @Test fun `fila coalesce glicose e prioriza vibracao`() {
        val q = WearableCommandQueue()
        val p = GlucosePayload(null, "100", "+0", "→", 1, false)
        q.enqueue(WearableCommand.SendGlucose(p.copy(displayValue = "90")))
        q.enqueue(WearableCommand.Vibrate(WearableAlertKind.LOW))
        q.enqueue(WearableCommand.SendGlucose(p.copy(displayValue = "110")))
        q.enqueue(WearableCommand.Ping)
        val a = q.poll()!!
        assertTrue(a is WearableCommand.Vibrate)
        val b = q.poll()!! as WearableCommand.SendGlucose
        assertEquals("110", b.payload.displayValue) // mais recente
        assertTrue(q.poll() is WearableCommand.Ping)
        assertTrue(q.isEmpty())
    }

    @Test fun `backoff exponencial deterministico e com teto`() {
        val b = ReconnectBackoff(initialMs = 1000, maxMs = 8000, factor = 2.0, jitterRatio = 0.0)
        assertEquals(1000, b.nextDelayMs())
        assertEquals(2000, b.nextDelayMs())
        assertEquals(4000, b.nextDelayMs())
        assertEquals(8000, b.nextDelayMs())
        assertEquals(8000, b.nextDelayMs())
        b.reset()
        assertEquals(1000, b.nextDelayMs())
    }

    @Test fun `manager envia comando e respeita soneca em vibracao`() = runBlocking {
        val t = FakeWearableTransport()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val m = WearableDeviceManager(t, scope, ReconnectBackoff(1, 2, 2.0, 0.0), nowMs = { 1_000L })
        m.start()
        delay(80)
        val p = GlucosePayload(null, "120", "+1", "→", 0, false)
        m.enqueue(WearableCommand.SendGlucose(p))
        delay(200)
        assertTrue(t.sent.any { it is WearableCommand.SendGlucose })
        m.snoozeFor(10)
        m.enqueue(WearableCommand.Vibrate(WearableAlertKind.URGENT_LOW))
        delay(200)
        assertFalse(t.sent.any { it is WearableCommand.Vibrate })
        m.stop()
        scope.cancel()
    }

    @Test fun `politica de vibracao por tipo`() {
        assertEquals(WearableAlertKind.URGENT_LOW, WearableVibrationPolicy.kindFor(com.gluview.app.domain.model.GlucoseZone.URGENT_LOW))
        assertTrue(WearableVibrationPolicy.pattern(WearableAlertKind.URGENT_LOW).size >= 5)
        assertEquals(0, WearableVibrationPolicy.pattern(WearableAlertKind.NORMAL).size)
    }

    @Test fun `manager reconecta apos falha de envio`() = runBlocking {
        val t = FakeWearableTransport()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val m = WearableDeviceManager(t, scope, ReconnectBackoff(1, 2, 2.0, 0.0), nowMs = { 1_000L })
        m.start(); delay(80)
        t.sendOk = false
        m.enqueue(WearableCommand.Ping)
        delay(150)
        t.sendOk = true
        m.enqueue(WearableCommand.Ping)
        delay(250)
        assertTrue(t.connectCalls >= 1)
        m.stop(); scope.cancel()
    }
}
