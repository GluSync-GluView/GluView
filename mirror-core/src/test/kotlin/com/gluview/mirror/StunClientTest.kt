package com.gluview.mirror

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import kotlin.concurrent.thread

class StunClientTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    /** Id da transação dos vetores de teste da RFC 5769. */
    private val rfcTx = bytes(0xb7, 0xe7, 0xa7, 0x01, 0xbc, 0x34, 0xd6, 0x86, 0xfa, 0x87, 0xdf, 0xae)

    /** RFC 5769 §2.2 (resposta IPv4). MESSAGE-INTEGRITY e FINGERPRINT zerados: o cliente não os confere. */
    private val rfcIpv4 = bytes(
        0x01, 0x01, 0x00, 0x3c, 0x21, 0x12, 0xa4, 0x42) + rfcTx + bytes(
        0x80, 0x22, 0x00, 0x0b, 0x74, 0x65, 0x73, 0x74, 0x20, 0x76, 0x65, 0x63, 0x74, 0x6f, 0x72, 0x20,
        0x00, 0x20, 0x00, 0x08, 0x00, 0x01, 0xa1, 0x47, 0xe1, 0x12, 0xa6, 0x43,
        0x00, 0x08, 0x00, 0x14) + ByteArray(20) + bytes(0x80, 0x28, 0x00, 0x04, 0, 0, 0, 0)

    /** RFC 5769 §2.3 (resposta IPv6). */
    private val rfcIpv6 = bytes(
        0x01, 0x01, 0x00, 0x48, 0x21, 0x12, 0xa4, 0x42) + rfcTx + bytes(
        0x80, 0x22, 0x00, 0x0b, 0x74, 0x65, 0x73, 0x74, 0x20, 0x76, 0x65, 0x63, 0x74, 0x6f, 0x72, 0x20,
        0x00, 0x20, 0x00, 0x14, 0x00, 0x02, 0xa1, 0x47,
        0x01, 0x13, 0xa9, 0xfa, 0xa5, 0xd3, 0xf1, 0x79, 0xbc, 0x25, 0xf4, 0xb5, 0xbe, 0xd2, 0xb9, 0xd9,
        0x00, 0x08, 0x00, 0x14) + ByteArray(20) + bytes(0x80, 0x28, 0x00, 0x04, 0, 0, 0, 0)

    @Test fun rfc5769Ipv4() {
        val a = StunClient.parseResponse(rfcIpv4, txId = rfcTx)!!
        assertEquals("192.0.2.1", a.address.hostAddress)
        assertEquals(32853, a.port)
    }

    @Test fun rfc5769Ipv6() {
        val a = StunClient.parseResponse(rfcIpv6, txId = rfcTx)!!
        assertEquals(InetAddress.getByName("2001:db8:1234:5678:11:2233:4455:6677"), a.address)
        assertEquals(32853, a.port)
    }

    @Test fun recusaOutraTransacaoOuTipo() {
        assertNull(StunClient.parseResponse(rfcIpv4, txId = ByteArray(12)))
        val notSuccess = rfcIpv4.copyOf().also { it[1] = 0x11 } // 0x0111 = erro
        assertNull(StunClient.parseResponse(notSuccess, txId = rfcTx))
        assertNull(StunClient.parseResponse(rfcIpv4.copyOf(30), txId = rfcTx)) // cortada
    }

    @Test fun aceitaMappedAddressDeServidorAntigo() {
        val tx = StunClient.newTransactionId()
        val msg = ByteBuffer.allocate(32).putShort(0x0101).putShort(12).putInt(StunClient.MAGIC_COOKIE).put(tx)
            .putShort(0x0001).putShort(8).put(0).put(1).putShort(4321).put(bytes(198, 51, 100, 9)).array()
        val a = StunClient.parseResponse(msg, txId = tx)!!
        assertEquals("198.51.100.9", a.address.hostAddress)
        assertEquals(4321, a.port)
    }

    @Test fun pedidoTemCabecalhoDaRfc() {
        val tx = StunClient.newTransactionId()
        val r = StunClient.bindingRequest(tx)
        assertEquals(20, r.size)
        assertArrayEquals(bytes(0x00, 0x01, 0x00, 0x00, 0x21, 0x12, 0xa4, 0x42), r.copyOf(8))
        assertArrayEquals(tx, r.copyOfRange(8, 20))
    }

    /** Servidor STUN falso no próprio computador: responde com o endereço de origem visto (XOR). */
    @Test fun idaEVoltaPorUdp() {
        DatagramSocket(0, InetAddress.getLoopbackAddress()).use { srv ->
            thread(isDaemon = true) {
                val buf = ByteArray(512)
                val p = DatagramPacket(buf, buf.size)
                srv.receive(p)
                val tx = buf.copyOfRange(8, 20)
                val port = p.port xor 0x2112
                val ip = p.address.address
                val mask = ByteBuffer.allocate(4).putInt(StunClient.MAGIC_COOKIE).array()
                val x = ByteArray(4) { (ip[it].toInt() xor mask[it].toInt()).toByte() }
                val resp = ByteBuffer.allocate(32).putShort(0x0101).putShort(12).putInt(StunClient.MAGIC_COOKIE).put(tx)
                    .putShort(0x0020).putShort(8).put(0).put(1).putShort(port.toShort()).put(x).array()
                srv.send(DatagramPacket(resp, resp.size, p.socketAddress))
            }
            val seen = StunClient.query(InetSocketAddress(InetAddress.getLoopbackAddress(), srv.localPort), timeoutMs = 2_000)!!
            assertEquals(InetAddress.getLoopbackAddress(), seen.address)
        }
    }

    @Test fun semRespostaDevolveNull() {
        DatagramSocket(0, InetAddress.getLoopbackAddress()).use { mudo ->
            assertNull(StunClient.query(InetSocketAddress(InetAddress.getLoopbackAddress(), mudo.localPort), timeoutMs = 200))
        }
    }

    /** Consulta de verdade aos servidores públicos; só roda com -Dstun.live=true (não depende de rede no CI). */
    @Test fun servidorPublicoDeVerdade() {
        if (System.getProperty("stun.live") != "true") return
        val ip = StunClient.publicIpv4()
        println("STUN público: IPv4 ${if (ip != null) "recebido" else "sem resposta"}")
        org.junit.Assert.assertNotNull(ip)
    }
}
