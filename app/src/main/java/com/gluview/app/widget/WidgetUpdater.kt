package com.gluview.app.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import androidx.glance.appwidget.state.updateAppWidgetState
import com.gluview.app.data.datastore.AppPreferences
import com.gluview.app.data.repository.GlucoseRepository
import com.gluview.app.data.sibionics.GlucoseDisplayMode
import com.gluview.app.domain.model.GlucoseReading
import com.gluview.app.util.GlucoseUnit
import com.gluview.app.util.agedFor
import com.gluview.app.util.applyDisplayMode
import com.gluview.app.util.graph.systemIsDark
import com.gluview.app.util.toGraphReading
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Atualiza todos os widgets de glicemia (antes vivia dentro do GluSyncService). Agora também roda
 * quando um widget é COLOCADO na tela (ver [refreshFromLatest]) — antes ele mostrava "Sem sinal"
 * até a próxima leitura chegar, e parecia não carregar.
 */
@Singleton
class WidgetUpdater @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: GlucoseRepository,
    private val preferences: AppPreferences
) {
    private val mutex = Mutex()

    /** Última leitura conhecida (memória do serviço ou banco), com a idade recalculada. */
    suspend fun refreshFromLatest() {
        // O botão "Registrar no diário" não tem dado: só precisa ser desenhado.
        runCatching { GluViewQuickAddWidgetReceiver.refreshAll(context) }
        val latest = repository.currentReading.value ?: repository.getLatestOnce()
        if (latest == null) {
            // Sem leitura nenhuma: desenha o estado "Sem sinal" em vez de deixar o widget em branco.
            listOf(GluViewWidget(), GluViewDiaryWidget(), GluViewChartWidget(), GluViewXDripWidget())
                .forEach { runCatching { it.updateAll(context) } }
            return
        }
        val reading = latest.agedFor(preferences.sensorNoSignalMin.first())
        update(reading, GlucoseUnit.fromString(preferences.glucoseUnit.first()), systemIsDark(context.resources))
    }

    suspend fun update(reading: GlucoseReading, unit: GlucoseUnit, dark: Boolean) = mutex.withLock {
        val manager = GlanceAppWidgetManager(context)
        // Horário exibido no widget deve refletir o instante da MEDIÇÃO, não o do push —
        // assim um sensor sem sinal mostra o horário real mesmo que o serviço re-publique.
        val readingMs = reading.epochMs.takeIf { it > 0 } ?: System.currentTimeMillis()
        val timeFormatStr = preferences.timeFormat.first()
        val lang = preferences.appLanguage.first()

        // Simple widget (existing)
        val simpleIds = manager.getGlanceIds(GluViewWidget::class.java)
        simpleIds.forEach { id ->
            updateAppWidgetState(context, id) { prefs ->
                prefs[GluViewWidget.KEY_SGV]         = reading.sgvMgDl.toFloat()
                prefs[GluViewWidget.KEY_ARROW]       = reading.arrow
                prefs[GluViewWidget.KEY_ZONE]        = reading.zone.name
                prefs[GluViewWidget.KEY_STALE]       = reading.isStale
                prefs[GluViewWidget.KEY_UNIT]        = unit.name
                prefs[GluViewWidget.KEY_LAST_MS]     = readingMs.toString()
                prefs[GluViewWidget.KEY_TIME_FORMAT] = timeFormatStr
                prefs[GluViewWidget.KEY_LANG]        = lang
            }
            GluViewWidget().update(context, id)
        }
        // Plano 17 — widget de glicemia com o lápis do diário: mesmas chaves do simples.
        manager.getGlanceIds(com.gluview.app.widget.GluViewDiaryWidget::class.java).forEach { id ->
            updateAppWidgetState(context, id) { prefs ->
                prefs[GluViewWidget.KEY_SGV]         = reading.sgvMgDl.toFloat()
                prefs[GluViewWidget.KEY_ARROW]       = reading.arrow
                prefs[GluViewWidget.KEY_ZONE]        = reading.zone.name
                prefs[GluViewWidget.KEY_STALE]       = reading.isStale
                prefs[GluViewWidget.KEY_UNIT]        = unit.name
                prefs[GluViewWidget.KEY_LAST_MS]     = readingMs.toString()
                prefs[GluViewWidget.KEY_TIME_FORMAT] = timeFormatStr
                prefs[GluViewWidget.KEY_LANG]        = lang
            }
            com.gluview.app.widget.GluViewDiaryWidget().update(context, id)
        }

        // Chart widget + xDrip widget compartilham os mesmos dados de leitura recente
        val widgetHours = preferences.widgetGraphHours.first()
        val calFactor = preferences.sibionicsCalFactor.first()
        val limits = preferences.glucoseThresholds.first()
        val hipo = limits.low
        val hiper = limits.high
        val fromMs = System.currentTimeMillis() - widgetHours * 3_600_000L
        val recentReadings = repository.getRecentReadingsForNotification(fromMs) // seguidor: o buffer do paciente (L9)
        val displayMode = GlucoseDisplayMode.fromId(preferences.glucoseDisplayMode.first())
        val graphReadings = recentReadings.map { it.toGraphReading(calFactor) }.applyDisplayMode(displayMode)

        val chartIds = manager.getGlanceIds(GluViewChartWidget::class.java)
        if (chartIds.isNotEmpty()) {
            // As leituras (não um bitmap pronto): o widget desenha no tamanho real dele.
            val points = encodeWidgetPoints(graphReadings)
            with(unit) {
                val displayDelta = reading.sgvMgDl.deltaToDisplay(reading.delta)
                chartIds.forEach { id ->
                    updateAppWidgetState(context, id) { prefs ->
                        prefs[GluViewChartWidget.KEY_SGV]         = reading.sgvMgDl.toFloat()
                        prefs[GluViewChartWidget.KEY_ARROW]       = reading.arrow
                        prefs[GluViewChartWidget.KEY_ZONE]        = reading.zone.name
                        prefs[GluViewChartWidget.KEY_STALE]       = reading.isStale
                        prefs[GluViewChartWidget.KEY_UNIT]        = unit.name
                        prefs[GluViewChartWidget.KEY_DELTA]       = displayDelta
                        prefs[GluViewChartWidget.KEY_POINTS]      = points
                        prefs[GluViewChartWidget.KEY_HIPO]        = hipo
                        prefs[GluViewChartWidget.KEY_HIPER]       = hiper
                        prefs[GluViewChartWidget.KEY_URGENT_LOW]  = limits.urgentLow
                        prefs[GluViewChartWidget.KEY_URGENT_HIGH] = limits.urgentHigh
                        prefs[GluViewChartWidget.KEY_LANG]        = lang
                        prefs[GluViewChartWidget.KEY_HOURS]       = widgetHours.toFloat()
                        prefs[GluViewChartWidget.KEY_LAST_MS]     = readingMs.toString()
                        prefs[GluViewChartWidget.KEY_IS_DARK]     = dark
                        prefs[GluViewChartWidget.KEY_TIME_FORMAT] = timeFormatStr
                    }
                    GluViewChartWidget().update(context, id)
                }
            }
        }

        // Widget estilo xDrip
        val xdripIds = manager.getGlanceIds(GluViewXDripWidget::class.java)
        if (xdripIds.isNotEmpty()) {
            val dotsB64 = if (graphReadings.isNotEmpty()) {
                GluViewXDripWidget.renderDotsToBase64(graphReadings, widgetHours, hipo, hiper, limits.urgentLow, limits.urgentHigh)
            } else ""
            with(unit) {
                val displayDelta = reading.sgvMgDl.deltaToDisplay(reading.delta)
                xdripIds.forEach { id ->
                    updateAppWidgetState(context, id) { prefs ->
                        prefs[GluViewXDripWidget.KEY_SGV]         = reading.sgvMgDl.toFloat()
                        prefs[GluViewXDripWidget.KEY_ARROW]       = reading.arrow
                        prefs[GluViewXDripWidget.KEY_ZONE]        = reading.zone.name
                        prefs[GluViewXDripWidget.KEY_STALE]       = reading.isStale
                        prefs[GluViewXDripWidget.KEY_UNIT]        = unit.name
                        prefs[GluViewXDripWidget.KEY_DELTA]       = displayDelta
                        prefs[GluViewXDripWidget.KEY_LAST_MS]     = readingMs.toString()
                        prefs[GluViewXDripWidget.KEY_DOTS]        = dotsB64
                        prefs[GluViewXDripWidget.KEY_IS_DARK]     = dark
                        prefs[GluViewXDripWidget.KEY_TIME_FORMAT] = timeFormatStr
                        prefs[GluViewXDripWidget.KEY_LANG]        = lang
                    }
                    GluViewXDripWidget().update(context, id)
                }
            }
        }
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps { fun widgetUpdater(): WidgetUpdater }

    companion object {
        fun from(context: Context): WidgetUpdater =
            EntryPointAccessors.fromApplication(context.applicationContext, Deps::class.java).widgetUpdater()
    }
}

/**
 * Base dos widgets de glicemia: ao ser colocado (ou o sistema pedir atualização), o widget já
 * recebe a última leitura guardada em vez de esperar a próxima chegar.
 */
abstract class GlucoseWidgetReceiver : androidx.glance.appwidget.GlanceAppWidgetReceiver() {
    override fun onUpdate(context: Context, appWidgetManager: android.appwidget.AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        val pending = goAsync()
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO).launch {
            try { runCatching { WidgetUpdater.from(context).refreshFromLatest() } } finally { pending.finish() }
        }
    }
}
