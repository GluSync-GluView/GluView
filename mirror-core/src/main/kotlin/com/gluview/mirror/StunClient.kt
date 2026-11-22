package com.gluview.mirror

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.security.SecureRandom

/**
 * Plano 21, F2.4 — cliente STUN mínimo (RFC 5389, só "Binding"), para descobrir o IP público do
 * celular do paciente. Usa servidores públicos e grátis; não manda nenhum dado do paciente (o pedido
 * tem só 20 bytes: tipo, tamanho, "magic cookie" e um id aleatório).
 *
 * Ver [MirrorCandidate] sobre o limite: isso NÃO fura CGNAT; o IP serve quando a porta do espelho
 * está redirecionada no roteador ou o celular tem IP público.
 */
object StunClient {
    /** Servidores STUN públicos e grátis (Google e Cloudflare). */
    val PUBLIC_SERVERS = listOf("stun.l.google.com" to 19302, "stun.cloudflare.com" to 3478)

    const val MAGIC_COOKIE = 0x2112A442
    private const val BINDING_REQUEST = 0x0001
    private const val BINDING_SUCCESS = 0x0101
    private const val ATTR_MAPPED_ADDRESS = 0x0001
    private const val ATTR_XOR_MAPPED_ADDRESS = 0x0020
    /** Código antigo do XOR-MAPPED-ADDRESS, ainda usado por alguns servidores. */
    private const val ATTR_XOR_MAPPED_ADDRESS_OLD = 0x8020
    private val random = SecureRandom()

    fun newTransactionId(): ByteArray = ByteArray(12).also { random.nextBytes(it) }

    fun bindingRequest(txId: ByteArray): ByteArray {
        require(txId.size == 12)
        return ByteBuffer.allocate(20).putShort(BINDING_REQUEST.toShort()).putShort(0).putInt(MAGIC_COOKIE).put(txId).array()
    }

    /**
     * Endereço público visto pelo servidor, ou null se a resposta não for um "Binding Success" desta
     * transação. Prefere XOR-MAPPED-ADDRESS; aceita MAPPED-ADDRESS (servidores antigos).
     */
    fun parseResponse(data: ByteArray, length: Int = data.size, txId: ByteArray): InetSocketAddress? {
        if (length < 20 || length > data.size) return null
        val b = ByteBuffer.wrap(data, 0, length)
        val type = b.short.toInt() and 0xFFFF
        val msgLen = b.short.toInt() and 0xFFFF
        if (type != BINDING_SUCCESS || b.int != MAGIC_COOKIE || 20 + msgLen > length) return null
        val tx = ByteArray(12).also { b.get(it) }
        if (!tx.contentEquals(txId)) return null
        var mapped: InetSocketAddress? = null
        var pos = 20
        val end = 20 + msgLen
        while (pos + 4 <= end) {
            val at = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
            val al = ((data[pos + 2].toInt() and 0xFF) shl 8) or (data[pos + 3].toInt() and 0xFF)
            val v = pos + 4
            if (v + al > end) return null
            when (at) {
                ATTR_XOR_MAPPED_ADDRESS, ATTR_XOR_MAPPED_ADDRESS_OLD -> address(data, v, al, xor = true, txId)?.let { return it }
                ATTR_MAPPED_ADDRESS -> if (mapped == null) mapped = address(data, v, al, xor = false, txId)
            }
            pos = v + ((al + 3) and 3.inv())   // atributos alinhados em 4 bytes
        }
        return mapped
    }

    private fun address(d: ByteArray, off: Int, len: Int, xor: Boolean, txId: ByteArray): InetSocketAddress? {
        if (len < 8) return null
        val family = d[off + 1].toInt() and 0xFF
        val addrLen = when (family) { 0x01 -> 4; 0x02 -> 16; else -> return null }
        if (len < 4 + addrLen) return null
        var port = ((d[off + 2].toInt() and 0xFF) shl 8) or (d[off + 3].toInt() and 0xFF)
        val addr = d.copyOfRange(off + 4, off + 4 + addrLen)
        if (xor) {
            port = port xor (MAGIC_COOKIE ushr 16)
            val mask = ByteBuffer.allocate(16).putInt(MAGIC_COOKIE).put(txId).array()
            for (i in addr.indices) addr[i] = (addr[i].toInt() xor mask[i].toInt()).toByte()
        }
        return runCatching { InetSocketAddress(InetAddress.getByAddress(addr), port) }.getOrNull()
    }

    /** Pergunta a um servidor (UDP, até [attempts] envios). Null em falha ou tempo esgotado. */
    fun query(server: InetSocketAddress, timeoutMs: Int = 2_500, attempts: Int = 2): InetSocketAddress? {
        val tx = newTransactionId()
        val req = bindingRequest(tx)
        return runCatching {
            DatagramSocket().use { sock ->
                sock.soTimeout = timeoutMs
                val buf = ByteArray(1024)
                repeat(attempts) {
                    sock.send(DatagramPacket(req, req.size, server))
                    try {
                        while (true) {
                            val p = DatagramPacket(buf, buf.size)
                            sock.receive(p)
                            if (p.address == server.address && p.port == server.port)
                                parseResponse(buf, p.length, tx)?.let { return@use it }
                        }
                    } catch (_: SocketTimeoutException) { /* tenta de novo */ }
                }
                null
            }
        }.getOrNull()
    }

    /**
     * IPv4 público deste aparelho pelo primeiro servidor que responder. Faz DNS e rede: chamar fora
     * da thread principal.
     */
    fun publicIpv4(servers: List<Pair<String, Int>> = PUBLIC_SERVERS): String? {
        for ((host, port) in servers) {
            val ip = runCatching { InetAddress.getAllByName(host).firstOrNull { it is Inet4Address } }.getOrNull() ?: continue
            val mapped = query(InetSocketAddress(ip, port)) ?: continue
            if (mapped.address is Inet4Address) return mapped.address.hostAddress
        }
        return null
    }
}
