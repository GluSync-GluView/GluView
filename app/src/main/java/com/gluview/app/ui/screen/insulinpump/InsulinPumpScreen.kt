package com.gluview.app.ui.screen.insulinpump

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Vaccines
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.gluview.app.R
import com.gluview.app.ui.components.kit.ErrorState
import com.gluview.app.ui.components.kit.GluCallout
import com.gluview.app.ui.components.kit.GluCard
import com.gluview.app.ui.components.kit.Page
import com.gluview.app.ui.components.kit.SkeletonBlock
import com.gluview.app.ui.components.kit.Tone
import com.gluview.app.ui.screen.settings.ProgressLabel
import com.gluview.app.ui.theme.Spacing

@Composable
fun InsulinPumpScreen(
    viewModel: InsulinPumpViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    InsulinPumpContent(uiState, viewModel::applyForBeta, viewModel::refresh)
}

/**
 * Bomba de insulina (redesign visual, Fase 6): rola (com fonte grande o botão saía da tela), o
 * texto vem em blocos com a regra de segurança destacada, o status é um aviso que não parece
 * botão (antes um AssistChip com onClick vazio) e a ação ocupa a largura toda.
 */
@Composable
internal fun InsulinPumpContent(ui: InsulinPumpUiState, onApply: () -> Unit, onRetry: () -> Unit = {}) {
    // Aba principal: sem seta de voltar.
    Page(title = stringResource(R.string.insulin_pump_title)) {
        GluCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Icon(Icons.Filled.Vaccines, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
                Text(stringResource(R.string.insulin_pump_how_title), style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { heading() })
            }
            Text(stringResource(R.string.insulin_pump_how_body), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.insulin_pump_future_body), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // A regra que não pode passar despercebida.
        GluCallout(stringResource(R.string.insulin_pump_safety), tone = Tone.Info, title = stringResource(R.string.insulin_pump_safety_title))
        Text(stringResource(R.string.insulin_pump_not_active), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)

        when (ui.betaState) {
            BetaState.LOADING -> SkeletonBlock(Modifier.fillMaxWidth(), height = 48.dp)
            BetaState.ERROR -> ErrorState(stringResource(R.string.history_load_error_body), onRetry, detail = ui.error)
            BetaState.NONE, BetaState.REJECTED -> {
                if (ui.betaState == BetaState.REJECTED) {
                    GluCallout(stringResource(R.string.insulin_pump_status_rejected), tone = Tone.Warning)
                }
                Button(onClick = onApply, enabled = !ui.submitting, modifier = Modifier.fillMaxWidth()) {
                    ProgressLabel(stringResource(if (ui.betaState == BetaState.REJECTED) R.string.settings_beta_rejected_reapply
                                                 else R.string.insulin_pump_apply_beta), ui.submitting)
                }
            }
            BetaState.PENDING -> GluCallout(stringResource(R.string.insulin_pump_status_pending), tone = Tone.Info)
            BetaState.WAITLISTED -> GluCallout(stringResource(R.string.insulin_pump_status_waitlisted), tone = Tone.Info)
            BetaState.APPROVED -> GluCallout(stringResource(R.string.insulin_pump_status_approved), tone = Tone.Success)
        }

        ui.error?.let { detail ->
            GluCallout(stringResource(R.string.insulin_pump_apply_failed, detail), tone = Tone.Danger, announce = true)
        }
    }
}
