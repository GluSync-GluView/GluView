package com.gluview.app.ui.screen.integrations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gluview.app.R
import com.gluview.app.data.remote.dto.IntegrationStatusDto
import com.gluview.app.ui.components.kit.ConfirmDialog
import com.gluview.app.ui.components.kit.ErrorState
import com.gluview.app.ui.components.kit.Fact
import com.gluview.app.ui.components.kit.FactList
import com.gluview.app.ui.components.kit.GluCallout
import com.gluview.app.ui.components.kit.GluCard
import com.gluview.app.ui.components.kit.GluSection
import com.gluview.app.ui.components.kit.ListSkeleton
import com.gluview.app.ui.components.kit.NavRow
import com.gluview.app.ui.components.kit.Page
import com.gluview.app.ui.components.kit.RowGroup
import com.gluview.app.ui.components.kit.SecretTextField
import com.gluview.app.ui.components.kit.SwitchRow
import com.gluview.app.ui.components.kit.Tone
import com.gluview.app.ui.screen.settings.ProgressLabel
import com.gluview.app.ui.screen.settings.SettingsViewModel
import com.gluview.app.ui.theme.Spacing
import com.gluview.app.util.ServerDates
import com.gluview.app.util.formatElapsed

private const val PROVIDER_DEXCOM  = "dexcom_share"
private const val PROVIDER_LIBRE   = "librelinkup"

/** Nome de marca do provedor (fica igual nos dois idiomas). */
private fun providerName(provider: String) = if (provider == PROVIDER_DEXCOM) "Dexcom Share" else "LibreLinkUp"

@Composable
fun ExternalIntegrationScreen(
    onBack: () -> Unit,
    onApiKeys: () -> Unit,
    vm: ExternalIntegrationViewModel = hiltViewModel(),
    settingsVm: SettingsViewModel = hiltViewModel()
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val apiKeysEnabled by settingsVm.apiKeysEnabled.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    // provider sendo configurado no diálogo de conexão (null = fechado)
    var connectingProvider by remember { mutableStateOf<String?>(null) }
    var disconnectProvider by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(ui.message) {
        ui.message?.let { snackbar.showSnackbar(it.asString(context)); vm.clearMessage() }
    }

    IntegrationsContent(
        ui = ui,
        apiKeysEnabled = apiKeysEnabled,
        onRetry = vm::refresh,
        onConnect = { connectingProvider = it },
        onDisconnect = { disconnectProvider = it },
        onApiKeysEnabled = settingsVm::onApiKeysEnabledChange,
        onApiKeys = onApiKeys,
        onBack = onBack,
        snackbarHostState = snackbar
    )

    connectingProvider?.let { provider ->
        ConnectDialog(
            provider    = provider,
            connecting  = ui.connecting,
            onDismiss   = { connectingProvider = null },
            onConfirm   = { user, pass, region -> vm.connect(provider, user, pass, region) },
            // O diálogo fica aberto com o botão em progresso e fecha quando a conexão termina
            // (antes fechava na hora e o progresso nunca aparecia).
            onFinished  = { connectingProvider = null }
        )
    }

    disconnectProvider?.let { provider ->
        val name = providerName(provider)
        ConfirmDialog(
            title = stringResource(R.string.integrations_disconnect_title, name),
            message = stringResource(R.string.integrations_disconnect_body),
            confirmLabel = stringResource(R.string.integrations_disconnect),
            onConfirm = { vm.disconnect(provider); disconnectProvider = null },
            onDismiss = { disconnectProvider = null }
        )
    }
}

/**
 * Integrações (redesign visual, Fase 6): textos traduzidos (antes 100% em pt fixo); carregando,
 * erro com "Tentar novamente" e os dois provedores; cada provedor conectado em rótulo → valor
 * (status, última sincronização "há X", região pelo nome) e "Desconectar" como botão — antes uma
 * lixeira, que sugeria apagar dados; erro de autenticação num aviso com "Reconectar". As chaves
 * de API vieram de Geral (decisão 5.5 #4).
 */
@Composable
internal fun IntegrationsContent(
    ui: ExternalIntegrationUiState,
    apiKeysEnabled: Boolean,
    onRetry: () -> Unit,
    onConnect: (String) -> Unit,
    onDisconnect: (String) -> Unit,
    onApiKeysEnabled: (Boolean) -> Unit,
    onApiKeys: () -> Unit,
    onBack: () -> Unit,
    snackbarHostState: SnackbarHostState? = null,
    nowMs: Long = System.currentTimeMillis()
) {
    Page(title = stringResource(R.string.settings_integrations_title), onBack = onBack, snackbarHostState = snackbarHostState) {
        GluSection(stringResource(R.string.integrations_import_section), description = stringResource(R.string.integrations_intro)) {
            when {
                ui.loading -> ListSkeleton(rows = 2)
                ui.error != null -> ErrorState(ui.error.asString(), onRetry, detail = ui.errorDetail)
                else -> Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    for (provider in listOf(PROVIDER_DEXCOM, PROVIDER_LIBRE)) {
                        ProviderCard(provider, ui.integrations.find { it.provider == provider }, nowMs,
                            onConnect = { onConnect(provider) }, onDisconnect = { onDisconnect(provider) })
                    }
                }
            }
        }

        GluSection(stringResource(R.string.settings_api_keys_section)) {
            GluCard {
                RowGroup(buildList {
                    add {
                        SwitchRow(stringResource(R.string.settings_api_keys_switch_title), apiKeysEnabled, onApiKeysEnabled,
                            description = stringResource(R.string.settings_api_keys_switch_subtitle))
                    }
                    if (apiKeysEnabled) add { NavRow(stringResource(R.string.settings_manage_api_keys), onApiKeys, indent = true) }
                })
            }
        }
    }
}

@Composable
private fun ProviderCard(
    provider: String,
    status: IntegrationStatusDto?,
    nowMs: Long,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit
) {
    val context = LocalContext.current
    val name = providerName(provider)
    GluCard {
        Text(name, style = MaterialTheme.typography.titleMedium)
        Text(stringResource(if (provider == PROVIDER_DEXCOM) R.string.integrations_dexcom_desc else R.string.integrations_libre_desc),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (status == null) {
            Button(onClick = onConnect, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.integrations_connect)) }
            return@GluCard
        }
        val authError = status.status == "auth_error"
        FactList(buildList {
            add(Fact(stringResource(R.string.integrations_status), stringResource(when (status.status) {
                "active"     -> R.string.integrations_status_active
                "auth_error" -> R.string.integrations_status_auth_error
                "disabled"   -> R.string.integrations_status_disabled
                else         -> R.string.integrations_status_unknown
            }), valueColor = if (authError) MaterialTheme.colorScheme.error else null))
            status.lastSyncAt?.let(ServerDates::toEpochMs)?.let {
                add(Fact(stringResource(R.string.integrations_last_sync), context.formatElapsed(it, nowMs)))
            }
            status.region?.let { add(Fact(stringResource(R.string.integrations_region), regionLabel(provider, it))) }
        })
        if (authError) {
            // O erro do provedor vem cru (inglês, técnico): frase amigável com o detalhe embaixo.
            GluCallout(
                message = listOfNotNull(stringResource(R.string.integrations_auth_error_body), status.lastError).joinToString("\n"),
                tone = Tone.Danger,
                actionLabel = stringResource(R.string.integrations_reconnect),
                onAction = onConnect
            )
        }
        OutlinedButton(
            onClick = onDisconnect,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.fillMaxWidth()
        ) { Text(stringResource(R.string.integrations_disconnect)) }
    }
}

/** Regiões de cada provedor: código → (nome traduzido, servidor). */
@Composable
private fun regions(provider: String): List<Triple<String?, String, String>> =
    if (provider == PROVIDER_DEXCOM) listOf(
        Triple("us", stringResource(R.string.integrations_region_us), "share2.dexcom.com"),
        Triple("ous", stringResource(R.string.integrations_region_outside_us), "shareous1.dexcom.com")
    ) else listOf(
        Triple(null, stringResource(R.string.integrations_region_global), "api.libreview.io"),
        Triple("us", stringResource(R.string.integrations_region_us), "api-us.libreview.io"),
        Triple("eu", stringResource(R.string.integrations_region_eu), "api-eu.libreview.io"),
        Triple("de", stringResource(R.string.integrations_region_de), "api-de.libreview.io"),
        Triple("fr", stringResource(R.string.integrations_region_fr), "api-fr.libreview.io"),
        Triple("jp", stringResource(R.string.integrations_region_jp), "api-jp.libreview.io"),
        Triple("au", stringResource(R.string.integrations_region_au), "api-au.libreview.io")
    )

/** Nome da região (antes o código cru: "eu", "ous"); o servidor fica só no menu de escolha. */
@Composable
private fun regionLabel(provider: String, code: String): String =
    regions(provider).firstOrNull { it.first == code }?.second ?: code

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectDialog(
    provider:   String,
    connecting: Boolean,
    onDismiss:  () -> Unit,
    onConfirm:  (username: String, password: String, region: String?) -> Unit,
    onFinished: () -> Unit
) {
    val isDexcom = provider == PROVIDER_DEXCOM
    var username  by remember { mutableStateOf("") }
    var password  by remember { mutableStateOf("") }
    // Sem escolha = null (o servidor usa o padrão); a Dexcom mostra "Fora dos EUA" nesse caso, como antes.
    var region    by remember { mutableStateOf<String?>(null) }
    var regionExp by remember { mutableStateOf(false) }
    var started   by remember { mutableStateOf(false) }

    // Fecha quando a conexão que ESTE diálogo disparou termina (o resultado vai na snackbar).
    LaunchedEffect(connecting) {
        if (connecting) started = true else if (started) onFinished()
    }

    val options = regions(provider)
    AlertDialog(
        onDismissRequest = { if (!connecting) onDismiss() },
        title = { Text(stringResource(R.string.integrations_connect_title, providerName(provider))) },
        text = {
            // Rola: com fonte grande, texto + 2 campos + região passavam da altura.
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(stringResource(if (isDexcom) R.string.integrations_dexcom_login_hint else R.string.integrations_libre_login_hint),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text(stringResource(if (isDexcom) R.string.integrations_dexcom_user else R.string.integrations_libre_email)) },
                    singleLine = true,
                    enabled = !connecting,
                    keyboardOptions = KeyboardOptions(keyboardType = if (isDexcom) KeyboardType.Text else KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth()
                )
                SecretTextField(password, { password = it }, stringResource(R.string.integrations_password), enabled = !connecting)
                ExposedDropdownMenuBox(expanded = regionExp, onExpandedChange = { if (!connecting) regionExp = it }) {
                    OutlinedTextField(
                        value = (options.firstOrNull { it.first == (region ?: if (isDexcom) "ous" else null) } ?: options.first())
                            .let { stringResource(R.string.integrations_region_with_host, it.second, it.third) },
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.integrations_region)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(regionExp) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    )
                    ExposedDropdownMenu(expanded = regionExp, onDismissRequest = { regionExp = false }) {
                        options.forEach { (key, label, host) ->
                            DropdownMenuItem(text = { Text(stringResource(R.string.integrations_region_with_host, label, host)) },
                                onClick = { region = key; regionExp = false })
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick  = { onConfirm(username, password, region) },
                enabled  = username.isNotBlank() && password.isNotBlank() && !connecting
            ) { ProgressLabel(stringResource(R.string.integrations_connect_short), connecting) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !connecting) { Text(stringResource(R.string.common_cancel)) }
        }
    )
}
