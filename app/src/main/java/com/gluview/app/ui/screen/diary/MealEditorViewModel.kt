package com.gluview.app.ui.screen.diary

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gluview.app.data.diary.DiaryRepository
import com.gluview.app.data.diary.DiaryType
import com.gluview.app.data.diary.FoodCatalog
import com.gluview.app.data.diary.FoodItem
import com.gluview.app.data.diary.FoodRepository
import com.gluview.app.data.diary.MealItem
import com.gluview.app.data.diary.MealTotals
import com.gluview.app.data.diary.Portion
import com.gluview.app.data.diary.PortionsJson
import com.gluview.app.data.diary.round1
import com.gluview.app.data.local.entity.EventLogEntity
import com.gluview.app.data.local.entity.FoodEntity
import com.gluview.app.data.local.entity.MealFavoriteEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Refeição sendo montada (F1.4/F1.5). */
data class MealDraft(
    val uuid: String? = null,
    val occurredAt: Long = System.currentTimeMillis(),
    val items: List<MealItem> = emptyList(),
    /** Carboidrato avulso (g), além dos itens. */
    val extraCarbsText: String = "",
    val notes: String = "",
    val sgvAtEvent: Double = 0.0,
    val loaded: Boolean = false
) {
    val extraCarbs: Double get() = parseDecimalInput(extraCarbsText)?.takeIf { it > 0 } ?: 0.0
    val totals: MealTotals get() = MealTotals.of(items).let { it.copy(carbsG = round1(it.carbsG + extraCarbs)) }
    val canSave: Boolean get() = items.isNotEmpty() || extraCarbs > 0
}

@HiltViewModel
class MealEditorViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val diary: DiaryRepository,
    private val foods: FoodRepository
) : ViewModel() {

    private val editUuid: String? = savedState.get<String>("uuid")?.takeIf { it.isNotBlank() }

    private val _draft = MutableStateFlow(MealDraft(uuid = editUuid, loaded = editUuid == null))
    val draft: StateFlow<MealDraft> = _draft.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    val results: StateFlow<List<FoodItem>> = combine(foods.observeAll(), _query) { all, q -> FoodCatalog.search(all, q) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val favorites: StateFlow<List<MealFavoriteEntity>> = foods.observeFavorites()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _saved = MutableStateFlow(false)
    val saved: StateFlow<Boolean> = _saved.asStateFlow()

    /** Nome do item de carboidrato avulso (texto do idioma, posto pela tela). */
    var extraLabel: String = ""

    init {
        if (editUuid != null) viewModelScope.launch {
            val e = diary.getByUuid(editUuid)
            if (e == null) { _draft.value = _draft.value.copy(loaded = true); return@launch }
            val items = MealItem.decode(e.itemsJson)
            val extraFromItems = items.filter { it.foodKey.isBlank() }.sumOf { it.carbsG }
            val foodItems = items.filter { it.foodKey.isNotBlank() }
            // Registro antigo (só número): todo o carboidrato vira "avulso".
            val extra = if (items.isEmpty()) e.value else extraFromItems
            _draft.value = MealDraft(
                uuid = e.uuid, occurredAt = e.occurredAt, items = foodItems,
                extraCarbsText = if (extra > 0) trimNumber(extra) else "",
                notes = e.notes, sgvAtEvent = e.sgvAtEvent, loaded = true
            )
        }
    }

    fun setQuery(q: String) { _query.value = q }
    fun setTime(ms: Long) { _draft.value = _draft.value.copy(occurredAt = ms, sgvAtEvent = 0.0) }
    fun setExtraCarbs(t: String) { _draft.value = _draft.value.copy(extraCarbsText = t) }
    fun setNotes(t: String) { _draft.value = _draft.value.copy(notes = t) }
    fun addItem(item: MealItem) { _draft.value = _draft.value.copy(items = _draft.value.items + item) }
    fun removeItem(index: Int) { _draft.value = _draft.value.copy(items = _draft.value.items.filterIndexed { i, _ -> i != index }) }

    fun applyFavorite(f: MealFavoriteEntity) {
        val items = MealItem.decode(f.itemsJson)
        val foodItems = items.filter { it.foodKey.isNotBlank() }
        val extra = items.filter { it.foodKey.isBlank() }.sumOf { it.carbsG } + if (items.isEmpty()) f.carbsG else 0.0
        _draft.value = _draft.value.copy(items = foodItems, extraCarbsText = if (extra > 0) trimNumber(extra) else "")
        viewModelScope.launch { foods.markFavoriteUsed(f.uuid) }
    }

    fun deleteFavorite(f: MealFavoriteEntity) { viewModelScope.launch { foods.deleteFavorite(f.uuid) } }

    /** Salva a refeição; [favoriteName] não vazio = também vira favorita. */
    fun save(favoriteName: String) {
        val d = _draft.value
        if (!d.canSave) return
        viewModelScope.launch {
            val allItems = d.items + if (d.extraCarbs > 0) listOf(MealItem("", extraLabel, 0.0, d.extraCarbs)) else emptyList()
            val t = d.totals
            val base = d.uuid?.let { diary.getByUuid(it) } ?: EventLogEntity(type = DiaryType.MEAL.key)
            diary.save(base.copy(
                occurredAt = d.occurredAt, value = t.carbsG, kcal = round1(t.kcal), proteinG = round1(t.proteinG),
                fatG = round1(t.fatG), itemsJson = MealItem.encode(allItems), notes = d.notes.trim(),
                sgvAtEvent = d.sgvAtEvent
            ))
            foods.markUsed(d.items)
            if (favoriteName.isNotBlank()) foods.saveFavorite(favoriteName, allItems, t.carbsG, round1(t.kcal))
            _saved.value = true
        }
    }

    /** Alimento próprio (F1.4: "a receita da casa"). Volta o item para já usar. */
    fun createFood(name: String, carbs: Double, kcal: Double, protein: Double, fat: Double, portionLabel: String, portionGrams: Double?) {
        viewModelScope.launch {
            val portions = if (portionLabel.isNotBlank() && portionGrams != null && portionGrams > 0)
                listOf(Portion(portionLabel.trim(), portionGrams)) else emptyList()
            foods.saveUserFood(FoodEntity(name = name.trim(), carbsPer100 = carbs, kcalPer100 = kcal, proteinPer100 = protein,
                fatPer100 = fat, portionsJson = PortionsJson.encode(portions)))
            _query.value = name.trim()
        }
    }

    private fun trimNumber(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else round1(v).toString().replace('.', ',')
}
