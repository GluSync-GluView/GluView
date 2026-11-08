package com.gluview.app.ui.screen.watchface

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material.icons.outlined.Watch
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gluview.app.R
import com.gluview.app.data.local.entity.WatchFaceEntity
import com.gluview.app.ui.components.kit.AppDialog
import com.gluview.app.ui.components.kit.AppTopBar
import com.gluview.app.ui.components.kit.ConfirmDialog
import com.gluview.app.ui.components.kit.EmptyState
import com.gluview.app.ui.components.kit.GluCard
import com.gluview.app.ui.components.kit.GluSection
import com.gluview.app.ui.components.kit.ListSkeleton
import com.gluview.app.ui.components.kit.NavRow
import com.gluview.app.ui.components.kit.StatusBadge
import com.gluview.app.ui.components.kit.Tone
import com.gluview.app.ui.theme.Spacing
import com.gluview.app.util.formatElapsed
import com.gluview.app.watchface.WatchFaceDesign
import com.gluview.app.watchface.WearableSpec
import com.gluview.app.watchface.applicableWearableTargets
import com.gluview.app.watchface.toDesign
import com.gluview.app.wear.WearStatus

/** Ações do gerenciador, separadas para a captura sem ViewModel. */
internal class ManagerActions(
    val onBack: () -> Unit = {},
    val onCreate: () -> Unit = {},
    val onEdit: (Long) -> Unit = {},
    val onMiBand: () -> Unit = {},
    val onWear: () -> Unit = {},
    val onStore: () -> Unit = {},
    val onDuplicate: (WatchFaceEntity) -> Unit = {},
    val onDelete: (WatchFaceEntity) -> Unit = {},
    val onApply: (WatchFaceEntity, String) -> Unit = { _, _ -> },
    val onPublish: (WatchFaceEntity) -> Unit = {}
)

@Composable
fun WatchFaceManagerScreen(
    onBack: () -> Unit,
    onCreate: () -> Unit,
    onEdit: (Long) -> Unit,
    onMiBand: () -> Unit,
    onStore: () -> Unit,
    vm: WatchFaceManagerViewModel = hiltViewModel()
) {
    val faces by vm.watchFaces.collectAsStateWithLifecycle()
    val runtime by vm.runtime.collectAsStateWithLifecycle()
    val applyMessage by vm.applyMessage.collectAsStateWithLifecycle()
    val applyingId by vm.applyingFaceId.collectAsStateWithLifecycle()
    val wearStatus by vm.wearStatus.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val configureLabel = stringResource(R.string.watchface_configure)

    LaunchedEffect(applyMessage) {
        applyMessage?.let { msg ->
            vm.clearApplyMessage()
            val setup = msg.action == WatchFaceMessageAction.SETUP_MIBAND
            // "Precisa configurar a Mi Band" leva à tela de pareamento.
            val result = snackbar.showSnackbar(msg.text.asString(context), actionLabel = if (setup) configureLabel else null)
            if (setup && result == SnackbarResult.ActionPerformed) onMiBand()
        }
    }

    WatchFaceManagerContent(faces, runtime, applyingId, snackbar, ManagerActions(
        onBack = onBack, onCreate = onCreate, onEdit = onEdit, onMiBand = onMiBand, onStore = onStore,
        onDuplicate = vm::duplicate, onDelete = vm::delete,
        onApply = { face, target -> vm.requestApply(face, target) },
        onPublish = vm::publish,
        onWear = vm::sendToWatch
    ), wearStatus)
}

/**
 * Wearables / visores (redesign visual, Fase 9): explicação no topo e "Criar visor" em largura
 * toda (o FAB cobria os botões do último card); Mi Band e Loja como linhas visíveis (a loja era só
 * um ícone na barra); card do visor com a prévia, "Wearable · atualizado há X", status do último
 * envio, "Enviar para a pulseira" como ação principal com progresso (antes um ícone de relógio, e
 * "Publicar" com o avião de papel que parecia "enviar") e Publicar/Excluir no menu ⋮; publicar
 * pede confirmação (decisão 5.6 #4); carregando sem piscar "nenhum visor"; vazio que explica.
 */
@Composable
internal fun WatchFaceManagerContent(
    faces: List<WatchFaceEntity>?,
    runtime: WatchFaceRuntimeData,
    applyingId: Long?,
    snackbarHostState: SnackbarHostState?,
    actions: ManagerActions,
    wearStatus: WearStatus? = null
) {
    var deleteTarget by remember { mutableStateOf<WatchFaceEntity?>(null) }
    var applyTarget by remember { mutableStateOf<WatchFaceEntity?>(null) }
    var publishTarget by remember { mutableStateOf<WatchFaceEntity?>(null) }

    Scaffold(
        topBar = { AppTopBar(stringResource(R.string.watchface_manager_title), actions.onBack) },
        snackbarHost = { if (snackbarHostState != null) SnackbarHost(snackbarHostState) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = Spacing.gutter, vertical = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Text(stringResource(R.string.wf_manager_intro), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = actions.onCreate, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Icon(Icons.Default.Add, null, modifier = Modifier.padding(end = Spacing.xs))
                        Text(stringResource(R.string.wf_create))
                    }
                }
            }
            item {
                GluSection(stringResource(R.string.wf_device_section)) {
                    GluCard {
                        NavRow(stringResource(R.string.miband_title), actions.onMiBand, icon = Icons.Outlined.Watch,
                            subtitle = stringResource(R.string.watchface_device_card_desc))
                        // Fase 8 (plano 14): relógio Wear OS — toque reenvia a leitura atual.
                        NavRow(stringResource(R.string.wear_row_title), actions.onWear, icon = Icons.Outlined.Watch,
                            subtitle = wearStatusText(wearStatus))
                        NavRow(stringResource(R.string.watchface_store_title), actions.onStore, icon = Icons.Outlined.Storefront,
                            subtitle = stringResource(R.string.wf_store_row_desc))
                    }
                }
            }
            item {
                Text(
                    if (faces.isNullOrEmpty()) stringResource(R.string.wf_your_faces)
                    else stringResource(R.string.wf_your_faces_count, faces.size),
                    style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.semantics { heading() }
                )
            }
            when {
                faces == null -> item { ListSkeleton(rows = 2) }
                faces.isEmpty() -> item {
                    EmptyState(
                        title = stringResource(R.string.watchface_manager_none_yet),
                        body = stringResource(R.string.wf_empty_body),
                        icon = Icons.Outlined.Watch,
                        actionLabel = stringResource(R.string.wf_create),
                        onAction = actions.onCreate
                    )
                }
                else -> items(faces, key = { it.id }) { face ->
                    WatchFaceItem(
                        face = face,
                        runtime = runtime,
                        applying = applyingId == face.id,
                        anyApplying = applyingId != null,
                        onEdit = { actions.onEdit(face.id) },
                        onDuplicate = { actions.onDuplicate(face) },
                        onDelete = { deleteTarget = face },
                        onApply = { applyTarget = face },
                        onPublish = { publishTarget = face }
                    )
                }
            }
        }
    }

    deleteTarget?.let { face ->
        ConfirmDialog(
            title = stringResource(R.string.watchface_delete_title),
            message = stringResource(R.string.watchface_delete_confirm, face.name),
            confirmLabel = stringResource(R.string.watchface_delete_button),
            onConfirm = { actions.onDelete(face); deleteTarget = null },
            onDismiss = { deleteTarget = null }
        )
    }
    publishTarget?.let { face ->
        ConfirmDialog(
            title = stringResource(R.string.wf_publish_title, face.name),
            message = stringResource(R.string.wf_publish_body),
            confirmLabel = stringResource(R.string.watchface_publish),
            onConfirm = { actions.onPublish(face); publishTarget = null },
            onDismiss = { publishTarget = null },
            destructive = false
        )
    }
    applyTarget?.let { face ->
        val design = remember(face) { face.toDesign() }
        WearablePickerDialog(
            title = stringResource(R.string.watchface_apply_to_wearable),
            targets = remember(design) { design.applicableWearableTargets() },
            initial = design.wearable.id,
            confirmLabel = stringResource(R.string.wf_send_to_band),
            onDismiss = { applyTarget = null },
            onConfirm = { target -> actions.onApply(face, target); applyTarget = null }
        )
    }
}

@Composable
private fun WatchFaceItem(
    face: WatchFaceEntity,
    runtime: WatchFaceRuntimeData,
    applying: Boolean,
    anyApplying: Boolean,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onApply: () -> Unit,
    onPublish: () -> Unit
) {
    // Decodifica o JSON uma vez por visor (antes a cada recomposição, e o runtime recompõe a cada segundo).
    val design = remember(face) { face.toDesign() }
    val canSend = remember(design) { design.applicableWearableTargets().isNotEmpty() }
    var menu by remember { mutableStateOf(false) }
    GluCard {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = MaterialTheme.shapes.small,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth()) {
            WatchFacePreview(
                wearable = design.wearable,
                components = design.components,
                runtime = runtime,
                modifier = Modifier.fillMaxWidth().height(190.dp).padding(Spacing.sm),
                contentDescription = stringResource(R.string.wf_preview_of, face.name, wearableLabel(design.wearable))
            )
        }
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(face.name, style = MaterialTheme.typography.titleMedium)
                Text(wearableLabel(design.wearable), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                // "Atualizado" e não "Editado em": o envio também mexe nessa data (L27).
                Text(stringResource(R.string.wf_updated, formatElapsed(face.updatedAt, runtime.nowMs)),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Default.MoreVert, stringResource(R.string.wf_more_actions, face.name))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.watchface_publish)) }, onClick = { menu = false; onPublish() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.watchface_delete_button), color = MaterialTheme.colorScheme.error) },
                        onClick = { menu = false; onDelete() })
                }
            }
        }
        applyStateBadge(face.applyState)?.let { (tone, res) -> StatusBadge(stringResource(res), tone = tone) }
        // O do visor enviando continua habilitado (só ignora o toque) para o progresso ficar visível.
        Button(onClick = { if (!applying) onApply() }, enabled = canSend && (applying || !anyApplying),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            if (applying) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
                Text(stringResource(R.string.wf_sending), modifier = Modifier.padding(start = Spacing.xs))
            } else {
                Icon(Icons.Default.Watch, null, modifier = Modifier.padding(end = Spacing.xs).size(18.dp))
                Text(stringResource(R.string.wf_send_to_band))
            }
        }
        if (!canSend) {
            Text(stringResource(R.string.wf_no_auto_send), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            TextButton(onClick = onEdit, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Edit, null, modifier = Modifier.padding(end = Spacing.xs).size(18.dp))
                Text(stringResource(R.string.watchface_edit_button))
            }
            TextButton(onClick = onDuplicate, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.ContentCopy, null, modifier = Modifier.padding(end = Spacing.xs).size(18.dp))
                Text(stringResource(R.string.watchface_duplicate))
            }
        }
    }
}

/** Último envio gravado (applyState existia e nenhuma tela mostrava). */
private fun applyStateBadge(state: String): Pair<Tone, Int>? = when (state) {
    "success" -> Tone.Success to R.string.wf_state_success
    "needs_setup" -> Tone.Warning to R.string.wf_state_needs_setup
    "failure" -> Tone.Danger to R.string.wf_state_failure
    "pending" -> Tone.Info to R.string.wf_sending
    else -> null
}

/**
 * Escolha do wearable, comum ao envio e à loja: linha inteira selecionável como rádio (antes dois
 * pontos de foco e sem papel de rádio), rótulo "Nome · dimensões". Sem nenhum alvo, explica que
 * não há envio automático e só oferece Fechar (antes abria, confirmava e só então avisava).
 */
@Composable
internal fun WearablePickerDialog(
    title: String,
    targets: List<WearableSpec>,
    initial: String?,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    if (targets.isEmpty()) {
        AppDialog(title = title, body = stringResource(R.string.wf_no_auto_send), onDismiss = onDismiss)
        return
    }
    var selected by remember(targets) { mutableStateOf(targets.firstOrNull { it.id == initial }?.id ?: targets.first().id) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                if (targets.size > 1) {
                    Text(stringResource(R.string.watchface_apply_choose_wearable), style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = Spacing.xs))
                    targets.forEach { w ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .selectable(selected = w.id == selected, role = Role.RadioButton, onClick = { selected = w.id }),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = w.id == selected, onClick = null)
                            Text(wearableLabel(w), modifier = Modifier.padding(start = Spacing.xs))
                        }
                    }
                } else {
                    Text(stringResource(R.string.wf_send_to_one, wearableLabel(targets.first())), style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(selected) }) { Text(confirmLabel, textAlign = TextAlign.End) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } }
    )
}

@Composable
private fun wearStatusText(status: WearStatus?): String = when (status) {
    null -> stringResource(R.string.wear_status_checking)
    WearStatus.Unavailable -> stringResource(R.string.wear_status_unavailable)
    WearStatus.NoWatch -> stringResource(R.string.wear_status_no_watch)
    is WearStatus.AppMissing -> stringResource(R.string.wear_status_app_missing, status.watchName)
    is WearStatus.Ready -> stringResource(R.string.wear_status_ready, status.watchName)
}
