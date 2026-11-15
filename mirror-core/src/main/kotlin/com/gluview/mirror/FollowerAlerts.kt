package com.gluview.mirror

/**
 * Alertas no celular do SEGUIDOR para uma pessoa seguida pelo espelho. Uma condição por vez, na
 * ordem de gravidade da informação que falta:
 *  1. link caído (não chega nada do celular do paciente) — não dá para saber a glicemia;
 *  2. link ok mas sem leitura nova do sensor;
 *  3. faixa da glicemia, com a MESMA regra do app do paciente (o valor do limite conta como fora).
 * Avisa ao entrar na condição e repete a cada [FollowerAlertPrefs.repeatMin] enquanto durar.
 */
enum class FollowerAlertKind { LINK_DOWN, NO_SENSOR_DATA, URGENT_LOW, LOW, HIGH, URGENT_HIGH }

data class FollowerAlertPrefs(
    val enabled: Set<FollowerAlertKind> = FollowerAlertKind.entries.toSet(),
    val repeatMin: Int = 30,
    val linkTimeoutMin: Int = 10,
    val noSignalMin: Int = 20
)

class FollowerAlerts {
    private var activeKind: FollowerAlertKind? = null
    private var lastFiredMs = 0L

    /** Condição atual (ou null se tudo bem). */
    fun condition(nowMs: Long, latest: MirrorReading?, lastAliveMs: Long, thresholds: MirrorThresholds,
                  prefs: FollowerAlertPrefs): FollowerAlertKind? {
        when (FollowerState.linkStatus(nowMs, lastAliveMs, prefs.linkTimeoutMin)) {
            LinkStatus.DOWN -> return FollowerAlertKind.LINK_DOWN
            LinkStatus.NEVER -> return null // ainda não conectou nenhuma vez: a tela mostra, sem alarme
            LinkStatus.OK -> Unit
        }
        if (latest == null || nowMs - latest.epochMs >= prefs.noSignalMin * 60_000L) return FollowerAlertKind.NO_SENSOR_DATA
        val v = latest.sgvMgDl
        return when {
            v <= thresholds.urgentLow -> FollowerAlertKind.URGENT_LOW
            v <= thresholds.low -> FollowerAlertKind.LOW
            v >= thresholds.urgentHigh -> FollowerAlertKind.URGENT_HIGH
            v >= thresholds.high -> FollowerAlertKind.HIGH
            else -> null
        }
    }

    /** O alerta a disparar agora, ou null. */
    @Synchronized
    fun evaluate(nowMs: Long, latest: MirrorReading?, lastAliveMs: Long, thresholds: MirrorThresholds,
                 prefs: FollowerAlertPrefs): FollowerAlertKind? {
        val kind = condition(nowMs, latest, lastAliveMs, thresholds, prefs)
        if (kind == null) { activeKind = null; return null }
        val fire = kind != activeKind || nowMs - lastFiredMs >= prefs.repeatMin * 60_000L
        activeKind = kind
        if (!fire || kind !in prefs.enabled) return null
        lastFiredMs = nowMs
        return kind
    }

    companion object {
        fun isUrgent(k: FollowerAlertKind) = k == FollowerAlertKind.URGENT_LOW || k == FollowerAlertKind.URGENT_HIGH ||
            k == FollowerAlertKind.LINK_DOWN
    }
}
