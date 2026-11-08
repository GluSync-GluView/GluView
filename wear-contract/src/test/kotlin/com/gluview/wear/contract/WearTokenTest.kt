package com.gluview.wear.contract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WearTokenTest {
    private val now = 1_794_150_000_000L
    private val t = WearToken("eyJhbGciOi.x.y", "https://glusync-herz.onrender.com", now + 3_600_000)

    @Test fun encodeDecode_idaEVolta() = assertEquals(t, WearToken.decode(WearToken.encode(t)))

    @Test fun cleared_idaEVolta_eNaoEhUtilizavel() {
        val c = WearToken.decode(WearToken.encode(WearToken.CLEARED))
        assertEquals(WearToken.CLEARED, c)
        assertFalse(c!!.isUsable(now))
    }

    @Test fun validade_exigeMaisDe1MinRestante() {
        assertTrue(t.isUsable(now))
        assertFalse(t.copy(expiresAtMs = now + 30_000).isUsable(now))
        assertFalse(t.copy(expiresAtMs = now - 1).isUsable(now))
    }

    @Test fun barraFinalDaUrlEhRemovida() =
        assertEquals("https://x.y", WearToken.fromMap(t.copy(baseUrl = "https://x.y/").toMap())!!.baseUrl)

    @Test fun urlSemEsquemaOuVersaoFutura_recusadas() {
        assertNull(WearToken.fromMap(t.copy(baseUrl = "javascript:alert(1)").toMap()))
        assertNull(WearToken.fromMap(t.toMap() + (WearToken.K_VERSION to 2)))
        assertNull(WearToken.decode("lixo".toByteArray()))
    }

    @Test fun caminhoDoToken_naoMuda() = assertEquals("/gluview/token", WearPaths.TOKEN)
}
