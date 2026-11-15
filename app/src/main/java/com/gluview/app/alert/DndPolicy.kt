package com.gluview.app.alert

import android.app.NotificationManager

/**
 * PT-5 do plano de sensores e recursos — alertas e Não Perturbe (DND), regra pura e testável.
 *
 * Cada faixa de alerta tem duas escolhas da pessoa, e o app respeita as duas:
 * - **Furar o Não Perturbe** ([AlertType.DND]): o alerta toca mesmo com o DND ligado, pelo canal
 *   de alerta crítico (categoria e som de ALARME). Ele passa quando o canal pode ignorar o DND
 *   (acesso ao Não Perturbe concedido) e também, sem acesso, quando o DND deixa alarmes passarem:
 *   modo "só alarmes", ou "prioridade" com alarmes liberados (o padrão do Android). Visto no
 *   emulador em 15/11/2026: mIntercept=false sem acesso. Fora isso o app **não finge**: avisa e
 *   registra no histórico que não conseguiu furar.
 * - **Vibrar no Não Perturbe** ([AlertType.DND_VIBRATE]): quando o alerta respeita o DND, em vez de
 *   ficar mudo ele só vibra (como o AndroidAPS 4.0 faz).
 *
 * O que pode fazer um alarme não tocar, e como fica registrado: DND ligado com a faixa sem "furar"
 * ([DndOutcome.SUPPRESSED] / [DndOutcome.VIBRATE_ONLY]); "furar" escolhido sem o canal poder ignorar
 * o DND ([DndOutcome.BYPASS_UNAVAILABLE]); "Silêncio total" ([DndState.TOTAL_SILENCE]), que o
 * Android não deixa nenhum app furar. Em todos esses casos a entrada do histórico diz o porquê.
 */
enum class DndState {
    /** Sem Não Perturbe (ou estado desconhecido — o app age como se não houvesse). */
    OFF,
    /** Só prioridade. */
    PRIORITY,
    /** Só alarmes. */
    ALARMS_ONLY,
    /** Silêncio total: nenhum som nem vibração passa, nem de canal que ignora o DND. */
    TOTAL_SILENCE;

    val active: Boolean get() = this != OFF

    companion object {
        /** De [NotificationManager.getCurrentInterruptionFilter]. */
        fun fromInterruptionFilter(filter: Int): DndState = when (filter) {
            NotificationManager.INTERRUPTION_FILTER_PRIORITY -> PRIORITY
            NotificationManager.INTERRUPTION_FILTER_ALARMS   -> ALARMS_ONLY
            NotificationManager.INTERRUPTION_FILTER_NONE     -> TOTAL_SILENCE
            else                                             -> OFF // ALL e UNKNOWN
        }
    }
}

enum class DndOutcome(val logType: String?) {
    /** DND desligado: dispara como sempre. */
    NORMAL(null),
    /** DND ligado, faixa configurada para furar e o canal pode ignorar o DND: toca. */
    BYPASS("dnd"),
    /** DND ligado, faixa respeita o DND com "vibrar": só vibra. */
    VIBRATE_ONLY("dnd_vibrou"),
    /** DND ligado, faixa respeita o DND sem vibrar: fica em silêncio (escolha da pessoa). */
    SUPPRESSED("dnd_suprimido"),
    /** Pediu para furar, mas o canal não pode ignorar o DND (sem acesso, e o DND barra alarmes). */
    BYPASS_UNAVAILABLE("dnd_sem_permissao"),
    /** Pediu para furar, mas é "Silêncio total": o Android barra até canal com acesso. */
    TOTAL_SILENCE_BLOCKED("dnd_silencio_total")
}

/**
 * @param postBypassChannel publicar no canal que ignora o DND.
 * @param vibrate vibrar direto pelo vibrador (tentativa; em silêncio total o Android pode barrar).
 */
data class DndDecision(
    val outcome: DndOutcome,
    val postBypassChannel: Boolean,
    val vibrate: Boolean
)

object DndPolicy {

    /**
     * @param wantsBypass a faixa está com "Furar o Não Perturbe" ligado.
     * @param vibrateWhenQuiet a faixa está com "Vibrar no Não Perturbe" ligado.
     * @param bypassReady o canal de furar o DND pode, de fato, ignorar o DND agora.
     * @param alarmsAllowed a política do Não Perturbe deixa alarmes passarem no modo prioridade.
     */
    fun decide(
        dnd: DndState,
        wantsBypass: Boolean,
        vibrateWhenQuiet: Boolean,
        bypassReady: Boolean,
        alarmsAllowed: Boolean = false
    ): DndDecision {
        if (!dnd.active) return DndDecision(DndOutcome.NORMAL, postBypassChannel = wantsBypass, vibrate = false)
        if (wantsBypass) {
            // Silêncio total barra até canal que ignora o DND: dizer isso, não fingir que tocou.
            val passes = when (dnd) {
                DndState.TOTAL_SILENCE -> false
                DndState.ALARMS_ONLY   -> true   // o alerta crítico tem categoria de alarme
                DndState.PRIORITY      -> bypassReady || alarmsAllowed
                DndState.OFF           -> true
            }
            if (passes) {
                return DndDecision(DndOutcome.BYPASS, postBypassChannel = true, vibrate = false)
            }
            // Quis furar e não dá: o mais perto da intenção é vibrar (tentativa) e registrar o porquê.
            val why = if (dnd == DndState.TOTAL_SILENCE) DndOutcome.TOTAL_SILENCE_BLOCKED else DndOutcome.BYPASS_UNAVAILABLE
            return DndDecision(why, postBypassChannel = true, vibrate = true)
        }
        return if (vibrateWhenQuiet) DndDecision(DndOutcome.VIBRATE_ONLY, postBypassChannel = false, vibrate = true)
        else DndDecision(DndOutcome.SUPPRESSED, postBypassChannel = false, vibrate = false)
    }
}
