package com.gluview.app.ui.screen.diary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.gluview.app.R
import com.gluview.app.data.diary.DiaryType
import com.gluview.app.data.diary.ExerciseIntensity
import com.gluview.app.data.diary.InsulinKind
import com.gluview.app.data.diary.KetoneLevel
import com.gluview.app.data.diary.KetoneMethod
import com.gluview.app.data.diary.KetoneUrine
import com.gluview.app.data.diary.ketoneLevel
import com.gluview.app.data.local.entity.EventLogEntity
import com.gluview.app.data.local.entity.InsulinEntity
import com.gluview.app.data.local.entity.MedicationEntity
import com.gluview.app.ui.components.kit.AppModalBottomSheet
import com.gluview.app.ui.components.kit.ChoiceChips
import com.gluview.app.ui.components.kit.GluCallout
import com.gluview.app.ui.theme.Spacing
import com.gluview.app.util.DiaryText
import com.gluview.app.util.GlucoseUnit
import com.gluview.app.util.TimeFormat
import kotlin.math.roundToInt

/**
 * Registro (novo ou edição) de tudo menos refeição, que tem tela própria (busca de alimentos).
 * Cada tipo pede só o que precisa; o horário é "agora" e dá para mudar.
 */
@OptIn(ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun DiaryEntrySheet(
    initial: EventLogEntity,
    insulins: List<InsulinEntity>,
    medications: List<MedicationEntity>,
    unit: GlucoseUnit,
    timeFormat: TimeFormat,
    onDismiss: () -> Unit,
    onSave: (EventLogEntity) -> Unit,
    onManageCatalog: () -> Unit
) {
    val context = LocalContext.current
    val text = remember(context, unit) { DiaryText(context, unit) }
    val type = DiaryType.of(initial.type)
    val isNew = initial.id == 0L

    var at by remember { mutableLongStateOf(initial.occurredAt) }
    var notes by remember { mutableStateOf(initial.notes) }
    var subtype by remember { mutableStateOf(initial.subtype) }
    var itemName by remember { mutableStateOf(initial.itemName) }
    var itemUnit by remember { mutableStateOf(initial.unit) }
    var valueText by remember {
        mutableStateOf(
            when {
                initial.value <= 0 -> ""
                type == DiaryType.FINGERSTICK && unit == GlucoseUnit.MMOL_L -> text.amount((initial.value / 18.0 * 10).roundToInt() / 10.0)
                type == DiaryType.FINGERSTICK -> initial.value.roundToInt().toString()
                else -> text.amount(initial.value)
            }
        )
    }
    var urineLevel by remember { mutableStateOf(if (type == DiaryType.KETONE && KetoneMethod.of(initial.subtype) == KetoneMethod.URINE) initial.value.roundToInt() else 0) }

    val parsed = parseDecimalInput(valueText)
    val needsValue = type in setOf(DiaryType.INSULIN, DiaryType.FINGERSTICK, DiaryType.EXERCISE) ||
        (type == DiaryType.KETONE && KetoneMethod.of(subtype) == KetoneMethod.BLOOD)
    val valid = when (type) {
        DiaryType.NOTE -> notes.isNotBlank()
        DiaryType.MEDICATION -> itemName.isNotBlank()
        DiaryType.KETONE -> KetoneMethod.of(subtype) == KetoneMethod.URINE || (parsed != null && parsed >= 0)
        else -> !needsValue || (parsed != null && parsed > 0)
    }

    AppModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg).padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Text(
                stringResource(if (isNew) R.string.diary_new_entry else R.string.diary_edit_entry, text.typeLabel(type)),
                style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() }
            )

            when (type) {
                DiaryType.INSULIN -> {
                    if (insulins.isNotEmpty()) {
                        Text(stringResource(R.string.diary_my_insulins), style = MaterialTheme.typography.labelLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                            insulins.forEach { ins ->
                                FilterChip(
                                    selected = itemName == ins.name && subtype == ins.kind,
                                    onClick = { itemName = ins.name; subtype = ins.kind },
                                    label = { Text("${ins.name} · ${text.insulinKind(InsulinKind.of(ins.kind))}") }
                                )
                            }
                        }
                    } else {
                        GluCallout(stringResource(R.string.diary_no_insulins), actionLabel = stringResource(R.string.diary_register_insulin),
                            onAction = onManageCatalog)
                    }
                    ChoiceChips(InsulinKind.entries, InsulinKind.of(subtype), { subtype = it.key }, { text.insulinKind(it) })
                    DecimalField(valueText, { valueText = it }, stringResource(R.string.diary_field_units), placeholder = "4")
                }
                DiaryType.FINGERSTICK -> {
                    DecimalField(valueText, { valueText = it }, stringResource(R.string.diary_field_glucose, unit.label),
                        placeholder = if (unit == GlucoseUnit.MG_DL) "120" else "6,7")
                    Text(stringResource(R.string.diary_fingerstick_hint), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DiaryType.KETONE -> {
                    val method = KetoneMethod.of(subtype)
                    ChoiceChips(KetoneMethod.entries, method, { subtype = it.key }, {
                        stringResource(if (it == KetoneMethod.BLOOD) R.string.diary_ketone_blood else R.string.diary_ketone_urine)
                    })
                    val level: KetoneLevel? = if (method == KetoneMethod.BLOOD) {
                        DecimalField(valueText, { valueText = it }, stringResource(R.string.diary_field_mmol), placeholder = "0,4")
                        parsed?.let { ketoneLevel(KetoneMethod.BLOOD, it) }
                    } else {
                        ChoiceChips((0..4).toList(), urineLevel, { urineLevel = it }, { KetoneUrine.LABELS[it] })
                        ketoneLevel(KetoneMethod.URINE, urineLevel.toDouble())
                    }
                    if (level != null) {
                        GluCallout(
                            stringResource(ketoneAdvice(level)),
                            title = stringResource(ketoneLevelLabel(level)),
                            tone = ketoneTone(level)
                        )
                    }
                }
                DiaryType.MEDICATION -> {
                    if (medications.isNotEmpty()) {
                        Text(stringResource(R.string.diary_my_medications), style = MaterialTheme.typography.labelLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                            medications.forEach { m ->
                                FilterChip(
                                    selected = itemName == m.name,
                                    onClick = {
                                        itemName = m.name; itemUnit = m.unit
                                        if (m.defaultDose > 0) valueText = text.amount(m.defaultDose)
                                    },
                                    label = { Text(m.name) }
                                )
                            }
                        }
                    }
                    OutlinedTextField(itemName, { itemName = it }, label = { Text(stringResource(R.string.diary_field_med_name)) },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    DecimalField(valueText, { valueText = it }, stringResource(R.string.diary_field_dose))
                    OutlinedTextField(itemUnit, { itemUnit = it }, label = { Text(stringResource(R.string.diary_field_dose_unit)) },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = onManageCatalog) { Text(stringResource(R.string.diary_manage_medications)) }
                }
                DiaryType.EXERCISE -> {
                    DecimalField(valueText, { valueText = it }, stringResource(R.string.diary_field_minutes), placeholder = "30")
                    ChoiceChips(listOf<ExerciseIntensity?>(null) + ExerciseIntensity.entries, ExerciseIntensity.of(subtype),
                        { subtype = it?.key ?: "" }, { it?.let(text::intensity) ?: stringResource(R.string.diary_not_informed) })
                    OutlinedTextField(itemName, { itemName = it }, label = { Text(stringResource(R.string.diary_field_exercise_kind)) },
                        placeholder = { Text(stringResource(R.string.diary_field_exercise_kind_hint)) },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                DiaryType.NOTE, DiaryType.MEAL -> Unit
            }

            OutlinedTextField(
                notes, { notes = it },
                label = { Text(stringResource(if (type == DiaryType.NOTE) R.string.diary_field_note else R.string.eventlog_notes_optional)) },
                maxLines = 4, modifier = Modifier.fillMaxWidth()
            )
            DateTimeRow(at, timeFormat, { at = it })

            Button(
                enabled = valid,
                onClick = {
                    val v = when {
                        type == DiaryType.KETONE && KetoneMethod.of(subtype) == KetoneMethod.URINE -> urineLevel.toDouble()
                        type == DiaryType.FINGERSTICK && unit == GlucoseUnit.MMOL_L -> (parsed ?: 0.0) * 18.0
                        else -> parsed ?: 0.0
                    }
                    onSave(initial.copy(
                        occurredAt = at, notes = notes.trim(), value = v, subtype = subtype,
                        itemName = itemName.trim(), unit = itemUnit.trim(),
                        // Mudou o horário: a glicemia do momento é recalculada.
                        sgvAtEvent = if (at != initial.occurredAt) 0.0 else initial.sgvAtEvent
                    ))
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.settings_save)) }
        }
    }
}

internal fun ketoneAdvice(level: KetoneLevel): Int = when (level) {
    KetoneLevel.NORMAL -> R.string.diary_ketone_advice_normal
    KetoneLevel.ATTENTION -> R.string.diary_ketone_advice_attention
    KetoneLevel.RISK -> R.string.diary_ketone_advice_risk
    KetoneLevel.URGENT -> R.string.diary_ketone_advice_urgent
}
