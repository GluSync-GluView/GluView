package com.gluview.app.ui.screen.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewModelScope
import com.gluview.app.R
import com.gluview.app.data.datastore.AppPreferences
import com.gluview.app.overlay.FloatingConfig
import com.gluview.app.ui.components.kit.GluCallout
import com.gluview.app.ui.components.kit.GluCard
import com.gluview.app.ui.components.kit.GluSection
import com.gluview.app.ui.components.kit.Page
import com.gluview.app.ui.components.kit.RowGroup
import com.gluview.app.ui.components.kit.SliderRow
import com.gluview.app.ui.components.kit.SwitchRow
import com.gluview.app.ui.components.kit.Tone
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.roundToInt

@HiltViewModel
class FloatingSettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: AppPreferences
) : ViewModel() {
    val config: StateFlow<FloatingConfig> = preferences.floatingConfig
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FloatingConfig())
    private val _canDraw = MutableStateFlow(Settings.canDrawOverlays(context))
    val canDraw: StateFlow<Boolean> = _canDraw

    fun refresh() { _canDraw.value = Settings.canDrawOverlays(context) }

    fun change(transform: (FloatingConfig) -> FloatingConfig) {
        viewModelScope.launch { preferences.saveFloatingConfig(transform(preferences.floatingConfig.first())) }
    }
}

/**
 * Plano 21, F1 — Ajustes › Janela flutuante. A permissão "Sobrepor a outros apps" só é pedida
 * depois desta tela explicar para que serve; sem ela a janela não aparece (o resto fica salvo).
 */
@Composable
fun FloatingSettingsScreen(onBack: () -> Unit, vm: FloatingSettingsViewModel = hiltViewModel()) {
    val config by vm.config.collectAsState()
    val canDraw by vm.canDraw.collectAsState()
    val context = LocalContext.current
    LifecycleResumeEffect(Unit) { vm.refresh(); onPauseOrDispose {} }
    FloatingSettingsContent(config, canDraw, onChange = vm::change,
        onGrant = { openOverlayPermission(context) }, onBack = onBack)
}

private fun openOverlayPermission(context: Context) {
    val i = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(i) }
}

@Composable
internal fun FloatingSettingsContent(
    config: FloatingConfig,
    canDraw: Boolean,
    onChange: ((FloatingConfig) -> FloatingConfig) -> Unit,
    onGrant: () -> Unit,
    onBack: () -> Unit
) {
    Page(title = stringResource(R.string.floating_title), onBack = onBack) {
        Text(stringResource(R.string.floating_intro), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!canDraw) {
            GluCallout(stringResource(R.string.floating_permission_body), tone = Tone.Warning,
                title = stringResource(R.string.floating_permission_title),
                actionLabel = stringResource(R.string.floating_permission_action), onAction = onGrant)
        }
        GluCard {
            SwitchRow(stringResource(R.string.floating_enable), config.enabled,
                { on -> onChange { it.copy(enabled = on) }; if (on && !canDraw) onGrant() },
                description = stringResource(R.string.floating_enable_desc))
        }
        GluSection(stringResource(R.string.floating_section_look)) {
            GluCard {
                RowGroup(listOf(
                    { SliderRow(stringResource(R.string.floating_size), config.scale,
                        FloatingConfig.MIN_SCALE..FloatingConfig.MAX_SCALE, 0.1f,
                        { "${(it * 100).roundToInt()}%" }, { v -> onChange { it.copy(scale = v) } }) },
                    { SliderRow(stringResource(R.string.floating_opacity), config.alpha,
                        FloatingConfig.MIN_ALPHA..1f, 0.1f,
                        { "${(it * 100).roundToInt()}%" }, { v -> onChange { it.copy(alpha = v) } }) },
                    { SwitchRow(stringResource(R.string.floating_show_arrow), config.showArrow,
                        { v -> onChange { it.copy(showArrow = v) } }) },
                    { SwitchRow(stringResource(R.string.floating_show_delta), config.showDelta,
                        { v -> onChange { it.copy(showDelta = v) } }) },
                    { SwitchRow(stringResource(R.string.floating_show_age), config.showAge,
                        { v -> onChange { it.copy(showAge = v) } },
                        description = stringResource(R.string.floating_show_age_desc)) },
                    { SwitchRow(stringResource(R.string.floating_zone_bg), config.zoneBackground,
                        { v -> onChange { it.copy(zoneBackground = v) } }) },
                    { SwitchRow(stringResource(R.string.floating_highlight), config.highlightAlerts,
                        { v -> onChange { it.copy(highlightAlerts = v) } },
                        description = stringResource(R.string.floating_highlight_desc)) }
                ))
            }
        }
        GluSection(stringResource(R.string.floating_section_behavior)) {
            GluCard {
                RowGroup(listOf(
                    { SwitchRow(stringResource(R.string.floating_locked), config.locked,
                        { v -> onChange { it.copy(locked = v) } },
                        description = stringResource(R.string.floating_locked_desc)) },
                    { SwitchRow(stringResource(R.string.floating_hide_locked), config.hideWhenLocked,
                        { v -> onChange { it.copy(hideWhenLocked = v) } }) },
                    { SliderRow(stringResource(R.string.floating_hide_minutes), config.hideMinutes.toFloat(),
                        5f..120f, 5f, { "${it.roundToInt()} min" },
                        { v -> onChange { it.copy(hideMinutes = v.roundToInt()) } }) }
                ))
            }
            OutlinedButton(onClick = { onChange { it.copy(x = -1, y = -1) } }) {
                Text(stringResource(R.string.floating_reset_position))
            }
        }
        Text(stringResource(R.string.floating_how_to), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
