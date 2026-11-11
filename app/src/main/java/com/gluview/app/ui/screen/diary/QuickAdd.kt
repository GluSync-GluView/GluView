package com.gluview.app.ui.screen.diary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gluview.app.R
import com.gluview.app.data.datastore.AppPreferences
import com.gluview.app.data.diary.DiaryRepository
import com.gluview.app.data.diary.DiaryType
import com.gluview.app.data.diary.FoodRepository
import com.gluview.app.data.diary.MealItem
import com.gluview.app.data.local.entity.EventLogEntity
import com.gluview.app.data.local.entity.InsulinEntity
import com.gluview.app.data.local.entity.MealFavoriteEntity
import com.gluview.app.data.local.entity.MedicationEntity
import com.gluview.app.ui.components.kit.AppModalBottomSheet
import com.gluview.app.ui.theme.Spacing
import com.gluview.app.util.DiaryText
import com.gluview.app.util.GlucoseUnit
import com.gluview.app.util.TimeFormat
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Registro rápido (pedido do Felipe, 10/11/2026): o mesmo fluxo no lápis da Home, no widget e na
 * notificação fixa — escolhe o tipo, preenche e fecha. Grava no aparelho (GluView first).
 */
@HiltViewModel
class QuickAddViewModel @Inject constructor(
    private val diary: DiaryRepository,
    private val foods: FoodRepository,
    preferences: AppPreferences
) : ViewModel() {
    val insulins: StateFlow<List<InsulinEntity>> = diary.observeInsulins()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val medications: StateFlow<List<MedicationEntity>> = diary.observeMedications()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val favorites: StateFlow<List<MealFavoriteEntity>> = foods.observeFavorites()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val unit: StateFlow<GlucoseUnit> = preferences.glucoseUnit.map { GlucoseUnit.fromString(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, GlucoseUnit.MG_DL)
    val timeFormat: StateFlow<TimeFormat> = preferences.timeFormat.map { TimeFormat.fromString(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, TimeFormat.H24)

    fun save(entry: EventLogEntity, onSaved: () -> Unit) {
        viewModelScope.launch { diary.save(entry); onSaved() }
    }

    fun saveFavorite(f: MealFavoriteEntity, at: Long, onSaved: () -> Unit) {
        viewModelScope.launch {
            val items = MealItem.decode(f.itemsJson)
            diary.save(EventLogEntity(type = DiaryType.MEAL.key, occurredAt = at, value = f.carbsG, kcal = f.kcal,
                itemsJson = f.itemsJson, notes = f.name))
            foods.markUsed(items.filter { it.foodKey.isNotBlank() })
            foods.markFavoriteUsed(f.uuid)
            onSaved()
        }
    }
}

/**
 * Fluxo completo: [initialType] null = começa escolhendo o tipo. [onDone] fecha (salvou ou
 * desistiu). [onOpenMealEditor] = montar a refeição com a busca de alimentos (abre o app).
 */
@Composable
fun QuickAddFlow(
    initialType: DiaryType? = null,
    onDone: () -> Unit,
    onOpenMealEditor: () -> Unit,
    /** Cadastrar insulina/remédio (abre o diário no app). */
    onOpenCatalog: () -> Unit = onDone,
    vm: QuickAddViewModel = hiltViewModel()
) {
    var type by remember { mutableStateOf(initialType) }
    val insulins by vm.insulins.collectAsState()
    val medications by vm.medications.collectAsState()
    val favorites by vm.favorites.collectAsState()
    val unit by vm.unit.collectAsState()
    val timeFormat by vm.timeFormat.collectAsState()

    when (val t = type) {
        null -> TypeChooserSheet(onPick = { type = it }, onDismiss = onDone)
        DiaryType.MEAL -> QuickMealSheet(
            favorites = favorites, timeFormat = timeFormat, onDismiss = onDone,
            onSave = { vm.save(it, onDone) },
            onFavorite = { f, at -> vm.saveFavorite(f, at, onDone) },
            onOpenMealEditor = onOpenMealEditor
        )
        else -> DiaryEntrySheet(
            initial = newEntry(t, insulins), insulins = insulins, medications = medications, unit = unit,
            timeFormat = timeFormat, onDismiss = onDone, onSave = { vm.save(it, onDone) },
            onManageCatalog = onOpenCatalog
        )
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun TypeChooserSheet(onPick: (DiaryType) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val text = remember(context) { DiaryText(context) }
    AppModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.lg).padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text(stringResource(R.string.quick_add_title), style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() })
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                DiaryType.entries.forEach { t ->
                    AssistChip(onClick = { onPick(t) }, label = { Text(text.typeLabel(t)) },
                        leadingIcon = { Icon(t.icon(), null, tint = t.color()) })
                }
            }
        }
    }
}

/** Refeição rápida: favorita com um toque ou só o carboidrato; "montar com alimentos" abre o app. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun QuickMealSheet(
    favorites: List<MealFavoriteEntity>,
    timeFormat: TimeFormat,
    onDismiss: () -> Unit,
    onSave: (EventLogEntity) -> Unit,
    onFavorite: (MealFavoriteEntity, Long) -> Unit,
    onOpenMealEditor: () -> Unit
) {
    val context = LocalContext.current
    val text = remember(context) { DiaryText(context) }
    var at by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var carbs by remember { mutableStateOf("") }
    var kcal by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    val c = parseDecimalInput(carbs)?.takeIf { it > 0 }

    AppModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.lg).padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text(stringResource(R.string.diary_new_entry, text.typeLabel(DiaryType.MEAL)), style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() })
            if (favorites.isNotEmpty()) {
                Text(stringResource(R.string.quick_add_favorites), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    favorites.take(8).forEach { f ->
                        AssistChip(onClick = { onFavorite(f, at) }, label = { Text("${f.name} · ${text.amount(f.carbsG)} g") },
                            leadingIcon = { Icon(Icons.Outlined.Star, null) })
                    }
                }
            }
            DecimalField(carbs, { carbs = it }, stringResource(R.string.diary_meal_extra), placeholder = stringResource(R.string.eventlog_example_meal))
            DecimalField(kcal, { kcal = it }, stringResource(R.string.quick_add_kcal))
            OutlinedTextField(notes, { notes = it }, label = { Text(stringResource(R.string.eventlog_notes_optional)) },
                maxLines = 3, modifier = Modifier.fillMaxWidth())
            DateTimeRow(at, timeFormat, { at = it })
            Button(enabled = c != null, modifier = Modifier.fillMaxWidth(), onClick = {
                onSave(EventLogEntity(type = DiaryType.MEAL.key, occurredAt = at, value = c ?: 0.0,
                    kcal = parseDecimalInput(kcal) ?: 0.0, notes = notes.trim()))
            }) { Text(stringResource(R.string.settings_save)) }
            TextButton(onClick = onOpenMealEditor, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.quick_add_meal_full))
            }
        }
    }
}
