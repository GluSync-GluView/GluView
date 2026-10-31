package com.gluview.app.ui.components.kit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gluview.app.ui.theme.Spacing
import kotlin.math.roundToInt

/*
 * Controles do kit (redesign visual, Fase 1). Corrigem o que o diagnóstico mediu: Switch esmagado
 * por texto sem weight, switch sem rótulo para o TalkBack, slider com trilho pontilhado (steps
 * demais) e chips em Row sem quebra.
 */

/**
 * Linha de ajuste com Switch: a LINHA inteira é o alvo de toque (Role.Switch, ≥56dp) e o TalkBack
 * lê o título; o texto tem weight(1f), então o Switch nunca some com fonte grande.
 */
@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    enabled: Boolean = true
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (description != null) {
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        // onCheckedChange = null: o toque vai para a linha (um alvo só, sem duplo anúncio).
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/**
 * Linha de ajuste numérico: título, valor formatado à direita e slider de trilho LISO (sem o
 * pontilhado de dezenas de marcas). Controlado pelo pai, como o Slider do M3: [onValueChange]
 * recebe cada passo já arredondado para [step] — o mesmo momento de entrega dos sliders de hoje.
 * Entregar o valor só ao soltar é a pendência L24 e depende de aval; [onValueChangeFinished]
 * existe para quando ela for aprovada.
 *
 * Os passos são os nativos do Slider (marcas transparentes): assim o TalkBack anda exatamente
 * um [step] por gesto e anuncia o valor em [valueText] (stateDescription), não "N por cento".
 */
@Composable
fun SliderRow(
    title: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    step: Float,
    valueText: (Float) -> String,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onValueChangeFinished: (() -> Unit)? = null,
    enabled: Boolean = true
) {
    val start = valueRange.start
    val steps = (((valueRange.endInclusive - start) / step).roundToInt() - 1).coerceAtLeast(0)
    // O lerp do Slider devolve 4,9999995 em vez de 5: arredonda para o passo antes de entregar.
    fun snap(v: Float) = (start + ((v - start) / step).roundToInt() * step).coerceIn(start, valueRange.endInclusive)
    val shown = valueText(value)
    val noTicks = SliderDefaults.colors(
        activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent,
        disabledActiveTickColor = Color.Transparent, disabledInactiveTickColor = Color.Transparent
    )
    Column(modifier.fillMaxWidth()) {
        // Título e valor já vão no nó do Slider: a linha de cima fica fora do TalkBack (sem leitura dupla).
        Row(Modifier.fillMaxWidth().clearAndSetSemantics {}, verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(shown, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = value,
            onValueChange = { onValueChange(snap(it)) },
            onValueChangeFinished = onValueChangeFinished,
            valueRange = valueRange,
            steps = steps,
            enabled = enabled,
            colors = noTicks,
            modifier = Modifier.semantics { contentDescription = title; stateDescription = shown }
        )
    }
}

/**
 * Escolha única em chips que QUEBRAM de linha (FlowRow) em vez de cortar, com semântica de grupo
 * de rádio para o TalkBack. Serve para períodos (1/3/6/12/24h, 7/14/30 dias), tema etc.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> ChoiceChips(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: @Composable (T) -> String,
    modifier: Modifier = Modifier,
    /** false = só a largura dos chips (para dividir a linha com um título que quebra junto). */
    fillWidth: Boolean = true
) {
    FlowRow(
        modifier = modifier.then(if (fillWidth) Modifier.fillMaxWidth() else Modifier).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        verticalArrangement = Arrangement.spacedBy(Spacing.xxs)
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            // O FilterChip já é selecionável (um nó, um clique); só troca o papel Checkbox por
            // RadioButton, que é o que a escolha única é.
            FilterChip(
                selected = isSelected,
                onClick = { onSelect(option) },
                label = { Text(label(option), maxLines = 1, softWrap = false, overflow = TextOverflow.Visible) },
                modifier = Modifier.semantics { role = Role.RadioButton }
            )
        }
    }
}

/**
 * Seletor de período (1/3/6/12/24h, 7/14/30 dias): [ChoiceChips] com um rótulo opcional à
 * frente. Os chips quebram de linha em vez de cortar ("24/h" na Home a fonte 2,0).
 */
@Composable
fun <T> PeriodSelector(
    periods: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: @Composable (T) -> String,
    modifier: Modifier = Modifier,
    title: String? = null
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
        if (title != null) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        ChoiceChips(periods, selected, onSelect, label)
    }
}
