package com.gluview.app.ui.screen.alertsettings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.gluview.app.R
import com.gluview.app.alert.ALERT_ZONES
import com.gluview.app.alert.AlertType
import com.gluview.app.domain.model.GlucoseZone
import com.gluview.app.ui.components.chart.rememberChartAppearance
import com.gluview.app.ui.components.kit.AppDialog
import com.gluview.app.ui.components.kit.CheckboxRow
import com.gluview.app.ui.components.kit.GluCallout
import com.gluview.app.ui.components.kit.GluCard
import com.gluview.app.ui.components.kit.GluSection
import com.gluview.app.ui.components.kit.NavRow
import com.gluview.app.ui.components.kit.Page
import com.gluview.app.ui.components.kit.RadioRow
import com.gluview.app.ui.components.kit.RowGroup
import com.gluview.app.ui.components.kit.SecretTextField
import com.gluview.app.ui.components.kit.SwitchRow
import com.gluview.app.ui.components.kit.Tone
import com.gluview.app.ui.theme.Spacing
import com.gluview.app.util.GlucoseUnit
import com.gluview.app.util.TimeFormat
import kotlin.math.roundToInt

/** Prefixo padrão dos eventos do IFTTT (o mesmo do AppPreferences). */
private const val DEFAULT_IFTTT_PREFIX = "gluview_"

/** Ações da tela de Alertas (para o harness desenhar sem ViewModel). */
internal data class AlertActions(
    val onUrgentLow: (Float) -> Unit,
    val onLow: (Float) -> Unit,
    val onHigh: (Float) -> Unit,
    val onUrgentHigh: (Float) -> Unit,
    val onToggle: (GlucoseZone, AlertType, Boolean) -> Unit,
    val onPredictive: (Boolean, Int) -> Unit,
    val onReAlarm: (Boolean, Int) -> Unit,
    val onTts: (Boolean) -> Unit,
    val onQuiet: (Boolean, Int, Int) -> Unit,
    val onCall: (Boolean, String, Int) -> Unit,
    val onSms: (Boolean, String, String) -> Unit,
    val onNotifyFollowers: (Boolean) -> Unit,
    val onToggleFollower: (String) -> Unit
)

/** O que muda de cara conforme a conta (seguidor-apenas não tem faixas; Pushover/seguidores exigem GluSync). */
internal data class AlertContext(
    val unit: GlucoseUnit,
    val timeFormat: TimeFormat,
    val isFollowerOnly: Boolean,
    val isGluSyncConnected: Boolean
)

/** Erro de sincronia com o GluSync numa snackbar (antes só aparecia dentro da seção Pushover). */
@Composable
private fun SyncErrorSnackbar(syncError: String?, snackbar: SnackbarHostState) {
    val context = LocalContext.current
    LaunchedEffect(syncError) {
        syncError?.let { snackbar.showSnackbar(context.getString(R.string.alertsettings_sync_failed, it)) }
    }
}

@Composable
fun AlertSettingsScreen(
    onBack: () -> Unit,
    onIfttt: () -> Unit,
    onPushover: () -> Unit,
    viewModel: AlertSettingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val isFollowerOnly by viewModel.isFollowerOnly.collectAsState()
    val isGluSyncConnected by viewModel.isGluSyncConnected.collectAsState()
    val unit by viewModel.glucoseUnit.collectAsState()
    val syncError by viewModel.syncError.collectAsState()
    val timeFormat = rememberChartAppearance().timeFormat
    val snackbar = remember { SnackbarHostState() }
    SyncErrorSnackbar(syncError, snackbar)

    AlertsContent(
        state = state,
        ctx = AlertContext(unit, timeFormat, isFollowerOnly, isGluSyncConnected),
        actions = AlertActions(viewModel::onUrgentLowChange, viewModel::onLowChange, viewModel::onHighChange,
            viewModel::onUrgentHighChange, viewModel::onToggle, viewModel::savePredictive, viewModel::saveReAlarm,
            viewModel::saveTts, viewModel::saveQuiet, viewModel::saveCall, viewModel::saveSms,
            viewModel::saveNotifyFollowers, viewModel::toggleFollowerSelection),
        onIfttt = onIfttt,
        onPushover = onPushover,
        onBack = onBack,
        snackbarHostState = snackbar
    )
}

/**
 * Configurar alertas (redesign visual, Fase 6). Antes: 11 seções de mesmo peso, várias com um
 * switch só. Agora seis grupos — Faixas, Quando alertar, Comportamento, Emergência, Seguidores e
 * Integrações — com IFTTT e Pushover em sub-telas (decisão 5.5 #2). Linhas de switch e de caixa
 * de seleção inteiras tocáveis e com rótulo; valores com caixa ≥ 48dp, unidade e rótulo falado;
 * sliders de trilho liso; tipos por zona em chips que quebram, com nome inteiro ("Notificação");
 * horário do silêncio escolhido numa lista no formato 12/24h do usuário (antes "23h" com −/+).
 * Os valores e o momento em que cada um é salvo não mudaram.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AlertsContent(
    state: AlertSettingsUiState,
    ctx: AlertContext,
    actions: AlertActions,
    onIfttt: () -> Unit,
    onPushover: () -> Unit,
    onBack: () -> Unit,
    snackbarHostState: SnackbarHostState? = null
) {
    val unit = ctx.unit
    var showZoneTypesHelp by remember { mutableStateOf(false) }
    var editingHour by remember { mutableStateOf<QuietEdge?>(null) }

    Page(title = stringResource(R.string.settings_configure_alerts), onBack = onBack, snackbarHostState = snackbarHostState) {

        // Faixas de glicemia — avaliadas sobre a leitura LOCAL própria (não existem pro seguidor).
        if (!ctx.isFollowerOnly) {
            GluSection(stringResource(R.string.settings_glucose_ranges, unit.label)) {
                GluCard {
                    ThresholdRow(stringResource(R.string.settings_range_urgent_low), state.urgentLow, 40f..65f, unit, actions.onUrgentLow)
                    ThresholdRow(stringResource(R.string.settings_range_low), state.low, 60f..90f, unit, actions.onLow)
                    ThresholdRow(stringResource(R.string.settings_range_high), state.high, 150f..220f, unit, actions.onHigh)
                    ThresholdRow(stringResource(R.string.settings_range_urgent_high), state.urgentHigh, 200f..300f, unit, actions.onUrgentHigh)
                }
            }
        }

        // S13 — 7 zonas × 3 tipos
        GluSection(stringResource(R.string.alertsettings_glucose_zones), description = stringResource(R.string.alertsettings_zones_desc),
            onHelp = { showZoneTypesHelp = true }) {
            GluCard {
                RowGroup(ALERT_ZONES.map { zone ->
                    {
                        Column(Modifier.fillMaxWidth().padding(vertical = Spacing.xs), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                            Text(zoneLabel(zone, state, unit), style = MaterialTheme.typography.bodyLarge)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                                listOf(AlertType.NOTIFICATION, AlertType.ALARM, AlertType.DND).forEach { type ->
                                    val on = state.toggles[zone to type] ?: false
                                    FilterChip(selected = on, onClick = { actions.onToggle(zone, type, !on) }, label = { Text(typeLabel(type)) })
                                }
                            }
                        }
                    }
                })
            }
        }

        GluSection(stringResource(R.string.alertsettings_behavior_section)) {
            GluCard {
                RowGroup(buildList {
                    // S14 — Preditivo
                    add {
                        Column {
                            SwitchRow(stringResource(R.string.alertsettings_predictive_toggle), state.predictiveEnabled,
                                { actions.onPredictive(it, state.predictiveMinutes) },
                                description = stringResource(R.string.alertsettings_predictive))
                            if (state.predictiveEnabled) {
                                MinutesRow(stringResource(R.string.alertsettings_predictive_minutes_label), state.predictiveMinutes, 5..30, 5) {
                                    actions.onPredictive(state.predictiveEnabled, it)
                                }
                            }
                        }
                    }
                    // S14 — Re-alarme
                    add {
                        Column {
                            SwitchRow(stringResource(R.string.alertsettings_realarm_toggle), state.reAlarmEnabled,
                                { actions.onReAlarm(it, state.reAlarmInterval) })
                            if (state.reAlarmEnabled) {
                                MinutesRow(stringResource(R.string.alertsettings_realarm_interval_label), state.reAlarmInterval, 1..15, 1) {
                                    actions.onReAlarm(state.reAlarmEnabled, it)
                                }
                            }
                        }
                    }
                    // S15 — Voz
                    add {
                        SwitchRow(stringResource(R.string.alertsettings_tts_toggle), state.ttsEnabled, actions.onTts,
                            description = stringResource(R.string.alertsettings_tts))
                    }
                    // S15 — Silêncio noturno
                    add {
                        Column {
                            SwitchRow(stringResource(R.string.alertsettings_quiet_toggle), state.quietEnabled,
                                { actions.onQuiet(it, state.quietStart, state.quietEnd) },
                                description = stringResource(R.string.alertsettings_quiet_rule))
                            if (state.quietEnabled) {
                                NavRow(stringResource(R.string.alertsettings_quiet_start), { editingHour = QuietEdge.START },
                                    subtitle = hourLabel(state.quietStart, ctx.timeFormat), indent = true)
                                NavRow(stringResource(R.string.alertsettings_quiet_end), { editingHour = QuietEdge.END },
                                    subtitle = hourLabel(state.quietEnd, ctx.timeFormat), indent = true)
                            }
                        }
                    }
                })
            }
        }

        GluSection(stringResource(R.string.alertsettings_emergency_section)) {
            GluCard {
                RowGroup(listOf(
                    // S16 — Ligação
                    {
                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                            SwitchRow(stringResource(R.string.alertsettings_call_toggle), state.callEnabled,
                                { actions.onCall(it, state.callPhone, state.callDelay) },
                                description = stringResource(R.string.alertsettings_call_section))
                            if (state.callEnabled) {
                                OutlinedTextField(
                                    value = state.callPhone,
                                    onValueChange = { actions.onCall(state.callEnabled, it, state.callDelay) },
                                    label = { Text(stringResource(R.string.alertsettings_phone_number)) },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                                    modifier = Modifier.fillMaxWidth()
                                )
                                MinutesRow(stringResource(R.string.alertsettings_call_delay_label), state.callDelay, 2..15, 1) {
                                    actions.onCall(state.callEnabled, state.callPhone, it)
                                }
                            }
                        }
                    },
                    // S17 — SMS
                    {
                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                            SwitchRow(stringResource(R.string.alertsettings_sms_toggle), state.smsEnabled,
                                { actions.onSms(it, state.smsContact, state.smsMessage) },
                                description = stringResource(R.string.alertsettings_sms_section))
                            if (state.smsEnabled) {
                                OutlinedTextField(
                                    value = state.smsContact,
                                    onValueChange = { actions.onSms(state.smsEnabled, it, state.smsMessage) },
                                    label = { Text(stringResource(R.string.alertsettings_contact_number)) },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                                    modifier = Modifier.fillMaxWidth()
                                )
                                // O modelo padrão é a dica do campo (antes era o VALOR: apagar fazia ele
                                // voltar na hora). Vazio continua usando o modelo, como sempre.
                                OutlinedTextField(
                                    value = state.smsMessage,
                                    onValueChange = { actions.onSms(state.smsEnabled, state.smsContact, it) },
                                    label = { Text(stringResource(R.string.alertsettings_sms_message_label)) },
                                    placeholder = { Text(stringResource(R.string.alertsettings_sms_default_message)) },
                                    supportingText = { Text(stringResource(R.string.alertsettings_sms_message_hint)) },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                ))
            }
        }

        // Avisar seguidores (só com GluSync conectado e conta que não é só seguidor)
        if (ctx.isGluSyncConnected && !ctx.isFollowerOnly) {
            GluSection(stringResource(R.string.alertsettings_followers_section)) {
                GluCard {
                    SwitchRow(stringResource(R.string.alertsettings_followers_toggle), state.notifyFollowersEnabled, actions.onNotifyFollowers)
                    val n = state.notifyFollowersSpecificIds.size
                    GluCallout(
                        if (n == 0) stringResource(R.string.alertsettings_followers_all)
                        else pluralStringResource(R.plurals.alertsettings_followers_selected, n, n)
                    )
                    if (state.followers.isEmpty()) {
                        Text(stringResource(R.string.alertsettings_followers_empty), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        state.followers.forEach { link ->
                            val name = link.followerName?.ifBlank { null } ?: link.followerEmail
                            CheckboxRow(
                                title = name,
                                checked = link.followerId in state.notifyFollowersSpecificIds,
                                onCheckedChange = { actions.onToggleFollower(link.followerId) },
                                description = link.followerEmail.takeIf { link.followerName != null },
                                enabled = state.notifyFollowersEnabled
                            )
                        }
                    }
                }
            }
        }

        GluSection(stringResource(R.string.settings_integrations_title)) {
            GluCard {
                RowGroup(buildList {
                    add {
                        NavRow(stringResource(R.string.alertsettings_ifttt_section), onIfttt, icon = Icons.Default.Link,
                            subtitle = stringResource(if (state.iftttEnabled) R.string.common_on else R.string.common_off))
                    }
                    if (ctx.isGluSyncConnected && !ctx.isFollowerOnly) add {
                        NavRow(stringResource(R.string.alertsettings_pushover_title), onPushover, icon = Icons.Default.Notifications,
                            subtitle = stringResource(if (state.pushoverEnabled) R.string.common_on else R.string.common_off))
                    }
                })
            }
        }
    }

    if (showZoneTypesHelp) {
        AppDialog(
            title = stringResource(R.string.alertsettings_zone_types_help_title),
            body = stringResource(R.string.alertsettings_zone_types_help_body),
            onDismiss = { showZoneTypesHelp = false },
            confirmLabel = stringResource(R.string.alertsettings_zone_types_help_close)
        )
    }

    editingHour?.let { edge ->
        val current = if (edge == QuietEdge.START) state.quietStart else state.quietEnd
        HourDialog(
            title = stringResource(if (edge == QuietEdge.START) R.string.alertsettings_quiet_start else R.string.alertsettings_quiet_end),
            selected = current,
            timeFormat = ctx.timeFormat,
            onSelect = { h ->
                if (edge == QuietEdge.START) actions.onQuiet(state.quietEnabled, h, state.quietEnd)
                else actions.onQuiet(state.quietEnabled, state.quietStart, h)
                editingHour = null
            },
            onDismiss = { editingHour = null }
        )
    }
}

private enum class QuietEdge { START, END }

/** Hora cheia no formato do usuário ("23:00" / "11 PM"). */
private fun hourLabel(hour: Int, format: TimeFormat): String =
    if (format == TimeFormat.H24) "%02d:00".format(hour)
    else "${if (hour % 12 == 0) 12 else hour % 12} ${if (hour < 12) "AM" else "PM"}"

/** Lista das 24 horas (antes: "23h" e botões −/+, 8 toques para ir de 7h a 23h). */
@Composable
private fun HourDialog(title: String, selected: Int, timeFormat: TimeFormat, onSelect: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()).selectableGroup()) {
                (0 until 24).forEach { h -> RadioRow(hourLabel(h, timeFormat), h == selected, { onSelect(h) }) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } }
    )
}

/** Valor da faixa na unidade do usuário, no idioma (antes Locale.US: "5.5" em português). */
private fun formatThreshold(mgdl: Float, unit: GlucoseUnit): String = with(unit) { mgdl.toDouble().toDisplay() }

@Composable
private fun zoneLabel(zone: GlucoseZone, state: AlertSettingsUiState, unit: GlucoseUnit): String = when (zone) {
    GlucoseZone.URGENT_LOW  -> stringResource(R.string.alertsettings_zone_urgent_low, formatThreshold(state.urgentLow, unit))
    GlucoseZone.LOW         -> stringResource(R.string.alertsettings_zone_low, formatThreshold(state.urgentLow, unit), formatThreshold(state.low, unit))
    GlucoseZone.HIGH        -> stringResource(R.string.alertsettings_zone_high, formatThreshold(state.high, unit), formatThreshold(state.urgentHigh, unit))
    GlucoseZone.URGENT_HIGH -> stringResource(R.string.alertsettings_zone_urgent_high, formatThreshold(state.urgentHigh, unit))
    GlucoseZone.STALE       -> stringResource(R.string.alerthistory_zone_stale)
    GlucoseZone.QUICK_DROP  -> stringResource(R.string.alertsettings_zone_quick_drop)
    GlucoseZone.NORMAL      -> stringResource(R.string.zone_in_range)
}

@Composable
private fun typeLabel(type: AlertType): String = stringResource(when (type) {
    AlertType.NOTIFICATION -> R.string.alertsettings_type_notification
    AlertType.ALARM        -> R.string.alerthistory_type_alarm
    AlertType.DND          -> R.string.alertsettings_type_overrides_dnd
    AlertType.PUSHOVER     -> R.string.alertsettings_pushover_title
})

private val noTicks @Composable get() = SliderDefaults.colors(
    activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent,
    disabledActiveTickColor = Color.Transparent, disabledInactiveTickColor = Color.Transparent
)

/**
 * Linha de valor com slider e caixa para digitar: rótulo com weight (não espreme a caixa), caixa
 * ≥ 48dp de altura com a unidade e o rótulo falado (antes uma caixa de 56dp de largura que cortava
 * "250" com fonte grande e o TalkBack lia só o número).
 */
@Composable
private fun ValueSliderRow(
    label: String,
    text: String,
    onText: (String) -> Unit,
    suffix: String,
    keyboard: KeyboardType,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    step: Float,
    shown: String,
    onSlide: (Float) -> Unit
) {
    val steps = (((range.endInclusive - range.start) / step).roundToInt() - 1).coerceAtLeast(0)
    // Com fonte grande o rótulo vai para cima da caixa ("antecedência" quebrava no meio).
    val stacked = LocalDensity.current.fontScale >= 1.5f
    val field: @Composable () -> Unit = {
        OutlinedTextField(
            value = text,
            onValueChange = onText,
            singleLine = true,
            suffix = { Text(suffix, style = MaterialTheme.typography.labelMedium) },
            textStyle = MaterialTheme.typography.bodyLarge.copy(textAlign = TextAlign.End),
            keyboardOptions = KeyboardOptions(keyboardType = keyboard),
            modifier = Modifier.widthIn(min = 96.dp, max = 128.dp).semantics { contentDescription = label }
        )
    }
    Column(Modifier.fillMaxWidth()) {
        if (stacked) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            field()
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                field()
            }
        }
        Slider(
            value = value,
            onValueChange = onSlide,
            valueRange = range,
            steps = steps,
            colors = noTicks,
            modifier = Modifier.semantics { contentDescription = label; stateDescription = shown }
        )
    }
}

@Composable
private fun ThresholdRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, unit: GlucoseUnit, onChange: (Float) -> Unit) {
    // Armazenado sempre em mg/dL inteiro; só a exibição/digitação vira mmol/L (1 casa).
    val display = remember(value, unit) { formatThreshold(value, unit) }
    var text by remember(display) { mutableStateOf(display) }
    ValueSliderRow(
        label = label, text = text, suffix = unit.label,
        onText = { new ->
            val filtered = if (unit == GlucoseUnit.MMOL_L) new.filter { it.isDigit() || it == '.' || it == ',' }.take(4)
                           else new.filter { it.isDigit() }.take(3)
            text = filtered
            filtered.replace(',', '.').toFloatOrNull()?.let { typed ->
                val mgdl = if (unit == GlucoseUnit.MMOL_L) (typed * 18f).roundToInt().toFloat() else typed
                if (mgdl in range) onChange(mgdl)
            }
        },
        keyboard = if (unit == GlucoseUnit.MMOL_L) KeyboardType.Decimal else KeyboardType.Number,
        value = value, range = range, step = 1f, shown = "$display ${unit.label}",
        onSlide = { onChange(it.roundToInt().toFloat()) }
    )
}

/** Minutos (preditivo, re-alarme, atraso da ligação): mesma linha das faixas, com "min". */
@Composable
private fun MinutesRow(label: String, value: Int, range: IntRange, step: Int, onChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    val unit = stringResource(R.string.alertsettings_minutes_unit)
    ValueSliderRow(
        label = label, text = text, suffix = unit,
        onText = { new ->
            val digits = new.filter { it.isDigit() }.take(3)
            text = digits
            digits.toIntOrNull()?.let { v -> if (v in range) onChange(v) }
        },
        keyboard = KeyboardType.Number,
        value = value.toFloat(), range = range.first.toFloat()..range.last.toFloat(), step = step.toFloat(),
        shown = "$value $unit",
        onSlide = { onChange(it.roundToInt()) }
    )
}

// ── Sub-telas: IFTTT e Pushover (decisão 5.5 #2) ────────────────────────────────────────────

private fun iftttSlug(zone: GlucoseZone): String = when (zone) {
    GlucoseZone.URGENT_LOW  -> "urgent_low"
    GlucoseZone.LOW         -> "low"
    GlucoseZone.HIGH        -> "high"
    GlucoseZone.URGENT_HIGH -> "urgent_high"
    GlucoseZone.STALE       -> "stale"
    GlucoseZone.QUICK_DROP  -> "quick_drop"
    GlucoseZone.NORMAL      -> "normal"
}

@Composable
private fun HelpAction(label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(Icons.AutoMirrored.Outlined.HelpOutline, contentDescription = "${stringResource(R.string.common_help)}: $label")
    }
}

@Composable
fun AlertIftttScreen(onBack: () -> Unit, viewModel: AlertSettingsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()
    val unit by viewModel.glucoseUnit.collectAsState()
    val result by viewModel.iftttTestResult.collectAsState()
    IftttContent(state, unit, result, IftttActions(viewModel::saveIftttEnabled, viewModel::saveIftttApiKey,
        viewModel::saveIftttPrefix, viewModel::setIftttZone, viewModel::setIftttPredictive, viewModel::testIfttt), onBack)
}

internal data class IftttActions(
    val onEnabled: (Boolean) -> Unit,
    val onApiKey: (String) -> Unit,
    val onPrefix: (String) -> Unit,
    val onZone: (GlucoseZone, Boolean) -> Unit,
    val onPredictive: (Boolean) -> Unit,
    val onTest: () -> Unit
)

/** IFTTT: a chave como segredo (antes em texto aberto), zonas em linhas tocáveis com o nome do evento. */
@Composable
internal fun IftttContent(state: AlertSettingsUiState, unit: GlucoseUnit, result: TestResult?, actions: IftttActions, onBack: () -> Unit) {
    var showHelp by remember { mutableStateOf(false) }
    val title = stringResource(R.string.alertsettings_ifttt_section)
    Page(title = title, onBack = onBack, actions = { HelpAction(title) { showHelp = true } }) {
        GluCard { SwitchRow(stringResource(R.string.alertsettings_ifttt_toggle), state.iftttEnabled, actions.onEnabled) }
        if (state.iftttEnabled) {
            val prefix = state.iftttEventPrefix.ifBlank { DEFAULT_IFTTT_PREFIX }
            GluSection(stringResource(R.string.alertsettings_ifttt_key_section)) {
                GluCard {
                    SecretTextField(state.iftttApiKey, actions.onApiKey, stringResource(R.string.alertsettings_ifttt_api_key_label),
                        keyboardType = KeyboardType.Text)
                    OutlinedTextField(
                        value = state.iftttEventPrefix,
                        onValueChange = actions.onPrefix,
                        label = { Text(stringResource(R.string.alertsettings_ifttt_prefix_label)) },
                        supportingText = { Text(stringResource(R.string.alertsettings_ifttt_prefix_hint, prefix)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            GluSection(stringResource(R.string.alertsettings_ifttt_zones_label)) {
                GluCard {
                    ALERT_ZONES.forEach { zone ->
                        CheckboxRow(zoneLabel(zone, state, unit), state.iftttZoneToggles[zone] ?: false, { actions.onZone(zone, it) },
                            description = stringResource(R.string.alertsettings_ifttt_event_name, prefix + iftttSlug(zone)))
                    }
                    CheckboxRow(stringResource(R.string.alertsettings_ifttt_zone_predictive), state.iftttPredictive, actions.onPredictive,
                        description = stringResource(R.string.alertsettings_ifttt_event_name, prefix + "predictive"))
                }
            }
            Button(onClick = actions.onTest, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.alertsettings_ifttt_test_button)) }
            result?.let {
                GluCallout(
                    stringResource(if (it.ok) R.string.alertsettings_ifttt_test_ok else R.string.alertsettings_ifttt_test_fail),
                    tone = if (it.ok) Tone.Success else Tone.Danger, announce = true
                )
            }
        }
    }
    if (showHelp) {
        AppDialog(stringResource(R.string.alertsettings_ifttt_help_title), stringResource(R.string.alertsettings_ifttt_help_body),
            { showHelp = false }, confirmLabel = stringResource(R.string.alertsettings_zone_types_help_close))
    }
}

@Composable
fun AlertPushoverScreen(onBack: () -> Unit, viewModel: AlertSettingsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()
    val unit by viewModel.glucoseUnit.collectAsState()
    val result by viewModel.pushoverTestResult.collectAsState()
    val syncError by viewModel.syncError.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    SyncErrorSnackbar(syncError, snackbar)
    PushoverContent(state, unit, result, { viewModel.savePushover(it, state.pushoverUserKey) }, { viewModel.savePushover(true, it) },
        { zone, on -> viewModel.onToggle(zone, AlertType.PUSHOVER, on) }, viewModel::testPushover, onBack, snackbar)
}

/** Pushover: textos traduzidos (antes literais em pt, com "envía"), chave com rótulo no olho. */
@Composable
internal fun PushoverContent(
    state: AlertSettingsUiState,
    unit: GlucoseUnit,
    result: TestResult?,
    onEnabled: (Boolean) -> Unit,
    onUserKey: (String) -> Unit,
    onZone: (GlucoseZone, Boolean) -> Unit,
    onTest: () -> Unit,
    onBack: () -> Unit,
    snackbarHostState: SnackbarHostState? = null
) {
    var showHelp by remember { mutableStateOf(false) }
    val title = stringResource(R.string.alertsettings_pushover_title)
    Page(title = title, onBack = onBack, snackbarHostState = snackbarHostState, actions = { HelpAction(title) { showHelp = true } }) {
        GluCard { SwitchRow(stringResource(R.string.alertsettings_pushover_toggle), state.pushoverEnabled, onEnabled) }
        if (state.pushoverEnabled) {
            GluCard {
                SecretTextField(state.pushoverUserKey, onUserKey, stringResource(R.string.alertsettings_pushover_key_label),
                    supportingText = stringResource(R.string.alertsettings_pushover_key_hint), keyboardType = KeyboardType.Text)
            }
            GluSection(stringResource(R.string.alertsettings_pushover_zones_label)) {
                GluCard {
                    ALERT_ZONES.forEach { zone ->
                        CheckboxRow(zoneLabel(zone, state, unit), state.toggles[zone to AlertType.PUSHOVER] ?: false, { onZone(zone, it) })
                    }
                }
            }
            Button(onClick = onTest, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.alertsettings_pushover_test)) }
            result?.let {
                GluCallout(
                    if (it.ok) it.detail?.ifBlank { null } ?: stringResource(R.string.alertsettings_pushover_test_ok)
                    else listOfNotNull(stringResource(R.string.alertsettings_pushover_test_fail), it.detail).joinToString("\n"),
                    tone = if (it.ok) Tone.Success else Tone.Danger, announce = true
                )
            }
        }
    }
    if (showHelp) {
        AppDialog(title, stringResource(R.string.alertsettings_pushover_help_body), { showHelp = false },
            confirmLabel = stringResource(R.string.alertsettings_zone_types_help_close))
    }
}
