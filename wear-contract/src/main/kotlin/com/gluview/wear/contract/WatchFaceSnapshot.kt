package com.gluview.wear.contract

import java.util.Locale
import kotlin.math.abs

/** Faixa da glicemia, calculada no celular com os limites do usuário (espelha o GlucoseZone do :app). */
enum class SnapshotZone { URGENT_LOW, LOW, NORMAL, HIGH, URGENT_HIGH, QUICK_DROP }

enum class SnapshotUnit { MG_DL, MMOL_L }

/** De onde o relógio recebeu a leitura — mostrado no tile ("via celular" / "via nuvem"). */
enum class SnapshotSource { PHONE, CLOUD }

/**
 * Leitura enviada do celular ao relógio em [WearPaths.READING] — o contrato de rede entre os dois.
 *
 * Valores sempre em mg/dL (inteiros); a conversão para mmol/L acontece só na hora de mostrar,
 * com o mesmo fator 18 do :app (util/GlucoseUnit). [measuredAtMs] é a hora da MEDIÇÃO, não a do
 * envio: é dela que sai a regra de dado velho, avaliada no relógio pelo relógio do próprio relógio
 * — o celular pode simplesmente parar de mandar, e aí ninguém avisaria que o valor envelheceu.
 */
data class WatchFaceSnapshot(
    val sgvMgdl: Int,
    val deltaMgdl: Int?,
    val direction: String,
    val measuredAtMs: Long,
    val zone: SnapshotZone,
    val urgentLowMgdl: Int,
    val lowMgdl: Int,
    val highMgdl: Int,
    val urgentHighMgdl: Int,
    val unit: SnapshotUnit,
    val use24h: Boolean,
    val source: SnapshotSource,
    val staleAfterMin: Int = DEFAULT_STALE_AFTER_MIN,
) {
    /** Velho = medição há mais de [staleAfterMin] min, ou no futuro além de 5 min (relógio errado). */
    fun isStale(nowMs: Long): Boolean {
        val age = nowMs - measuredAtMs
        return age > staleAfterMin * 60_000L || age < -FUTURE_TOLERANCE_MS
    }

    /** Valor para tela; [PLACEHOLDER] quando velho — dado velho nunca aparece como valor atual. */
    fun displayValue(nowMs: Long, locale: Locale = Locale.getDefault()): String =
        if (isStale(nowMs)) PLACEHOLDER else formatGlucose(sgvMgdl, unit, locale)

    /** Seta de tendência; vazia quando velho (uma seta sobre "--" daria a falsa ideia de dado vivo). */
    fun arrow(nowMs: Long): String = if (isStale(nowMs)) "" else arrowFor(direction)

    /** Delta com sinal ("+3", "-0,4", "0"); vazio quando velho ou sem leitura anterior. */
    fun deltaText(nowMs: Long, locale: Locale = Locale.getDefault()): String {
        val d = deltaMgdl ?: return ""
        if (isStale(nowMs)) return ""
        return when (unit) {
            SnapshotUnit.MG_DL -> if (d > 0) "+$d" else d.toString()
            SnapshotUnit.MMOL_L -> {
                if (d == 0) return "0"
                val text = String.format(locale, "%.1f", abs(d) / MMOL_FACTOR)
                (if (d > 0) "+" else "-") + text
            }
        }
    }

    /** Minutos desde a medição (para "há X min" ou para depuração); nunca negativo. */
    fun ageMinutes(nowMs: Long): Int = ((nowMs - measuredAtMs).coerceAtLeast(0) / 60_000L).toInt()

    /** Serializa para o DataMap (o :app copia cada par chave/valor para o DataMap do Data Layer). */
    fun toMap(): Map<String, Any> = buildMap {
        put(K_VERSION, VERSION)
        put(K_SGV, sgvMgdl)
        deltaMgdl?.let { put(K_DELTA, it) }
        put(K_DIRECTION, direction)
        put(K_MEASURED_AT, measuredAtMs)
        put(K_ZONE, zone.name)
        put(K_URGENT_LOW, urgentLowMgdl)
        put(K_LOW, lowMgdl)
        put(K_HIGH, highMgdl)
        put(K_URGENT_HIGH, urgentHighMgdl)
        put(K_UNIT, unit.name)
        put(K_USE_24H, use24h)
        put(K_SOURCE, source.name)
        put(K_STALE_AFTER, staleAfterMin)
    }

    companion object {
        const val VERSION = 1
        const val DEFAULT_STALE_AFTER_MIN = 15
        const val PLACEHOLDER = "--"
        const val MMOL_FACTOR = 18.0
        private const val FUTURE_TOLERANCE_MS = 5 * 60_000L

        // Faixa fisicamente plausível de um sensor; fora disso o dado é tratado como corrompido.
        private const val SGV_MIN = 20
        private const val SGV_MAX = 600

        const val K_VERSION = "v"
        const val K_SGV = "sgv"
        const val K_DELTA = "delta"
        const val K_DIRECTION = "direction"
        const val K_MEASURED_AT = "measuredAt"
        const val K_ZONE = "zone"
        const val K_URGENT_LOW = "urgentLow"
        const val K_LOW = "low"
        const val K_HIGH = "high"
        const val K_URGENT_HIGH = "urgentHigh"
        const val K_UNIT = "unit"
        const val K_USE_24H = "use24h"
        const val K_SOURCE = "source"
        const val K_STALE_AFTER = "staleAfterMin"

        /**
         * Lê o que veio do Data Layer. Qualquer campo faltando, de tipo errado ou fora de faixa →
         * null, e o relógio mostra [PLACEHOLDER]: na dúvida, nenhum número em vez de um número errado.
         * Versão maior que a conhecida também é recusada (formato que este relógio não entende).
         */
        fun fromMap(map: Map<String, Any?>): WatchFaceSnapshot? {
            val version = map[K_VERSION] as? Int ?: return null
            if (version > VERSION) return null
            val sgv = map[K_SGV] as? Int ?: return null
            if (sgv !in SGV_MIN..SGV_MAX) return null
            val measuredAt = (map[K_MEASURED_AT] as? Number)?.toLong() ?: return null
            if (measuredAt <= 0) return null
            val urgentLow = map[K_URGENT_LOW] as? Int ?: return null
            val low = map[K_LOW] as? Int ?: return null
            val high = map[K_HIGH] as? Int ?: return null
            val urgentHigh = map[K_URGENT_HIGH] as? Int ?: return null
            if (!(urgentLow < low && low < high && high < urgentHigh)) return null
            return WatchFaceSnapshot(
                sgvMgdl = sgv,
                deltaMgdl = map[K_DELTA] as? Int,
                direction = map[K_DIRECTION] as? String ?: "NONE",
                measuredAtMs = measuredAt,
                zone = enumOrNull<SnapshotZone>(map[K_ZONE]) ?: return null,
                urgentLowMgdl = urgentLow,
                lowMgdl = low,
                highMgdl = high,
                urgentHighMgdl = urgentHigh,
                unit = enumOrNull<SnapshotUnit>(map[K_UNIT]) ?: SnapshotUnit.MG_DL,
                use24h = map[K_USE_24H] as? Boolean ?: true,
                source = enumOrNull<SnapshotSource>(map[K_SOURCE]) ?: SnapshotSource.PHONE,
                staleAfterMin = (map[K_STALE_AFTER] as? Int)?.takeIf { it in 1..120 } ?: DEFAULT_STALE_AFTER_MIN,
            )
        }

        fun formatGlucose(mgdl: Int, unit: SnapshotUnit, locale: Locale = Locale.getDefault()): String =
            when (unit) {
                SnapshotUnit.MG_DL -> mgdl.toString()
                SnapshotUnit.MMOL_L -> String.format(locale, "%.1f", mgdl / MMOL_FACTOR)
            }

        /** Mesmas setas do Nightscout (direction.js), para o relógio bater com os outros apps. */
        fun arrowFor(direction: String): String = when (direction) {
            "DoubleUp" -> "⇈"
            "SingleUp" -> "↑"
            "FortyFiveUp" -> "↗"
            "Flat" -> "→"
            "FortyFiveDown" -> "↘"
            "SingleDown" -> "↓"
            "DoubleDown" -> "⇊"
            else -> ""
        }

        private inline fun <reified E : Enum<E>> enumOrNull(value: Any?): E? =
            (value as? String)?.let { name -> enumValues<E>().firstOrNull { it.name == name } }
    }
}
