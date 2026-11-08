package com.gluview.app.ui.components

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gluview.app.R
import com.gluview.app.data.source.DataSourceType

/**
 * ADR-0029 — candidatura de beta de um sensor "Em breve". Mora no Passo 1 do assistente de
 * sensor (único lugar onde se escolhe o sensor, plano 15). [betaState] vem do mesmo
 * GET /access-requests/me/status que desenha os selos da lista.
 */
@Composable
fun SensorBetaDialog(
    source: DataSourceType,
    betaState: String?,
    submitting: Boolean,
    onApply: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_apply_beta_title, stringResource(source.labelRes))) },
        text = {
            Text(
                when (betaState) {
                    "pending", "waitlisted" -> stringResource(R.string.settings_beta_already_applied)
                    "approved" -> stringResource(R.string.settings_beta_approved)
                    // Achado 2026-09-26 — sem este caso parecia nunca ter candidatado e deixava
                    // recandidatar sem avisar que já foi recusado.
                    "rejected" -> stringResource(R.string.settings_beta_rejected)
                    else -> stringResource(R.string.settings_apply_beta_body)
                },
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())
            )
        },
        confirmButton = {
            if (betaState !in setOf("pending", "waitlisted", "approved")) {
                TextButton(onClick = onApply, enabled = !submitting) {
                    Text(
                        if (betaState == "rejected") stringResource(R.string.settings_beta_rejected_reapply)
                        else stringResource(R.string.insulin_pump_apply_beta)
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) }
        }
    )
}
