package com.gluview.app.ui.screen.accessrequests

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gluview.app.R
import com.gluview.app.ui.components.chart.rememberChartAppearance
import com.gluview.app.ui.components.kit.EmptyState
import com.gluview.app.ui.components.kit.ErrorState
import com.gluview.app.ui.components.kit.Fact
import com.gluview.app.ui.components.kit.FactList
import com.gluview.app.ui.components.kit.GluCard
import com.gluview.app.ui.components.kit.ListSkeleton
import com.gluview.app.ui.components.kit.Page
import com.gluview.app.ui.components.kit.StatusBadge
import com.gluview.app.ui.theme.Spacing
import com.gluview.app.util.ServerDates
import com.gluview.app.util.TimeFormat
import com.gluview.app.util.formatDateTime

@Composable
fun AccessRequestStatusScreen(
    onBack: () -> Unit,
    vm: AccessRequestStatusViewModel = hiltViewModel()
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    AccessRequestStatusContent(ui, rememberChartAppearance().timeFormat, vm::refresh, onBack)
}

/**
 * Status do pedido de acesso (redesign visual, Fase 8): selo com cor por status, datas de envio e
 * decisão (vinham no DTO e não apareciam), "O que acontece agora" por status e "Atualizar";
 * carregando em esqueleto, erro com "Tentar novamente" em largura toda (antes literal em pt) e
 * "nenhum pedido" com saída de volta ao login. A tela rola (nota longa com fonte grande cortava).
 */
@Composable
internal fun AccessRequestStatusContent(
    ui: AccessRequestStatusUiState,
    timeFormat: TimeFormat,
    onRefresh: () -> Unit,
    onBack: () -> Unit
) {
    Page(title = stringResource(R.string.access_request_status_title), onBack = onBack) {
        when {
            ui.loading -> ListSkeleton(rows = 1)
            ui.notFound -> EmptyState(
                title = stringResource(R.string.access_request_status_not_found_title),
                body = stringResource(R.string.access_request_status_not_found),
                icon = Icons.Outlined.Inbox,
                actionLabel = stringResource(R.string.access_request_status_back_to_login),
                onAction = onBack
            )
            ui.error != null -> ErrorState(stringResource(R.string.access_request_status_error), onRefresh, detail = ui.error)
            ui.request != null -> {
                val request = ui.request
                fun date(iso: String) = ServerDates.toEpochMs(iso)?.let { formatDateTime(it, timeFormat) } ?: iso
                GluCard {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        Text(featureKeyLabel(request.featureKey), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        StatusBadge(statusLabel(request.status), tone = statusTone(request.status))
                    }
                    FactList(buildList {
                        add(Fact(stringResource(R.string.access_requests_created_at_label), date(request.createdAt)))
                        request.decidedAt?.let { add(Fact(stringResource(R.string.access_requests_decided_at_label), date(it))) }
                        request.adminNote?.takeIf { it.isNotBlank() }?.let {
                            add(Fact(stringResource(R.string.access_requests_admin_note_label), it, wide = true))
                        }
                    })
                }
                nextStepRes(request.status)?.let { res ->
                    Text(stringResource(R.string.access_request_status_next_title), style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.semantics { heading() })
                    Text(stringResource(res), style = MaterialTheme.typography.bodyMedium)
                }
                OutlinedButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.access_request_status_refresh))
                }
            }
        }
    }
}

private fun nextStepRes(status: String): Int? = when (status) {
    "unverified" -> R.string.access_request_status_next_unverified
    "pending" -> R.string.access_request_status_next_pending
    "waitlisted" -> R.string.access_request_status_next_waitlisted
    "approved" -> R.string.access_request_status_next_approved
    "rejected" -> R.string.access_request_status_next_rejected
    else -> null
}
