package com.gluview.app.ui.screen.apikeys

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gluview.app.R
import com.gluview.app.data.remote.dto.ApiKeyInfoDto
import com.gluview.app.ui.components.kit.CheckboxRow
import com.gluview.app.ui.components.kit.ConfirmDialog
import com.gluview.app.ui.components.kit.EmptyState
import com.gluview.app.ui.components.kit.ErrorState
import com.gluview.app.ui.components.kit.GluCard
import com.gluview.app.ui.components.kit.ItemCard
import com.gluview.app.ui.components.kit.ListSkeleton
import com.gluview.app.ui.components.kit.Page
import com.gluview.app.ui.components.kit.StatusBadge
import com.gluview.app.ui.components.kit.Tone
import com.gluview.app.ui.theme.Spacing
import com.gluview.app.util.ServerDates
import com.gluview.app.util.formatElapsed

@Composable
fun ApiKeysScreen(
    onBack: () -> Unit,
    vm: ApiKeysViewModel = hiltViewModel()
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    var showCreate by remember { mutableStateOf(false) }
    var revoking by remember { mutableStateOf<ApiKeyInfoDto?>(null) }

    LaunchedEffect(ui.message) {
        ui.message?.let { snackbar.showSnackbar(it.asString(context)); vm.clearMessage() }
    }

    ApiKeysContent(ui, onRetry = vm::refresh, onCreate = { showCreate = true }, onRevoke = { revoking = it },
        onBack = onBack, snackbarHostState = snackbar)

    if (showCreate) {
        CreateKeyDialog(
            onDismiss = { showCreate = false },
            onCreate = { name, read, write -> vm.create(name, read, write); showCreate = false }
        )
    }

    // Revogar sem volta: confirmação com o nome da chave (decisão 5.5 #3; antes um toque na lixeira).
    revoking?.let { key ->
        ConfirmDialog(
            title = stringResource(R.string.apikeys_revoke_title, key.name),
            message = stringResource(R.string.apikeys_revoke_body),
            confirmLabel = stringResource(R.string.apikeys_revoke),
            onConfirm = { vm.revoke(key.id); revoking = null },
            onDismiss = { revoking = null }
        )
    }

    ui.createdKey?.let { created ->
        val clipboard = LocalClipboardManager.current
        AlertDialog(
            onDismissRequest = vm::dismissCreated,
            title = { Text(stringResource(R.string.apikeys_created_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Text(stringResource(R.string.apikeys_created_copy_now), style = MaterialTheme.typography.bodyMedium)
                    // Selecionável (antes só dava para copiar pelo botão).
                    GluCard {
                        SelectionContainer {
                            Text(created.key, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(created.key))
                    vm.dismissCreated()
                }) {
                    Icon(Icons.Default.ContentCopy, null, modifier = Modifier.padding(end = 4.dp))
                    Text(stringResource(R.string.apikeys_copy_and_close))
                }
            },
            dismissButton = { TextButton(onClick = vm::dismissCreated) { Text(stringResource(R.string.action_close)) } }
        )
    }
}

/** Nome da permissão (antes o escopo cru: "glucose:read"). */
@Composable
private fun scopeLabel(scope: String): String = when (scope) {
    "glucose:read"  -> stringResource(R.string.apikeys_read_glucose)
    "glucose:write" -> stringResource(R.string.apikeys_write_glucose)
    else            -> scope
}

/**
 * Chaves de API (redesign visual, Fase 6): botão "Nova chave" no topo (antes um FAB que cobria a
 * lixeira da última chave); carregando, erro com "Tentar novamente" e vazio com ação; cada chave
 * com as permissões pelo nome, criada/último uso "há X" e "Revogar" com confirmação.
 */
@Composable
internal fun ApiKeysContent(
    ui: ApiKeysUiState,
    onRetry: () -> Unit,
    onCreate: () -> Unit,
    onRevoke: (ApiKeyInfoDto) -> Unit,
    onBack: () -> Unit,
    snackbarHostState: SnackbarHostState? = null,
    nowMs: Long = System.currentTimeMillis()
) {
    val context = LocalContext.current
    Page(title = stringResource(R.string.apikeys_title), onBack = onBack, snackbarHostState = snackbarHostState) {
        Text(stringResource(R.string.apikeys_intro), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        when {
            ui.loading -> ListSkeleton(rows = 2)
            ui.error != null -> ErrorState(ui.error.asString(), onRetry, detail = ui.errorDetail)
            ui.keys.isEmpty() -> EmptyState(
                title = stringResource(R.string.apikeys_empty_title),
                body = stringResource(R.string.apikeys_none_yet),
                icon = Icons.Outlined.VpnKey,
                actionLabel = stringResource(R.string.apikeys_new_key),
                onAction = onCreate
            )
            else -> {
                Button(onClick = onCreate, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Add, null, modifier = Modifier.padding(end = Spacing.xs))
                    Text(stringResource(R.string.apikeys_new_key))
                }
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    ui.keys.forEach { key ->
                        val scopes = if (key.scopes.isEmpty()) stringResource(R.string.apikeys_no_scope)
                                     else key.scopes.map { scopeLabel(it) }.joinToString(", ")
                        val dates = listOfNotNull(
                            key.createdAt?.let(ServerDates::toEpochMs)?.let { stringResource(R.string.apikeys_created_ago, context.formatElapsed(it, nowMs)) },
                            key.lastUsedAt?.let(ServerDates::toEpochMs)?.let { stringResource(R.string.apikeys_last_used_ago, context.formatElapsed(it, nowMs)) }
                        )
                        ItemCard(
                            title = key.name,
                            meta = (listOf(stringResource(R.string.apikeys_prefix_scopes, key.prefix, scopes)) + dates).joinToString("\n"),
                            actions = {
                                if (key.revoked) StatusBadge(stringResource(R.string.apikeys_revoked), tone = Tone.Danger)
                                else TextButton(onClick = { onRevoke(key) },
                                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                                    Text(stringResource(R.string.apikeys_revoke))
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
private fun CreateKeyDialog(onDismiss: () -> Unit, onCreate: (String, Boolean, Boolean) -> Unit) {
    var name by remember { mutableStateOf("") }
    var read by remember { mutableStateOf(true) }
    var write by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.apikeys_new_key)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true,
                    label = { Text(stringResource(R.string.apikeys_name_description)) }, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.apikeys_permissions), style = MaterialTheme.typography.labelLarge)
                CheckboxRow(stringResource(R.string.apikeys_read_glucose), read, { read = it })
                CheckboxRow(stringResource(R.string.apikeys_write_glucose), write, { write = it })
            }
        },
        confirmButton = { TextButton(onClick = { onCreate(name, read, write) }) { Text(stringResource(R.string.apikeys_create)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) } }
    )
}
