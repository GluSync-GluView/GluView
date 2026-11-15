package com.gluview.mirror

import org.junit.Assert.assertEquals
import org.junit.Test

class MirrorCoreTest {
    @Test fun protocoloInicial() = assertEquals(1, MirrorCore.PROTOCOL_VERSION)
}
