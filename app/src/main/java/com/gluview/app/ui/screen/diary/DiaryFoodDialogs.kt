package com.gluview.app.ui.screen.diary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxWidth
import com.gluview.app.R
import com.gluview.app.data.diary.FoodItem
import com.gluview.app.data.diary.MealItem
import com.gluview.app.data.diary.Portion
import com.gluview.app.ui.components.kit.ChoiceChips
import com.gluview.app.ui.theme.Spacing
import com.gluview.app.util.DiaryText

/** Quanto do alimento: porção caseira × quantidade, ou gramas. Mostra o carboidrato antes de somar. */
@Composable
internal fun AddFoodDialog(food: FoodItem, onDismiss: () -> Unit, onAdd: (MealItem) -> Unit) {
    val context = LocalContext.current
    val text = remember(context) { DiaryText(context) }
    val gramsLabel = stringResource(R.string.diary_food_grams)
    val gramsOption = remember(gramsLabel) { Portion(gramsLabel, 1.0) }
    val options = remember(food, gramsOption) { food.portions + gramsOption }
    var portion by remember { mutableStateOf(options.first()) }
    var qtyText by remember { mutableStateOf(if (portion === gramsOption) "100" else "1") }
    val qty = parseDecimalInput(qtyText)?.takeIf { it > 0 }
    val grams = qty?.let { it * portion.grams }
    val preview = grams?.let { food.itemFor(it) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(food.name) },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(stringResource(R.string.diary_food_per100, text.amount(food.carbsPer100), text.amount(food.kcalPer100)),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ChoiceChips(options, portion, {
                    portion = it
                    qtyText = if (it === gramsOption) "100" else "1"
                }, { if (it === gramsOption) it.label else "${it.label} (${text.amount(it.grams)} g)" })
                DecimalField(qtyText, { qtyText = it },
                    stringResource(if (portion === gramsOption) R.string.diary_food_grams else R.string.diary_food_quantity))
                if (preview != null) {
                    Text(stringResource(R.string.diary_food_preview, text.amount(preview.grams), text.amount(preview.carbsG), text.amount(preview.kcal)),
                        style = MaterialTheme.typography.titleSmall)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = preview != null, onClick = {
                val portionText = if (portion === gramsOption) "" else "${text.amount(qty!!)} × ${portion.label}"
                onAdd(food.itemFor(grams!!, portionText))
            }) { Text(stringResource(R.string.diary_food_add)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } }
    )
}

/** Valores de um alimento próprio, por 100 g, e uma porção caseira opcional. */
data class FoodForm(
    val name: String = "",
    val carbs: String = "",
    val kcal: String = "",
    val protein: String = "",
    val fat: String = "",
    val portionLabel: String = "",
    val portionGrams: String = ""
)

@Composable
internal fun FoodEditorDialog(
    initial: FoodForm = FoodForm(),
    onDismiss: () -> Unit,
    onSave: (FoodForm) -> Unit
) {
    var f by remember { mutableStateOf(initial) }
    val valid = f.name.isNotBlank() && parseDecimalInput(f.carbs) != null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (initial.name.isBlank()) R.string.diary_food_new else R.string.diary_food_edit)) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                OutlinedTextField(f.name, { f = f.copy(name = it) }, label = { Text(stringResource(R.string.diary_food_name)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.diary_food_values_hint), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                DecimalField(f.carbs, { f = f.copy(carbs = it) }, stringResource(R.string.diary_food_carbs100))
                DecimalField(f.kcal, { f = f.copy(kcal = it) }, stringResource(R.string.diary_food_kcal100))
                DecimalField(f.protein, { f = f.copy(protein = it) }, stringResource(R.string.diary_food_protein100))
                DecimalField(f.fat, { f = f.copy(fat = it) }, stringResource(R.string.diary_food_fat100))
                OutlinedTextField(f.portionLabel, { f = f.copy(portionLabel = it) }, label = { Text(stringResource(R.string.diary_food_portion_label)) },
                    placeholder = { Text(stringResource(R.string.diary_food_portion_hint)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                DecimalField(f.portionGrams, { f = f.copy(portionGrams = it) }, stringResource(R.string.diary_food_portion_grams))
            }
        },
        confirmButton = { TextButton(enabled = valid, onClick = { onSave(f) }) { Text(stringResource(R.string.settings_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } }
    )
}
