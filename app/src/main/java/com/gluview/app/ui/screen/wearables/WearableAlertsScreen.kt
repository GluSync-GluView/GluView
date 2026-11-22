package com.gluview.app.ui.screen.wearables

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.gluview.app.R
import com.gluview.app.ui.components.kit.ChoiceChips
import com.gluview.app.ui.components.kit.GluCard
import com.gluview.app.ui.components.kit.GluSection
import com.gluview.app.ui.components.kit.Page
import com.gluview.app.ui.components.kit.StatusBadge
import com.gluview.app.ui.components.kit.SwitchRow
import com.gluview.app.ui.components.kit.Tone
import com.gluview.app.ui.theme.Spacing
import com.gluview.app.wearables.hub.WearableFamily
import com.gluview.wearables.core.AlertRule
import com.gluview.wearables.core.VibrationStyle
import com.gluview.wearables.core.WearableAlertKind
import com.gluview.wearables.core.WearableAlertSettings

class WearableAlertsActions(
    val onBack: () -> Unit,
    val onEnabled: (WearableFamily, Boolean) -> Unit,
    val onRule: (WearableFamily, WearableAlertKind, AlertRule) -> Unit,
    val onSnooze: (Int) -> Unit,
    val onTest: (WearableFamily, String, String) -> Unit,
    val onFind: (WearableFamily) -> Unit
)

@Composable
fun WearableAlertsScreen(onBack: () -> Unit, viewModel: WearableAlertsViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsState()
    // Voltando da tela da Mi Band (pareou agora), confere de novo quem está configurado.
    LifecycleResumeEffect(Unit) {
        viewModel.refreshConfigured()
        onPauseOrDispose {}
    }
    WearableAlertsContent(ui, WearableAlertsActions(onBack, viewModel::setEnabled, viewModel::setRule,
        viewModel::setSnooze, viewModel::test, viewModel::find))
}

/**
 * Plano 21, F7.1 (pedido do Felipe, 22/11/2026): a vibração de cada alerta é configurável em cada tipo
 * de pulseira ou relógio. Um cartão por tipo de aparelho: liga/desliga, e por alerta se ele vai e com que
 * vibração; "Testar" manda um alerta agora e "Encontrar" faz a pulseira vibrar.
 */
@Composable
internal fun WearableAlertsContent(ui: WearableAlertsUi, actions: WearableAlertsActions) {
    Page(title = stringResource(R.string.wearable_alerts_title), onBack = actions.onBack) {
        Text(stringResource(R.string.wearable_alerts_intro), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)

        GluSection(stringResource(R.string.wearable_alerts_snooze_section),
            description = stringResource(R.string.wearable_alerts_snooze_desc)) {
            GluCard {
                ChoiceChips(listOf(15, 30, 60, 120), ui.snoozeMinutes.takeIf { it in listOf(15, 30, 60, 120) } ?: 30,
                    actions.onSnooze, label = { stringResource(R.string.wearable_alerts_minutes, it) })
            }
        }

        ui.families.forEach { f -> FamilyCard(f, actions) }
    }
}

@Composable
private fun FamilyCard(f: WearableFamilyUi, actions: WearableAlertsActions) {
    val family = f.family
    GluSection(stringResource(family.label), description = stringResource(family.hint)) {
        GluCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                StatusBadge(
                    stringResource(if (f.configured) R.string.wearable_alerts_ready else R.string.wearable_alerts_not_paired),
                    tone = if (f.configured) Tone.Success else Tone.Warning
                )
            }
            SwitchRow(stringResource(R.string.wearable_alerts_enabled), f.settings.enabled, { actions.onEnabled(family, it) },
                description = stringResource(R.string.wearable_alerts_enabled_desc))
            if (f.settings.enabled) {
                WearableAlertSettings.KINDS.forEach { kind ->
                    HorizontalDivider()
                    KindRow(family, kind, f.settings.ruleFor(kind)) { actions.onRule(family, kind, it) }
                }
                HorizontalDivider()
                val testTitle = stringResource(R.string.wearable_alerts_test_title)
                val testBody = stringResource(R.string.wearable_alerts_test_body)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    OutlinedButton(onClick = { actions.onTest(family, testTitle, testBody) }, enabled = !f.busy && f.configured,
                        modifier = Modifier.weight(1f)) {
                        if (f.busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        else Text(stringResource(R.string.wearable_alerts_test))
                    }
                    if (family.canFind) OutlinedButton(onClick = { actions.onFind(family) }, enabled = !f.busy && f.configured,
                        modifier = Modifier.weight(1f)) { Text(stringResource(R.string.wearable_alerts_find)) }
                }
                f.result?.let { r ->
                    val ok = r == "ok" || r == "find_ok"
                    Text(stringResource(when (r) {
                        "ok" -> R.string.wearable_alerts_test_ok
                        "find_ok" -> R.string.wearable_alerts_find_ok
                        else -> R.string.wearable_alerts_test_fail
                    }), style = MaterialTheme.typography.bodySmall,
                        color = if (ok) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun KindRow(family: WearableFamily, kind: WearableAlertKind, rule: AlertRule, onRule: (AlertRule) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = Spacing.xxs), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
        SwitchRow(stringResource(kindLabel(kind)), rule.enabled, { onRule(rule.copy(enabled = it)) })
        if (rule.enabled) {
            val options = VibrationStyle.entries.filter { it in family.supported || it == VibrationStyle.OFF }
            // Estilo salvo que este aparelho não toca (ex.: "chamada" vinda de outro) aparece como o equivalente.
            val shown = if (rule.style in options) rule.style else VibrationStyle.INSISTENT
            ChoiceChips(options, shown, { onRule(rule.copy(style = it)) }, label = { stringResource(styleLabel(it)) })
        }
    }
}

internal fun kindLabel(k: WearableAlertKind): Int = when (k) {
    WearableAlertKind.URGENT_LOW -> R.string.wearable_kind_urgent_low
    WearableAlertKind.LOW -> R.string.wearable_kind_low
    WearableAlertKind.HIGH -> R.string.wearable_kind_high
    WearableAlertKind.URGENT_HIGH -> R.string.wearable_kind_urgent_high
    WearableAlertKind.QUICK_DROP -> R.string.wearable_kind_quick_drop
    WearableAlertKind.STALE -> R.string.wearable_kind_stale
    WearableAlertKind.PREDICTIVE -> R.string.wearable_kind_predictive
    WearableAlertKind.NORMAL -> R.string.wearable_kind_low
}

internal fun styleLabel(s: VibrationStyle): Int = when (s) {
    VibrationStyle.OFF -> R.string.vibration_off
    VibrationStyle.SHORT -> R.string.vibration_short
    VibrationStyle.DOUBLE -> R.string.vibration_double
    VibrationStyle.TRIPLE -> R.string.vibration_triple
    VibrationStyle.LONG -> R.string.vibration_long
    VibrationStyle.INSISTENT -> R.string.vibration_insistent
    VibrationStyle.CALL -> R.string.vibration_call
}
