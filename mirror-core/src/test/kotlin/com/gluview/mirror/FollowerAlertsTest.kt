package com.gluview.mirror

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FollowerAlertsTest {
    private val now = 1_800_000_000_000L
    private val th = MirrorThresholds()
    private val prefs = FollowerAlertPrefs(repeatMin = 30, linkTimeoutMin = 10, noSignalMin = 20)
    private fun r(v: Double, minAgo: Int = 1) = MirrorReading(now - minAgo * 60_000L, v, "Flat")

    @Test fun linkCaidoTemPrioridade() {
        val a = FollowerAlerts()
        assertEquals(FollowerAlertKind.LINK_DOWN, a.evaluate(now, r(50.0), now - 11 * 60_000L, th, prefs))
    }

    @Test fun nuncaConectouNaoAlarma() = assertNull(FollowerAlerts().evaluate(now, null, 0L, th, prefs))

    @Test fun semLeituraNovaComLinkOk() {
        assertEquals(FollowerAlertKind.NO_SENSOR_DATA, FollowerAlerts().evaluate(now, r(120.0, 25), now, th, prefs))
        assertEquals(FollowerAlertKind.NO_SENSOR_DATA, FollowerAlerts().evaluate(now, null, now, th, prefs))
    }

    @Test fun faixasComLimiteContandoComoFora() {
        fun k(v: Double) = FollowerAlerts().evaluate(now, r(v), now, th, prefs)
        assertEquals(FollowerAlertKind.URGENT_LOW, k(55.0))
        assertEquals(FollowerAlertKind.LOW, k(70.0))
        assertNull(k(71.0)); assertNull(k(179.0))
        assertEquals(FollowerAlertKind.HIGH, k(180.0))
        assertEquals(FollowerAlertKind.URGENT_HIGH, k(250.0))
    }

    @Test fun naoRepeteAntesDoIntervaloMasAvisaMudanca() {
        val a = FollowerAlerts()
        assertEquals(FollowerAlertKind.LOW, a.evaluate(now, r(65.0), now, th, prefs))
        assertNull(a.evaluate(now + 5 * 60_000L, r(66.0, -4), now + 5 * 60_000L, th, prefs))
        assertEquals(FollowerAlertKind.URGENT_LOW, a.evaluate(now + 6 * 60_000L, r(50.0, -5), now + 6 * 60_000L, th, prefs))
        assertEquals(FollowerAlertKind.URGENT_LOW, a.evaluate(now + 37 * 60_000L, r(50.0, -36), now + 37 * 60_000L, th, prefs))
    }

    @Test fun voltaAoNormalZeraEDesligadoNaoDispara() {
        val a = FollowerAlerts()
        assertEquals(FollowerAlertKind.HIGH, a.evaluate(now, r(200.0), now, th, prefs))
        assertNull(a.evaluate(now + 60_000L, r(150.0, 0), now + 60_000L, th, prefs))
        assertEquals(FollowerAlertKind.HIGH, a.evaluate(now + 120_000L, r(200.0, -1), now + 120_000L, th, prefs))
        val off = prefs.copy(enabled = setOf(FollowerAlertKind.URGENT_LOW))
        assertNull(FollowerAlerts().evaluate(now, r(200.0), now, th, off))
    }
}
