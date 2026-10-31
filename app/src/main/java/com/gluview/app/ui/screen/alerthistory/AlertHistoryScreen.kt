package com.gluview.app.ui.screen.alerthistory

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.gluview.app.R
import com.gluview.app.data.local.entity.AlertLogEntity
import com.gluview.app.domain.model.GlucoseZone
import com.gluview.app.ui.components.chart.rememberChartAppearance
import com.gluview.app.ui.components.kit.AppTopBar
import com.gluview.app.ui.components.kit.ConfirmDialog
import com.gluview.app.ui.components.kit.EmptyState
import com.gluview.app.ui.components.kit.GluCard
import com.gluview.app.ui.components.kit.GlucoseZoneChip
import com.gluview.app.ui.components.kit.ListSkeleton
import com.gluview.app.ui.components.kit.RowGroup
import com.gluview.app.ui.components.kit.StatusBadge
import com.gluview.app.ui.components.kit.Tone
import com.gluview.app.ui.theme.Spacing
import com.gluview.app.util.GlucoseUnit
import com.gluview.app.util.TimeFormat
import com.gluview.app.util.dayLabel
import com.gluview.app.util.epochDay
import com.gluview.app.util.formatGlucoseWithUnit
import com.gluview.app.util.formatTime

@Composable
fun AlertHistoryScreen(
    onBack: () -> Unit,
    onOpenCaregiver: () -> Unit = {},
    viewModel: AlertHistoryViewModel = hiltViewModel()
) {
    val alerts by viewModel.alerts.collectAsState()
    val appearance = rememberChartAppearance()
    var showClearDialog by remember { mutableStateOf(false) }

    AlertHistoryContent(
        alerts = alerts,
        unit = appearance.unit,
        timeFormat = appearance.timeFormat,
        onDismissAll = viewModel::dismissAll,
        onClear = { showClearDialog = true },
        onOpen = { alert ->
            if (!alert.dismissed) viewModel.dismissOne(alert.id)
            // Avisos de acompanhamento sempre levam à tela de gerenciamento ao tocar.
            if (alert.kind == "caregiver_invite" || alert.kind == "caregiver_accepted") onOpenCaregiver()
        },
        onReconnect = viewModel::forceUploadReconnect,
        onBack = onBack
    )

    if (showClearDialog) {
        ConfirmDialog(
            title = stringResource(R.string.alerthistory_clear_confirm_title),
            message = stringResource(R.string.alerthistory_clear_confirm_body),
            confirmLabel = stringResource(R.string.alerthistory_clear_confirm_button),
            onConfirm = { viewModel.clearAll(); showClearDialog = false },
            onDismiss = { showClearDialog = false }
        )
    }
}

/**
 * Histórico de alertas (redesign visual, Fase 7): agrupado por dia com a hora no formato do
 * usuário (antes "dd/MM HH:mm" fixo em pt-BR); valor na unidade do usuário; a zona num chip com
 * ícone + nome em cor de texto com contraste (antes texto colorido de ~2,8:1 sobre fundo tingido);
 * tipo por extenso ("Notificação", não "Notif."/"DND"); "Novo" como selo, não só um tom de fundo;
 * títulos pelo tipo do aviso no idioma do app (o título gravado é em pt); reconectar em largura
 * toda; apagar com confirmação em vermelho; carregando sem piscar "Nenhum alerta".
 */
@Composable
internal fun AlertHistoryContent(
    alerts: List<AlertLogEntity>?,
    unit: GlucoseUnit,
    timeFormat: TimeFormat,
    onDismissAll: () -> Unit,
    onClear: () -> Unit,
    onOpen: (AlertLogEntity) -> Unit,
    onReconnect: () -> Unit,
    onBack: () -> Unit,
    nowMs: Long = System.currentTimeMillis()
) {
    val context = LocalContext.current
    val list = alerts.orEmpty()
    Scaffold(topBar = {
        // Só "Apagar" na barra: com dois ícones o título cortava com fonte grande.
        AppTopBar(stringResource(R.string.home_alert_history), onBack) {
            IconButton(onClick = onClear, enabled = list.isNotEmpty()) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.alerthistory_clear_history))
            }
        }
    }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = Spacing.gutter, vertical = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            when {
                alerts == null -> item { ListSkeleton(rows = 3) }
                list.isEmpty() -> item {
                    EmptyState(stringResource(R.string.alerthistory_no_alerts), stringResource(R.string.alerthistory_empty_body),
                        Icons.Outlined.NotificationsNone)
                }
                else -> {
                    // Marcar todos como vistos → zera o badge do sino
                    if (list.any { !it.dismissed }) item {
                        OutlinedButton(onClick = onDismissAll, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.DoneAll, null, modifier = Modifier.padding(end = Spacing.xs))
                            Text(stringResource(R.string.alerthistory_mark_all_seen))
                        }
                    }
                    list.groupBy { epochDay(it.triggeredAt) }.forEach { (_, dayAlerts) ->
                    item(key = "day-${dayAlerts.first().triggeredAt}") {
                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                            Text(context.dayLabel(dayAlerts.first().triggeredAt, nowMs),
                                style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.semantics { heading() })
                            GluCard {
                                RowGroup(dayAlerts.map { a -> { AlertRow(a, unit, timeFormat, { onOpen(a) }, onReconnect) } })
                            }
                        }
                    }
                    }
                }
            }
        }
    }
}

@Composable
private fun typeLabel(type: String): String = stringResource(when (type) {
    "alarm" -> R.string.alerthistory_type_alarm
    "dnd"   -> R.string.alertsettings_type_overrides_dnd
    else    -> R.string.alertsettings_type_notification
})

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AlertRow(alert: AlertLogEntity, unit: GlucoseUnit, timeFormat: TimeFormat, onClick: () -> Unit, onReconnect: () -> Unit) {
    val time = formatTime(alert.triggeredAt, timeFormat)
    Column(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick).padding(vertical = Spacing.xs),
        verticalArrangement = Arrangement.spacedBy(Spacing.xxs)
    ) {
        // 1ª linha: o que foi (largura toda); 2ª: valor/mensagem, hora e "Novo" (quebram juntos).
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                when (alert.kind) {
                    "upload_error" -> KindTitle(Icons.Default.CloudOff, stringResource(R.string.alerthistory_upload_error_title), alert.dismissed)
                    "caregiver_invite" -> KindTitle(Icons.Default.PersonAdd, stringResource(R.string.alerthistory_caregiver_invite), alert.dismissed)
                    "caregiver_accepted" -> KindTitle(Icons.Default.Check, stringResource(R.string.alerthistory_caregiver_accepted), alert.dismissed)
                    else -> {
                        val zone = runCatching { GlucoseZone.valueOf(alert.zone) }.getOrNull()
                        if (zone != null) GlucoseZoneChip(zone)
                        else StatusBadge(stringResource(if (alert.zone == "PREDICTIVE") R.string.alerthistory_zone_predictive
                                                       else R.string.alerthistory_alert_generic),
                            tone = Tone.Warning, icon = Icons.AutoMirrored.Filled.TrendingDown)
                    }
                }
            }
        }
        val detail = when (alert.kind) {
            "glucose" -> if (alert.sgvMgDl > 0) "${formatGlucoseWithUnit(alert.sgvMgDl, unit)} · ${typeLabel(alert.alertType)}" else null
            else -> alert.message
        }
        detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(time, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterVertically))
            if (!alert.dismissed) StatusBadge(stringResource(R.string.alerthistory_new), tone = Tone.Info)
        }
        if (alert.kind == "upload_error") {
            FilledTonalButton(onClick = onReconnect, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.alerthistory_reconnect_action))
            }
        }
    }
}

@Composable
private fun KindTitle(icon: ImageVector, title: String, dismissed: Boolean) {
    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
    Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = if (dismissed) FontWeight.Normal else FontWeight.SemiBold)
}
