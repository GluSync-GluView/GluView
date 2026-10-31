package com.gluview.app.data.repository

import com.gluview.app.data.repository.CloudAccessRepository.Companion.stateFrom
import org.junit.Assert.assertEquals
import org.junit.Test

/** ADR-0030 — estado da liberação "nuvem e acompanhamento" a partir do /access-requests/me/status. */
class CloudAccessStateTest {

    @Test fun `nunca pediu`() = assertEquals("none", stateFrom(emptyMap()))

    @Test fun `pacote aprovado`() = assertEquals("approved", stateFrom(mapOf("cloud_sync" to "approved")))

    @Test fun `pedido em analise ou recusado aparece como esta`() {
        assertEquals("pending", stateFrom(mapOf("cloud_sync" to "pending")))
        assertEquals("rejected", stateFrom(mapOf("cloud_sync" to "rejected")))
    }

    @Test fun `envio de glicemia concedido avulso pelo admin tambem libera o envio`() =
        assertEquals("approved", stateFrom(mapOf("cloud_upload:glucose" to "approved", "cloud_sync" to "revoked")))

    @Test fun `outras liberacoes nao contam`() =
        assertEquals("none", stateFrom(mapOf("insulin_pump_beta" to "approved", "cloud_follow" to "approved")))
}
