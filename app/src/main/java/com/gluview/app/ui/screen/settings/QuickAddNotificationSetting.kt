package com.gluview.app.ui.screen.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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

    /** PT-6: notificação fixa como Live Update (Android 16). */
    val liveUpdate: StateFlow<Boolean> = preferences.notifLiveUpdate
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setLiveUpdate(on: Boolean) { viewModelScope.launch { preferences.saveNotifLiveUpdate(on) } }
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
    LiveUpdateSetting(vm)
}

/**
 * PT-6 — Ajustes › Geral: notificação fixa como Live Update (Android 16; na Samsung One UI 8, barra
 * Now). Só aparece no Android 16. Se o Android não deixar promover, diz isso e leva ao ajuste.
 */
@Composable
private fun LiveUpdateSetting(vm: QuickAddSettingsViewModel) {
    if (android.os.Build.VERSION.SDK_INT < 36) return
    val context = androidx.compose.ui.platform.LocalContext.current
    val on by vm.liveUpdate.collectAsState()
    var allowed by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(true) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        allowed = runCatching {
            context.getSystemService(android.app.NotificationManager::class.java).canPostPromotedNotifications()
        }.getOrDefault(false)
        onPauseOrDispose {}
    }
    GluSection(stringResource(R.string.settings_live_update_section)) {
        GluCard {
            SwitchRow(
                stringResource(R.string.settings_live_update), on, vm::setLiveUpdate,
                description = stringResource(R.string.settings_live_update_desc)
            )
        }
        if (on && !allowed) {
            com.gluview.app.ui.components.kit.GluCallout(
                stringResource(R.string.settings_live_update_not_allowed),
                tone = com.gluview.app.ui.components.kit.Tone.Warning,
                actionLabel = stringResource(R.string.settings_live_update_open_settings),
                onAction = {
                    runCatching {
                        context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS)
                            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
            )
        }
    }
}
