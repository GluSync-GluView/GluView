package com.gluview.app.ui.screen.accessrequests

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gluview.app.R
import com.gluview.app.data.remote.dto.MyAccessRequestDto
import com.gluview.app.data.source.DataSourceType
import com.gluview.app.ui.components.chart.rememberChartAppearance
import com.gluview.app.ui.components.kit.EmptyState
import com.gluview.app.ui.components.kit.ErrorState
import com.gluview.app.ui.components.kit.Fact
import com.gluview.app.ui.components.kit.FactList
import com.gluview.app.ui.components.kit.GluCard
import com.gluview.app.ui.components.kit.ListSkeleton
import com.gluview.app.ui.components.kit.Page
import com.gluview.app.ui.components.kit.StatusBadge
import com.gluview.app.ui.components.kit.Tone
import com.gluview.app.ui.theme.Spacing
import com.gluview.app.util.ServerDates
import com.gluview.app.util.TimeFormat
import com.gluview.app.util.formatDateTime

@Composable
fun MyAccessRequestsScreen(
    onBack: () -> Unit,
    vm: MyAccessRequestsViewModel = hiltViewModel()
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    MyAccessRequestsContent(ui, rememberChartAppearance().timeFormat, vm::refresh, onBack)
}

/**
 * Minhas candidaturas (redesign visual, Fase 6): selo de status com cor própria (antes pendente,
 * lista de espera e aprovado tinham o mesmo fundo), dados em rótulo → valor no lugar de
 * "Rótulo: valor" concatenado, datas no idioma e no formato de hora do usuário, e os três estados.
 */
@Composable
internal fun MyAccessRequestsContent(ui: MyAccessRequestsUiState, timeFormat: TimeFormat, onRetry: () -> Unit, onBack: () -> Unit) {
    Page(title = stringResource(R.string.access_requests_title), onBack = onBack) {
        when {
            ui.loading -> ListSkeleton(rows = 2)
            ui.error != null -> ErrorState(stringResource(R.string.history_load_error_body), onRetry, detail = ui.error)
            ui.requests.isEmpty() -> EmptyState(
                title = stringResource(R.string.access_requests_empty_title),
                body = stringResource(R.string.access_requests_empty),
                icon = Icons.Outlined.History
            )
            else -> Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                ui.requests.sortedByDescending { it.createdAt }.forEach { AccessRequestCard(it, timeFormat) }
            }
        }
    }
}

@Composable
private fun AccessRequestCard(request: MyAccessRequestDto, timeFormat: TimeFormat) {
    fun date(iso: String) = ServerDates.toEpochMs(iso)?.let { formatDateTime(it, timeFormat) } ?: iso
    GluCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(featureKeyLabel(request.featureKey), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            StatusBadge(statusLabel(request.status), tone = statusTone(request.status))
        }
        FactList(buildList {
            request.note?.takeIf { it.isNotBlank() }?.let { add(Fact(stringResource(R.string.access_requests_note_label), it)) }
            request.adminNote?.takeIf { it.isNotBlank() }?.let { add(Fact(stringResource(R.string.access_requests_admin_note_label), it)) }
            add(Fact(stringResource(R.string.access_requests_created_at_label), date(request.createdAt)))
            request.decidedAt?.let { add(Fact(stringResource(R.string.access_requests_decided_at_label), date(it))) }
        })
    }
}

@Composable
internal fun featureKeyLabel(featureKey: String): String = when {
    featureKey == "APP" -> stringResource(R.string.access_requests_feature_app)
    featureKey == "insulin_pump_beta" -> stringResource(R.string.access_requests_feature_insulin_pump)
    featureKey.startsWith("sensor_beta:") ->
        stringResource(DataSourceType.fromId(featureKey.removePrefix("sensor_beta:")).labelRes)
    else -> featureKey
}

internal fun statusTone(status: String): Tone = when (status) {
    "approved" -> Tone.Success
    "rejected" -> Tone.Danger
    "pending", "waitlisted", "unverified" -> Tone.Info
    else -> Tone.Neutral
}

@Composable
internal fun statusLabel(status: String): String = when (status) {
    "unverified" -> stringResource(R.string.access_requests_status_unverified)
    "pending"    -> stringResource(R.string.access_requests_status_pending)
    "waitlisted" -> stringResource(R.string.access_requests_status_waitlisted)
    "approved"   -> stringResource(R.string.access_requests_status_approved)
    "rejected"   -> stringResource(R.string.access_requests_status_rejected)
    "revoked"    -> stringResource(R.string.access_requests_status_revoked)
    else         -> stringResource(R.string.access_requests_status_unknown)
}
