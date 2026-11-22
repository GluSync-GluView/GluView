package com.gluview.wearables.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class WearableAlertsTest {
    @Test fun `ajustes ida e volta e padroes`() {
        val d = WearableAlertSettings.defaults(supportsCall = true, enabled = true)
        assertEquals(VibrationStyle.CALL, d.ruleFor(WearableAlertKind.URGENT_LOW).style)
        assertEquals(VibrationStyle.INSISTENT, WearableAlertSettings.defaults(false).ruleFor(WearableAlertKind.URGENT_LOW).style)
        val custom = d.with(WearableAlertKind.HIGH, AlertRule(false, VibrationStyle.LONG))
        assertEquals(custom, WearableAlertSettings.decode(custom.encode(), supportsCall = true))
        // lixo e campos desconhecidos ficam no padrão, sem quebrar
        val messy = WearableAlertSettings.decode("1;LOW=1:xyz;FOO=1:short;HIGH=0:long", supportsCall = false)
        assertTrue(messy.enabled)
        assertEquals(VibrationStyle.TRIPLE, messy.ruleFor(WearableAlertKind.LOW).style)
        assertEquals(AlertRule(false, VibrationStyle.LONG), messy.ruleFor(WearableAlertKind.HIGH))
        assertEquals(false, WearableAlertSettings.decode(null, false).enabled)
    }

    @Test fun `estilos tem pulsos e tempos`() {
        assertEquals(3, VibrationStyle.TRIPLE.pulses)
        assertEquals(500L, VibrationStyle.TRIPLE.onMs)
        assertEquals(200L, VibrationStyle.TRIPLE.offMs)
        assertEquals(0, VibrationStyle.OFF.pulses)
    }

    @Test fun `roteador respeita ligado, regra, soneca e adapta o estilo`() {
        val s = WearableAlertSettings.defaults(supportsCall = true, enabled = true)
        val all = VibrationStyle.entries.toSet()
        assertEquals(VibrationStyle.CALL, WearableAlertRouter.decide(s, WearableAlertKind.URGENT_LOW, all, 0, 10))
        // sem "chamada" no aparelho: insistente
        assertEquals(VibrationStyle.INSISTENT, WearableAlertRouter.decide(s, WearableAlertKind.URGENT_LOW, VibrationStyle.BASIC, 0, 10))
        // aparelho que só notifica (padrão dele)
        assertEquals(VibrationStyle.SHORT, WearableAlertRouter.decide(s, WearableAlertKind.LOW, setOf(VibrationStyle.OFF, VibrationStyle.SHORT), 0, 10))
        assertNull(WearableAlertRouter.decide(s, WearableAlertKind.LOW, all, snoozedUntilMs = 100, nowMs = 10))
        assertNull(WearableAlertRouter.decide(s.copy(enabled = false), WearableAlertKind.LOW, all, 0, 10))
        // "sem vibrar" ainda leva o texto
        assertEquals(VibrationStyle.OFF, WearableAlertRouter.decide(s.with(WearableAlertKind.LOW, AlertRule(true, VibrationStyle.OFF)), WearableAlertKind.LOW, VibrationStyle.BASIC, 0, 10))
        assertNull(WearableAlertRouter.decide(s.with(WearableAlertKind.LOW, AlertRule(false, VibrationStyle.TRIPLE)), WearableAlertKind.LOW, all, 0, 10))
    }

    private class FakeTransport(private val failFirst: Int = 0) : WearableTransport {
        override val info = WearableDeviceInfo("x", "X", "fake", WearableCapabilities(canShowGlucose = true, canVibrate = true))
        override val events = MutableSharedFlow<WearableEvent>(extraBufferCapacity = 8)
        val sent = CopyOnWriteArrayList<WearableCommand>()
        var failures = 0
        override suspend fun connect() = true
        override suspend fun disconnect() {}
        override suspend fun isConnected() = true
        override suspend fun send(command: WearableCommand): Boolean {
            if (failures < failFirst) { failures++; return false }
            sent += command; return true
        }
    }

    @Test fun `manager entrega alerta, respeita soneca e desiste depois de 3 tentativas`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val t = FakeTransport()
            val m = WearableDeviceManager(t, scope, ReconnectBackoff(10, 10))
            m.start()
            m.enqueue(WearableCommand.Alert(WearableAlertKind.LOW, "Baixa", "62", VibrationStyle.TRIPLE))
            withTimeout(2_000) { while (t.sent.isEmpty()) kotlinx.coroutines.delay(10) }
            m.snoozeUntil(System.currentTimeMillis() + 60_000)
            m.enqueue(WearableCommand.Alert(WearableAlertKind.LOW, "Baixa", "60", VibrationStyle.TRIPLE))
            m.enqueue(WearableCommand.Find)
            withTimeout(2_000) { while (t.sent.size < 2) kotlinx.coroutines.delay(10) }
            assertEquals(listOf("62"), t.sent.filterIsInstance<WearableCommand.Alert>().map { it.body })   // o 2º caiu na soneca
            assertTrue(t.sent.last() is WearableCommand.Find)

            val flaky = FakeTransport(failFirst = 10)
            val m2 = WearableDeviceManager(flaky, scope, ReconnectBackoff(5, 5))
            m2.start()
            m2.enqueue(WearableCommand.Alert(WearableAlertKind.URGENT_LOW, "Urgente", "48", VibrationStyle.CALL))
            kotlinx.coroutines.delay(500)
            assertEquals(3, flaky.failures)   // 1ª + 2 novas tentativas, e desiste

            // "Encontrar" que falha não fica tentando para sempre.
            val dead = FakeTransport(failFirst = 100)
            val m3 = WearableDeviceManager(dead, scope, ReconnectBackoff(5, 5))
            m3.start()
            m3.enqueue(WearableCommand.Find)
            kotlinx.coroutines.delay(300)
            assertEquals(1, dead.failures)
        } finally { scope.cancel() }
    }

    @Test fun `soneca pedida no aparelho vira um evento so e silencia os alertas`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val t = FakeTransport()
            var now = 1_000_000L
            val m = WearableDeviceManager(t, scope, ReconnectBackoff(10, 10), nowMs = { now }, snoozeMinutes = 15)
            val seen = CopyOnWriteArrayList<WearableEvent>()
            val job = scope.launch { m.events.collect { seen += it } }
            m.start()
            kotlinx.coroutines.delay(100)
            t.events.emit(WearableEvent.SnoozeRequested("x"))
            withTimeout(2_000) { while (seen.isEmpty()) kotlinx.coroutines.delay(10) }
            kotlinx.coroutines.delay(100)
            assertEquals(1, seen.count { it is WearableEvent.SnoozeRequested })
            assertEquals(now + 15 * 60_000L, m.state.value.snoozedUntilMs)
            assertTrue(m.isSnoozed())
            now += 16 * 60_000L
            assertTrue(!m.isSnoozed())
            job.cancel()
        } finally { scope.cancel() }
    }
}
