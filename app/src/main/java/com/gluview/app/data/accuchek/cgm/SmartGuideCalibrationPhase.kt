package com.gluview.app.data.accuchek.cgm

/**
 * Em que ponto da calibração o SmartGuide está, para a Home explicar o que fazer.
 *
 * Regras da Roche (manual/FAQ Accu-Chek, conferido em 20/11/2026): depois do aquecimento o sensor
 * fica em Modo Tendência (valores que não servem para tratamento); a 1ª calibração só é liberada
 * 12 h após a aplicação; a rotina inicial tem 2 passos e o 2º pedido vem de 30 min a 3 h depois do
 * 1º; com os dois, Modo Terapia. O sensor só diz "pede" e "não permite" (bits do Status): o
 * horário de liberação vem do início da sessão + 12 h, e o 2º passo da hora da última aceita.
 */
object SmartGuideCalibrationPhase {
    const val FIRST_CALIBRATION_MINUTES = 12 * 60
    const val SECOND_MIN_MS = 30 * 60_000L
    const val SECOND_MAX_MS = 3 * 60 * 60_000L

    sealed interface Phase {
        data object None : Phase
        /** Modo Tendência; [unlockAtMs] = início + 12 h, ou null se já passou/desconhecido ("em breve"). */
        data class WaitingFirst(val unlockAtMs: Long?) : Phase
        /** 1ª aceita; o 2º pedido vem a partir de [fromMs]. */
        data class WaitingSecond(val fromMs: Long) : Phase
        /** Pode calibrar (1ª ou recalibração pedida pelo sensor). */
        data object Ready : Phase
        /** 2º passo da rotina inicial liberado; fazer até [deadlineMs]. */
        data class ReadySecond(val deadlineMs: Long) : Phase
    }

    fun of(required: Boolean, allowed: Boolean, sensorStartMs: Long, lastAcceptedMs: Long, nowMs: Long): Phase {
        if (!required) return Phase.None
        val afterFirst = lastAcceptedMs > 0L && nowMs - lastAcceptedMs in 0..SECOND_MAX_MS
        if (!allowed) {
            if (afterFirst) return Phase.WaitingSecond(lastAcceptedMs + SECOND_MIN_MS)
            val unlock = if (sensorStartMs > 0L) sensorStartMs + FIRST_CALIBRATION_MINUTES * 60_000L else 0L
            return Phase.WaitingFirst(unlock.takeIf { it > nowMs })
        }
        return if (afterFirst) Phase.ReadySecond(lastAcceptedMs + SECOND_MAX_MS) else Phase.Ready
    }
}
