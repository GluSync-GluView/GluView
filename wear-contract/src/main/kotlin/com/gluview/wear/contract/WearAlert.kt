package com.gluview.wear.contract

/**
 * Plano 21, F7.1 — alerta do GluView no relógio Wear OS (mensagem em [WearPaths.ALERT]): título, texto e a
 * vibração escolhida pela pessoa para este tipo de alerta, em trechos [liga, pausa, liga, …] ms.
 * Formato de linhas, versão na primeira: `1`, id, tipo, título, texto, trechos separados por vírgula.
 * Versão desconhecida ou formato ruim = null (o relógio ignora em vez de vibrar errado).
 */
data class WearAlert(
    val id: Long,
    val kind: String,
    val title: String,
    val body: String,
    val segmentsMs: List<Long>
) {
    /** Forma da API de vibração do Android: começa com uma pausa (0) antes do 1º "liga". */
    fun waveform(): LongArray = (listOf(0L) + segmentsMs.map { it.coerceIn(0L, 10_000L) }).toLongArray()

    companion object {
        const val VERSION = "1"
        private const val MAX_SEGMENTS = 24

        fun encode(a: WearAlert): ByteArray = listOf(
            VERSION, a.id.toString(), clean(a.kind), clean(a.title), clean(a.body),
            a.segmentsMs.take(MAX_SEGMENTS).joinToString(",")
        ).joinToString("\n").toByteArray(Charsets.UTF_8)

        fun decode(bytes: ByteArray): WearAlert? = runCatching {
            val l = bytes.toString(Charsets.UTF_8).split('\n')
            if (l.size < 6 || l[0] != VERSION) return null
            val segs = if (l[5].isBlank()) emptyList() else l[5].split(',').map { it.trim().toLong() }
            if (segs.size > MAX_SEGMENTS || segs.any { it < 0 }) return null
            WearAlert(l[1].toLong(), l[2], l[3], l[4], segs)
        }.getOrNull()

        private fun clean(s: String) = s.replace('\n', ' ').replace('\r', ' ').take(200)
    }
}
