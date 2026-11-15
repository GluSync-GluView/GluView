package com.gluview.app.data.sibionics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Âncora do início do sensor: mover no meio da sessão não pode derrubar o "Conectado" (plano 14_, V2). */
class SibionicsStatusHolderTest {
    @Test fun moverAncora_mantemConexaoEVidaUtil() {
        val h = SibionicsStatusHolder()
        h.restoreAnchor("22222A2WC8", 1_000_000L)
        h.onConnectionChanged(true)
        h.onReading(index = 125, sensorStartMs = 1_000_000L, nowMs = 9_000_000L, batteryMv = 3000)
        h.onSensorLifetime(expiryMinutes = 20_160, sessionEnded = true)

        h.moveAnchor(2_800_000L)

        val s = h.status.value
        assertEquals(2_800_000L, s.sensorStartMs)
        assertTrue(s.connected)
        assertEquals("22222A2WC8", s.serial)
        assertEquals(9_000_000L, s.lastReadingMs)
        assertEquals(3000, s.batteryMv)
        assertEquals(20_160, s.expiryMinutes)
        assertTrue(s.sessionEnded)
    }

    @Test fun restaurarAncora_continuaComecandoDoZero() {
        val h = SibionicsStatusHolder()
        h.onConnectionChanged(true)
        h.restoreAnchor("X", 5L)
        assertFalse(h.status.value.connected)
        assertEquals(5L, h.status.value.sensorStartMs)
    }
}
