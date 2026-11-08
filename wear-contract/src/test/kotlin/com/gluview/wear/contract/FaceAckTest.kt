package com.gluview.wear.contract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FaceAckTest {
    @Test fun idaEVolta() {
        listOf(FaceAck.Installed(updated = true, active = false), FaceAck.Installed(false, true),
            FaceAck.Unsupported, FaceAck.NoSlot, FaceAck.Failed("Erro: x | y"))
            .forEach { assertEquals(it, FaceAck.decode(FaceAck.encode(it))) }
    }

    @Test fun lixoViraNull() = assertNull(FaceAck.decode("???".toByteArray()))

    @Test fun caminhosDoVisor_naoMudam() {
        assertEquals("/gluview/watchface", WearPaths.WATCHFACE)
        assertEquals("/gluview/watchface/ack", WearPaths.WATCHFACE_ACK)
        assertEquals("gluview_watchface_push", WearPaths.CAPABILITY_FACE_PUSH)
    }
}
