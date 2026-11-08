package com.gluview.wear.contract

/** Resposta do relógio ao receber um visor em [WearPaths.WATCHFACE] (plano 16, W3). */
sealed interface FaceAck {
    /** Instalado em slot novo ([updated]=false) ou atualizado no slot que já era do GluView. */
    data class Installed(val updated: Boolean, val active: Boolean) : FaceAck
    /** Relógio sem Watch Face Push (Wear OS 5 ou anterior). */
    data object Unsupported : FaceAck
    /** Slot ocupado por outro app e nenhum do GluView para atualizar. */
    data object NoSlot : FaceAck
    data class Failed(val reason: String) : FaceAck

    companion object {
        fun encode(a: FaceAck): ByteArray = when (a) {
            is Installed -> "INSTALLED|${a.updated}|${a.active}"
            Unsupported -> "UNSUPPORTED"
            NoSlot -> "NO_SLOT"
            is Failed -> "FAILED|" + a.reason.take(300).replace('\n', ' ')
        }.toByteArray(Charsets.UTF_8)

        fun decode(bytes: ByteArray): FaceAck? {
            val text = bytes.toString(Charsets.UTF_8)
            val kind = text.substringBefore('|')
            val rest = if ('|' in text) text.substringAfter('|') else ""
            return when (kind) {
                "INSTALLED" -> rest.split('|').let { Installed(it.getOrNull(0) == "true", it.getOrNull(1) == "true") }
                "UNSUPPORTED" -> Unsupported
                "NO_SLOT" -> NoSlot
                // O motivo é tudo depois do primeiro "|" (pode conter "|").
                "FAILED" -> Failed(rest)
                else -> null
            }
        }
    }
}
