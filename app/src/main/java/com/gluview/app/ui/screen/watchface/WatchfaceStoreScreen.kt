package com.gluview.app.ui.screen.watchface

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gluview.app.R
import com.gluview.app.data.remote.dto.WatchfaceStoreListingDto
import com.gluview.app.ui.components.kit.AppTopBar
import com.gluview.app.ui.components.kit.EmptyState
import com.gluview.app.ui.components.kit.ErrorState
import com.gluview.app.ui.components.kit.GluCallout
import com.gluview.app.ui.components.kit.GluCard
import com.gluview.app.ui.components.kit.ListSkeleton
import com.gluview.app.ui.components.kit.Tone
import com.gluview.app.ui.theme.Spacing
import com.gluview.app.util.ServerDates
import com.gluview.app.util.formatDate
import com.gluview.app.watchface.WearableCatalog
import com.gluview.app.watchface.WearableSpec

/** Tudo o que a loja mostra (flows do [WatchfaceStoreViewModel]). */
internal data class StoreUi(
    val listings: List<WatchfaceStoreListingDto> = emptyList(),
    val isLoading: Boolean = false,
    val loadError: StoreLoadError? = null,
    val lastSearch: String? = null,
    val installingId: String? = null
)

/**
 * Fase 6 do plano 06_watchfaces_v2_arquitetura.md — vitrine da loja dentro do app (antes só dava
 * pra navegar/instalar pelo GluWebView). Instala direto por Bluetooth no Mi Band pareado, usando
 * sempre o artefato oficial recompilado no servidor (ver [WatchfaceStoreViewModel]).
 */
@Composable
fun WatchfaceStoreScreen(
    onBack: () -> Unit,
    vm: WatchfaceStoreViewModel = hiltViewModel()
) {
    val listings by vm.listings.collectAsStateWithLifecycle()
    val isLoading by vm.isLoading.collectAsStateWithLifecycle()
    val loadError by vm.loadError.collectAsStateWithLifecycle()
    val lastSearch by vm.lastSearch.collectAsStateWithLifecycle()
    val installMessage by vm.installMessage.collectAsStateWithLifecycle()
    val installingId by vm.installingId.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(installMessage) {
        installMessage?.let { snackbar.showSnackbar(it.text.asString(context)); vm.clearInstallMessage() }
    }

    WatchfaceStoreContent(
        ui = StoreUi(listings, isLoading, loadError, lastSearch, installingId),
        installableIds = vm::installableWearableIds,
        snackbarHostState = snackbar,
        onBack = onBack,
        onSearch = vm::load,
        onInstall = vm::install
    )
}

/**
 * Loja (redesign visual, Fase 9): busca com rótulo, "Buscar" no teclado e limpar; três estados
 * distintos (decisão 5.6 #4) — erro com "Tentar novamente", beta fechado com aviso próprio, vazio
 * que diz se é a busca ("Nada para X" + limpar) — antes tudo virava "nenhum visor encontrado";
 * carregando em esqueleto; card empilhado com wearable pelo catálogo, data de publicação, onde
 * instala e "Instalar" em largura toda com progresso ("Instalando…"); sem wearable compatível vira
 * aviso neutro, não texto vermelho de erro.
 */
@Composable
internal fun WatchfaceStoreContent(
    ui: StoreUi,
    installableIds: (WatchfaceStoreListingDto) -> List<String>,
    onBack: () -> Unit,
    onSearch: (String?) -> Unit,
    onInstall: (WatchfaceStoreListingDto, String) -> Unit,
    snackbarHostState: SnackbarHostState? = null
) {
    var search by rememberSaveable { mutableStateOf(ui.lastSearch.orEmpty()) }
    var installTarget by remember { mutableStateOf<WatchfaceStoreListingDto?>(null) }

    Scaffold(
        topBar = { AppTopBar(stringResource(R.string.watchface_store_title), onBack) },
        snackbarHost = { if (snackbarHostState != null) SnackbarHost(snackbarHostState) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = Spacing.gutter, vertical = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            item {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    label = { Text(stringResource(R.string.wf_store_search_label)) },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = {
                        if (search.isNotEmpty()) IconButton(onClick = { search = ""; onSearch(null) }) {
                            Icon(Icons.Default.Clear, stringResource(R.string.wf_store_clear_search))
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSearch(search) }),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            when {
                ui.isLoading -> item { ListSkeleton(rows = 3) }
                ui.loadError == StoreLoadError.CLOSED_BETA -> item {
                    GluCallout(stringResource(R.string.watchface_store_closed_beta), tone = Tone.Info,
                        title = stringResource(R.string.wf_store_closed_title))
                }
                ui.loadError == StoreLoadError.FAILED -> item {
                    ErrorState(stringResource(R.string.watchface_store_load_error), onRetry = { onSearch(ui.lastSearch) })
                }
                ui.listings.isEmpty() && ui.lastSearch != null -> item {
                    EmptyState(
                        title = stringResource(R.string.wf_store_no_results, ui.lastSearch),
                        body = stringResource(R.string.wf_store_no_results_body),
                        icon = Icons.Default.Search,
                        actionLabel = stringResource(R.string.wf_store_clear_search),
                        onAction = { search = ""; onSearch(null) }
                    )
                }
                ui.listings.isEmpty() -> item {
                    EmptyState(stringResource(R.string.watchface_store_empty), stringResource(R.string.wf_store_empty_body),
                        Icons.Outlined.Storefront)
                }
                else -> items(ui.listings, key = { it.publicationId }) { listing ->
                    val ids = remember(listing) { installableIds(listing) }
                    StoreListingCard(listing, ids, installing = ui.installingId == listing.publicationId,
                        anyInstalling = ui.installingId != null) { installTarget = listing }
                }
            }
        }
    }

    installTarget?.let { listing ->
        val ids = remember(listing) { installableIds(listing) }
        WearablePickerDialog(
            title = stringResource(R.string.watchface_store_install_to_wearable),
            targets = ids.toSpecs(),
            initial = listing.wearableId,
            confirmLabel = stringResource(R.string.watchface_store_install),
            onDismiss = { installTarget = null },
            onConfirm = { id -> onInstall(listing, id); installTarget = null }
        )
    }
}

private fun List<String>.toSpecs(): List<WearableSpec> = mapNotNull { id -> WearableCatalog.items.firstOrNull { it.id == id } }

@Composable
private fun StoreListingCard(
    listing: WatchfaceStoreListingDto,
    installableIds: List<String>,
    installing: Boolean,
    anyInstalling: Boolean,
    onInstall: () -> Unit
) {
    val published = ServerDates.toEpochMs(listing.publishedAt)?.let { formatDate(it) }
    GluCard {
        Text(listing.name, style = MaterialTheme.typography.titleMedium)
        Text(
            listOfNotNull(
                wearableLabelById(listing.wearableId, stringResource(R.string.wf_wearable_label, listing.wearableName,
                    listing.wearableWidthPx, listing.wearableHeightPx)),
                published?.let { stringResource(R.string.wf_store_published, it) }
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (installableIds.isEmpty()) {
            GluCallout(stringResource(R.string.watchface_store_no_installable_wearable), tone = Tone.Neutral)
        } else {
            val names = installableIds.toSpecs().joinToString(", ") { "${it.brand} ${it.name}".trim() }
            if (names.isNotBlank()) {
                Text(stringResource(R.string.wf_store_installs_on, names), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // O botão do item instalando continua habilitado (só ignora o toque) para o progresso ficar visível.
            Button(onClick = { if (!installing) onInstall() }, enabled = installing || !anyInstalling,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                if (installing) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
                    Text(stringResource(R.string.watchface_store_installing), modifier = Modifier.padding(start = Spacing.xs))
                } else {
                    Icon(Icons.Default.Download, null, modifier = Modifier.padding(end = Spacing.xs).size(18.dp))
                    Text(stringResource(R.string.watchface_store_install))
                }
            }
        }
    }
}
