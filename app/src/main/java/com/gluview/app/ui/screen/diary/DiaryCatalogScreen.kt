package com.gluview.app.ui.screen.diary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gluview.app.R
import com.gluview.app.data.diary.DiaryRepository
import com.gluview.app.data.diary.FoodRepository
import com.gluview.app.data.diary.InsulinKind
import com.gluview.app.data.diary.Portion
import com.gluview.app.data.diary.PortionsJson
import com.gluview.app.data.local.entity.FoodEntity
import com.gluview.app.data.local.entity.InsulinEntity
import com.gluview.app.data.local.entity.MedicationEntity
import com.gluview.app.ui.components.kit.ChoiceChips
import com.gluview.app.ui.components.kit.ConfirmDialog
import com.gluview.app.ui.components.kit.GluCard
import com.gluview.app.ui.components.kit.GluSection
import com.gluview.app.ui.components.kit.Page
import com.gluview.app.ui.theme.Spacing
import com.gluview.app.util.DiaryText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DiaryCatalogViewModel @Inject constructor(
    private val diary: DiaryRepository,
    private val foods: FoodRepository
) : ViewModel() {
    val insulins: StateFlow<List<InsulinEntity>> = diary.observeInsulins()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val medications: StateFlow<List<MedicationEntity>> = diary.observeMedications()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val userFoods: StateFlow<List<FoodEntity>> = foods.observeUserFoods()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun saveInsulin(i: InsulinEntity) { viewModelScope.launch { diary.saveInsulin(i) } }
    fun archiveInsulin(i: InsulinEntity) { viewModelScope.launch { diary.archiveInsulin(i.uuid) } }
    fun saveMedication(m: MedicationEntity) { viewModelScope.launch { diary.saveMedication(m) } }
    fun archiveMedication(m: MedicationEntity) { viewModelScope.launch { diary.archiveMedication(m.uuid) } }
    fun saveFood(f: FoodEntity) { viewModelScope.launch { foods.saveUserFood(f) } }
    fun deleteFood(f: FoodEntity) { viewModelScope.launch { foods.deleteUserFood(f.uuid) } }
}

/** Cadastros do diário (F1.2 e F1.4): minhas insulinas, meus remédios e meus alimentos. */
@Composable
fun DiaryCatalogScreen(onBack: () -> Unit, vm: DiaryCatalogViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val text = remember(context) { DiaryText(context) }
    val insulins by vm.insulins.collectAsState()
    val medications by vm.medications.collectAsState()
    val foods by vm.userFoods.collectAsState()
    var editInsulin by remember { mutableStateOf<InsulinEntity?>(null) }
    var editMed by remember { mutableStateOf<MedicationEntity?>(null) }
    var editFood by remember { mutableStateOf<FoodEntity?>(null) }
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }

    Page(stringResource(R.string.diary_catalog_title), onBack = onBack) {
        GluSection(stringResource(R.string.diary_my_insulins), description = stringResource(R.string.diary_my_insulins_desc)) {
            CatalogList(
                rows = insulins.map { it.name to text.insulinKind(InsulinKind.of(it.kind)) },
                onEdit = { editInsulin = insulins[it] },
                onDelete = { i -> confirm = insulins[i].name to { vm.archiveInsulin(insulins[i]) } }
            )
            OutlinedButton(onClick = { editInsulin = InsulinEntity(name = "", kind = InsulinKind.RAPID.key) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.diary_register_insulin))
            }
        }
        GluSection(stringResource(R.string.diary_my_medications), description = stringResource(R.string.diary_my_medications_desc)) {
            CatalogList(
                rows = medications.map { m -> m.name to listOf(if (m.defaultDose > 0) text.amount(m.defaultDose) else "", m.unit).filter { it.isNotBlank() }.joinToString(" ") },
                onEdit = { editMed = medications[it] },
                onDelete = { i -> confirm = medications[i].name to { vm.archiveMedication(medications[i]) } }
            )
            OutlinedButton(onClick = { editMed = MedicationEntity(name = "") }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.diary_register_medication))
            }
        }
        GluSection(stringResource(R.string.diary_my_foods), description = stringResource(R.string.diary_my_foods_desc)) {
            CatalogList(
                rows = foods.map { it.name to stringResource(R.string.diary_food_per100, text.amount(it.carbsPer100), text.amount(it.kcalPer100)) },
                onEdit = { editFood = foods[it] },
                onDelete = { i -> confirm = foods[i].name to { vm.deleteFood(foods[i]) } }
            )
            OutlinedButton(onClick = { editFood = FoodEntity(name = "", carbsPer100 = 0.0) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.diary_food_new))
            }
        }
    }

    editInsulin?.let { ins ->
        var name by remember(ins) { mutableStateOf(ins.name) }
        var kind by remember(ins) { mutableStateOf(InsulinKind.of(ins.kind)) }
        AlertDialog(
            onDismissRequest = { editInsulin = null },
            title = { Text(stringResource(R.string.diary_register_insulin)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.diary_field_insulin_name)) },
                        placeholder = { Text(stringResource(R.string.diary_field_insulin_name_hint)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    ChoiceChips(InsulinKind.entries, kind, { kind = it }, { text.insulinKind(it) })
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    vm.saveInsulin(ins.copy(name = name.trim(), kind = kind.key, active = true)); editInsulin = null
                }) { Text(stringResource(R.string.settings_save)) }
            },
            dismissButton = { TextButton(onClick = { editInsulin = null }) { Text(stringResource(R.string.common_cancel)) } }
        )
    }
    editMed?.let { med ->
        var name by remember(med) { mutableStateOf(med.name) }
        var dose by remember(med) { mutableStateOf(if (med.defaultDose > 0) text.amount(med.defaultDose) else "") }
        var unit by remember(med) { mutableStateOf(med.unit) }
        AlertDialog(
            onDismissRequest = { editMed = null },
            title = { Text(stringResource(R.string.diary_register_medication)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.diary_field_med_name)) },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    DecimalField(dose, { dose = it }, stringResource(R.string.diary_field_default_dose))
                    OutlinedTextField(unit, { unit = it }, label = { Text(stringResource(R.string.diary_field_dose_unit)) },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    vm.saveMedication(med.copy(name = name.trim(), defaultDose = parseDecimalInput(dose) ?: 0.0, unit = unit.trim(), active = true))
                    editMed = null
                }) { Text(stringResource(R.string.settings_save)) }
            },
            dismissButton = { TextButton(onClick = { editMed = null }) { Text(stringResource(R.string.common_cancel)) } }
        )
    }
    editFood?.let { food ->
        val portion = PortionsJson.decode(food.portionsJson).firstOrNull()
        FoodEditorDialog(
            initial = if (food.name.isBlank()) FoodForm() else FoodForm(
                food.name, text.amount(food.carbsPer100), text.amount(food.kcalPer100), text.amount(food.proteinPer100),
                text.amount(food.fatPer100), portion?.label ?: "", portion?.let { text.amount(it.grams) } ?: ""
            ),
            onDismiss = { editFood = null }
        ) { f ->
            val grams = parseDecimalInput(f.portionGrams)
            val portions = if (f.portionLabel.isNotBlank() && grams != null && grams > 0) listOf(Portion(f.portionLabel.trim(), grams)) else emptyList()
            vm.saveFood(food.copy(name = f.name.trim(), carbsPer100 = parseDecimalInput(f.carbs) ?: 0.0,
                kcalPer100 = parseDecimalInput(f.kcal) ?: 0.0, proteinPer100 = parseDecimalInput(f.protein) ?: 0.0,
                fatPer100 = parseDecimalInput(f.fat) ?: 0.0, portionsJson = PortionsJson.encode(portions)))
            editFood = null
        }
    }
    confirm?.let { (name, action) ->
        ConfirmDialog(
            title = stringResource(R.string.diary_catalog_remove_title, name),
            message = stringResource(R.string.diary_catalog_remove_body),
            confirmLabel = stringResource(R.string.watchface_delete_button),
            onConfirm = { action(); confirm = null },
            onDismiss = { confirm = null }
        )
    }
}

@Composable
private fun CatalogList(rows: List<Pair<String, String>>, onEdit: (Int) -> Unit, onDelete: (Int) -> Unit) {
    if (rows.isEmpty()) {
        Text(stringResource(R.string.diary_catalog_empty), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    GluCard {
        rows.forEachIndexed { i, (title, sub) ->
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.bodyLarge)
                    if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { onEdit(i) }) { Icon(Icons.Outlined.Edit, stringResource(R.string.diary_edit)) }
                IconButton(onClick = { onDelete(i) }) { Icon(Icons.Outlined.Delete, stringResource(R.string.watchface_delete_button)) }
            }
        }
    }
}
