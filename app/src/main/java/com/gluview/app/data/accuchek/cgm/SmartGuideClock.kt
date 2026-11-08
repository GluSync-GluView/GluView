package com.gluview.app.data.accuchek.cgm

import kotlin.math.abs

/**
 * Regras de horário do SmartGuide (puras, testáveis).
 *
 * O sensor só informa "minutos desde o início". O início sai do CGM Status: agora − minutos
 * (Juggluco `accuSetStartTime`, xDrip `readStaticParameters`). O minuto do status é inteiro, então
 * cada leitura do status dá um início com até 1 min de variação; para a hora das leituras não
 * pular, o início salvo só é trocado quando a diferença passa de [START_TOLERANCE_MS] (Juggluco
 * usa 20 s; o xDrip só reescreve o Start Time acima de 2 min).
 */
object SmartGuideClock {
    const val START_TOLERANCE_MS = 90_000L

    fun startFromStatus(receivedAtMs: Long, status: CgmStatus): Long =
        receivedAtMs - status.timeOffsetMinutes * 60_000L

    /** Início a usar: mantém o [savedStartMs] se o novo cálculo estiver dentro da tolerância. */
    fun reconcileStart(savedStartMs: Long, computedStartMs: Long): Long =
        if (savedStartMs > 0 && abs(savedStartMs - computedStartMs) <= START_TOLERANCE_MS) savedStartMs
        else computedStartMs

    /** Hora da leitura = início + minutos, nunca no futuro (igual ao xDrip e ao Juggluco). */
    fun readingTimeMs(startMs: Long, timeOffsetMinutes: Int, nowMs: Long): Long =
        minOf(startMs + timeOffsetMinutes * 60_000L, nowMs)

    /** Folga para atraso entre status e leituras antes de concluir que a contagem recomeçou. */
    const val NEW_SESSION_SLACK_MINUTES = 15

    /**
     * O sensor recomeçou a contar (sensor novo sem passar pela configuração, ou sessão reiniciada):
     * o minuto atual do status ficou bem abaixo do último minuto salvo. Sem isso, o cursor antigo
     * faria todas as leituras novas parecerem repetidas.
     */
    fun isNewSession(lastSavedMinute: Int, statusMinute: Int): Boolean =
        lastSavedMinute >= 0 && statusMinute + NEW_SESSION_SLACK_MINUTES < lastSavedMinute

    /**
     * Minuto a partir do qual pedir o histórico: o seguinte ao último salvo; sem nada salvo, null
     * (pede tudo o que o sensor guarda — as últimas 8 h).
     */
    fun historyFromMinute(lastSavedMinute: Int): Int? =
        if (lastSavedMinute < 0) null else lastSavedMinute + 1

    /** Pulo menor que isto é acerto fino do relógio (rede/NTP): não mexe no início. */
    const val CLOCK_JUMP_MIN_MS = 60_000L

    /**
     * Quanto a hora do celular pulou desde a âncora: tempo de parede menos tempo monotônico
     * (`elapsedRealtime`, que não muda quando o usuário ou a rede acertam o relógio). Com a conexão
     * aberta o início não é recalculado; sem isso, depois de um pulo as leituras ficariam fora da
     * janela de "ao vivo" (Home parada, sem alarme) até a próxima reconexão — achado no sensor
     * virtual, plano 14_, cenário 16.
     */
    fun wallClockJumpMs(anchorWallMs: Long, anchorElapsedMs: Long, nowWallMs: Long, nowElapsedMs: Long): Long =
        (nowWallMs - anchorWallMs) - (nowElapsedMs - anchorElapsedMs)
}
