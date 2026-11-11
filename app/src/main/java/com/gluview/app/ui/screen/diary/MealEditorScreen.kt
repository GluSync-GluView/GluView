package com.gluview.app.ui.screen.diary

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.gluview.app.R
import com.gluview.app.data.diary.FoodItem
import com.gluview.app.data.local.entity.MealFavoriteEntity
import com.gluview.app.ui.components.chart.rememberChartAppearance
import com.gluview.app.ui.components.kit.AppTopBar
import com.gluview.app.ui.components.kit.CheckboxRow
import com.gluview.app.ui.components.kit.ConfirmDialog
import com.gluview.app.ui.components.kit.GluCard
import com.gluview.app.ui.components.kit.GluCallout
import com.gluview.app.ui.components.kit.Tone
import com.gluview.app.ui.theme.Spacing
import com.gluview.app.util.DiaryText

/** Refeição (plano 17, F1.4–F1.5): busca na base embutida + alimentos próprios, favoritas e avulso. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MealEditorScreen(onBack: () -> Unit, vm: MealEditorViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val text = remember(context) { DiaryText(context) }
    val appearance = rememberChartAppearance()
    val draft by vm.draft.collectAsState()
    val query by vm.query.collectAsState()
    val results by vm.results.collectAsState()
    val favorites by vm.favorites.collectAsState()
    val saved by vm.saved.collectAsState()
    var picking by remember { mutableStateOf<FoodItem?>(null) }
    var creating by remember { mutableStateOf(false) }
    var asFavorite by remember { mutableStateOf(false) }
    var favoriteName by remember { mutableStateOf("") }
    var deletingFavorite by remember { mutableStateOf<MealFavoriteEntity?>(null) }
    vm.extraLabel = stringResource(R.string.diary_meal_extra_item)

    LaunchedEffect(saved) { if (saved) onBack() }

    Scaffold(
        topBar = { AppTopBar(stringResource(if (draft.uuid == null) R.string.diary_meal_new else R.string.diary_meal_edit), onBack) },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter, vertical = Spacing.sm)) {
                    val t = draft.totals
                    Text(stringResource(R.string.diary_meal_total, text.amount(t.carbsG), text.amount(t.kcal)),
                        style = MaterialTheme.typography.titleMedium)
                    Button(onClick = { vm.save(if (asFavorite) favoriteName else "") }, enabled = draft.canSave && (!asFavorite || favoriteName.isNotBlank()),
                        modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.settings_save)) }
                }
            }
        }
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).imePadding(),
            contentPadding = PaddingValues(horizontal = Spacing.gutter, vertical = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            item(key = "time") { DateTimeRow(draft.occurredAt, appearance.timeFormat, vm::setTime) }

            if (draft.uuid == null && favorites.isNotEmpty()) item(key = "fav") {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Text(stringResource(R.string.diary_meal_favorites), style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary, modifier = Modifier.semantics { heading() })
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        favorites.forEach { f ->
                            InputChip(
                                selected = false,
                                onClick = { vm.applyFavorite(f) },
                                label = { Text("${f.name} · ${text.amount(f.carbsG)} g") },
                                leadingIcon = { Icon(Icons.Outlined.Star, null) },
                                trailingIcon = {
                                    Icon(Icons.Outlined.Close, stringResource(R.string.diary_meal_favorite_delete, f.name),
                                        modifier = Modifier.clickable { deletingFavorite = f })
                                }
                            )
                        }
                    }
                }
            }

            item(key = "items") {
                GluCard {
                    Text(stringResource(R.string.diary_meal_items), style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.semantics { heading() })
                    if (draft.items.isEmpty()) {
                        Text(stringResource(R.string.diary_meal_no_items), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    draft.items.forEachIndexed { i, item ->
                        if (i > 0) HorizontalDivider()
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(item.name, style = MaterialTheme.typography.bodyLarge)
                                Text(listOf(item.portionText, "${text.amount(item.grams)} g",
                                    stringResource(R.string.diary_value_carbs, text.amount(item.carbsG))).filter { it.isNotBlank() }.joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { vm.removeItem(i) }) {
                                Icon(Icons.Outlined.Close, stringResource(R.string.diary_meal_remove_item, item.name))
                            }
                        }
                    }
                    DecimalField(draft.extraCarbsText, vm::setExtraCarbs, stringResource(R.string.diary_meal_extra),
                        placeholder = stringResource(R.string.eventlog_example_meal))
                }
            }

            item(key = "search") {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Text(stringResource(R.string.diary_meal_add_food), style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary, modifier = Modifier.semantics { heading() })
                    OutlinedTextField(query, vm::setQuery, leadingIcon = { Icon(Icons.Outlined.Search, null) },
                        label = { Text(stringResource(R.string.diary_meal_search)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    if (query.isBlank()) Text(stringResource(R.string.diary_meal_search_hint), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(results, key = { "f-" + it.key }) { food ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { picking = food }.padding(vertical = Spacing.xxs),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(food.name, style = MaterialTheme.typography.bodyLarge)
                        Text(stringResource(R.string.diary_food_per100, text.amount(food.carbsPer100), text.amount(food.kcalPer100)) +
                            if (food.isUser) " · " + stringResource(R.string.diary_food_mine) else "",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Outlined.Add, stringResource(R.string.diary_food_add))
                }
            }
            if (query.isNotBlank() && results.isEmpty()) item(key = "noresult") {
                GluCallout(stringResource(R.string.diary_meal_not_found, query), tone = Tone.Neutral)
            }
            item(key = "create") {
                OutlinedButton(onClick = { creating = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.diary_food_new))
                }
                Text(stringResource(R.string.diary_food_source), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Spacing.xs))
            }
            item(key = "notes") {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    OutlinedTextField(draft.notes, vm::setNotes, label = { Text(stringResource(R.string.eventlog_notes_optional)) },
                        maxLines = 3, modifier = Modifier.fillMaxWidth())
                    CheckboxRow(stringResource(R.string.diary_meal_save_favorite), asFavorite, { asFavorite = it })
                    if (asFavorite) OutlinedTextField(favoriteName, { favoriteName = it },
                        label = { Text(stringResource(R.string.diary_meal_favorite_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }

    picking?.let { food ->
        AddFoodDialog(food, onDismiss = { picking = null }, onAdd = { vm.addItem(it); picking = null; vm.setQuery("") })
    }
    if (creating) {
        FoodEditorDialog(FoodForm(name = query), onDismiss = { creating = false }) { f ->
            vm.createFood(f.name, parseDecimalInput(f.carbs) ?: 0.0, parseDecimalInput(f.kcal) ?: 0.0,
                parseDecimalInput(f.protein) ?: 0.0, parseDecimalInput(f.fat) ?: 0.0, f.portionLabel, parseDecimalInput(f.portionGrams))
            creating = false
        }
    }
    deletingFavorite?.let { f ->
        ConfirmDialog(
            title = stringResource(R.string.diary_meal_favorite_delete, f.name),
            message = stringResource(R.string.diary_meal_favorite_delete_body),
            confirmLabel = stringResource(R.string.watchface_delete_button),
            onConfirm = { vm.deleteFavorite(f); deletingFavorite = null },
            onDismiss = { deletingFavorite = null }
        )
    }
}
