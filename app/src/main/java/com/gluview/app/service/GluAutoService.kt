package com.gluview.app.service

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.GridItem
import androidx.car.app.model.GridTemplate
import androidx.car.app.model.ItemList
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.validation.HostValidator
import androidx.core.graphics.drawable.IconCompat
import com.gluview.app.R
import com.gluview.app.data.datastore.AppPreferences
import com.gluview.app.data.repository.CaregiverRepository
import com.gluview.app.data.repository.GlucoseRepository
import com.gluview.app.ui.screen.caregiver.CaregiverItem
import com.gluview.app.domain.model.GlucoseReading
import com.gluview.app.util.GlucoseGraphRenderer
import com.gluview.app.util.GlucoseUnit
import com.gluview.app.util.GraphTheme
import com.gluview.app.util.TimeFormat
import com.gluview.app.util.epochMsToTimeOfDay
import com.gluview.app.util.graph.surfaceSpec
import com.gluview.app.util.toGraphReading
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.gluview.app.data.sibionics.GlucoseDisplayMode
import com.gluview.app.util.agedFor
import com.gluview.app.util.applyDisplayMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class GluAutoService : CarAppService() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface GluAutoEntryPoint {
        fun glucoseRepository(): GlucoseRepository
        fun appPreferences(): AppPreferences
        fun caregiverRepository(): CaregiverRepository
    }

    // IOT: categoria real da lib pra apps de status/dado de sensor externo (era declarada
    // errada no manifesto como "IOAPP", que não existe — corrigido junto com este @OptIn).
    //
    // Auditoria 2026-07-09 (achado #2) — ALLOW_ALL_HOSTS_VALIDATOR é documentado pelo Google
    // como apropriado só para debug: qualquer app no aparelho podia se passar por host do
    // Android Auto e ler a glicemia (inclusive de pacientes acompanhados) sem autenticação.
    // hosts_allowlist_sample é a allowlist oficial mantida DENTRO da própria biblioteca
    // androidx.car.app (pacote+fingerprint do Android Auto e do Android Automotive OS) — não
    // precisa ser copiada à mão, e se atualiza junto com a versão da lib.
    override fun createHostValidator(): HostValidator =
        HostValidator.Builder(applicationContext)
            .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
            .build()

    override fun onCreateSession(): Session {
        val ep = EntryPointAccessors.fromApplication(
            applicationContext, GluAutoEntryPoint::class.java
        )
        return GluAutoSession(ep.glucoseRepository(), ep.appPreferences(), ep.caregiverRepository())
    }
}

// ── Session ───────────────────────────────────────────────────────────────────

private class GluAutoSession(
    private val repository: GlucoseRepository,
    private val preferences: AppPreferences,
    private val caregiverRepository: CaregiverRepository
) : Session() {
    override fun onCreateScreen(intent: Intent): Screen =
        GluAutoScreen(carContext, repository, preferences, caregiverRepository)
}

// ── Screen principal ─────────────────────────────────────────────────────────

private class GluAutoScreen(
    carContext: CarContext,
    private val repository: GlucoseRepository,
    private val preferences: AppPreferences,
    private val caregiverRepository: CaregiverRepository
) : Screen(carContext) {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var unit: GlucoseUnit = GlucoseUnit.MG_DL
    private var timeFormat: TimeFormat = TimeFormat.H24
    // Modo Seguidor: sem sensor próprio, mostra o paciente ativo (mesma fonte de dado da
    // Home/notificação/Mi Band — GluSyncService já assina o SignalR dele em segundo plano).
    private var isFollowerOnly = false
    private var followedPatientName = ""
    private var hasActivePatient = false
    private var autoShowGraph = false
    private var autoGraphHours = 3
    private var autoGraphTheme = "AUTO"
    private var noSignalMin = 30

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) { scope.cancel() }
        })
        scope.launch { preferences.glucoseUnit.collect { unit = GlucoseUnit.fromString(it); invalidate() } }
        scope.launch { preferences.timeFormat.collect { timeFormat = TimeFormat.fromString(it); invalidate() } }
        scope.launch { preferences.isFollowerOnly.collect { isFollowerOnly = it; invalidate() } }
        scope.launch { preferences.activeFollowedPatientName.collect { followedPatientName = it; invalidate() } }
        scope.launch { preferences.activeFollowedPatientId.collect { hasActivePatient = it.isNotBlank(); invalidate() } }
        scope.launch { preferences.autoShowGraph.collect { autoShowGraph = it; invalidate() } }
        scope.launch { preferences.autoGraphHours.collect { autoGraphHours = it; invalidate() } }
        scope.launch { preferences.autoGraphTheme.collect { autoGraphTheme = it; invalidate() } }
        scope.launch { repository.currentReading.filterNotNull().collect { invalidate() } }
        scope.launch { preferences.sensorNoSignalMin.collect { noSignalMin = it; invalidate() } }
        // L12: a cada minuto reavalia a idade (a leitura vira "sem sinal" sem precisar de uma nova).
        scope.launch { while (true) { delay(60_000L); invalidate() } }
    }

    override fun onGetTemplate(): Template {
        val appName = carContext.getString(R.string.app_name)

        // Seguidor sem paciente selecionado — nem tenta ler currentReading (não existe).
        if (isFollowerOnly && !hasActivePatient) {
            return MessageTemplate.Builder(carContext.getString(R.string.autoapp_select_patient_message))
                .setTitle(appName)
                .build()
        }

        val screenTitle = if (isFollowerOnly) followedPatientName.ifBlank { appName } else appName

        val reading = repository.currentReading.value?.agedFor(noSignalMin)
            ?: return MessageTemplate.Builder(
                if (isFollowerOnly) carContext.getString(R.string.autoapp_waiting_patient_data)
                else carContext.getString(R.string.home_waiting_sensor_data)
            ).setTitle(screenTitle).build()

        return run {
            val pane = Pane.Builder().addRow(readingRow(carContext, reading, unit, timeFormat)).build()

            val builder = PaneTemplate.Builder(pane).setTitle(screenTitle)

            val stripBuilder = ActionStrip.Builder()
            var hasActions = false

            if (isFollowerOnly) {
                stripBuilder.addAction(
                    Action.Builder()
                        .setTitle(carContext.getString(R.string.autoapp_switch_patient))
                        .setOnClickListener {
                            screenManager.push(PatientPickerScreen(carContext, preferences, caregiverRepository))
                        }
                        .build()
                )
                hasActions = true
            }
            if (autoShowGraph) {
                stripBuilder.addAction(
                    Action.Builder()
                        .setTitle(carContext.getString(R.string.autoapp_view_graph))
                        .setOnClickListener {
                            screenManager.push(GluAutoGraphScreen(carContext, repository, preferences, autoGraphHours, autoGraphTheme))
                        }
                        .build()
                )
                hasActions = true
            }
            if (hasActions) builder.setActionStrip(stripBuilder.build())

            builder.build()
        }
    }
}

/**
 * Linha da leitura atual: "135 mg/dL ↗" e "+3 · 14:05". Sem sinal escrito no título, sem o valor
 * antigo como se fosse atual (antes só a hora virava "sem sinal").
 */
private fun readingRow(carContext: CarContext, reading: GlucoseReading, unit: GlucoseUnit, timeFormat: TimeFormat): Row =
    with(unit) {
        val time = if (reading.epochMs > 0L) epochMsToTimeOfDay(reading.epochMs, timeFormat) else null
        if (reading.isStale) {
            Row.Builder()
                .setTitle(carContext.getString(R.string.home_sensor_no_signal))
                .apply { time?.let { addText(carContext.getString(R.string.notif_last_reading, it)) } }
                .build()
        } else {
            Row.Builder()
                .setTitle("${reading.sgvMgDl.toDisplay()} ${unit.label}  ${reading.arrow}")
                .addText(listOfNotNull(reading.sgvMgDl.deltaToDisplay(reading.delta), time).joinToString("  ·  "))
                .build()
        }
    }

// ── Tela de gráfico ──────────────────────────────────────────────────────────

/**
 * Gráfico no Auto (redesign visual, Fase 5): o modelo da Home com os limiares do USUÁRIO (L13:
 * antes 70/180 fixos), eixos na unidade e no formato de hora dele, em 16:9 e grande — num
 * PaneTemplate ao lado da leitura quando o host tem Car API ≥ 4 (antes uma miniatura de grade com
 * o título repetido no item); refeito a cada leitura nova (antes congelado ao abrir).
 */
private class GluAutoGraphScreen(
    carContext: CarContext,
    private val repository: GlucoseRepository,
    private val preferences: AppPreferences,
    private val hoursBack: Int,
    private val graphTheme: String
) : Screen(carContext) {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var loading = true
    private var graphIcon: CarIcon? = null
    private var unit = GlucoseUnit.MG_DL
    private var timeFormat = TimeFormat.H24

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) { scope.cancel() }
        })
        scope.launch {
            repository.currentReading.collect {
                runCatching { render() }
                loading = false
                invalidate()
            }
        }
    }

    private suspend fun render() {
        unit = GlucoseUnit.fromString(preferences.glucoseUnit.first())
        timeFormat = TimeFormat.fromString(preferences.timeFormat.first())
        val nowMs = System.currentTimeMillis()
        // L11: o modo de exibição do Sibionics ("só confirmadas"), como na Home e na notificação.
        val mode = GlucoseDisplayMode.fromId(preferences.glucoseDisplayMode.first())
        val readings = repository.getRecentReadingsForNotification(nowMs - hoursBack * 3_600_000L)
            .map { it.toGraphReading(1.0) }.applyDisplayMode(mode)
        graphIcon = if (readings.isEmpty()) null else {
            val dark = when (graphTheme) {
                GraphTheme.DARK  -> true
                GraphTheme.LIGHT -> false
                else             -> carContext.isDarkMode
            }
            val bitmap = GlucoseGraphRenderer.render(readings, surfaceSpec(
                AUTO_GRAPH_W, AUTO_GRAPH_H, hoursBack, nowMs, dark,
                hipo = preferences.lowThreshold.first(), hiper = preferences.highThreshold.first(),
                unit = unit, timeFormat = timeFormat, labelSizePx = AUTO_LABEL_PX, transparentBackground = false,
                urgentLow = preferences.urgentLowThreshold.first(), urgentHigh = preferences.urgentHighThreshold.first()
            ))
            CarIcon.Builder(IconCompat.createWithBitmap(bitmap)).build()
        }
    }

    override fun onGetTemplate(): Template {
        val title = carContext.getString(R.string.autoapp_graph_title, hoursBack)
        if (loading) {
            return MessageTemplate.Builder(carContext.getString(R.string.autoapp_graph_loading))
                .setTitle(title)
                .setHeaderAction(Action.BACK)
                .build()
        }
        val icon = graphIcon ?: return MessageTemplate.Builder(
            carContext.getString(R.string.autoapp_graph_no_data)
        ).setTitle(title).setHeaderAction(Action.BACK).build()

        val reading = repository.currentReading.value
        if (carContext.carAppApiLevel >= 4 && reading != null) {
            // Imagem grande ao lado da leitura (Pane.setImage exige Car API 4).
            val pane = Pane.Builder()
                .addRow(readingRow(carContext, reading, unit, timeFormat))
                .setImage(icon)
                .build()
            return PaneTemplate.Builder(pane).setTitle(title).setHeaderAction(Action.BACK).build()
        }
        // Host antigo: grade com um item; o título do item é a janela de horário, não o título
        // da tela de novo.
        val nowMs = System.currentTimeMillis()
        val range = "${epochMsToTimeOfDay(nowMs - hoursBack * 3_600_000L, timeFormat)}–${epochMsToTimeOfDay(nowMs, timeFormat)}"
        val itemList = ItemList.Builder()
            .addItem(GridItem.Builder().setTitle(range).setImage(icon, GridItem.IMAGE_TYPE_LARGE).build())
            .build()
        return GridTemplate.Builder()
            .setTitle(title)
            .setHeaderAction(Action.BACK)
            .setSingleList(itemList)
            .build()
    }

    private companion object {
        // 16:9, com rótulos legíveis depois que o host reduz a imagem.
        const val AUTO_GRAPH_W = 800
        const val AUTO_GRAPH_H = 450
        const val AUTO_LABEL_PX = 28f
    }
}

// ── Seletor de paciente (modo seguidor) ─────────────────────────────────────

/** GridTemplate com os pacientes acompanhados — tocar num item troca o paciente ativo e volta. */
private class PatientPickerScreen(
    carContext: CarContext,
    private val preferences: AppPreferences,
    private val caregiverRepository: CaregiverRepository
) : Screen(carContext) {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var loading = true
    private var following: List<CaregiverItem> = emptyList()

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) { scope.cancel() }
        })
        scope.launch {
            runCatching {
                val myId = caregiverRepository.me().id
                caregiverRepository.links()
                    .filter { it.status == "accepted" && it.iAmFollower(myId) }
                    .map { CaregiverItem(it, it.otherPartyName(myId), it.patientId, iAmFollower = true) }
            }.onSuccess { following = it }
            loading = false
            invalidate()
        }
    }

    override fun onGetTemplate(): Template {
        val personIcon = CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_glucose)).build()
        val itemList = ItemList.Builder().apply {
            if (following.isEmpty() && !loading) {
                setNoItemsMessage(carContext.getString(R.string.autoapp_picker_empty))
            }
            following.forEach { item ->
                addItem(
                    GridItem.Builder()
                        .setTitle(item.otherName)
                        .setImage(personIcon)
                        .setOnClickListener {
                            scope.launch {
                                preferences.saveActiveFollowedPatient(item.patientId, item.otherName)
                                screenManager.pop()
                            }
                        }
                        .build()
                )
            }
        }.build()

        return GridTemplate.Builder()
            .setTitle(carContext.getString(R.string.autoapp_picker_title))
            .setHeaderAction(Action.BACK)
            .setLoading(loading)
            .setSingleList(itemList)
            .build()
    }
}
