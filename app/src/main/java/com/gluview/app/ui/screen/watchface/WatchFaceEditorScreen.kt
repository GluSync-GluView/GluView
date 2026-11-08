package com.gluview.app.ui.screen.watchface

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FlipToBack
import androidx.compose.material.icons.filled.FlipToFront
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gluview.app.R
import com.gluview.app.ui.components.kit.AppModalBottomSheet
import com.gluview.app.ui.components.kit.AppTopBar
import com.gluview.app.ui.components.kit.CheckboxRow
import com.gluview.app.ui.components.kit.ConfirmDialog
import com.gluview.app.ui.components.kit.Fact
import com.gluview.app.ui.components.kit.FactList
import com.gluview.app.ui.components.kit.GluCallout
import com.gluview.app.ui.components.kit.GluCard
import com.gluview.app.ui.components.kit.GluSection
import com.gluview.app.ui.components.kit.SkeletonBlock
import com.gluview.app.ui.components.kit.StatusBadge
import com.gluview.app.ui.components.kit.SwitchRow
import com.gluview.app.ui.components.kit.Tone
import com.gluview.app.ui.theme.Spacing
import com.gluview.app.util.formatDateTime
import com.gluview.app.watchface.ChartComponentKind
import com.gluview.app.watchface.ComponentKind
import com.gluview.app.watchface.ComponentRegistry
import com.gluview.app.watchface.FixedTextKind
import com.gluview.app.watchface.WatchFaceComponent
import com.gluview.app.watchface.WatchFaceComponentDefinition
import com.gluview.app.watchface.WatchFaceComponentLibrary
import com.gluview.app.watchface.WatchFacePreset
import com.gluview.app.watchface.WatchFacePresets
import com.gluview.app.watchface.WatchFaceTextAlign
import com.gluview.app.watchface.WearableCatalog
import com.gluview.app.watchface.WearableDefinitionCatalog
import com.gluview.app.watchface.WearableKind
import com.gluview.app.watchface.WearableShape
import com.gluview.app.watchface.WearableSpec
import java.text.NumberFormat
import kotlin.math.roundToInt

/** Ações do editor (todas do [WatchFaceEditorViewModel]), separadas para a captura sem ViewModel. */
internal class EditorActions(
    val onBack: () -> Unit = {},
    val onUndo: () -> Unit = {},
    val onRedo: () -> Unit = {},
    val onSave: (String) -> Unit = {},
    val onSelectWearable: (WearableSpec) -> Unit = {},
    val onCustomWearable: (Int, Int, WearableKind, WearableShape) -> Unit = { _, _, _, _ -> },
    val onToggleCompatible: (String) -> Unit = {},
    val onApplyPreset: (WatchFacePreset) -> Unit = {},
    val onToggleGrid: () -> Unit = {},
    val onToggleGuides: () -> Unit = {},
    val onToggleSnap: () -> Unit = {},
    val onSimulation: (Boolean) -> Unit = {},
    val onSelect: (String) -> Unit = {},
    val onTransformStart: () -> Unit = {},
    val onTransformEnd: () -> Unit = {},
    val onMove: (String, Float, Float) -> Unit = { _, _, _ -> },
    val onResize: (String, Float, Float) -> Unit = { _, _, _ -> },
    val onDelete: (String) -> Unit = {},
    val onDuplicate: () -> Unit = {},
    val onRemoveSelected: () -> Unit = {},
    val onChangeZ: (Int) -> Unit = {},
    val onAdd: (WatchFaceComponentDefinition) -> Unit = {}
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatchFaceEditorScreen(
    onBack: () -> Unit,
    vm: WatchFaceEditorViewModel = hiltViewModel()
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val runtime by vm.runtime.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var showProperties by remember { mutableStateOf(false) }
    var showSimulation by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }

    val undoLabel = stringResource(R.string.watchface_undo)
    LaunchedEffect(state.message) {
        state.message?.let { msg ->
            vm.clearMessage()
            val undo = msg.action == WatchFaceMessageAction.UNDO
            val result = snackbarHostState.showSnackbar(msg.text.asString(context),
                actionLabel = if (undo) undoLabel else null,
                duration = if (undo) SnackbarDuration.Long else SnackbarDuration.Short)
            if (undo && result == SnackbarResult.ActionPerformed) vm.undo()
        }
    }

    // Sair com mudança não salva pede confirmação (decisão 5.6 #4), na seta e no voltar do sistema.
    val requestBack = { if (state.dirty) confirmDiscard = true else onBack() }
    BackHandler(enabled = state.dirty) { confirmDiscard = true }

    WatchFaceEditorContent(
        state = state,
        runtime = runtime,
        snackbarHostState = snackbarHostState,
        onAdjust = { showProperties = true },
        onAdjustSimulation = { showSimulation = true },
        actions = EditorActions(
            onBack = requestBack,
            onUndo = vm::undo,
            onRedo = vm::redo,
            onSave = vm::save,
            onSelectWearable = vm::selectWearable,
            onCustomWearable = vm::selectCustomWearable,
            onToggleCompatible = vm::toggleCompatibleWearable,
            onApplyPreset = vm::applyPreset,
            onToggleGrid = vm::toggleGrid,
            onToggleGuides = vm::toggleGuides,
            onToggleSnap = vm::toggleSnap,
            onSimulation = { vm.updateSimulation(enabled = it) },
            onSelect = vm::selectComponent,
            onTransformStart = vm::beginUserAction,
            onTransformEnd = vm::endUserAction,
            onMove = vm::moveComponentTo,
            onResize = vm::resizeComponentTo,
            onDelete = vm::deleteComponent,
            onDuplicate = vm::duplicateSelected,
            onRemoveSelected = vm::removeSelected,
            onChangeZ = vm::changeZ,
            onAdd = vm::addComponent
        )
    )

    if (showProperties && state.selectedComponent != null) {
        // Meia altura: o visor continua visível acima enquanto se ajusta (antes a folha cobria tudo).
        AppModalBottomSheet(onDismissRequest = { showProperties = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)) {
            PropertiesSheetContent(state = state, vm = vm, onDone = { showProperties = false })
        }
    }
    if (showSimulation) {
        AppModalBottomSheet(onDismissRequest = { showSimulation = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)) {
            SimulationSheetContent(simulation = state.simulation, vm = vm, onDone = { showSimulation = false })
        }
    }
    if (confirmDiscard) {
        ConfirmDialog(
            title = stringResource(R.string.wf_discard_title),
            message = stringResource(R.string.wf_discard_body),
            confirmLabel = stringResource(R.string.wf_discard_confirm),
            onConfirm = { confirmDiscard = false; onBack() },
            onDismiss = { confirmDiscard = false }
        )
    }
    state.applyPromptFaceId?.let {
        ConfirmDialog(
            title = stringResource(R.string.watchface_apply_prompt_title),
            message = stringResource(R.string.watchface_apply_prompt_body),
            confirmLabel = stringResource(R.string.wf_send_to_band),
            onConfirm = vm::applyDesign,
            onDismiss = vm::dismissApplyPrompt,
            destructive = false
        )
    }
}

/**
 * Editor de visor (redesign visual, Fase 9): o visor no topo (antes depois de 6 blocos), com
 * moldura e altura pela proporção do wearable (antes caixa fixa de 430dp); título = nome do visor
 * e o wearable embaixo; "Salvar" em largura toda numa barra fixa (antes um ícone); componente
 * selecionado com ações nomeadas (camada com FlipToBack/FlipToFront, não setas de recolher);
 * "Adicionar componente" e "Usar um modelo pronto" abrem folhas com tudo à vista (antes 29 chips e
 * cards com descrição cortada em rolagem lateral); lista de Camadas para selecionar componente
 * pequeno ou pelo TalkBack; "Dados de teste" e "Exibição" em linhas de Switch; wearable e
 * compatibilidade numa seção recolhível no fim; selo "Dados simulados" junto do visor.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun WatchFaceEditorContent(
    state: WatchFaceEditorUiState,
    runtime: WatchFaceRuntimeData,
    actions: EditorActions,
    onAdjust: () -> Unit,
    onAdjustSimulation: () -> Unit,
    snackbarHostState: SnackbarHostState? = null
) {
    var showSave by remember { mutableStateOf(false) }
    var showAdd by remember { mutableStateOf(false) }
    var showPresets by remember { mutableStateOf(false) }
    val design = state.design
    val title = design.name.ifBlank { stringResource(if (design.id == 0L) R.string.watchface_new else R.string.watchface_edit) }

    Scaffold(
        // Desfazer/refazer junto do Salvar, embaixo: na barra de cima espremiam o nome do visor.
        topBar = { AppTopBar(title, actions.onBack) },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = Spacing.gutter, vertical = Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                ) {
                    IconButton(onClick = actions.onUndo, enabled = state.canUndo) {
                        Icon(Icons.AutoMirrored.Filled.Undo, stringResource(R.string.watchface_undo))
                    }
                    IconButton(onClick = actions.onRedo, enabled = state.canRedo) {
                        Icon(Icons.AutoMirrored.Filled.Redo, stringResource(R.string.watchface_redo))
                    }
                    Button(onClick = { showSave = true }, enabled = !state.loading,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.settings_save))
                    }
                }
            }
        },
        snackbarHost = { if (snackbarHostState != null) SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.gutter, vertical = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            // Quebra em vez de espremer o nome do wearable ao lado do selo (fonte grande).
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                Text(wearableLabel(design.wearable), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterVertically))
                if (state.simulation.enabled) StatusBadge(stringResource(R.string.wf_simulated_badge), tone = Tone.Info)
            }

            if (state.loading) {
                SkeletonBlock(Modifier.fillMaxWidth(), height = 320.dp)
                return@Column
            }

            EditorCanvas(state, runtime, actions)
            // Dica abaixo do visor, em cor de texto (sobre a moldura clara o branco sumia).
            if (design.components.isEmpty()) {
                Text(stringResource(R.string.wf_empty_canvas), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth())
            }

            val overlapIds = remember(design.components) { overlappingComponentIds(design.components) }
            if (overlapIds.isNotEmpty()) {
                GluCallout(stringResource(R.string.wf_overlap_count, overlapIds.size), tone = Tone.Warning)
            }

            SelectedActionBar(state.selectedComponent, onAdjust, actions)

            Button(onClick = { showAdd = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Icon(Icons.Default.Add, null, modifier = Modifier.padding(end = Spacing.xs))
                Text(stringResource(R.string.wf_add_component))
            }
            OutlinedButton(onClick = { showPresets = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Dashboard, null, modifier = Modifier.padding(end = Spacing.xs))
                Text(stringResource(R.string.wf_use_template))
            }

            if (design.components.isNotEmpty()) LayersSection(design.components, state.selectedId, actions.onSelect)

            GluSection(stringResource(R.string.wf_test_data_section)) {
                GluCard {
                    SwitchRow(stringResource(R.string.watchface_simulation_toggle), state.simulation.enabled, actions.onSimulation,
                        description = stringResource(R.string.watchface_simulation_hint))
                    if (state.simulation.enabled) {
                        OutlinedButton(onClick = onAdjustSimulation, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.wf_adjust_test_data))
                        }
                    }
                }
            }

            GluSection(stringResource(R.string.wf_display_section)) {
                GluCard {
                    SwitchRow(stringResource(R.string.watchface_grid), state.gridEnabled, { actions.onToggleGrid() })
                    SwitchRow(stringResource(R.string.watchface_guides), state.guidesEnabled, { actions.onToggleGuides() })
                    // Encaixe só vale com a grade ligada (regra do ViewModel), então fica desligado sem ela.
                    SwitchRow(stringResource(R.string.watchface_snap), state.snapEnabled, { actions.onToggleSnap() },
                        enabled = state.gridEnabled)
                }
            }

            WearableSection(design.wearable, design.compatibleWearableIds, actions)
        }
    }

    if (showAdd) {
        AppModalBottomSheet(onDismissRequest = { showAdd = false }) {
            // Plano 16 (W4): visor de relógio Wear OS só aceita o que existe no Watch Face Format.
            val wearOs = com.gluview.app.watchface.apply.WearOsWatchFaceApplier.isWearOs(design.wearable.id)
            AddComponentSheet(runtime, wearOs) { def -> actions.onAdd(def); showAdd = false }
        }
    }
    if (showPresets) {
        AppModalBottomSheet(onDismissRequest = { showPresets = false }) {
            PresetsSheet(design.wearable, runtime) { preset -> actions.onApplyPreset(preset); showPresets = false }
        }
    }
    if (showSave) {
        SaveWatchFaceDialog(state, onDismiss = { showSave = false }, onSave = { actions.onSave(it); showSave = false })
    }
}

/** Visor com moldura neutra; altura pela proporção do wearable, até ~45% da tela (antes 430dp fixos). */
@Composable
private fun EditorCanvas(state: WatchFaceEditorUiState, runtime: WatchFaceRuntimeData, actions: EditorActions) {
    val wearable = state.design.wearable
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.45f).dp
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        BoxWithConstraints(Modifier.padding(Spacing.lg)) {
            val aspect = wearable.widthPx.toFloat() / wearable.heightPx.coerceAtLeast(1)
            val height = (maxWidth / aspect).coerceIn(80.dp, maxHeight)
            WatchFacePreview(
                wearable = wearable,
                components = state.design.components,
                runtime = runtime,
                options = WatchFaceCanvasOptions(showGrid = state.gridEnabled, showGuides = state.guidesEnabled,
                    snapToGrid = state.snapEnabled, editable = true),
                selectedId = state.selectedId,
                overlapIds = remember(state.design.components) { overlappingComponentIds(state.design.components) },
                onSelect = actions.onSelect,
                onTransformStart = actions.onTransformStart,
                onTransformEnd = actions.onTransformEnd,
                onMove = actions.onMove,
                onResize = actions.onResize,
                onDelete = actions.onDelete,
                modifier = Modifier.fillMaxWidth().height(height)
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SelectedActionBar(component: WatchFaceComponent?, onAdjust: () -> Unit, actions: EditorActions) {
    if (component == null) {
        Text(stringResource(R.string.watchface_tap_component_to_select), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    GluCard(selected = true) {
        Text(componentDisplayName(component), style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.semantics { heading() })
        Button(onClick = onAdjust, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Icon(Icons.Default.Edit, null, modifier = Modifier.padding(end = Spacing.xs).size(18.dp))
            Text(stringResource(R.string.wf_adjust_component))
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            OutlinedButton(onClick = actions.onDuplicate) {
                Icon(Icons.Default.ContentCopy, null, modifier = Modifier.padding(end = Spacing.xs).size(18.dp))
                Text(stringResource(R.string.watchface_duplicate))
            }
            OutlinedButton(onClick = { actions.onChangeZ(-1) }) {
                Icon(Icons.Default.FlipToBack, null, modifier = Modifier.padding(end = Spacing.xs).size(18.dp))
                Text(stringResource(R.string.watchface_send_backward))
            }
            OutlinedButton(onClick = { actions.onChangeZ(1) }) {
                Icon(Icons.Default.FlipToFront, null, modifier = Modifier.padding(end = Spacing.xs).size(18.dp))
                Text(stringResource(R.string.watchface_bring_forward))
            }
            OutlinedButton(onClick = actions.onRemoveSelected,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                Icon(Icons.Default.Delete, null, modifier = Modifier.padding(end = Spacing.xs).size(18.dp))
                Text(stringResource(R.string.watchface_remove))
            }
        }
    }
}

/** Uma linha por componente, de cima (frente) para baixo; tocar seleciona. */
@Composable
private fun LayersSection(components: List<WatchFaceComponent>, selectedId: String?, onSelect: (String) -> Unit) {
    GluSection(stringResource(R.string.wf_layers_section), count = components.size) {
        GluCard {
            components.sortedByDescending { it.zIndex }.forEach { c ->
                val selected = c.id == selectedId
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .selectable(selected = selected, role = Role.RadioButton, onClick = { onSelect(c.id) })
                        .padding(vertical = Spacing.xxs),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                ) {
                    Text(componentDisplayName(c), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f),
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    if (selected) Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

/** Wearable alvo e compatíveis, recolhíveis no fim (mudam pouco; antes ocupavam o topo da tela). */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun WearableSection(selected: WearableSpec, compatibleIds: List<String>, actions: EditorActions) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.wf_wearable_section), style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary, modifier = Modifier.semantics { heading() })
                Text(wearableLabel(selected), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                stringResource(if (expanded) R.string.common_hide else R.string.common_show))
        }
        if (!expanded) return@Column
        GluCard {
            var kind by remember(selected.kind) { mutableStateOf(selected.kind) }
            var menu by remember { mutableStateOf(false) }
            var widthText by remember(selected.widthPx) { mutableStateOf(selected.widthPx.toString()) }
            var heightText by remember(selected.heightPx) { mutableStateOf(selected.heightPx.toString()) }
            var shape by remember(selected.shape) { mutableStateOf(selected.shape) }

            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                WearableKind.entries.forEachIndexed { i, k ->
                    SegmentedButton(selected = kind == k, onClick = { kind = k },
                        shape = SegmentedButtonDefaults.itemShape(i, WearableKind.entries.size)) { Text(kindLabel(k)) }
                }
            }
            ExposedDropdownMenuBox(expanded = menu, onExpandedChange = { menu = it }) {
                OutlinedTextField(
                    value = wearableLabel(selected), onValueChange = {}, readOnly = true,
                    label = { Text(stringResource(R.string.watchface_catalog)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(menu) },
                    modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth()
                )
                ExposedDropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    WearableCatalog.items.filter { it.kind == kind }.forEach { w ->
                        DropdownMenuItem(text = { Text(wearableLabel(w)) }, onClick = { actions.onSelectWearable(w); menu = false })
                    }
                }
            }
            Text(stringResource(R.string.wf_custom_size), style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                OutlinedTextField(widthText, { widthText = it.filter(Char::isDigit).take(4) },
                    label = { Text(stringResource(R.string.watchface_width)) }, suffix = { Text("px") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                OutlinedTextField(heightText, { heightText = it.filter(Char::isDigit).take(4) },
                    label = { Text(stringResource(R.string.watchface_height)) }, suffix = { Text("px") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                WearableShape.entries.forEach { FilterChip(selected = shape == it, onClick = { shape = it }, label = { Text(shapeLabel(it)) }) }
            }
            OutlinedButton(
                onClick = { actions.onCustomWearable(widthText.toIntOrNull() ?: selected.widthPx, heightText.toIntOrNull() ?: selected.heightPx, kind, shape) },
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.watchface_use_manual_dimensions)) }
        }

        // Compatíveis ALÉM do principal, só do mesmo tipo (Fase 5 — WatchfaceCompatibility).
        val candidates = WearableCatalog.items.filter { it.kind == selected.kind && it.id != selected.id }
        if (candidates.isNotEmpty()) {
            GluSection(stringResource(R.string.watchface_compatible_wearables), description = stringResource(R.string.watchface_compatible_wearables_hint)) {
                GluCard {
                    candidates.forEach { w ->
                        val hasCompiler = WearableDefinitionCatalog.hasRealCompiler(w.id)
                        CheckboxRow(
                            title = "${w.brand} ${w.name}".trim(),
                            checked = w.id in compatibleIds,
                            onCheckedChange = { actions.onToggleCompatible(w.id) },
                            description = if (hasCompiler) stringResource(R.string.wf_px_size, w.widthPx, w.heightPx)
                                          else stringResource(R.string.wf_px_size_no_send, w.widthPx, w.heightPx)
                        )
                    }
                }
            }
        }
    }
}

/** Grupos da biblioteca (antes 29 chips numa rolagem lateral, a maioria fora da tela). */
private val COMPONENT_GROUPS: List<Pair<Int, List<String>>> = listOf(
    R.string.wf_group_time to listOf("time_hm", "time_hms", "seconds", "weekday", "date_dm", "date_dmy", "date_ymd", "date_md", "month", "year"),
    R.string.wf_group_glucose to listOf("glucose_current", "glucose_delta", "glucose_unit", "glucose_chart", "last_reading_time",
        "reading_age", "target_range", "hipo_alert", "hiper_alert"),
    R.string.wf_group_device to listOf("sensor_connected", "sensor_life", "phone_battery", "fixed_text"),
    R.string.wf_group_pump to listOf("insulin_iob", "insulin_basal_rate", "insulin_last_bolus", "insulin_reservoir",
        "insulin_pump_battery", "insulin_pump_connected")
)

@Composable
private fun AddComponentSheet(runtime: WatchFaceRuntimeData, wearOs: Boolean = false, onAdd: (WatchFaceComponentDefinition) -> Unit) {
    var kind by remember { mutableStateOf<ComponentKind?>(null) }
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.lg).padding(bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        val current = kind
        if (current == null) {
            Text(stringResource(R.string.wf_add_component), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            COMPONENT_GROUPS.forEach { (group, ids) ->
                Text(stringResource(group), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = Spacing.xs).semantics { heading() })
                ids.mapNotNull { id -> ComponentRegistry.all.firstOrNull { it.id == id } }.forEach { k ->
                    val available = !wearOs || com.gluview.app.watchface.wff.WffCompiler.isSupported(k.id)
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = available) { kind = k },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                    ) {
                        Text(stringResource(componentNameRes(k.id)), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        if (!available) StatusBadge(stringResource(R.string.wear_component_unavailable), tone = Tone.Neutral)
                        else if (isPumpComponent(k.id)) StatusBadge(stringResource(R.string.wf_pump_beta), tone = Tone.Neutral)
                    }
                }
            }
        } else {
            TextButton(onClick = { kind = null }) { Text(stringResource(R.string.login_back)) }
            Text(stringResource(componentNameRes(current.id)), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            Text(stringResource(R.string.watchface_tap_variation_to_add), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            // O gráfico só muda a largura entre "estilos" na pulseira: um cartão só (achado da Fase 0).
            val defs = WatchFaceComponentLibrary.definitionsFor(current).let { if (current is ChartComponentKind) it.take(1) else it }
            defs.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    pair.forEach { def ->
                        GluCard(Modifier.weight(1f), onClick = { onAdd(def) }) {
                            WatchFaceComponentThumbnail(def, runtime, Modifier.fillMaxWidth().height(64.dp))
                            Text(
                                if (current is ChartComponentKind) stringResource(R.string.wf_chart_3h)
                                else stringResource(R.string.wf_style_n, def.variation),
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                    if (pair.size == 1) Box(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun PresetsSheet(wearable: WearableSpec, runtime: WatchFaceRuntimeData, onApply: (WatchFacePreset) -> Unit) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.lg).padding(bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        Text(stringResource(R.string.watchface_ready_made_templates), style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() })
        Text(stringResource(R.string.wf_template_replaces), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        // O modelo de teste vai para o fim, com selo (decisão 5.6 #3).
        WatchFacePresets.all.sortedBy { isTestPreset(it) }.forEach { preset ->
            GluCard(onClick = { onApply(preset) }) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                    val components = remember(preset.id, wearable) { preset.build(wearable) }
                    WatchFacePreview(wearable, components, runtime, Modifier.width(72.dp).height(120.dp),
                        contentDescription = presetName(preset))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                            Text(presetName(preset), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f, fill = false))
                            if (isTestPreset(preset)) StatusBadge(stringResource(R.string.wf_test_badge), tone = Tone.Warning)
                        }
                        Text(presetDescription(preset), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SimulationSheetContent(simulation: SimulatedPreviewInput, vm: WatchFaceEditorViewModel, onDone: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.lg).padding(bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        Text(stringResource(R.string.wf_test_data_section), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        Text(stringResource(R.string.watchface_simulation_hint), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        SwitchRow(stringResource(R.string.watchface_simulation_toggle), simulation.enabled, { vm.updateSimulation(enabled = it) })
        if (simulation.enabled) {
            SliderField(stringResource(R.string.watchface_sim_glucose), simulation.glucoseMgDl.toFloat(), 40f, 300f,
                display = { "${it.roundToInt()} mg/dL" }) { vm.updateSimulation(glucoseMgDl = it.roundToInt()) }
            Text(stringResource(R.string.watchface_sim_arrow), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                SimulatedPreviewInput.ARROWS.forEach { arrow ->
                    val desc = stringResource(arrowDescriptionRes(arrow))
                    FilterChip(selected = simulation.arrow == arrow, onClick = { vm.updateSimulation(arrow = arrow) },
                        label = { Text(arrow) }, modifier = Modifier.semantics { contentDescription = desc })
                }
            }
            SliderField(stringResource(R.string.watchface_sim_delta), simulation.deltaMgDl.toFloat(), -30f, 30f,
                display = { "%+d".format(it.roundToInt()) }) { vm.updateSimulation(deltaMgDl = it.roundToInt()) }
            SwitchRow(stringResource(R.string.watchface_sim_sensor_connected), simulation.sensorConnected,
                { vm.updateSimulation(sensorConnected = it) })
            SliderField(stringResource(R.string.watchface_sim_sensor_age), simulation.sensorAgeDays.toFloat(), 0f, 30f,
                display = { it.roundToInt().toString() }) { vm.updateSimulation(sensorAgeDays = it.roundToInt()) }
            SliderField(stringResource(R.string.watchface_sim_battery), simulation.batteryPercent.toFloat(), 0f, 100f,
                display = { "${it.roundToInt()}%" }) { vm.updateSimulation(batteryPercent = it.roundToInt()) }
            SwitchRow(stringResource(R.string.watchface_sim_custom_time), simulation.useCustomTime, { vm.updateSimulation(useCustomTime = it) })
            if (simulation.useCustomTime) {
                SliderField(stringResource(R.string.watchface_sim_hour), simulation.hour.toFloat(), 0f, 23f,
                    display = { it.roundToInt().toString() }) { vm.updateSimulation(hour = it.roundToInt()) }
                SliderField(stringResource(R.string.watchface_sim_minute), simulation.minute.toFloat(), 0f, 59f,
                    display = { it.roundToInt().toString() }) { vm.updateSimulation(minute = it.roundToInt()) }
            }
        }
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.wf_done)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PropertiesSheetContent(state: WatchFaceEditorUiState, vm: WatchFaceEditorViewModel, onDone: () -> Unit) {
    val c = state.selectedComponent ?: return
    val isChart = ComponentRegistry.byId(c.typeId) is ChartComponentKind
    val percent = remember { NumberFormat.getPercentInstance() }
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.lg).padding(bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        Text(componentDisplayName(c), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })

        if (ComponentRegistry.byId(c.typeId) === FixedTextKind) {
            OutlinedTextField(
                value = c.customText.orEmpty(),
                onValueChange = { vm.updateSelected(customText = it) },
                label = { Text(stringResource(R.string.watchface_custom_text)) },
                placeholder = { Text(stringResource(R.string.watchface_custom_text_placeholder)) },
                modifier = Modifier.fillMaxWidth()
            )
        }

        SheetLabel(stringResource(R.string.watchface_position))
        SliderField("X", c.x, 0f, state.design.wearable.widthPx.toFloat(), display = { "${it.roundToInt()} px" }) { vm.updateSelected(x = it) }
        SliderField("Y", c.y, 0f, state.design.wearable.heightPx.toFloat(), display = { "${it.roundToInt()} px" }) { vm.updateSelected(y = it) }

        SheetLabel(stringResource(R.string.watchface_size))
        SliderField(stringResource(R.string.watchface_width), c.width, 20f, state.design.wearable.widthPx.toFloat(),
            display = { "${it.roundToInt()} px" }) { vm.updateSelected(width = it) }
        SliderField(stringResource(R.string.watchface_height), c.height, 16f, state.design.wearable.heightPx.toFloat(),
            display = { "${it.roundToInt()} px" }) { vm.updateSelected(height = it) }

        SheetLabel(stringResource(R.string.watchface_transform))
        SliderField(stringResource(R.string.watchface_scale), c.scale, 0.2f, 3f,
            display = { "×" + NumberFormat.getNumberInstance().apply { minimumFractionDigits = 2; maximumFractionDigits = 2 }.format(it) }) {
            vm.updateSelected(scale = it)
        }
        SliderField(stringResource(R.string.watchface_rotation), c.rotation, -180f, 180f, display = { "${it.roundToInt()}°" }) {
            vm.updateSelected(rotation = it)
        }
        // "Opacidade" em %: 100% = opaco (antes "Transparência" com 1,00 querendo dizer o contrário).
        SliderField(stringResource(R.string.wf_opacity), c.alpha, 0.1f, 1f, display = { percent.format(it) }) { vm.updateSelected(alpha = it) }

        SheetLabel(stringResource(R.string.watchface_color))
        if (isChart) {
            // A pulseira desenha o gráfico com as cores por zona; a cor escolhida não vale lá (decisão 5.6 #1).
            Text(stringResource(R.string.wf_chart_color_note), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            ColorPicker(selected = c.color, onColor = { vm.updateSelected(color = it) })
        }

        if (!isChart) {
            FontPicker(selected = c.font, onFont = { vm.updateSelected(font = it) })
            SheetLabel(stringResource(R.string.watchface_alignment))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                WatchFaceTextAlign.entries.forEach { align ->
                    FilterChip(selected = c.alignment == align.value, onClick = { vm.updateSelected(alignment = align.value) },
                        label = { Text(alignLabel(align)) })
                }
            }
        }
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.wf_done)) }
    }
}

@Composable
private fun SheetLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = Spacing.xs).semantics { heading() })
}

@Composable
private fun SliderField(label: String, value: Float, min: Float, max: Float, display: (Float) -> String, onChange: (Float) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(display(value), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(value = value.coerceIn(min, max), onValueChange = onChange, valueRange = min..max)
    }
}

/**
 * Cores com alvo de 48dp, borda em todas (preto e branco sumiam no fundo), anel + check na
 * escolhida (antes nada indicava) e nome lido como rádio pelo TalkBack.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorPicker(selected: Long, onColor: (Long) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xxs), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
        WATCHFACE_COLORS.forEach { (color, nameRes) ->
            val isSelected = selected == color
            val name = stringResource(nameRes)
            Box(
                Modifier.size(48.dp)
                    .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onColor(color) })
                    .semantics { contentDescription = name },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier.size(if (isSelected) 40.dp else 34.dp)
                        .then(if (isSelected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, CircleShape) else Modifier)
                        .padding(if (isSelected) 5.dp else 0.dp)
                        .background(Color(color), CircleShape)
                        .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    if (isSelected) Icon(Icons.Default.Check, null, modifier = Modifier.size(16.dp),
                        tint = if (color == 0xFF000000L) Color.White else Color.Black)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FontPicker(selected: String, onFont: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val fonts = listOf("Default", "Sans", "Serif", "Mono", "Condensed")
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = fontLabel(selected), onValueChange = {}, readOnly = true,
            label = { Text(stringResource(R.string.watchface_font)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            fonts.forEach { font ->
                DropdownMenuItem(text = { Text(fontLabel(font)) }, onClick = { onFont(font); expanded = false })
            }
        }
    }
}

@Composable
private fun SaveWatchFaceDialog(state: WatchFaceEditorUiState, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    val defaultName = stringResource(R.string.watchface_my_watchface)
    var name by remember(state.design.name) { mutableStateOf(state.design.name.ifBlank { defaultName }) }
    val timeFormat = com.gluview.app.ui.components.chart.rememberChartAppearance().timeFormat
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.watchface_save_dialog_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true,
                    label = { Text(stringResource(R.string.watchface_name_label)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), modifier = Modifier.fillMaxWidth())
                FactList(listOf(
                    Fact(stringResource(R.string.wf_fact_wearable), wearableLabel(state.design.wearable), wide = true),
                    Fact(stringResource(R.string.wf_fact_created), formatDateTime(state.design.createdAt, timeFormat)),
                    Fact(stringResource(R.string.wf_fact_components), state.design.components.size.toString())
                ))
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name) }) { Text(stringResource(R.string.settings_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) } }
    )
}
