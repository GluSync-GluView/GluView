package com.gluview.mirror

/**
 * O que o SEGUIDOR sabe de uma pessoa seguida: histórico (sem repetir, em ordem de medição),
 * leitura atual e saúde do link. Regras de saúde (testadas em FollowerStateTest):
 *  - leitura repetida (mesma hora de medição) não entra duas vezes;
 *  - leitura do futuro (relógio do paciente adiantado mais de 5 min) é recusada;
 *  - "atual" é a medição mais nova; passou do limiar, deixa de ser atual (sem sinal);
 *  - link caído é avisado à parte de "sem sinal do sensor": o paciente pode estar conectado e o
 *    sensor parado, ou o sensor ok e o link caído.
 */
class FollowerState(private val keepMs: Long = 24 * 3_600_000L) {
    private val readings = java.util.TreeMap<Long, MirrorReading>()

    @Synchronized
    fun merge(items: List<MirrorReading>, nowMs: Long): List<MirrorReading> {
        val added = ArrayList<MirrorReading>()
        for (r in items) {
            if (r.epochMs > nowMs + FUTURE_TOLERANCE_MS) continue
            if (r.epochMs < nowMs - keepMs) continue
            if (readings.putIfAbsent(r.epochMs, r) == null) added.add(r)
        }
        while (readings.isNotEmpty() && readings.firstKey() < nowMs - keepMs) readings.pollFirstEntry()
        return added.sortedBy { it.epochMs }
    }

    @Synchronized fun latest(): MirrorReading? = readings.lastEntry()?.value
    @Synchronized fun all(): List<MirrorReading> = readings.values.toList()
    @Synchronized fun lastEpoch(): Long = if (readings.isEmpty()) 0L else readings.lastKey()
    @Synchronized fun clear() = readings.clear()

    /** Leitura atual só se ainda não passou do limiar de sem sinal. */
    fun current(nowMs: Long, noSignalMin: Int): MirrorReading? =
        latest()?.takeIf { nowMs - it.epochMs < noSignalMin * 60_000L }

    companion object {
        const val FUTURE_TOLERANCE_MS = 5 * 60_000L

        fun linkStatus(nowMs: Long, lastAliveMs: Long, linkTimeoutMin: Int): LinkStatus = when {
            lastAliveMs <= 0L -> LinkStatus.NEVER
            nowMs - lastAliveMs < linkTimeoutMin * 60_000L -> LinkStatus.OK
            else -> LinkStatus.DOWN
        }
    }
}

enum class LinkStatus { NEVER, OK, DOWN }
