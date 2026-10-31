package com.gluview.app.ui.components.chart

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.gluview.app.data.datastore.AppPreferences
import com.gluview.app.util.GlucoseUnit
import com.gluview.app.util.GraphTheme
import com.gluview.app.util.TimeFormat
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Preferências que definem a aparência do gráfico (antes de resolver o tema do sistema). */
data class ChartPrefs(
    val graphThemeMode: String = GraphTheme.FOLLOW_APP,
    val appThemeMode: String = "SYSTEM",
    val unit: GlucoseUnit = GlucoseUnit.MG_DL,
    val timeFormat: TimeFormat = TimeFormat.H24,
    /** Limiares de Ajustes > Alertas do próprio usuário. */
    val hipo: Float = 70f,
    val hiper: Float = 180f,
    val urgentLow: Float = 55f,
    val urgentHigh: Float = 250f
)

/**
 * Só LEITURA das preferências do gráfico (redesign visual, Fase 3) — para as telas que não têm o
 * HomeViewModel (cujo init tem efeitos colaterais): seguidor e paciente acompanhado.
 */
@HiltViewModel
class ChartAppearanceViewModel @Inject constructor(preferences: AppPreferences) : ViewModel() {
    val prefs: StateFlow<ChartPrefs> = combine(
        combine(preferences.graphThemeMode, preferences.themeMode, preferences.glucoseUnit, preferences.timeFormat) { graph, app, unit, time ->
            ChartPrefs(graph, app, GlucoseUnit.fromString(unit), TimeFormat.fromString(time))
        },
        preferences.glucoseThresholds
    ) { base, t -> base.copy(hipo = t.low, hiper = t.high, urgentLow = t.urgentLow, urgentHigh = t.urgentHigh) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ChartPrefs())
}

/** Aparência do gráfico com o tema já resolvido (preferência do gráfico, do app e do sistema). */
@Composable
fun rememberChartAppearance(
    /** null = os limiares do usuário (Ajustes > Alertas). */
    hipo: Float? = null,
    hiper: Float? = null,
    vm: ChartAppearanceViewModel = hiltViewModel()
): ChartAppearance {
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val systemDark = isSystemInDarkTheme()
    return remember(prefs, systemDark, hipo, hiper) {
        ChartAppearance(
            dark = GraphTheme.resolveDark(prefs.graphThemeMode, prefs.appThemeMode, systemDark),
            hipo = hipo ?: prefs.hipo,
            hiper = hiper ?: prefs.hiper,
            unit = prefs.unit,
            timeFormat = prefs.timeFormat,
            urgentLow = prefs.urgentLow,
            urgentHigh = prefs.urgentHigh
        )
    }
}
