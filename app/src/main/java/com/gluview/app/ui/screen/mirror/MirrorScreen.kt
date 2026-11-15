package com.gluview.app.ui.screen.mirror

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gluview.app.R
import com.gluview.app.data.datastore.AppPreferences
import com.gluview.app.mirror.FollowedPatient
import com.gluview.app.mirror.FollowedView
import com.gluview.app.mirror.MirrorManager
import com.gluview.app.mirror.MirrorServerStatus
import com.gluview.app.mirror.MirrorSettings
import com.gluview.app.mirror.MirrorStore
import com.gluview.app.mirror.SharedFollower
import com.gluview.app.ui.components.kit.ConfirmDialog
import com.gluview.app.ui.components.kit.GluCallout
import com.gluview.app.ui.components.kit.GluCard
import com.gluview.app.ui.components.kit.GluSection
import com.gluview.app.ui.components.kit.Page
import com.gluview.app.ui.components.kit.RowGroup
import com.gluview.app.ui.components.kit.SecretTextField
import com.gluview.app.ui.components.kit.SliderRow
import com.gluview.app.ui.components.kit.SwitchRow
import com.gluview.app.ui.components.kit.Tone
import com.gluview.app.ui.theme.Spacing
import com.gluview.mirror.FollowerAlertKind
import com.gluview.mirror.LinkStatus
import com.gluview.mirror.PairingCode
import com.gluview.mirror.directionToArrow
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.math.roundToInt

@HiltViewModel
class MirrorViewModel @Inject constructor(
    private val store: MirrorStore,
    private val manager: MirrorManager,
    preferences: AppPreferences
) : ViewModel() {
    val settings: StateFlow<MirrorSettings> = store.settings
    val followed: StateFlow<List<FollowedView>> = manager.followedViews
    val status: StateFlow<MirrorServerStatus> = manager.status
    val isFollowerOnly: StateFlow<Boolean> = preferences.isFollowerOnly
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    init { viewModelScope.launch(Dispatchers.IO) { store.load() } }

    fun update(t: (MirrorSettings) -> MirrorSettings) { viewModelScope.launch(Dispatchers.IO) { store.update(t) } }
    suspend fun addFollower(label: String): PairingCode = withContext(Dispatchers.IO) { manager.createPairing(label) }
    suspend fun codeFor(f: SharedFollower): PairingCode? = withContext(Dispatchers.IO) { manager.pairingFor(f) }
    fun revoke(id: String) { viewModelScope.launch(Dispatchers.IO) { manager.revoke(id) } }
    fun follow(code: PairingCode) { viewModelScope.launch(Dispatchers.IO) { manager.follow(code) } }
    fun unfollow(id: String) { viewModelScope.launch(Dispatchers.IO) { manager.unfollow(id) } }
    fun reconnect() = manager.reconnectAll()
    fun httpRequests() = manager.recentHttpRequests()
    fun wifi() = manager.wifiIpv4()
}

/**
 * Plano 21, F2 — Espelhamento: compartilhar a própria glicemia com quem você escolher, seguir
 * alguém sem servidor e o servidor web local. Tudo desligado até a pessoa ligar.
 */
@Composable
fun MirrorScreen(onBack: () -> Unit, vm: MirrorViewModel = hiltViewModel()) {
    val s by vm.settings.collectAsState()
    val followed by vm.followed.collectAsState()
    val status by vm.status.collectAsState()
    val followerOnly by vm.isFollowerOnly.collectAsState()
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(15_000); now = System.currentTimeMillis() } }

    var showCode by remember { mutableStateOf<Pair<String, PairingCode>?>(null) }
    var askLabel by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<PairingCode?>(null) }
    var pasteOpen by remember { mutableStateOf(false) }
    var revokeTarget by remember { mutableStateOf<SharedFollower?>(null) }
    var removeTarget by remember { mutableStateOf<FollowedView?>(null) }
    var editTarget by remember { mutableStateOf<FollowedPatient?>(null) }
    var invalidCode by remember { mutableStateOf(false) }
    // Texto local: salvar a cada letra e reler do armazenamento embaralhava o cursor ("aciente TesteP").
    var nameText by remember { mutableStateOf<String?>(null) }
    var secretText by remember { mutableStateOf<String?>(null) }
    var portText by remember { mutableStateOf<String?>(null) }

    val scanner = remember {
        GmsBarcodeScanning.getClient(context, GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
    }
    fun onCodeText(text: String) {
        val c = PairingCode.decode(text)
        if (c == null) invalidCode = true else pending = c
    }

    Page(title = stringResource(R.string.mirror_title), onBack = onBack) {
        Text(stringResource(R.string.mirror_intro), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        status.error?.let { GluCallout(it, tone = Tone.Danger) }

        // ---------------- Seguir alguém
        GluSection(stringResource(R.string.mirror_section_follow), description = stringResource(R.string.mirror_follow_desc)) {
            followed.forEach { f -> FollowedCard(f, now, onEdit = { editTarget = s.followed.firstOrNull { it.pairIdHex == f.pairIdHex } },
                onRemove = { removeTarget = f }) }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Button(onClick = {
                    scanner.startScan().addOnSuccessListener { b -> b.rawValue?.let(::onCodeText) }
                }) { Text(stringResource(R.string.mirror_scan)) }
                OutlinedButton(onClick = { pasteOpen = true }) { Text(stringResource(R.string.mirror_paste)) }
            }
            if (followed.isNotEmpty()) TextButton(onClick = vm::reconnect) { Text(stringResource(R.string.mirror_reconnect)) }
        }

        // ---------------- Compartilhar a minha glicemia
        GluSection(stringResource(R.string.mirror_section_share), description = stringResource(R.string.mirror_share_desc)) {
            if (followerOnly) {
                GluCallout(stringResource(R.string.mirror_share_follower_only), tone = Tone.Info)
            } else {
                GluCard {
                    RowGroup(listOf(
                        { SwitchRow(stringResource(R.string.mirror_share_enable), s.shareEnabled, { on -> vm.update { it.copy(shareEnabled = on) } },
                            description = if (status.shareRunning) stringResource(R.string.mirror_share_running,
                                status.wifiAddress ?: "—", s.sharePort, status.connectedFollowers.size) else null) },
                    ))
                }
                OutlinedTextField(nameText ?: s.patientName, { v -> val t = v.take(40); nameText = t; vm.update { it.copy(patientName = t) } },
                    label = { Text(stringResource(R.string.mirror_patient_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                s.shared.forEach { f ->
                    GluCard {
                        Column(Modifier.fillMaxWidth()) {
                            Text(f.label.ifBlank { stringResource(R.string.mirror_follower_unnamed) }, style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(if (f.pairIdHex in status.connectedFollowers) R.string.mirror_follower_connected
                                else R.string.mirror_follower_offline), style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                                TextButton(onClick = { scope.launch { vm.codeFor(f)?.let { showCode = f.label to it } } }) {
                                    Text(stringResource(R.string.mirror_show_code)) }
                                TextButton(onClick = { revokeTarget = f }) { Text(stringResource(R.string.mirror_revoke)) }
                            }
                        }
                    }
                }
                Button(onClick = { askLabel = true }) { Text(stringResource(R.string.mirror_add_follower)) }
            }
        }

        // ---------------- Servidor web local
        GluSection(stringResource(R.string.mirror_section_http), description = stringResource(R.string.mirror_http_desc)) {
            GluCard {
                RowGroup(listOf(
                    { SwitchRow(stringResource(R.string.mirror_http_enable), s.httpEnabled, { on -> vm.update { it.copy(httpEnabled = on) } },
                        description = if (status.httpRunning) httpUrl(s, vm.wifi()) else null) },
                    { SwitchRow(stringResource(R.string.mirror_http_wifi), s.httpOnWifi, { on -> vm.update { it.copy(httpOnWifi = on) } },
                        description = stringResource(R.string.mirror_http_wifi_desc)) }
                ))
            }
            if (s.httpOnWifi) {
                val secret = secretText ?: s.httpSecret
                SecretTextField(secret, { v -> val t = v.trim().take(64); secretText = t; vm.update { it.copy(httpSecret = t) } },
                    stringResource(R.string.mirror_http_secret), isError = secret.length < 12,
                    supportingText = stringResource(R.string.mirror_http_secret_hint))
            }
            OutlinedTextField(portText ?: s.httpPort.toString(), { v -> val t = v.filter(Char::isDigit).take(5); portText = t
                t.toIntOrNull()?.takeIf { it in 1024..65535 }?.let { p -> vm.update { it.copy(httpPort = p) } } },
                label = { Text(stringResource(R.string.mirror_http_port)) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            if (status.httpRunning) {
                val reqs = vm.httpRequests()
                Text(stringResource(R.string.mirror_http_recent, reqs.size), style = MaterialTheme.typography.labelLarge)
                reqs.take(8).forEach { r ->
                    Text("${android.text.format.DateFormat.format("HH:mm:ss", r.atMs)}  ${r.remote}  ${r.path}  ${r.status}",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Text(stringResource(R.string.mirror_safety), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    if (askLabel) LabelDialog(onDismiss = { askLabel = false }) { label ->
        askLabel = false
        scope.launch { showCode = label to vm.addFollower(label) }
    }
    showCode?.let { (label, code) -> CodeDialog(label, code, context) { showCode = null } }
    if (pasteOpen) PasteDialog(onDismiss = { pasteOpen = false }) { pasteOpen = false; onCodeText(it) }
    pending?.let { c ->
        ConfirmDialog(
            title = stringResource(R.string.mirror_confirm_title, c.patientName),
            message = stringResource(R.string.mirror_confirm_body, c.confirmation),
            confirmLabel = stringResource(R.string.mirror_confirm_ok),
            onConfirm = { vm.follow(c); pending = null }, onDismiss = { pending = null }, destructive = false)
    }
    if (invalidCode) ConfirmDialog(stringResource(R.string.mirror_invalid_title), stringResource(R.string.mirror_invalid_body),
        stringResource(android.R.string.ok), { invalidCode = false }, { invalidCode = false }, destructive = false)
    revokeTarget?.let { f ->
        ConfirmDialog(stringResource(R.string.mirror_revoke_title), stringResource(R.string.mirror_revoke_body, f.label),
            stringResource(R.string.mirror_revoke), { vm.revoke(f.pairIdHex); revokeTarget = null }, { revokeTarget = null })
    }
    removeTarget?.let { f ->
        ConfirmDialog(stringResource(R.string.mirror_remove_title), stringResource(R.string.mirror_remove_body, f.name),
            stringResource(R.string.mirror_remove), { vm.unfollow(f.pairIdHex); removeTarget = null }, { removeTarget = null })
    }
    editTarget?.let { f -> EditFollowedDialog(f, onDismiss = { editTarget = null }) { n ->
        vm.update { st -> st.copy(followed = st.followed.map { if (it.pairIdHex == n.pairIdHex) n else it }) }; editTarget = null } }
}

private fun httpUrl(s: MirrorSettings, wifi: String?): String =
    if (s.httpOnWifi) "http://${wifi ?: "?"}:${s.httpPort}/sgv.json" else "http://127.0.0.1:${s.httpPort}/sgv.json"

@Composable
private fun FollowedCard(f: FollowedView, now: Long, onEdit: () -> Unit, onRemove: () -> Unit) {
    val r = f.latest
    val ageMin = r?.let { ((now - it.epochMs) / 60_000L).toInt().coerceAtLeast(0) }
    val fresh = r != null && ageMin!! < f.noSignalMin
    GluCard {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(f.name.ifBlank { "—" }, style = MaterialTheme.typography.titleMedium)
            Text(if (fresh) "${r!!.sgvMgDl.roundToInt()} mg/dL ${directionToArrow(r.direction)}" else stringResource(R.string.zone_stale),
                style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            ageMin?.let { Text(stringResource(R.string.mirror_age, it), style = MaterialTheme.typography.bodySmall) }
            val linkText = when (f.link) {
                LinkStatus.OK -> stringResource(R.string.mirror_link_ok, f.address ?: "")
                LinkStatus.DOWN -> stringResource(R.string.mirror_link_down, ((now - f.lastAliveMs) / 60_000L).toInt())
                LinkStatus.NEVER -> stringResource(R.string.mirror_link_never)
            }
            val shownLink = if (f.revoked) stringResource(R.string.mirror_revoked) else linkText
            Text(shownLink, style = MaterialTheme.typography.bodySmall,
                color = if (f.link == LinkStatus.OK && !f.revoked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
            if (f.link != LinkStatus.OK && !f.revoked) f.lastError?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                TextButton(onClick = onEdit) { Text(stringResource(R.string.mirror_edit)) }
                TextButton(onClick = onRemove) { Text(stringResource(R.string.mirror_remove)) }
            }
        }
    }
}

@Composable
private fun LabelDialog(onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.mirror_add_follower)) },
        text = { OutlinedTextField(text, { text = it.take(40) }, label = { Text(stringResource(R.string.mirror_follower_label)) }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onOk(text) }) { Text(stringResource(R.string.mirror_create_code)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } })
}

@Composable
private fun PasteDialog(onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.mirror_paste)) },
        text = { OutlinedTextField(text, { text = it }, label = { Text(stringResource(R.string.mirror_code)) }) },
        confirmButton = { TextButton(onClick = { onOk(text) }) { Text(stringResource(android.R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } })
}

@Composable
private fun CodeDialog(label: String, code: PairingCode, context: Context, onDismiss: () -> Unit) {
    val text = remember(code) { code.encode() }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.mirror_code_title, label.ifBlank { "—" })) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                com.gluview.app.ui.components.QrCode(text, stringResource(R.string.mirror_qr_desc), Modifier.size(260.dp))
                Text(stringResource(R.string.mirror_code_confirm, code.confirmation), style = MaterialTheme.typography.titleMedium)
                if (code.host.isBlank()) Text(stringResource(R.string.mirror_code_no_wifi), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.mirror_code_secret_warning), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) } },
        dismissButton = { TextButton(onClick = {
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("GluView", text))
        }) { Text(stringResource(R.string.mirror_copy_code)) } })
}

@Composable
private fun EditFollowedDialog(f: FollowedPatient, onDismiss: () -> Unit, onSave: (FollowedPatient) -> Unit) {
    var host by remember { mutableStateOf(f.host) }
    var port by remember { mutableStateOf(f.port.toString()) }
    var alerts by remember { mutableStateOf(f.alerts) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(f.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                OutlinedTextField(host, { host = it.trim() }, label = { Text(stringResource(R.string.mirror_host)) },
                    supportingText = { Text(stringResource(R.string.mirror_host_hint)) }, singleLine = true)
                OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) }, label = { Text(stringResource(R.string.mirror_port)) },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                Text(stringResource(R.string.mirror_alerts), style = MaterialTheme.typography.titleSmall)
                FollowerAlertKind.entries.forEach { k ->
                    SwitchRow(stringResource(alertLabel(k)), k in alerts.enabled,
                        { on -> alerts = alerts.copy(enabled = if (on) alerts.enabled + k else alerts.enabled - k) })
                }
                SliderRow(stringResource(R.string.mirror_link_timeout), alerts.linkTimeoutMin.toFloat(), 5f..60f, 5f,
                    { "${it.roundToInt()} min" }, { alerts = alerts.copy(linkTimeoutMin = it.roundToInt()) })
                SliderRow(stringResource(R.string.mirror_repeat), alerts.repeatMin.toFloat(), 10f..120f, 10f,
                    { "${it.roundToInt()} min" }, { alerts = alerts.copy(repeatMin = it.roundToInt()) })
            }
        },
        confirmButton = { TextButton(onClick = {
            onSave(f.copy(host = host, port = port.toIntOrNull()?.takeIf { it in 1..65535 } ?: f.port, alerts = alerts))
        }) { Text(stringResource(R.string.mirror_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } })
}

private fun alertLabel(k: FollowerAlertKind) = when (k) {
    FollowerAlertKind.LINK_DOWN -> R.string.mirror_kind_link
    FollowerAlertKind.NO_SENSOR_DATA -> R.string.mirror_kind_nodata
    FollowerAlertKind.URGENT_LOW -> R.string.zone_urgent_low
    FollowerAlertKind.LOW -> R.string.zone_low
    FollowerAlertKind.HIGH -> R.string.zone_high
    FollowerAlertKind.URGENT_HIGH -> R.string.zone_urgent_high
}
