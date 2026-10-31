package com.gluview.app.ui.screen.caregiver

import com.gluview.app.ui.theme.Spacing
import com.gluview.app.ui.components.kit.ConfirmDialog
import com.gluview.app.ui.components.kit.EmptyState
import com.gluview.app.ui.components.kit.ErrorState
import com.gluview.app.ui.components.kit.ListSkeleton
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gluview.app.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaregiverScreen(
    onBack: () -> Unit,
    onOpenPatient: (patientId: String, name: String) -> Unit,
    vm: CaregiverViewModel = hiltViewModel()
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var showInvite by remember { mutableStateOf(false) }
    // L26: desfazer vínculo pede confirmação (antes a lixeira removia na hora, sem volta).
    var confirmRevoke by remember { mutableStateOf<Pair<CaregiverItem, RevokeKind>?>(null) }

    LaunchedEffect(ui.message) { ui.message?.let { snackbar.showSnackbar(it); vm.clearMessage() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_caregiver_section)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.settings_back)) } }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showInvite = true },
                icon = { Icon(Icons.Default.PersonAdd, null) },
                text = { Text(stringResource(R.string.caregiver_invite)) }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        when {
            // Carregando no formato da lista (sem spinner solto) e erro com "Tentar novamente".
            ui.loading -> ListSkeleton(Modifier.padding(padding).padding(Spacing.md))
            ui.error != null -> Box(Modifier.fillMaxSize().padding(padding).padding(Spacing.md), Alignment.Center) {
                ErrorState(ui.error!!, onRetry = vm::refresh)
            }
            else -> {
                val invitesReceivedTitle = stringResource(R.string.caregiver_invites_received)
                val youWouldFollow = stringResource(R.string.caregiver_you_would_follow)
                val askedToFollowYou = stringResource(R.string.caregiver_asked_to_follow_you)
                val invitesSentTitle = stringResource(R.string.caregiver_invites_sent)
                val awaitingResponse = stringResource(R.string.caregiver_awaiting_response)
                val cancelDesc = stringResource(R.string.caregiver_cancel)
                val patientsFollowingTitle = stringResource(R.string.caregiver_patients_following)
                val tapToViewRealtime = stringResource(R.string.caregiver_tap_to_view_realtime)
                val removeDesc = stringResource(R.string.caregiver_remove)
                val whoFollowsMeTitle = stringResource(R.string.caregiver_who_follows_me)
                val followerWithAccess = stringResource(R.string.caregiver_follower_with_access)
                val revokeAccessDesc = stringResource(R.string.caregiver_revoke_access)
                val noLinksYet = stringResource(R.string.caregiver_no_links_yet)

                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    // Espaço no fim para o botão flutuante não cobrir o último card.
                    contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (ui.invitesReceived.isEmpty() && ui.invitesSent.isEmpty() &&
                        ui.following.isEmpty() && ui.followers.isEmpty()
                    ) {
                        item {
                            EmptyState(
                                title = stringResource(R.string.settings_caregiver_section),
                                body = noLinksYet,
                                icon = Icons.Default.PersonAdd,
                                actionLabel = stringResource(R.string.caregiver_invite),
                                onAction = { showInvite = true }
                            )
                        }
                    }
                    section(invitesReceivedTitle, ui.invitesReceived) { it ->
                        InviteReceivedCard(
                            name     = it.otherName,
                            sub      = if (it.iAmFollower) youWouldFollow else askedToFollowYou,
                            onAccept = { vm.respond(it.link.id, true) },
                            onReject = { vm.respond(it.link.id, false) }
                        )
                    }
                    section(invitesSentTitle, ui.invitesSent) { it ->
                        LinkCard(it.otherName, sub = awaitingResponse) {
                            IconButton(onClick = { confirmRevoke = it to RevokeKind.INVITE }) {
                                Icon(Icons.Default.Delete, cancelDesc, tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                    section(patientsFollowingTitle, ui.following) { it ->
                        LinkCard(it.otherName, sub = tapToViewRealtime, onClick = { onOpenPatient(it.patientId, it.otherName) }) {
                            IconButton(onClick = { confirmRevoke = it to RevokeKind.FOLLOWING }) {
                                Icon(Icons.Default.Delete, removeDesc, tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                    section(whoFollowsMeTitle, ui.followers) { it ->
                        LinkCard(it.otherName, sub = followerWithAccess) {
                            IconButton(onClick = { confirmRevoke = it to RevokeKind.FOLLOWER }) {
                                Icon(Icons.Default.Delete, revokeAccessDesc, tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }

    confirmRevoke?.let { (item, kind) ->
        val (title, body, confirm) = when (kind) {
            RevokeKind.INVITE    -> Triple(R.string.caregiver_confirm_cancel_invite_title, R.string.caregiver_confirm_cancel_invite_body, R.string.caregiver_confirm_cancel_invite)
            RevokeKind.FOLLOWING -> Triple(R.string.caregiver_confirm_unfollow_title, R.string.caregiver_confirm_unfollow_body, R.string.caregiver_confirm_unfollow)
            RevokeKind.FOLLOWER  -> Triple(R.string.caregiver_confirm_revoke_title, R.string.caregiver_confirm_revoke_body, R.string.caregiver_confirm_revoke)
        }
        ConfirmDialog(
            title = stringResource(title, item.otherName),
            message = stringResource(body),
            confirmLabel = stringResource(confirm),
            onConfirm = { vm.revoke(item.link.id); confirmRevoke = null },
            onDismiss = { confirmRevoke = null }
        )
    }

    if (showInvite) {
        InviteDialog(
            onDismiss = { showInvite = false },
            onInvite = { email, asFollower -> vm.invite(email, asFollower); showInvite = false }
        )
    }
}

/** Os três jeitos de desfazer um vínculo — cada um com seu texto de confirmação. */
private enum class RevokeKind { INVITE, FOLLOWING, FOLLOWER }

private fun androidx.compose.foundation.lazy.LazyListScope.section(
    title: String,
    items: List<CaregiverItem>,
    row: @Composable (CaregiverItem) -> Unit
) {
    if (items.isEmpty()) return
    item { Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp)) }
    items(items, key = { it.link.id }) { row(it) }
}

/** Círculo com a inicial do nome/e-mail — mesmo tratamento visual em todos os cards de vínculo. */
@Composable
private fun Avatar(name: String) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center
    ) {
        Text(
            name.trim().take(1).uppercase().ifEmpty { "?" },
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}

/** Avatar + nome/e-mail (truncado com "…") + subtítulo — cabeçalho comum a todos os cards. */
@Composable
private fun LinkHeader(name: String, sub: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Avatar(name)
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LinkCard(
    name: String,
    sub: String,
    onClick: (() -> Unit)? = null,
    actions: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick ?: {}
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LinkHeader(name, sub, modifier = Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) { actions() }
        }
    }
}

/**
 * Convite recebido: aceitar/recusar em uma linha própria, sempre em largura cheia — nunca
 * disputa espaço com o nome/e-mail (era isso que quebrava e-mails longos letra a letra).
 */
@Composable
private fun InviteReceivedCard(name: String, sub: String, onAccept: () -> Unit, onReject: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            LinkHeader(name, sub)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = onAccept, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Check, null, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.caregiver_accept_padded))
                }
                OutlinedButton(onClick = onReject, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Close, null, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.caregiver_reject_padded))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InviteDialog(onDismiss: () -> Unit, onInvite: (String, Boolean) -> Unit) {
    var email by remember { mutableStateOf("") }
    var asFollower by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.caregiver_invite)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = email, onValueChange = { email = it }, label = { Text(stringResource(R.string.caregiver_person_email)) }, singleLine = true)
                Text(stringResource(R.string.caregiver_i_want_to), style = MaterialTheme.typography.labelMedium)
                // FlowRow: se os dois chips nao couberem lado a lado, o segundo desce inteiro
                // (em vez de quebrar o texto em duas linhas e desalinhar as alturas).
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = asFollower, onClick = { asFollower = true }, label = { Text(stringResource(R.string.caregiver_follow_this_person)) })
                    FilterChip(selected = !asFollower, onClick = { asFollower = false }, label = { Text(stringResource(R.string.caregiver_have_them_follow_me)) })
                }
            }
        },
        confirmButton = { TextButton(onClick = { onInvite(email, asFollower) }, enabled = email.contains('@')) { Text(stringResource(R.string.caregiver_invite)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) } }
    )
}
