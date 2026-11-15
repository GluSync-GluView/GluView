package com.gluview.wear

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.gluview.wear.contract.WearDayInfo
import com.gluview.wear.contract.WearDiaryEntry
import com.gluview.wear.contract.WearDiaryLimits
import com.gluview.wear.data.DiaryStore
import com.gluview.wear.ui.Display
import kotlinx.coroutines.launch
import java.text.DecimalFormat
import java.util.UUID

/**
 * Registrar no diário pelo relógio (plano 17, F4.2): escolhe o tipo, (na insulina) qual insulina,
 * gira a coroa até o valor, confirma. Vai para a fila do Data Layer e o celular grava no diário.
 * [EXTRA_TYPE] abre direto num tipo (atalhos do bloco).
 */
class LogActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val start = intent.getStringExtra(EXTRA_TYPE)?.takeIf { it in WearDiaryEntry.TYPES }
        setContent { MaterialTheme { LogFlow(start, onDone = { finish() }) } }
    }

    companion object {
        const val EXTRA_TYPE = "type"
        fun intent(context: Context, type: String? = null) =
            Intent(context, LogActivity::class.java).apply { type?.let { putExtra(EXTRA_TYPE, it) } }
    }
}

private sealed interface Step {
    data object Type : Step
    data object Insulin : Step
    data class Value(val type: String, val subtype: String = "", val itemName: String = "") : Step
    data class Confirm(val entry: WearDiaryEntry) : Step
    data object Sent : Step
}

@Composable
private fun LogFlow(startType: String?, onDone: () -> Unit) {
    val context = LocalContext.current
    val day by DiaryStore.day(context).collectAsState()
    var step by remember {
        mutableStateOf<Step>(when (startType) {
            null -> Step.Type
            "insulin" -> Step.Insulin
            else -> Step.Value(startType)
        })
    }
    val scope = rememberCoroutineScope()

    when (val s = step) {
        Step.Type -> TypeStep(day, onPick = { type -> step = if (type == "insulin") Step.Insulin else Step.Value(type) },
            onFavorite = { fav ->
                val carbs = fav.extra.toDoubleOrNull() ?: return@TypeStep
                step = Step.Confirm(WearDiaryEntry(UUID.randomUUID().toString(), "meal", carbs, System.currentTimeMillis(),
                    itemName = fav.name, favoriteUuid = fav.uuid))
            })
        Step.Insulin -> InsulinStep(day?.insulins.orEmpty()) { kind, name -> step = Step.Value("insulin", kind, name) }
        is Step.Value -> ValueStep(s) { value ->
            step = Step.Confirm(WearDiaryEntry(UUID.randomUUID().toString(), s.type, value, System.currentTimeMillis(),
                subtype = s.subtype, itemName = s.itemName))
        }
        is Step.Confirm -> ConfirmStep(s.entry, onBack = { step = Step.Type }, onOk = {
            scope.launch {
                runCatching { DiaryStore.send(context, s.entry) }
                step = Step.Sent
            }
        })
        Step.Sent -> SentStep(onDone)
    }
}

@Composable
private fun Screen(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) { content() }
}

@Composable
private fun TypeStep(day: WearDayInfo?, onPick: (String) -> Unit, onFavorite: (WearDayInfo.WearItem) -> Unit) {
    Screen {
        Text(stringResource(R.string.log_title), fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        listOf(
            "meal" to R.string.type_meal, "insulin" to R.string.type_insulin, "fingerstick" to R.string.type_fingerstick,
            "ketone" to R.string.type_ketone, "exercise" to R.string.type_exercise
        ).forEach { (type, label) ->
            Chip(onClick = { onPick(type) }, label = { Text(stringResource(label)) },
                colors = ChipDefaults.secondaryChipColors(), modifier = Modifier.fillMaxWidth())
        }
        val favorites = day?.favorites.orEmpty()
        if (favorites.isNotEmpty()) {
            Text(stringResource(R.string.log_favorites), fontSize = 12.sp, color = Color(Display.COLOR_TEXT_DIM))
            favorites.forEach { f ->
                Chip(onClick = { onFavorite(f) }, label = { Text("${f.name} · ${f.extra} g") },
                    colors = ChipDefaults.primaryChipColors(), modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun InsulinStep(insulins: List<WearDayInfo.WearItem>, onPick: (kind: String, name: String) -> Unit) {
    Screen {
        Text(stringResource(R.string.type_insulin), fontWeight = FontWeight.Bold)
        insulins.forEach { i ->
            Chip(onClick = { onPick(i.extra, i.name) }, label = { Text(i.name) },
                secondaryLabel = { Text(kindLabel(i.extra)) },
                colors = ChipDefaults.primaryChipColors(), modifier = Modifier.fillMaxWidth())
        }
        // Sem cadastro no celular (ou para registrar outra): só o tipo.
        listOf("rapid", "basal", "mixed").forEach { kind ->
            Chip(onClick = { onPick(kind, "") }, label = { Text(kindLabel(kind)) },
                colors = ChipDefaults.secondaryChipColors(), modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun kindLabel(kind: String): String = stringResource(when (kind) {
    "basal" -> R.string.insulin_basal
    "mixed" -> R.string.insulin_mixed
    else -> R.string.insulin_rapid
})

@Composable
private fun unitLabel(type: String): String = stringResource(when (type) {
    "meal" -> R.string.unit_carbs
    "insulin" -> R.string.unit_insulin
    "fingerstick" -> R.string.unit_mgdl
    "ketone" -> R.string.unit_mmol
    else -> R.string.unit_minutes
})

private fun fmt(v: Double) = DecimalFormat("0.#").format(v)

/** O seletor: a coroa (ou o bezel) anda um passo por "clique"; + e − para quem não tem coroa. */
@Composable
private fun ValueStep(s: Step.Value, onNext: (Double) -> Unit) {
    val range = WearDiaryLimits.range(s.type)
    var value by remember { mutableDoubleStateOf(range.start) }
    var acc by remember { mutableFloatStateOf(0f) }
    val haptic = LocalHapticFeedback.current
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    fun turn(steps: Int) {
        val next = WearDiaryLimits.turn(s.type, value, steps)
        if (next != value) { value = next; haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
    }
    val unit = unitLabel(s.type)
    Column(
        Modifier.fillMaxSize()
            .onRotaryScrollEvent { e ->
                acc += e.verticalScrollPixels
                val steps = (acc / ROTARY_PX_PER_STEP).toInt()
                if (steps != 0) { acc -= steps * ROTARY_PX_PER_STEP; turn(steps) }
                true
            }
            .focusRequester(focus).focusable()
            .padding(horizontal = 14.dp, vertical = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically)
    ) {
        Text(listOf(typeLabel(s.type), s.itemName).filter { it.isNotBlank() }.joinToString(" · "),
            fontSize = 13.sp, color = Color(Display.COLOR_TEXT_DIM), textAlign = TextAlign.Center)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { turn(-1) }, colors = ButtonDefaults.secondaryButtonColors(), modifier = Modifier.size(40.dp)
                .semantics { contentDescription = "−" }) { Text("−", fontSize = 20.sp) }
            Text(fmt(value), fontSize = 40.sp, fontWeight = FontWeight.Bold)
            Button(onClick = { turn(1) }, colors = ButtonDefaults.secondaryButtonColors(), modifier = Modifier.size(40.dp)
                .semantics { contentDescription = "+" }) { Text("+", fontSize = 20.sp) }
        }
        Text(unit, fontSize = 13.sp)
        Text(stringResource(R.string.log_rotary_hint), fontSize = 10.sp, color = Color(Display.COLOR_TEXT_DIM), textAlign = TextAlign.Center)
        Chip(onClick = { onNext(value) }, label = { Text(stringResource(R.string.log_next)) },
            colors = ChipDefaults.primaryChipColors())
    }
}

@Composable
private fun typeLabel(type: String): String = stringResource(when (type) {
    "meal" -> R.string.type_meal
    "insulin" -> R.string.type_insulin
    "fingerstick" -> R.string.type_fingerstick
    "ketone" -> R.string.type_ketone
    else -> R.string.type_exercise
})

@Composable
private fun ConfirmStep(e: WearDiaryEntry, onBack: () -> Unit, onOk: () -> Unit) {
    Screen {
        Text(stringResource(R.string.log_confirm), fontSize = 13.sp, color = Color(Display.COLOR_TEXT_DIM))
        Text(listOf(typeLabel(e.type), e.itemName).filter { it.isNotBlank() }.joinToString(" · "), textAlign = TextAlign.Center)
        Text("${fmt(e.value)} ${unitLabel(e.type)}", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Chip(onClick = onOk, label = { Text(stringResource(R.string.log_save)) }, colors = ChipDefaults.primaryChipColors(),
            modifier = Modifier.fillMaxWidth())
        Chip(onClick = onBack, label = { Text(stringResource(R.string.log_cancel)) }, colors = ChipDefaults.secondaryChipColors(),
            modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun SentStep(onDone: () -> Unit) {
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(1_500); onDone() }
    Screen {
        Text("✓", fontSize = 36.sp, color = Color(0xFF66BB6A))
        Text(stringResource(R.string.log_sent), textAlign = TextAlign.Center)
    }
}

/** Pixels de rotação por passo do seletor (coroa e bezel giram em pixels de rolagem). */
private const val ROTARY_PX_PER_STEP = 36f
