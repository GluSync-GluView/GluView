package com.gluview.app.mirror

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** F2.4: endereço público, STUN e endereços aprendidos sobrevivem ao salvar/ler. */
class MirrorStoreCodecTest {
    @Test fun idaEVoltaComCamposNovos() {
        val s = MirrorSettings(shareEnabled = true, patientName = "Paciente", publicHost = "casa.exemplo.net", stunEnabled = false,
            followed = listOf(FollowedPatient("00".repeat(16), "a2V5", "Fulano", "192.168.0.10", 17581,
                learned = listOf("v6|2001:db8::5|17581", "stun|203.0.113.7|17581"))))
        assertEquals(s, MirrorStore.decode(MirrorStore.encode(s)))
    }

    @Test fun ajustesAntigosLeemComPadroes() {
        val old = """{"shareEnabled":true,"sharePort":17581,"followed":[{"id":"${"00".repeat(16)}","k":"a2V5","name":"x","host":"","port":17581}]}"""
        val s = MirrorStore.decode(old)
        assertEquals("", s.publicHost)
        assertTrue("STUN ligado por padrão (decisão do Felipe: tentativa extra)", s.stunEnabled)
        assertEquals(emptyList<String>(), s.followed.single().learned)
    }
}
