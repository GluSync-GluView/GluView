package com.gluview.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** L32: "rodando" nasce de o serviço subir, não do 1º envio (o seguidor nunca envia). */
class ServiceMonitorTest {

    @Test fun `servico no ar sem nenhum envio aparece rodando`() {
        val m = ServiceMonitor()
        assertFalse(m.serviceRunning.value)
        m.recordStarted()
        assertTrue(m.serviceRunning.value)
        assertEquals(0L, m.lastSyncAt.value)
    }

    @Test fun `parar desliga e sincronizar limpa o erro`() {
        val m = ServiceMonitor()
        m.recordStarted()
        m.recordError("Falha ao buscar dados")
        m.recordSync()
        assertTrue(m.lastSyncAt.value > 0)
        assertEquals(null, m.syncError.value)
        m.recordStopped()
        assertFalse(m.serviceRunning.value)
    }
}
