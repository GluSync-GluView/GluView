package com.gluview.app.data.diary

import com.gluview.app.data.local.entity.EventLogEntity
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/** Tipos do diário (plano 17, F1.1). [key] é o que vai para o banco (`event_log.type`). */
enum class DiaryType(val key: String) {
    MEAL("meal"),
    INSULIN("insulin"),
    FINGERSTICK("fingerstick"),
    KETONE("ketone"),
    MEDICATION("medication"),
    EXERCISE("exercise"),
    NOTE("note");

    companion object {
        fun of(key: String): DiaryType = entries.firstOrNull { it.key == key } ?: NOTE
    }
}

/** Tipo de insulina (F1.2). */
enum class InsulinKind(val key: String) {
    RAPID("rapid"), BASAL("basal"), MIXED("mixed");

    companion object {
        fun of(key: String): InsulinKind = entries.firstOrNull { it.key == key } ?: RAPID
    }
}

/** Cetona medida no sangue (mmol/L) ou na fita de urina (níveis) — F1.3. */
enum class KetoneMethod(val key: String) {
    BLOOD("blood"), URINE("urine");

    companion object {
        fun of(key: String): KetoneMethod = entries.firstOrNull { it.key == key } ?: BLOOD
    }
}

/** Intensidade do exercício. */
enum class ExerciseIntensity(val key: String) {
    LIGHT("light"), MODERATE("moderate"), INTENSE("intense");

    companion object {
        fun of(key: String): ExerciseIntensity? = entries.firstOrNull { it.key == key }
    }
}

/** Níveis da fita de urina: negativo, traços, +, ++, +++. */
object KetoneUrine {
    val LABELS = listOf("−", "±", "+", "++", "+++")
    fun label(level: Double): String = LABELS[level.roundToInt().coerceIn(0, LABELS.lastIndex)]
}

/**
 * Faixas de atenção da cetona no sangue (mmol/L), as de uso comum nas orientações para DM1:
 * abaixo de 0,6 normal; 0,6–1,5 atenção; 1,6–3,0 risco; acima de 3,0 procurar atendimento.
 * Urina: "+" atenção, "++" ou mais risco. Só informa — a decisão é da pessoa e da equipe de saúde.
 */
enum class KetoneLevel { NORMAL, ATTENTION, RISK, URGENT }

fun ketoneLevel(method: KetoneMethod, value: Double): KetoneLevel = when (method) {
    KetoneMethod.BLOOD -> when {
        value < 0.6 -> KetoneLevel.NORMAL
        value <= 1.5 -> KetoneLevel.ATTENTION
        value <= 3.0 -> KetoneLevel.RISK
        else -> KetoneLevel.URGENT
    }
    KetoneMethod.URINE -> when (value.roundToInt()) {
        0, 1 -> KetoneLevel.NORMAL
        2 -> KetoneLevel.ATTENTION
        3 -> KetoneLevel.RISK
        else -> KetoneLevel.URGENT
    }
}

/** Porção caseira de um alimento ("fatia" = 25 g). */
data class Portion(val label: String, val grams: Double)

/**
 * Um item da refeição (F1.4): alimento, gramas e os valores já calculados — guardar os valores
 * (e não só a chave) faz o registro antigo não mudar se a base de alimentos for corrigida depois.
 */
data class MealItem(
    val foodKey: String,
    val name: String,
    val grams: Double,
    val carbsG: Double,
    val kcal: Double = 0.0,
    val proteinG: Double = 0.0,
    val fatG: Double = 0.0,
    /** Como a pessoa informou ("2 × fatia"); só para mostrar. */
    val portionText: String = ""
) {
    fun toJson(): JSONObject = JSONObject()
        .put("k", foodKey).put("n", name).put("g", grams).put("c", carbsG)
        .put("e", kcal).put("p", proteinG).put("f", fatG).put("t", portionText)

    companion object {
        fun fromJson(o: JSONObject) = MealItem(
            foodKey = o.optString("k"),
            name = o.optString("n"),
            grams = o.optDouble("g", 0.0),
            carbsG = o.optDouble("c", 0.0),
            kcal = o.optDouble("e", 0.0),
            proteinG = o.optDouble("p", 0.0),
            fatG = o.optDouble("f", 0.0),
            portionText = o.optString("t")
        )

        fun encode(items: List<MealItem>): String =
            if (items.isEmpty()) "" else JSONArray().apply { items.forEach { put(it.toJson()) } }.toString()

        fun decode(json: String): List<MealItem> {
            if (json.isBlank()) return emptyList()
            return runCatching {
                val a = JSONArray(json)
                (0 until a.length()).map { fromJson(a.getJSONObject(it)) }
            }.getOrDefault(emptyList())
        }
    }
}

/** Soma dos itens de uma refeição. */
data class MealTotals(val carbsG: Double, val kcal: Double, val proteinG: Double, val fatG: Double) {
    companion object {
        fun of(items: List<MealItem>) = MealTotals(
            items.sumOf { it.carbsG }, items.sumOf { it.kcal }, items.sumOf { it.proteinG }, items.sumOf { it.fatG }
        )
    }
}

/** Resumo do dia (F1.6). */
data class DaySummary(
    val carbsG: Double = 0.0,
    val kcal: Double = 0.0,
    val rapidU: Double = 0.0,
    val basalU: Double = 0.0,
    val mixedU: Double = 0.0,
    val entries: Int = 0,
    val exerciseMin: Double = 0.0
) {
    val totalInsulinU: Double get() = rapidU + basalU + mixedU

    companion object {
        fun of(events: List<EventLogEntity>): DaySummary {
            val live = events.filter { !it.deleted }
            val insulin = live.filter { it.type == DiaryType.INSULIN.key }
            return DaySummary(
                carbsG = live.filter { it.type == DiaryType.MEAL.key }.sumOf { it.value },
                kcal = live.filter { it.type == DiaryType.MEAL.key }.sumOf { it.kcal },
                rapidU = insulin.filter { InsulinKind.of(it.subtype) == InsulinKind.RAPID }.sumOf { it.value },
                basalU = insulin.filter { InsulinKind.of(it.subtype) == InsulinKind.BASAL }.sumOf { it.value },
                mixedU = insulin.filter { InsulinKind.of(it.subtype) == InsulinKind.MIXED }.sumOf { it.value },
                entries = live.size,
                exerciseMin = live.filter { it.type == DiaryType.EXERCISE.key }.sumOf { it.value }
            )
        }
    }
}

/** Porções em JSON ([{"l":"fatia","g":25}]). */
object PortionsJson {
    fun encode(portions: List<Portion>): String =
        JSONArray().apply { portions.forEach { put(JSONObject().put("l", it.label).put("g", it.grams)) } }.toString()

    fun decode(json: String): List<Portion> = runCatching {
        val a = JSONArray(json)
        (0 until a.length()).map { a.getJSONObject(it).let { o -> Portion(o.getString("l"), o.getDouble("g")) } }
    }.getOrDefault(emptyList())
}
