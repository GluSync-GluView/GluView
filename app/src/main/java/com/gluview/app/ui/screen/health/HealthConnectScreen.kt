package com.gluview.app.ui.screen.health

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gluview.app.R
import com.gluview.app.data.datastore.AppPreferences
import com.gluview.app.data.datastore.HealthConnectToggles
import com.gluview.app.data.health.HealthConnectGateway
import com.gluview.app.data.health.HealthConnectStatus
import com.gluview.app.data.health.HealthConnectSync
import com.gluview.app.data.local.dao.HealthRecordDao
import com.gluview.app.ui.components.chart.rememberChartAppearance
import com.gluview.app.ui.components.kit.ChoiceChips
import com.gluview.app.ui.components.kit.ConfirmDialog
import com.gluview.app.ui.components.kit.GluCallout
import com.gluview.app.ui.components.kit.GluCard
import com.gluview.app.ui.components.kit.GluSection
import com.gluview.app.ui.components.kit.NavRow
import com.gluview.app.ui.components.kit.Page
import com.gluview.app.ui.components.kit.SwitchRow
import com.gluview.app.ui.components.kit.Tone
import com.gluview.app.util.formatDateTime
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HealthConnectViewModel @Inject constructor(
    private val gateway: HealthConnectGateway,
    private val sync: HealthConnectSync,
    private val preferences: AppPreferences,
    private val records: HealthRecordDao,
    private val activityToday: com.gluview.app.data.health.HealthActivityToday
) : ViewModel() {
    private val _status = MutableStateFlow(gateway.status())
    val status: StateFlow<HealthConnectStatus> = _status.asStateFlow()
    private val _granted = MutableStateFlow<Set<String>>(emptySet())
    val granted: StateFlow<Set<String>> = _granted.asStateFlow()
    private val _background = MutableStateFlow(false)
    val backgroundSupported: StateFlow<Boolean> = _background.asStateFlow()
    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    val toggles: StateFlow<HealthConnectToggles> = preferences.healthConnectToggles
        .stateIn(viewModelScope, SharingStarted.Eagerly, HealthConnectToggles())
    val backfillDays: StateFlow<Int> = preferences.hcBackfillDays.stateIn(viewModelScope, SharingStarted.Eagerly, 7)
    val lastSync: StateFlow<Long> = preferences.hcLastSyncMs.stateIn(viewModelScope, SharingStarted.Eagerly, 0L)
    val lastError: StateFlow<String> = preferences.hcLastError.stateIn(viewModelScope, SharingStarted.Eagerly, "")

    fun refresh() {
        _status.value = gateway.status()
        viewModelScope.launch {
            if (_status.value == HealthConnectStatus.AVAILABLE) {
                _granted.value = gateway.granted()
                _background.value = gateway.backgroundReadSupported()
            }
        }
    }

    fun required(t: HealthConnectToggles): Set<String> = HealthConnectGateway.permissionsFor(t, _background.value)

    fun setToggles(t: HealthConnectToggles) {
        viewModelScope.launch {
            val before = preferences.healthConnectToggles.first()
            preferences.saveHealthConnectToggles(t)
            // Tipo de leitura ligado agora: a próxima troca busca o histórico inicial desse tipo.
            val newRead = (t.readGlucose && !before.readGlucose) || (t.readExercise && !before.readExercise) ||
                (t.readSleep && !before.readSleep) || (t.readCycle && !before.readCycle) || (t.readNutrition && !before.readNutrition)
            if (newRead) preferences.saveHcLastReadMs(0L)
            if (!t.readActivity) activityToday.clear()
            else if (!before.readActivity) activityToday.refresh(minIntervalMs = 0L)
        }
    }
    fun setBackfill(days: Int) { viewModelScope.launch { preferences.saveHcBackfillDays(days) } }

    fun syncNow() {
        if (_syncing.value) return
        viewModelScope.launch {
            _syncing.value = true
            sync.sync(foreground = true)
            activityToday.refresh(minIntervalMs = 0L)
            _granted.value = gateway.granted()
            _syncing.value = false
        }
    }

    fun clearImported() { viewModelScope.launch { records.deleteAll(); preferences.saveHcLastReadMs(0L) } }

    suspend fun currentToggles(): HealthConnectToggles = preferences.healthConnectToggles.first()
}

private fun openHealthConnectInstall(context: Context) {
    val pkg = "com.google.android.apps.healthdata"
    val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg&url=healthconnect%3A%2F%2Fonboarding"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(market) }.onFailure {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$pkg"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** Ajustes › Health Connect (plano 17, Fase 3). Tudo desligado por padrão; cada tipo liga separado. */
@Composable
fun HealthConnectScreen(onBack: () -> Unit, onPatterns: () -> Unit, vm: HealthConnectViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val appearance = rememberChartAppearance()
    val status by vm.status.collectAsState()
    val granted by vm.granted.collectAsState()
    val toggles by vm.toggles.collectAsState()
    val backfill by vm.backfillDays.collectAsState()
    val lastSync by vm.lastSync.collectAsState()
    val lastError by vm.lastError.collectAsState()
    val syncing by vm.syncing.collectAsState()
    var confirmClear by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) {
        vm.refresh(); vm.syncNow()
    }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) vm.refresh() }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    val missing = vm.required(toggles) - granted

    Page(stringResource(R.string.hc_title), onBack = onBack) {
        Text(stringResource(R.string.hc_intro), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)

        when (status) {
            HealthConnectStatus.NOT_INSTALLED -> GluCallout(stringResource(R.string.hc_not_installed), tone = Tone.Warning,
                actionLabel = stringResource(R.string.hc_install), onAction = { openHealthConnectInstall(context) })
            HealthConnectStatus.NEEDS_UPDATE -> GluCallout(stringResource(R.string.hc_needs_update), tone = Tone.Warning,
                actionLabel = stringResource(R.string.hc_update), onAction = { openHealthConnectInstall(context) })
            HealthConnectStatus.AVAILABLE -> if (toggles.any && missing.isNotEmpty()) {
                GluCallout(stringResource(R.string.hc_missing_permissions), tone = Tone.Warning,
                    actionLabel = stringResource(R.string.hc_grant), onAction = { launcher.launch(vm.required(toggles)) })
            }
        }

        val enabled = status == HealthConnectStatus.AVAILABLE
        GluSection(stringResource(R.string.hc_section_write), description = stringResource(R.string.hc_section_write_desc)) {
            GluCard {
                SwitchRow(stringResource(R.string.hc_write_glucose), toggles.writeGlucose, { vm.setToggles(toggles.copy(writeGlucose = it)) },
                    description = stringResource(R.string.hc_write_glucose_desc), enabled = enabled)
                SwitchRow(stringResource(R.string.hc_write_nutrition), toggles.writeNutrition, { vm.setToggles(toggles.copy(writeNutrition = it)) },
                    description = stringResource(R.string.hc_write_nutrition_desc), enabled = enabled)
            }
        }
        GluSection(stringResource(R.string.hc_section_read), description = stringResource(R.string.hc_section_read_desc)) {
            GluCard {
                SwitchRow(stringResource(R.string.hc_read_activity), toggles.readActivity, { vm.setToggles(toggles.copy(readActivity = it)) },
                    description = stringResource(R.string.hc_read_activity_desc), enabled = enabled)
                SwitchRow(stringResource(R.string.hc_read_exercise), toggles.readExercise, { vm.setToggles(toggles.copy(readExercise = it)) },
                    description = stringResource(R.string.hc_read_exercise_desc), enabled = enabled)
                SwitchRow(stringResource(R.string.hc_read_sleep), toggles.readSleep, { vm.setToggles(toggles.copy(readSleep = it)) },
                    description = stringResource(R.string.hc_read_sleep_desc), enabled = enabled)
                SwitchRow(stringResource(R.string.hc_read_cycle), toggles.readCycle, { vm.setToggles(toggles.copy(readCycle = it)) },
                    description = stringResource(R.string.hc_read_cycle_desc), enabled = enabled)
                SwitchRow(stringResource(R.string.hc_read_nutrition), toggles.readNutrition, { vm.setToggles(toggles.copy(readNutrition = it)) },
                    description = stringResource(R.string.hc_read_nutrition_desc), enabled = enabled)
                SwitchRow(stringResource(R.string.hc_read_glucose), toggles.readGlucose, { vm.setToggles(toggles.copy(readGlucose = it)) },
                    description = stringResource(R.string.hc_read_glucose_desc), enabled = enabled)
            }
        }
        GluSection(stringResource(R.string.hc_section_history)) {
            ChoiceChips(listOf(1, 7, 30), backfill, vm::setBackfill, { pluralStringResource(R.plurals.hc_days_plural, it, it) })
            Text(stringResource(R.string.hc_history_desc), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (enabled && toggles.any) {
            Button(onClick = { if (missing.isNotEmpty()) launcher.launch(vm.required(toggles)) else vm.syncNow() },
                enabled = !syncing, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(if (missing.isNotEmpty()) R.string.hc_grant else R.string.hc_sync_now))
            }
            Text(
                if (lastSync > 0) stringResource(R.string.hc_last_sync, formatDateTime(lastSync, appearance.timeFormat))
                else stringResource(R.string.hc_never_synced),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (lastError.isNotBlank()) GluCallout(stringResource(R.string.hc_last_error, lastError), tone = Tone.Danger)
        }

        GluCard {
            NavRow(stringResource(R.string.patterns_title), onPatterns, subtitle = stringResource(R.string.patterns_subtitle))
            if (enabled) NavRow(stringResource(R.string.hc_open), {
                runCatching { context.startActivity(HealthConnectClient.getHealthConnectManageDataIntent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }, subtitle = stringResource(R.string.hc_open_desc))
        }
        OutlinedButton(onClick = { confirmClear = true }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.hc_clear_imported))
        }
    }

    if (confirmClear) ConfirmDialog(
        title = stringResource(R.string.hc_clear_imported),
        message = stringResource(R.string.hc_clear_imported_body),
        confirmLabel = stringResource(R.string.watchface_delete_button),
        onConfirm = { vm.clearImported(); confirmClear = false },
        onDismiss = { confirmClear = false }
    )
}
