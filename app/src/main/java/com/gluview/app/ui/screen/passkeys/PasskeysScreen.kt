package com.gluview.app.ui.screen.passkeys

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gluview.app.R
import com.gluview.app.data.remote.dto.PasskeyInfoDto
import com.gluview.app.ui.components.kit.ConfirmDialog
import com.gluview.app.ui.components.kit.EmptyState
import com.gluview.app.ui.components.kit.ErrorState
import com.gluview.app.ui.components.kit.ItemCard
import com.gluview.app.ui.components.kit.ListSkeleton
import com.gluview.app.ui.components.kit.Page
import com.gluview.app.ui.theme.Spacing
import com.gluview.app.util.ServerDates
import com.gluview.app.util.formatElapsed

@Composable
fun PasskeysScreen(
    onBack: () -> Unit,
    vm: PasskeysViewModel = hiltViewModel()
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var showCreate by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<PasskeyInfoDto?>(null) }

    LaunchedEffect(ui.message) {
        ui.message?.let { snackbar.showSnackbar(it.asString(context)); vm.clearMessage() }
    }

    PasskeysContent(ui, onRetry = vm::refresh, onCreate = { showCreate = true }, onRemove = { removing = it },
        onBack = onBack, snackbarHostState = snackbar)

    if (showCreate) {
        CreatePasskeyDialog(
            onDismiss = { showCreate = false },
            onCreate = { label ->
                // O diálogo fecha para o prompt do sistema aparecer; o progresso fica na tela.
                vm.registerPasskey(context, label)
                showCreate = false
            }
        )
    }

    // Remover sem volta: confirmação com o nome (decisão 5.5 #3; antes um toque na lixeira).
    removing?.let { passkey ->
        ConfirmDialog(
            title = stringResource(R.string.passkeys_remove_title, passkey.label),
            message = stringResource(R.string.passkeys_remove_body),
            confirmLabel = stringResource(R.string.passkeys_revoke),
            onConfirm = { vm.revoke(passkey.id); removing = null },
            onDismiss = { removing = null }
        )
    }
}

/**
 * Chaves de acesso (redesign visual, Fase 6): o mesmo modelo das chaves de API — botão no topo
 * (sem FAB cobrindo o último item), estados com "Tentar novamente" e vazio com ação, datas "há X"
 * e "Remover" com confirmação. O cadastro mostra progresso na tela (antes o diálogo fechava e o
 * progresso nunca aparecia).
 */
@Composable
internal fun PasskeysContent(
    ui: PasskeysUiState,
    onRetry: () -> Unit,
    onCreate: () -> Unit,
    onRemove: (PasskeyInfoDto) -> Unit,
    onBack: () -> Unit,
    snackbarHostState: SnackbarHostState? = null,
    nowMs: Long = System.currentTimeMillis()
) {
    val context = LocalContext.current
    Page(title = stringResource(R.string.passkeys_title), onBack = onBack, snackbarHostState = snackbarHostState) {
        Text(stringResource(R.string.passkeys_intro), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (ui.registering) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                Text(stringResource(R.string.passkeys_registering), style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
        when {
            ui.loading -> ListSkeleton(rows = 2)
            ui.error != null -> ErrorState(ui.error.asString(), onRetry, detail = ui.errorDetail)
            ui.passkeys.isEmpty() -> EmptyState(
                title = stringResource(R.string.passkeys_empty_title),
                body = stringResource(R.string.passkeys_none_yet),
                icon = Icons.Outlined.Fingerprint,
                actionLabel = stringResource(R.string.passkeys_new_passkey),
                onAction = onCreate
            )
            else -> {
                Button(onClick = onCreate, enabled = !ui.registering, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Add, null, modifier = Modifier.padding(end = Spacing.xs))
                    Text(stringResource(R.string.passkeys_new_passkey))
                }
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    ui.passkeys.forEach { passkey ->
                        val created = ServerDates.toEpochMs(passkey.createdAt)
                            ?.let { stringResource(R.string.passkeys_created_ago, context.formatElapsed(it, nowMs)) }
                        val used = passkey.lastUsedAt?.let(ServerDates::toEpochMs)
                            ?.let { stringResource(R.string.passkeys_last_used_ago, context.formatElapsed(it, nowMs)) }
                            ?: stringResource(R.string.passkeys_never_used)
                        ItemCard(
                            title = passkey.label,
                            meta = listOfNotNull(created, used).joinToString("\n"),
                            actions = {
                                TextButton(onClick = { onRemove(passkey) },
                                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                                    Text(stringResource(R.string.passkeys_revoke))
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CreatePasskeyDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var label by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.passkeys_new_passkey)) },
        text = {
            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text(stringResource(R.string.passkeys_label_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = { TextButton(onClick = { onCreate(label) }) { Text(stringResource(R.string.passkeys_create)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) } }
    )
}
