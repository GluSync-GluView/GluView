package com.gluview.app.ui.screen.integrations

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewModelScope
import com.gluview.app.R
import com.gluview.app.data.broadcast.XdripBroadcaster
import com.gluview.app.data.datastore.AppPreferences
import com.gluview.app.ui.components.kit.CheckboxRow
import com.gluview.app.ui.components.kit.GluCallout
import com.gluview.app.ui.components.kit.GluCard
import com.gluview.app.ui.components.kit.GluSection
import com.gluview.app.ui.components.kit.SwitchRow
import com.gluview.app.ui.components.kit.Tone
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LocalBroadcastViewModel @Inject constructor(
    private val preferences: AppPreferences,
    private val broadcaster: XdripBroadcaster
) : ViewModel() {
    val enabled: StateFlow<Boolean> = preferences.localBroadcastEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val selected: StateFlow<Set<String>> = preferences.localBroadcastPackages
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    private val _apps = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val apps: StateFlow<List<Pair<String, String>>> = _apps.asStateFlow()

    fun refresh() { _apps.value = broadcaster.installedReceivers() }
    fun setEnabled(on: Boolean) { viewModelScope.launch { preferences.saveLocalBroadcastEnabled(on) } }
    fun toggle(pkg: String, on: Boolean) {
        viewModelScope.launch {
            val now = selected.value
            preferences.saveLocalBroadcastPackages(if (on) now + pkg else now - pkg)
        }
    }
}

/**
 * PT-7 Fase A — Integrações: enviar cada leitura para outros apps deste celular no formato do
 * xDrip+ (AndroidAPS e afins), sem nuvem. Só para os apps marcados aqui.
 */
@Composable
internal fun LocalBroadcastSetting(vm: LocalBroadcastViewModel = hiltViewModel()) {
    val enabled by vm.enabled.collectAsState()
    val selected by vm.selected.collectAsState()
    val apps by vm.apps.collectAsState()
    LifecycleResumeEffect(Unit) { vm.refresh(); onPauseOrDispose {} }
    GluSection(stringResource(R.string.local_broadcast_section), description = stringResource(R.string.local_broadcast_desc)) {
        GluCard {
            Column {
                SwitchRow(stringResource(R.string.local_broadcast_switch), enabled, vm::setEnabled)
                if (enabled) {
                    if (apps.isEmpty()) {
                        Text(stringResource(R.string.local_broadcast_no_apps), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    apps.forEach { (pkg, label) ->
                        CheckboxRow(label, pkg in selected, { vm.toggle(pkg, it) })
                    }
                }
            }
        }
        if (enabled && selected.isEmpty() && apps.isNotEmpty()) {
            GluCallout(stringResource(R.string.local_broadcast_pick_one), tone = Tone.Info)
        }
    }
}
