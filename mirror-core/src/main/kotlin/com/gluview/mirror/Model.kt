package com.gluview.mirror

/**
 * Uma leitura no espelho. Sempre datada pela MEDIÇÃO ([epochMs]), nunca pela chegada: é isso que
 * permite ao seguidor saber a idade real e nunca mostrar leitura velha como atual.
 */
data class MirrorReading(
    val epochMs: Long,
    val sgvMgDl: Double,
    /** Direção no padrão Nightscout (Flat, FortyFiveUp, SingleUp, DoubleUp, ...). */
    val direction: String,
    val deltaMgDl: Int? = null
) {
    fun toMap(): Map<String, Any?> = mapOf("t" to epochMs, "v" to sgvMgDl, "d" to direction, "dl" to deltaMgDl)

    companion object {
        /** Lê; devolve null para item inválido (sem hora, valor fora de 20–600 mg/dL). */
        fun fromMap(m: Map<*, *>): MirrorReading? {
            val t = m.long("t") ?: return null
            val v = m.dbl("v") ?: return null
            if (t <= 0L || v < 20.0 || v > 600.0) return null
            return MirrorReading(t, v, m.str("d") ?: "NONE", m.long("dl")?.toInt())
        }
    }
}

/** Limites do paciente, para o seguidor classificar e alertar igual ao app do paciente. */
data class MirrorThresholds(
    val urgentLow: Double = 55.0,
    val low: Double = 70.0,
    val high: Double = 180.0,
    val urgentHigh: Double = 250.0
) {
    fun toMap(): Map<String, Any?> = mapOf("ul" to urgentLow, "l" to low, "h" to high, "uh" to urgentHigh)

    companion object {
        fun fromMap(m: Map<*, *>?): MirrorThresholds {
            val d = MirrorThresholds()
            if (m == null) return d
            val t = MirrorThresholds(m.dbl("ul") ?: d.urgentLow, m.dbl("l") ?: d.low,
                m.dbl("h") ?: d.high, m.dbl("uh") ?: d.urgentHigh)
            return if (t.urgentLow < t.low && t.low < t.high && t.high < t.urgentHigh) t else d
        }
    }
}

/** Seta (como o app mostra) a partir da direção Nightscout. */
fun directionToArrow(direction: String): String = when (direction) {
    "DoubleUp" -> "↑↑"
    "SingleUp" -> "↑"
    "FortyFiveUp" -> "↗"
    "Flat" -> "→"
    "FortyFiveDown" -> "↘"
    "SingleDown" -> "↓"
    "DoubleDown" -> "↓↓"
    else -> ""
}
