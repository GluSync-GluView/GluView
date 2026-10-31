package com.gluview.app.util

import com.gluview.app.domain.model.GlucoseReading
import com.gluview.app.domain.model.GlucoseZone
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** L12: "sem sinal" pela idade real da leitura (Auto, Dream e notificação usam a mesma regra). */
class ReadingAgeTest {

    private val now = 1_790_000_000_000L
    private fun reading(ageMin: Int) = GlucoseReading(
        sgvMgDl = 120.0, direction = "Flat", arrow = "→", timestamp = "", zone = GlucoseZone.NORMAL,
        delta = 0, isStale = false, minutesAgo = 0, epochMs = now - ageMin * 60_000L
    )

    @Test fun `dentro do limiar continua atual`() {
        assertFalse(reading(29).agedFor(30, now).isStale)
    }

    @Test fun `no limiar vira sem sinal`() {
        assertTrue(reading(30).agedFor(30, now).isStale)
    }

    @Test fun `sem hora da medicao nao muda`() {
        assertFalse(reading(0).copy(epochMs = 0L).agedFor(30, now).isStale)
    }
}
