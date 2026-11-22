package com.gluview.wear.contract

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WearAlertTest {
    @Test fun idaEVolta() {
        val a = WearAlert(42L, "URGENT_LOW", "Glicose muito baixa", "48 mg/dL ↓↓", listOf(800, 300, 800))
        assertEquals(a, WearAlert.decode(WearAlert.encode(a)))
        assertArrayEquals(longArrayOf(0, 800, 300, 800), a.waveform())
    }

    @Test fun quebraDeLinhaNoTextoNaoQuebraOFormato() {
        val a = WearAlert(1L, "LOW", "Baixa\nagora", "62\r\nmg/dL", emptyList())
        val back = WearAlert.decode(WearAlert.encode(a))!!
        assertEquals("Baixa agora", back.title)
        assertEquals("62  mg/dL", back.body)
        assertEquals(emptyList<Long>(), back.segmentsMs)
    }

    @Test fun versaoDesconhecidaOuLixoViramNull() {
        assertNull(WearAlert.decode("2\n1\nLOW\nt\nb\n100".toByteArray()))
        assertNull(WearAlert.decode("1\nx\nLOW\nt\nb\n100".toByteArray()))
        assertNull(WearAlert.decode("1\n1\nLOW\nt\nb\n-5".toByteArray()))
        assertNull(WearAlert.decode(ByteArray(0)))
    }
}
