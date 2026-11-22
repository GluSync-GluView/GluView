package com.gluview.mirror

import com.gluview.mirror.MirrorCandidate.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class MirrorCandidateTest {
    @Test fun idaEVolta() {
        val c = MirrorCandidate(Kind.IPV6, "2001:db8::5", 17581)
        assertEquals(c, MirrorCandidate.decode(c.encode()))
        assertEquals(MirrorCandidate(Kind.PUBLIC, "casa.exemplo.net", 17581), MirrorCandidate.decode("pub|casa.exemplo.net|17581"))
    }

    @Test fun recusaInvalidos() {
        assertNull(MirrorCandidate.decode("xyz|1.2.3.4|17581"))
        assertNull(MirrorCandidate.decode("lan|1.2.3.4|0"))
        assertNull(MirrorCandidate.decode("lan|1.2.3.4|70000"))
        assertNull(MirrorCandidate.decode("lan||17581"))
        assertNull(MirrorCandidate.decode("lan|a b|17581"))
        assertNull(MirrorCandidate.decode("v6|fe80::1%wlan0|17581"))
        assertNull(MirrorCandidate.decode("lan|1.2.3.4"))
    }

    @Test fun ordemRedeLocalPrimeiroStunPorUltimo() {
        val l = MirrorCandidate.ordered(listOf(
            MirrorCandidate(Kind.STUN, "203.0.113.7", 17581),
            MirrorCandidate(Kind.PUBLIC, "casa.exemplo.net", 17581),
            MirrorCandidate(Kind.IPV6, "2001:db8::5", 17581),
            MirrorCandidate(Kind.LAN, "192.168.0.10", 17581),
            MirrorCandidate(Kind.STUN, "192.168.0.10", 17581)))   // repetido: fica o de rede local
        assertEquals(listOf(Kind.LAN, Kind.IPV6, Kind.PUBLIC, Kind.STUN), l.map { it.kind })
        assertEquals("203.0.113.7", l.last().host)
    }

    @Test fun limiteDeOito() {
        val many = (1..20).map { MirrorCandidate(Kind.PUBLIC, "h$it.exemplo.net", 17581) }
        assertEquals(MirrorCandidate.MAX, MirrorCandidate.ordered(many).size)
        assertEquals(MirrorCandidate.MAX, MirrorCandidate.decodeList(many.map { it.encode() }).size)
    }

    @Test fun ipv6Global() {
        assertTrue(MirrorCandidate.isGlobalIpv6(InetAddress.getByName("2804:14c:1234::5")))
        assertFalse(MirrorCandidate.isGlobalIpv6(InetAddress.getByName("fe80::1")))
        assertFalse(MirrorCandidate.isGlobalIpv6(InetAddress.getByName("fd00::1")))
        assertFalse(MirrorCandidate.isGlobalIpv6(InetAddress.getByName("::1")))
        assertFalse(MirrorCandidate.isGlobalIpv6(InetAddress.getByName("192.168.0.1")))
    }

    @Test fun codigoComEnderecosExtrasEOAntigoContinuaValendo() {
        val extra = listOf(MirrorCandidate(Kind.IPV6, "2001:db8::5", 17581), MirrorCandidate(Kind.STUN, "203.0.113.7", 17581))
        val c = PairingCode(MirrorCrypto.newPairId(), MirrorCrypto.newKey(), "192.168.0.10", 17581, "Paciente", extra)
        assertEquals(c, PairingCode.decode(c.encode()))
        val old = PairingCode(c.pairId, c.key, c.host, c.port, c.patientName)
        assertEquals(7, old.encode().split(':').size)
        assertEquals(old, PairingCode.decode(old.encode()))
    }
}
