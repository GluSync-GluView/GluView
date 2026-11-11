package com.gluview.app.ui.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import com.gluview.app.R
import com.gluview.app.ui.components.kit.GluCard
import com.gluview.app.ui.components.kit.GluSection
import com.gluview.app.ui.components.kit.Page
import com.gluview.app.ui.components.kit.PeriodSelector
import com.gluview.app.ui.components.kit.SwitchRow
import com.gluview.app.ui.theme.Spacing
import com.gluview.app.ui.theme.ThemeMode
import com.gluview.app.util.GlucoseUnit
import com.gluview.app.util.GraphTheme
import com.gluview.app.util.TimeFormat

/** Janelas do gráfico — as mesmas chips da Home (1/3/6/12/24h). */
internal val CHART_WINDOW_HOURS = listOf(1, 3, 6, 12, 24)
private val AUTO_WINDOW_HOURS = listOf(1, 3, 6)

/** Estado de Geral (para o harness desenhar sem ViewModel). */
internal data class GeneralUi(
    val unit: GlucoseUnit,
    val timeFormat: TimeFormat,
    val language: String,
    val themeMode: ThemeMode,
    val widgetHours: Int,
    val notifHours: Int,
    val graphTheme: String,
    val autoShowGraph: Boolean,
    val autoHours: Int,
    val autoTheme: String
)

internal data class GeneralActions(
    val onUnit: (GlucoseUnit) -> Unit,
    val onTimeFormat: (TimeFormat) -> Unit,
    val onLanguage: (String) -> Unit,
    val onTheme: (ThemeMode) -> Unit,
    val onWidgetHours: (Int) -> Unit,
    val onNotifHours: (Int) -> Unit,
    val onGraphTheme: (String) -> Unit,
    val onAutoShowGraph: (Boolean) -> Unit,
    val onAutoHours: (Int) -> Unit,
    val onAutoTheme: (String) -> Unit
)

@Composable
fun GeneralSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val state            by viewModel.uiState.collectAsState()
    val appLanguage      by viewModel.appLanguage.collectAsState()
    val widgetGraphHours by viewModel.widgetGraphHours.collectAsState()
    val notifGraphHours  by viewModel.notifGraphHours.collectAsState()
    val graphThemeMode   by viewModel.graphThemeMode.collectAsState()
    val autoShowGraph    by viewModel.autoShowGraph.collectAsState()
    val autoGraphHours   by viewModel.autoGraphHours.collectAsState()
    val autoGraphTheme   by viewModel.autoGraphTheme.collectAsState()

    GeneralContent(
        ui = GeneralUi(state.glucoseUnit, state.timeFormat, appLanguage, state.themeMode, widgetGraphHours, notifGraphHours,
            graphThemeMode, autoShowGraph, autoGraphHours, autoGraphTheme),
        actions = GeneralActions(viewModel::onGlucoseUnitChange, viewModel::onTimeFormatChange, viewModel::onLanguageChange,
            viewModel::onThemeModeChange, viewModel::onWidgetGraphHoursChange, viewModel::onNotifGraphHoursChange,
            viewModel::onGraphThemeModeChange, viewModel::onAutoShowGraphChange, viewModel::onAutoGraphHoursChange,
            viewModel::onAutoGraphThemeChange),
        onBack = onBack,
        extra = { QuickAddNotificationSetting() }
    )
}

/**
 * Geral (redesign visual, Fase 6): "Exibição" numa seção só (antes 4 seções de título + divisor
 * para 4 escolhas curtas), chips que QUEBRAM de linha em vez de cortar, com semântica de grupo;
 * "Gráficos" diz onde vale e "Android Auto" tem as opções na mesma ordem (Automático → Claro →
 * Escuro). As chaves e as candidaturas saíram daqui (decisão 5.5 #4).
 */
@Composable
internal fun GeneralContent(ui: GeneralUi, actions: GeneralActions, onBack: () -> Unit,
                            extra: @Composable () -> Unit = {}) {
    val hours: @Composable (Int) -> String = { "${it}h" }
    val graphThemes = listOf(GraphTheme.FOLLOW_APP, GraphTheme.LIGHT, GraphTheme.DARK)
    val graphThemeLabel: @Composable (String) -> String = {
        when (it) {
            GraphTheme.LIGHT -> stringResource(R.string.settings_graphs_theme_light)
            GraphTheme.DARK  -> stringResource(R.string.settings_graphs_theme_dark)
            else             -> stringResource(R.string.settings_graphs_theme_follow_app)
        }
    }

    Page(title = stringResource(R.string.settings_general_section), onBack = onBack) {
        GluSection(stringResource(R.string.settings_display_section)) {
            GluCard {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    PeriodSelector(GlucoseUnit.entries, ui.unit, actions.onUnit, { it.label },
                        title = stringResource(R.string.settings_unit))
                    PeriodSelector(listOf(TimeFormat.H24, TimeFormat.H12), ui.timeFormat, actions.onTimeFormat,
                        { stringResource(if (it == TimeFormat.H24) R.string.settings_time_format_24h else R.string.settings_time_format_12h) },
                        title = stringResource(R.string.settings_time_format))
                    PeriodSelector(listOf("pt", "en"), ui.language, actions.onLanguage,
                        { stringResource(if (it == "pt") R.string.settings_language_portuguese else R.string.settings_language_english) },
                        title = stringResource(R.string.settings_language))
                    // Mesma ordem dos outros grupos: automático primeiro.
                    PeriodSelector(listOf(ThemeMode.SYSTEM, ThemeMode.LIGHT, ThemeMode.DARK), ui.themeMode, actions.onTheme, {
                        stringResource(when (it) {
                            ThemeMode.LIGHT -> R.string.settings_theme_light
                            ThemeMode.DARK  -> R.string.settings_theme_dark
                            else            -> R.string.settings_theme_system
                        })
                    }, title = stringResource(R.string.settings_theme))
                }
            }
        }

        GluSection(stringResource(R.string.settings_graphs_section), description = stringResource(R.string.settings_graphs_section_desc)) {
            GluCard {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    PeriodSelector(CHART_WINDOW_HOURS, ui.widgetHours, actions.onWidgetHours, hours,
                        title = stringResource(R.string.settings_graphs_widget))
                    PeriodSelector(CHART_WINDOW_HOURS, ui.notifHours, actions.onNotifHours, hours,
                        title = stringResource(R.string.settings_graphs_notification))
                    PeriodSelector(graphThemes, ui.graphTheme, actions.onGraphTheme, graphThemeLabel,
                        title = stringResource(R.string.settings_graphs_theme))
                }
            }
        }

        // Plano 17 — botão "Registrar no diário" na notificação fixa (pode ser escondido).
        extra()

        // Android Auto — gráfico de glicemia no painel do carro
        GluSection(stringResource(R.string.auto_settings_title)) {
            GluCard {
                SwitchRow(stringResource(R.string.auto_settings_show_graph), ui.autoShowGraph, actions.onAutoShowGraph,
                    description = stringResource(R.string.auto_settings_show_graph_desc))
                if (ui.autoShowGraph) {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                        PeriodSelector(AUTO_WINDOW_HOURS, ui.autoHours, actions.onAutoHours, hours,
                            title = stringResource(R.string.auto_settings_graph_hours))
                        // "AUTO" (tema do carro) no lugar de "seguir o app"; mesma ordem dos outros grupos.
                        PeriodSelector(listOf("AUTO", GraphTheme.LIGHT, GraphTheme.DARK), ui.autoTheme, actions.onAutoTheme, {
                            if (it == "AUTO") stringResource(R.string.auto_settings_graph_theme_auto) else graphThemeLabel(it)
                        }, title = stringResource(R.string.settings_graphs_theme))
                    }
                }
            }
        }
    }
}

