package com.gluview.mirror

import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * Plano 21, F2.4 — espelho pela internet SEM servidor (decisão do Felipe, 21/11/2026): primeiro a
 * mesma rede, depois um endereço alcançável (IPv6 global; endereço público informado pela pessoa:
 * DDNS, VPN, porta redirecionada no roteador) e, como tentativa extra, o IP público descoberto por
 * STUN ([StunClient]).
 *
 * Limite honesto: sem um servidor de encontro o STUN NÃO fura CGNAT (o Juggluco usa um servidor
 * próprio de sinalização + TURN pago para isso). Aqui ele só descobre o IP público, que funciona
 * quando o roteador redireciona a porta do espelho para o celular ou quando o celular tem IP público.
 *
 * O paciente manda a lista atual dentro do "bem-vindo" (canal cifrado e autenticado); o seguidor
 * guarda e usa quando sai da rede de casa. Endereços mudam (IP dinâmico, IPv6 temporário), então a
 * lista é renovada a cada conexão.
 */
data class MirrorCandidate(val kind: Kind, val host: String, val port: Int) {
    /** Ordem de tentativa = ordem do enum. */
    enum class Kind(val code: String) { LAN("lan"), IPV6("v6"), PUBLIC("pub"), STUN("stun") }

    fun encode(): String = "${kind.code}|$host|$port"

    /** Resolve o nome (pode consultar DNS: chamar fora da thread principal). */
    fun socketAddress(): InetSocketAddress? = runCatching { InetSocketAddress(host, port) }.getOrNull()
        ?.takeIf { !it.isUnresolved }

    companion object {
        const val MAX = 8

        fun decode(s: String): MirrorCandidate? {
            val p = s.trim().split('|')
            if (p.size != 3) return null
            val kind = Kind.entries.firstOrNull { it.code == p[0] } ?: return null
            val host = p[1].takeIf(::validHost) ?: return null
            val port = p[2].toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            return MirrorCandidate(kind, host, port)
        }

        /** IP ou nome sem espaço nem separadores; IPv6 sem colchetes e sem "%escopo". */
        fun validHost(h: String): Boolean = h.isNotEmpty() && h.length <= 253 &&
            h.none { it.isWhitespace() || it == '|' || it == ',' || it == '/' || it == '[' || it == ']' || it == '%' }

        /** Rede local → IPv6 → endereço público informado → STUN; sem repetidos; no máximo [MAX]. */
        fun ordered(list: List<MirrorCandidate>): List<MirrorCandidate> =
            list.sortedBy { it.kind.ordinal }.distinctBy { it.host.lowercase() to it.port }.take(MAX)

        fun decodeList(items: List<*>?): List<MirrorCandidate> =
            items.orEmpty().mapNotNull { (it as? String)?.let(::decode) }.take(MAX)

        /** IPv6 alcançável de fora: global unicast (2000::/3), fora loopback, link-local e ULA (fc00::/7). */
        fun isGlobalIpv6(a: InetAddress): Boolean = a is Inet6Address && !a.isLoopbackAddress &&
            !a.isLinkLocalAddress && !a.isSiteLocalAddress && (a.address[0].toInt() and 0xE0) == 0x20

        /** Texto do IPv6 sem o "%escopo". */
        fun hostOf(a: InetAddress): String = a.hostAddress.substringBefore('%')
    }
}
