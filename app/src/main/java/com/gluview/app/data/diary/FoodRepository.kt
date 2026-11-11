package com.gluview.app.data.diary

import android.content.Context
import com.gluview.app.data.datastore.AppPreferences
import com.gluview.app.data.local.dao.DiaryCatalogDao
import com.gluview.app.data.local.entity.FoodEntity
import com.gluview.app.data.local.entity.FoodUsageEntity
import com.gluview.app.data.local.entity.MealFavoriteEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.Normalizer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.round

/** Alimento pronto para busca: da base embutida ("b:…") ou cadastrado pela pessoa ("u:<uuid>"). */
data class FoodItem(
    val key: String,
    val name: String,
    val carbsPer100: Double,
    val kcalPer100: Double,
    val proteinPer100: Double,
    val fatPer100: Double,
    val portions: List<Portion>,
    val isUser: Boolean,
    val useCount: Int = 0,
    val lastUsedAt: Long = 0
) {
    /** Valores para [grams] gramas, com 1 casa (o que se vê é o que se soma). */
    fun itemFor(grams: Double, portionText: String = ""): MealItem = MealItem(
        foodKey = key,
        name = name,
        grams = grams,
        carbsG = round1(carbsPer100 * grams / 100.0),
        kcal = round1(kcalPer100 * grams / 100.0),
        proteinG = round1(proteinPer100 * grams / 100.0),
        fatG = round1(fatPer100 * grams / 100.0),
        portionText = portionText
    )
}

internal fun round1(v: Double): Double = round(v * 10.0) / 10.0

/** Texto sem acento e em minúsculas — "Feijão" acha "feijao" e vice-versa. */
internal fun normalizeForSearch(s: String): String =
    Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase().trim()

object FoodCatalog {
    const val BASE_PREFIX = "b:"
    const val USER_PREFIX = "u:"
    const val ASSET = "diary/foods_base.json"

    /** Lê a base embutida no idioma ([lang] "pt" ou "en"). Puro: testável sem Android. */
    fun parse(json: String, lang: String): List<FoodItem> {
        val foods = JSONObject(json).getJSONArray("foods")
        val en = lang == "en"
        return (0 until foods.length()).map { i ->
            val o = foods.getJSONObject(i)
            val por = o.optJSONArray("por")
            val portions = if (por == null) emptyList() else (0 until por.length()).map { j ->
                val p = por.getJSONArray(j)
                Portion(if (en) p.getString(1) else p.getString(0), p.getDouble(2))
            }
            FoodItem(
                key = BASE_PREFIX + o.getString("k"),
                name = if (en) o.getString("en") else o.getString("pt"),
                carbsPer100 = o.getDouble("c"),
                kcalPer100 = o.optDouble("e", 0.0),
                proteinPer100 = o.optDouble("p", 0.0),
                fatPer100 = o.optDouble("f", 0.0),
                portions = portions,
                isUser = false
            )
        }
    }

    fun fromEntity(e: FoodEntity) = FoodItem(
        key = USER_PREFIX + e.uuid,
        name = e.name,
        carbsPer100 = e.carbsPer100,
        kcalPer100 = e.kcalPer100,
        proteinPer100 = e.proteinPer100,
        fatPer100 = e.fatPer100,
        portions = PortionsJson.decode(e.portionsJson),
        isUser = true
    )

    /**
     * Busca: sem texto, os mais usados primeiro (depois a ordem alfabética); com texto, primeiro os
     * que começam pelo termo, depois os que o contêm — sempre desempatando pelo uso.
     */
    fun search(all: List<FoodItem>, query: String, limit: Int = 60): List<FoodItem> {
        val q = normalizeForSearch(query)
        val byUse = compareByDescending<FoodItem> { it.useCount }.thenByDescending { it.lastUsedAt }
            .thenBy { normalizeForSearch(it.name) }
        if (q.isEmpty()) return all.sortedWith(byUse).take(limit)
        val words = q.split(Regex("\\s+"))
        return all.mapNotNull { f ->
            val n = normalizeForSearch(f.name)
            when {
                n.startsWith(q) -> 0 to f
                words.all { it in n } -> 1 to f
                else -> null
            }
        }.sortedWith(compareBy<Pair<Int, FoodItem>> { it.first }.thenComparator { a, b -> byUse.compare(a.second, b.second) })
            .map { it.second }.take(limit)
    }
}

/**
 * Alimentos e refeições favoritas (plano 17, F1.4 e F1.5). Tudo no aparelho: a base vem do APK
 * e funciona sem internet.
 */
@Singleton
class FoodRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: DiaryCatalogDao,
    private val preferences: AppPreferences
) {
    @Volatile private var baseCache: Pair<String, List<FoodItem>>? = null

    private suspend fun base(): List<FoodItem> {
        val lang = if (preferences.appLanguage.first() == "en") "en" else "pt"
        baseCache?.let { if (it.first == lang) return it.second }
        val list = withContext(Dispatchers.IO) {
            runCatching {
                FoodCatalog.parse(context.assets.open(FoodCatalog.ASSET).bufferedReader().use { it.readText() }, lang)
            }.getOrDefault(emptyList())
        }
        baseCache = lang to list
        return list
    }

    /** Todos os alimentos (base + próprios) com o uso de cada um. */
    fun observeAll(): Flow<List<FoodItem>> = combine(
        flow { emit(base()) },
        dao.observeFoods(),
        dao.observeUsage()
    ) { baseList, user, usage ->
        val use = usage.associateBy { it.foodKey }
        (user.map(FoodCatalog::fromEntity) + baseList).map { f ->
            use[f.key]?.let { f.copy(useCount = it.useCount, lastUsedAt = it.lastUsedAt) } ?: f
        }
    }

    fun observeUserFoods(): Flow<List<FoodEntity>> = dao.observeFoods()

    suspend fun saveUserFood(food: FoodEntity) { dao.upsertFood(food) }

    suspend fun deleteUserFood(uuid: String) { dao.deleteFood(uuid) }

    /** Conta o uso dos alimentos de uma refeição salva. */
    suspend fun markUsed(items: List<MealItem>, now: Long = System.currentTimeMillis()) {
        items.map { it.foodKey }.filter { it.isNotBlank() }.distinct().forEach { key ->
            val u = dao.getUsage(key)
            dao.upsertUsage(FoodUsageEntity(key, (u?.useCount ?: 0) + 1, now))
        }
    }

    // ── Favoritas ──
    fun observeFavorites(): Flow<List<MealFavoriteEntity>> = dao.observeFavorites()

    suspend fun saveFavorite(name: String, items: List<MealItem>, carbsG: Double, kcal: Double) {
        dao.upsertFavorite(MealFavoriteEntity(name = name.trim(), itemsJson = MealItem.encode(items), carbsG = carbsG, kcal = kcal))
    }

    suspend fun markFavoriteUsed(uuid: String, now: Long = System.currentTimeMillis()) {
        dao.getFavorite(uuid)?.let { dao.updateFavorite(it.copy(useCount = it.useCount + 1, lastUsedAt = now)) }
    }

    suspend fun deleteFavorite(uuid: String) = dao.deleteFavorite(uuid)
}
