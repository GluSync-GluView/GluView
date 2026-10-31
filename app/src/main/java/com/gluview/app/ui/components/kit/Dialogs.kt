package com.gluview.app.ui.components.kit

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gluview.app.R

/*
 * Diálogos do kit (redesign visual, Fase 1). O AlertDialog do M3 não rola o corpo: com fonte
 * grande, textos longos (ajuda do IFTTT, causas do sensor sem sinal, explicação do TIR) cortavam.
 */

/** Diálogo informativo/ajuda: corpo ROLÁVEL e um botão de fechar. */
@Composable
fun AppDialog(
    title: String,
    body: String,
    onDismiss: () -> Unit,
    confirmLabel: String = stringResource(R.string.common_close),
    onConfirm: () -> Unit = onDismiss,
    dismissLabel: String? = null
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = dismissLabel?.let { { TextButton(onClick = onDismiss) { Text(it) } } }
    )
}

/**
 * Confirmação de ação destrutiva: título, consequência e o botão de confirmar na cor de erro.
 * Só o componente — colocar confirmação onde hoje não há muda fluxo e entra com aval (L26).
 */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = true
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Text(message, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()))
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = if (destructive) ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                         else ButtonDefaults.textButtonColors()
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } }
    )
}
