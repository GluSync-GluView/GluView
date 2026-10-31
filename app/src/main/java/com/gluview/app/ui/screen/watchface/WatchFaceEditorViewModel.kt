package com.gluview.app.ui.screen.watchface

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gluview.app.R
import com.gluview.app.data.repository.GlucoseRepository
import com.gluview.app.data.repository.WatchFaceRepository
import com.gluview.app.data.sibionics.SibionicsStatusHolder
import com.gluview.app.util.UiText
import com.gluview.app.watchface.WatchFaceComponent
import com.gluview.app.watchface.WatchFaceComponentDefinition
import com.gluview.app.watchface.WatchFaceComponentLibrary
import com.gluview.app.watchface.WatchFaceDesign
import com.gluview.app.watchface.WatchFaceTextAlign
import com.gluview.app.watchface.WearableCatalog
import com.gluview.app.watchface.WearableKind
import com.gluview.app.watchface.WearableShape
import com.gluview.app.watchface.WearableSpec
import com.gluview.app.watchface.apply.WatchFaceApplierRegistry
import com.gluview.app.watchface.createComponent
import com.gluview.app.watchface.resolvedFor
import com.gluview.app.watchface.toDesign
import com.gluview.app.watchface.toEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.roundToInt

data class WatchFaceEditorUiState(
    val design: WatchFaceDesign = WatchFaceDesign(wearable = WearableCatalog.starter),
    val selectedId: String? = null,
    val gridEnabled: Boolean = true,
    val guidesEnabled: Boolean = true,
    val snapEnabled: Boolean = true,
    val savedFaceId: Long? = null,
    val applyPromptFaceId: Long? = null,
    val message: WatchFaceMessage? = null,
    /** Abrindo um visor salvo: ainda sem o design do Room (antes mostrava o visor vazio "Novo visor"). */
    val loading: Boolean = false,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    // Achado P6 do plano 06_watchfaces_v2_arquitetura.md: prévia usa dado SIMULADO por padrão,
    // nunca a glicemia real do usuário — deliberadamente fora do undo/redo (é estado de teste da
    // sessão do editor, não uma decisão de design do visor).
    val simulation: SimulatedPreviewInput = SimulatedPreviewInput()
) {
    val selectedComponent: WatchFaceComponent? =
        design.components.firstOrNull { it.id == selectedId }

    /** Há mudança não salva: sair pede confirmação (decisão 5.6 #4). */
    val dirty: Boolean get() = canUndo && savedFaceId == null
}

private data class EditorSnapshot(
    val design: WatchFaceDesign,
    val selectedId: String?,
    val gridEnabled: Boolean,
    val guidesEnabled: Boolean,
    val snapEnabled: Boolean
)

@HiltViewModel
class WatchFaceEditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @ApplicationContext context: Context,
    private val watchFaceRepository: WatchFaceRepository,
    glucoseRepository: GlucoseRepository,
    statusHolder: SibionicsStatusHolder,
    preferences: com.gluview.app.data.datastore.AppPreferences,
    private val applierRegistry: WatchFaceApplierRegistry
) : ViewModel() {

    private val faceId: Long = savedStateHandle.get<String>("faceId")?.toLongOrNull() ?: 0L

    private val _uiState = MutableStateFlow(WatchFaceEditorUiState(loading = faceId > 0L))
    val uiState: StateFlow<WatchFaceEditorUiState> = _uiState.asStateFlow()

    private val undoStack = ArrayDeque<EditorSnapshot>()
    private val redoStack = ArrayDeque<EditorSnapshot>()
    private var gestureSnapshotOpen = false

    private val realRuntime: StateFlow<WatchFaceRuntimeData> =
        watchFaceRuntimeFlow(viewModelScope, context, glucoseRepository, statusHolder, preferences)

    // Recalcula o runtime simulado a cada segundo (pro relógio da prévia continuar andando quando
    // não há hora customizada) e troca pra dado real só se o usuário desligar a simulação.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val simulationTick = flow {
        while (true) {
            emit(Unit)
            delay(1_000L)
        }
    }

    val runtime: StateFlow<WatchFaceRuntimeData> = combine(
        _uiState.map { it.simulation }.distinctUntilChanged(),
        simulationTick,
        realRuntime
    ) { simulation, _, real ->
        // Simulado só a glicemia/sensor/hora: limites, unidade e formato de hora são os do usuário
        // (antes a prévia simulada pintava com 70/180, mg/dL e 24h fixos).
        if (simulation.enabled) simulation.toRuntimeData().copy(
            hipo = real.hipo, hiper = real.hiper, urgentLow = real.urgentLow, urgentHigh = real.urgentHigh,
            timeFormat = real.timeFormat, unit = real.unit
        ) else real
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        WatchFaceEditorUiState().simulation.toRuntimeData()
    )

    init {
        if (faceId > 0L) {
            viewModelScope.launch {
                watchFaceRepository.observeById(faceId)
                    .filterNotNull()
                    .collect { entity ->
                        _uiState.value = _uiState.value.copy(design = entity.toDesign(), loading = false)
                    }
            }
        }
    }

    fun selectWearable(wearable: WearableSpec) = mutate {
        it.copy(design = it.design.resolvedFor(wearable).copy(updatedAt = System.currentTimeMillis()))
    }

    /** Liga/desliga um wearable na lista de compatíveis (Fase 5 — WatchfaceCompatibility, §13 do plano). */
    fun toggleCompatibleWearable(wearableId: String) = mutate {
        val current = it.design.compatibleWearableIds
        val updated = if (wearableId in current) current - wearableId else current + wearableId
        it.copy(design = it.design.copy(compatibleWearableIds = updated, updatedAt = System.currentTimeMillis()))
    }

    fun selectCustomWearable(width: Int, height: Int, kind: WearableKind, shape: WearableShape) {
        val safeWidth = width.coerceIn(80, 1200)
        val safeHeight = height.coerceIn(32, 1200)
        selectWearable(
            WearableSpec(
                id = WearableCatalog.customId(safeWidth, safeHeight, kind, shape),
                brand = "Personalizado",
                name = "$safeWidth x $safeHeight",
                widthPx = safeWidth,
                heightPx = safeHeight,
                kind = kind,
                shape = shape
            )
        )
    }

    fun addComponent(definition: WatchFaceComponentDefinition) = mutate {
        val z = (it.design.components.maxOfOrNull { c -> c.zIndex } ?: -1) + 1
        val component = definition.createComponent(it.design.wearable, z)
        it.copy(
            design = it.design.copy(
                components = it.design.components + component,
                updatedAt = System.currentTimeMillis()
            ),
            selectedId = component.id
        )
    }

    fun selectComponent(id: String) {
        _uiState.value = _uiState.value.copy(selectedId = id)
    }

    /** Aplica um modelo pronto, substituindo os componentes atuais; o aviso oferece Desfazer
     *  (decisão 5.6 #4 — antes trocava tudo sem dizer nada). */
    fun applyPreset(preset: com.gluview.app.watchface.WatchFacePreset) {
        val hadComponents = _uiState.value.design.components.isNotEmpty()
        mutate {
            it.copy(
                design = it.design.copy(
                    components = preset.build(it.design.wearable),
                    updatedAt = System.currentTimeMillis()
                ),
                selectedId = null,
                message = WatchFaceMessage(UiText.Res(R.string.wf_msg_template_applied),
                    if (hadComponents) WatchFaceMessageAction.UNDO else null)
            )
        }
    }

    /** Remove um componente especifico (usado pelo "X" no canvas). */
    fun deleteComponent(id: String) = mutate {
        it.copy(
            design = it.design.copy(
                components = it.design.components.filterNot { c -> c.id == id },
                updatedAt = System.currentTimeMillis()
            ),
            selectedId = if (it.selectedId == id) null else it.selectedId
        )
    }

    fun beginUserAction() {
        if (!gestureSnapshotOpen) {
            pushUndo()
            redoStack.clear()
            gestureSnapshotOpen = true
            syncHistory()
        }
    }

    fun endUserAction() {
        gestureSnapshotOpen = false
    }

    /** Posição ABSOLUTA (px do wearable), aplicada uma vez ao fim do arraste. Snap só no release. */
    fun moveComponentTo(id: String, x: Float, y: Float) {
        val snap = _uiState.value.gridEnabled && _uiState.value.snapEnabled
        updateComponent(id) { c ->
            val maxX = (_uiState.value.design.wearable.widthPx - c.width).coerceAtLeast(0f)
            val maxY = (_uiState.value.design.wearable.heightPx - c.height).coerceAtLeast(0f)
            val nx = x.coerceIn(0f, maxX)
            val ny = y.coerceIn(0f, maxY)
            c.copy(x = if (snap) snap(nx) else nx, y = if (snap) snap(ny) else ny)
        }
    }

    /** Tamanho ABSOLUTO, aplicado uma vez ao fim do redimensionamento. */
    fun resizeComponentTo(id: String, width: Float, height: Float) {
        updateComponent(id) { c ->
            c.copy(
                width = width.coerceIn(20f, _uiState.value.design.wearable.widthPx.toFloat()),
                height = height.coerceIn(16f, _uiState.value.design.wearable.heightPx.toFloat())
            )
        }
    }

    fun updateSelected(
        x: Float? = null,
        y: Float? = null,
        width: Float? = null,
        height: Float? = null,
        scale: Float? = null,
        rotation: Float? = null,
        color: Long? = null,
        font: String? = null,
        alpha: Float? = null,
        alignment: String? = null,
        customText: String? = null
    ) {
        val id = _uiState.value.selectedId ?: return
        mutateComponent(id) { c ->
            c.copy(
                x = x ?: c.x,
                y = y ?: c.y,
                width = width ?: c.width,
                height = height ?: c.height,
                scale = scale ?: c.scale,
                rotation = rotation ?: c.rotation,
                color = color ?: c.color,
                font = font ?: c.font,
                alpha = alpha ?: c.alpha,
                alignment = alignment ?: c.alignment,
                customText = customText ?: c.customText
            )
        }
    }

    fun duplicateSelected() {
        val selected = _uiState.value.selectedComponent ?: return
        mutate {
            val z = (it.design.components.maxOfOrNull { c -> c.zIndex } ?: -1) + 1
            val copy = selected.copy(
                id = java.util.UUID.randomUUID().toString(),
                x = (selected.x + 12f).coerceAtMost(it.design.wearable.widthPx - selected.width),
                y = (selected.y + 12f).coerceAtMost(it.design.wearable.heightPx - selected.height),
                zIndex = z
            )
            it.copy(design = it.design.copy(components = it.design.components + copy), selectedId = copy.id)
        }
    }

    fun removeSelected() {
        val id = _uiState.value.selectedId ?: return
        mutate {
            it.copy(
                design = it.design.copy(components = it.design.components.filterNot { c -> c.id == id }),
                selectedId = null
            )
        }
    }

    fun changeZ(delta: Int) {
        val id = _uiState.value.selectedId ?: return
        mutate {
            val components = it.design.components.map { c ->
                if (c.id == id) c.copy(zIndex = (c.zIndex + delta).coerceIn(0, 999)) else c
            }.normalizeZ()
            it.copy(design = it.design.copy(components = components))
        }
    }

    fun toggleGrid() { _uiState.value = _uiState.value.copy(gridEnabled = !_uiState.value.gridEnabled) }
    fun toggleGuides() { _uiState.value = _uiState.value.copy(guidesEnabled = !_uiState.value.guidesEnabled) }
    fun toggleSnap() { _uiState.value = _uiState.value.copy(snapEnabled = !_uiState.value.snapEnabled) }

    fun updateSimulation(
        enabled: Boolean? = null,
        glucoseMgDl: Int? = null,
        arrow: String? = null,
        deltaMgDl: Int? = null,
        sensorConnected: Boolean? = null,
        sensorAgeDays: Int? = null,
        batteryPercent: Int? = null,
        useCustomTime: Boolean? = null,
        hour: Int? = null,
        minute: Int? = null
    ) {
        val s = _uiState.value.simulation
        _uiState.value = _uiState.value.copy(
            simulation = s.copy(
                enabled = enabled ?: s.enabled,
                glucoseMgDl = glucoseMgDl ?: s.glucoseMgDl,
                arrow = arrow ?: s.arrow,
                deltaMgDl = deltaMgDl ?: s.deltaMgDl,
                sensorConnected = sensorConnected ?: s.sensorConnected,
                sensorAgeDays = sensorAgeDays ?: s.sensorAgeDays,
                batteryPercent = batteryPercent ?: s.batteryPercent,
                useCustomTime = useCustomTime ?: s.useCustomTime,
                hour = hour ?: s.hour,
                minute = minute ?: s.minute
            )
        )
    }

    fun undo() {
        val snapshot = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(currentSnapshot())
        restore(snapshot)
    }

    fun redo() {
        val snapshot = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(currentSnapshot())
        restore(snapshot)
    }

    fun save(name: String) {
        val trimmed = name.ifBlank { "Visor sem nome" }
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val current = _uiState.value.design
            val saved = current.copy(
                name = trimmed,
                createdAt = if (current.id == 0L) now else current.createdAt,
                updatedAt = now
            )
            val id = watchFaceRepository.save(saved.toEntity())
            _uiState.value = _uiState.value.copy(
                design = saved.copy(id = id),
                savedFaceId = id,
                applyPromptFaceId = id,
                message = WatchFaceMessage(UiText.Res(R.string.wf_msg_saved))
            )
            // Sincronização best-effort — nunca atrasa nem bloqueia o "Visor salvo." acima, que já
            // reflete o salvamento local (a fonte da verdade imediata).
            watchFaceRepository.getById(id)?.let { entity ->
                launch { watchFaceRepository.syncToServer(entity) }
            }
        }
    }

    fun dismissApplyPrompt() {
        _uiState.value = _uiState.value.copy(applyPromptFaceId = null)
    }

    fun applyDesign() {
        val id = _uiState.value.applyPromptFaceId ?: return
        _uiState.value = _uiState.value.copy(applyPromptFaceId = null)
        viewModelScope.launch {
            val entity = watchFaceRepository.getById(id) ?: return@launch
            val message = watchFaceRepository.applyAndPersist(entity, applierRegistry).toMessage()
            _uiState.value = _uiState.value.copy(message = message)
        }
    }

    fun clearMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }

    private fun mutate(transform: (WatchFaceEditorUiState) -> WatchFaceEditorUiState) {
        pushUndo()
        redoStack.clear()
        _uiState.value = transform(_uiState.value).copy(
            savedFaceId = null,
            canUndo = undoStack.isNotEmpty(),
            canRedo = redoStack.isNotEmpty()
        )
    }

    private fun syncHistory() {
        _uiState.value = _uiState.value.copy(
            canUndo = undoStack.isNotEmpty(),
            canRedo = redoStack.isNotEmpty()
        )
    }

    private fun mutateComponent(id: String, transform: (WatchFaceComponent) -> WatchFaceComponent) =
        mutate { state ->
            state.copy(
                design = state.design.copy(
                    components = state.design.components.map { if (it.id == id) transform(it) else it },
                    updatedAt = System.currentTimeMillis()
                )
            )
        }

    private fun updateComponent(id: String, transform: (WatchFaceComponent) -> WatchFaceComponent) {
        _uiState.value = _uiState.value.copy(
            design = _uiState.value.design.copy(
                components = _uiState.value.design.components.map { if (it.id == id) transform(it) else it },
                updatedAt = System.currentTimeMillis()
            ),
            selectedId = id
        )
    }

    private fun pushUndo() {
        undoStack.addLast(currentSnapshot())
        while (undoStack.size > 80) undoStack.removeFirst()
    }

    private fun currentSnapshot() = EditorSnapshot(
        design = _uiState.value.design,
        selectedId = _uiState.value.selectedId,
        gridEnabled = _uiState.value.gridEnabled,
        guidesEnabled = _uiState.value.guidesEnabled,
        snapEnabled = _uiState.value.snapEnabled
    )

    private fun restore(snapshot: EditorSnapshot) {
        _uiState.value = _uiState.value.copy(
            design = snapshot.design,
            selectedId = snapshot.selectedId,
            gridEnabled = snapshot.gridEnabled,
            guidesEnabled = snapshot.guidesEnabled,
            snapEnabled = snapshot.snapEnabled,
            canUndo = undoStack.isNotEmpty(),
            canRedo = redoStack.isNotEmpty()
        )
    }

    private fun snap(value: Float): Float = (value / 10f).roundToInt() * 10f

    private fun List<WatchFaceComponent>.normalizeZ(): List<WatchFaceComponent> =
        sortedBy { it.zIndex }.mapIndexed { index, component -> component.copy(zIndex = index) }
}
