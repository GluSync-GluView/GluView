package com.gluview.wear.contract

/**
 * Registro do diário feito no relógio (plano 17, F4.1/F4.2). Kotlin puro: os dois lados convertem
 * de/para DataMap com [toMap]/[fromMap]. Só os tipos que fazem sentido registrar no pulso.
 */
data class WearDiaryEntry(
    val uuid: String,
    /** meal | insulin | fingerstick | ketone | exercise (mesmas chaves do diário do celular). */
    val type: String,
    val value: Double,
    val occurredAtMs: Long,
    /** insulina: rapid/basal/mixed · cetona: blood. */
    val subtype: String = "",
    /** Nome da insulina ou da favorita. */
    val itemName: String = "",
    /** Favorita escolhida (uuid), quando o registro veio de uma favorita. */
    val favoriteUuid: String = "",
) {
    fun toMap(): Map<String, Any> = mapOf(
        K_VERSION to VERSION, K_UUID to uuid, K_TYPE to type, K_VALUE to value.toString(),
        K_AT to occurredAtMs, K_SUBTYPE to subtype, K_ITEM to itemName, K_FAVORITE to favoriteUuid,
    )

    companion object {
        const val VERSION = 1
        val TYPES = setOf("meal", "insulin", "fingerstick", "ketone", "exercise")

        const val K_VERSION = "v"
        const val K_UUID = "uuid"
        const val K_TYPE = "type"
        const val K_VALUE = "value"
        const val K_AT = "at"
        const val K_SUBTYPE = "subtype"
        const val K_ITEM = "item"
        const val K_FAVORITE = "favorite"

        /** null = formato inválido, de versão futura ou valor fora do que cada tipo aceita. */
        fun fromMap(map: Map<String, Any?>): WearDiaryEntry? {
            val v = map[K_VERSION] as? Int ?: return null
            if (v > VERSION) return null
            val uuid = (map[K_UUID] as? String)?.takeIf { it.isNotBlank() } ?: return null
            val type = (map[K_TYPE] as? String)?.takeIf { it in TYPES } ?: return null
            val value = (map[K_VALUE] as? String)?.toDoubleOrNull() ?: return null
            val at = (map[K_AT] as? Number)?.toLong() ?: return null
            if (!WearDiaryLimits.valid(type, value) || at <= 0) return null
            return WearDiaryEntry(
                uuid, type, value, at, map[K_SUBTYPE] as? String ?: "",
                map[K_ITEM] as? String ?: "", map[K_FAVORITE] as? String ?: ""
            )
        }
    }
}

/** Faixas e passos do seletor girando a coroa (F4.2) — os mesmos nos dois lados. */
object WearDiaryLimits {
    data class Range(val min: Double, val max: Double, val step: Double, val start: Double)

    fun range(type: String): Range = when (type) {
        "meal" -> Range(1.0, 300.0, 1.0, 30.0)
        "insulin" -> Range(0.5, 100.0, 0.5, 4.0)
        "fingerstick" -> Range(20.0, 600.0, 1.0, 120.0)
        "ketone" -> Range(0.0, 8.0, 0.1, 0.5)
        "exercise" -> Range(5.0, 300.0, 5.0, 30.0)
        else -> Range(0.0, 0.0, 1.0, 0.0)
    }

    fun valid(type: String, value: Double): Boolean = range(type).let { value >= it.min && value <= it.max }

    /** [steps] cliques da coroa a partir de [value], presos à faixa e arredondados ao passo. */
    fun turn(type: String, value: Double, steps: Int): Double {
        val r = range(type)
        val next = value + steps * r.step
        val rounded = Math.round(next / r.step) * r.step
        return (Math.round(rounded * 10.0) / 10.0).coerceIn(r.min, r.max)
    }
}

/**
 * O que o celular manda para o relógio sobre o dia (F4.3): totais do diário, próximo lembrete,
 * avisos de hoje e os cadastros usados nos seletores (insulinas e favoritas).
 */
data class WearDayInfo(
    val carbsG: Double,
    val rapidU: Double,
    val basalU: Double,
    val nextReminderTitle: String,
    /** 0 = nenhum lembrete agendado. */
    val nextReminderAtMs: Long,
    /** Avisos de hoje, do mais novo para o mais antigo (texto pronto no idioma do celular). */
    val notices: List<String>,
    /** Insulinas cadastradas: [WearItem.extra] = rapid/basal/mixed. */
    val insulins: List<WearItem>,
    /** Favoritas de refeição: [WearItem.extra] = carboidrato (g). */
    val favorites: List<WearItem>,
    val updatedAtMs: Long,
) {
    data class WearItem(val uuid: String, val name: String, val extra: String)

    fun toMap(): Map<String, Any> = mapOf(
        K_VERSION to VERSION, K_CARBS to carbsG.toString(), K_RAPID to rapidU.toString(), K_BASAL to basalU.toString(),
        K_NEXT_TITLE to nextReminderTitle, K_NEXT_AT to nextReminderAtMs,
        K_NOTICES to notices.joinToString(SEP_LIST) { it.replace(SEP_LIST, " ") },
        K_INSULINS to encodeItems(insulins), K_FAVORITES to encodeItems(favorites), K_UPDATED to updatedAtMs,
    )

    companion object {
        const val VERSION = 1
        private const val SEP_LIST = "\u001E"
        private const val SEP_FIELD = "\u001F"
        const val K_VERSION = "v"
        const val K_CARBS = "carbs"
        const val K_RAPID = "rapid"
        const val K_BASAL = "basal"
        const val K_NEXT_TITLE = "nextTitle"
        const val K_NEXT_AT = "nextAt"
        const val K_NOTICES = "notices"
        const val K_INSULINS = "insulins"
        const val K_FAVORITES = "favorites"
        const val K_UPDATED = "updated"

        private fun clean(s: String) = s.replace(SEP_FIELD, " ").replace(SEP_LIST, " ")

        private fun encodeItems(items: List<WearItem>) =
            items.joinToString(SEP_LIST) { listOf(clean(it.uuid), clean(it.name), clean(it.extra)).joinToString(SEP_FIELD) }

        private fun decodeItems(s: String?): List<WearItem> =
            s.orEmpty().split(SEP_LIST).filter { it.isNotBlank() }.mapNotNull {
                val f = it.split(SEP_FIELD)
                if (f.size == 3) WearItem(f[0], f[1], f[2]) else null
            }

        fun fromMap(map: Map<String, Any?>): WearDayInfo? {
            val v = map[K_VERSION] as? Int ?: return null
            if (v > VERSION) return null
            return WearDayInfo(
                carbsG = (map[K_CARBS] as? String)?.toDoubleOrNull() ?: 0.0,
                rapidU = (map[K_RAPID] as? String)?.toDoubleOrNull() ?: 0.0,
                basalU = (map[K_BASAL] as? String)?.toDoubleOrNull() ?: 0.0,
                nextReminderTitle = map[K_NEXT_TITLE] as? String ?: "",
                nextReminderAtMs = (map[K_NEXT_AT] as? Number)?.toLong() ?: 0L,
                notices = (map[K_NOTICES] as? String).orEmpty().split(SEP_LIST).filter { it.isNotBlank() },
                insulins = decodeItems(map[K_INSULINS] as? String),
                favorites = decodeItems(map[K_FAVORITES] as? String),
                updatedAtMs = (map[K_UPDATED] as? Number)?.toLong() ?: 0L,
            )
        }

        /** Resumo de outro dia não vale para hoje (o relógio zera os totais na virada). */
        fun isFromToday(info: WearDayInfo, nowMs: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): Boolean =
            java.time.Instant.ofEpochMilli(info.updatedAtMs).atZone(zone).toLocalDate() ==
                java.time.Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    }
}
