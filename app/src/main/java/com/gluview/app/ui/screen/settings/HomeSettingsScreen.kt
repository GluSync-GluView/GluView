package com.gluview.app.ui.screen.settings

import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gluview.app.R
import com.gluview.app.data.datastore.AppPreferences
import com.gluview.app.ui.components.kit.GluCard
import com.gluview.app.ui.components.kit.GluSection
import com.gluview.app.ui.components.kit.Page
import com.gluview.app.ui.components.kit.RowGroup
import com.gluview.app.ui.components.kit.SwitchRow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeSettingsViewModel @Inject constructor(
    private val preferences: AppPreferences
) : ViewModel() {
    val showStats: StateFlow<Boolean> = preferences.homeShowStats
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val showDiary: StateFlow<Boolean> = preferences.homeShowDiary
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    fun setShowStats(on: Boolean) { viewModelScope.launch { preferences.saveHomeShowStats(on) } }
    fun setShowDiary(on: Boolean) { viewModelScope.launch { preferences.saveHomeShowDiary(on) } }
}

/**
 * Ajustes › Home (15/11/2026): o que aparece na tela inicial além da glicemia e do gráfico. Ocultar
 * uma linha (aqui ou com toque longo nela, na Home) deixa o gráfico maior; aqui ela volta.
 */
@Composable
fun HomeSettingsScreen(onBack: () -> Unit, vm: HomeSettingsViewModel = hiltViewModel()) {
    val showStats by vm.showStats.collectAsState()
    val showDiary by vm.showDiary.collectAsState()
    HomeSettingsContent(showStats, showDiary, vm::setShowStats, vm::setShowDiary, onBack)
}

@Composable
internal fun HomeSettingsContent(
    showStats: Boolean,
    showDiary: Boolean,
    onShowStats: (Boolean) -> Unit,
    onShowDiary: (Boolean) -> Unit,
    onBack: () -> Unit
) {
    Page(title = stringResource(R.string.settings_home_title), onBack = onBack) {
        Text(stringResource(R.string.settings_home_intro), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        GluSection(stringResource(R.string.settings_home_section)) {
            GluCard {
                RowGroup(listOf(
                    { SwitchRow(stringResource(R.string.settings_home_stats), showStats, onShowStats,
                        description = stringResource(R.string.settings_home_stats_desc)) },
                    { SwitchRow(stringResource(R.string.settings_home_diary), showDiary, onShowDiary,
                        description = stringResource(R.string.settings_home_diary_desc)) }
                ))
            }
        }
    }
}
