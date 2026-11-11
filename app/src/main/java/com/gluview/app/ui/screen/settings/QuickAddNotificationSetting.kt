package com.gluview.app.ui.screen.settings

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
import com.gluview.app.ui.components.kit.SwitchRow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class QuickAddSettingsViewModel @Inject constructor(
    private val preferences: AppPreferences
) : ViewModel() {
    val enabled: StateFlow<Boolean> = preferences.notifQuickAdd
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    fun set(on: Boolean) { viewModelScope.launch { preferences.saveNotifQuickAdd(on) } }
}

/** Ajustes › Geral: mostrar ou esconder o botão "Registrar no diário" da notificação fixa. */
@Composable
internal fun QuickAddNotificationSetting(vm: QuickAddSettingsViewModel = hiltViewModel()) {
    val enabled by vm.enabled.collectAsState()
    GluSection(stringResource(R.string.diary_title)) {
        GluCard {
            SwitchRow(
                stringResource(R.string.settings_notif_quick_add), enabled, vm::set,
                description = stringResource(R.string.settings_notif_quick_add_desc)
            )
        }
    }
}
