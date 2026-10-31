package com.gluview.app.ui.screen.watchface

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gluview.app.R
import com.gluview.app.data.remote.dto.WatchfaceStoreListingDto
import com.gluview.app.data.repository.StoreAccessDeniedException
import com.gluview.app.data.repository.WatchFaceRepository
import com.gluview.app.util.UiText
import com.gluview.app.watchface.WatchFaceJson
import com.gluview.app.watchface.apply.ApplyResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Fase 6 do plano 06_watchfaces_v2_arquitetura.md — vitrine da loja DENTRO do app (antes só
 * existia no GluWebView, ver WatchfaceStorePage.tsx). Baixa o artefato oficial recompilado no
 * servidor e envia por Bluetooth pro Mi Band pareado, reaproveitando o mesmo transporte já
 * validado em hardware (MiBandUploader) — nunca gera nada localmente a partir do projeto alheio.
 */
/** Por que a lista não veio: falha de rede/servidor (dá para tentar de novo) ou beta fechado (não adianta). */
enum class StoreLoadError { FAILED, CLOSED_BETA }

@HiltViewModel
class WatchfaceStoreViewModel @Inject constructor(
    private val repository: WatchFaceRepository
) : ViewModel() {

    private val _listings = MutableStateFlow<List<WatchfaceStoreListingDto>>(emptyList())
    val listings: StateFlow<List<WatchfaceStoreListingDto>> = _listings.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    /** Erro persistente da última carga (decisão 5.6 #4: antes virava snackbar + "nenhum visor"). */
    private val _loadError = MutableStateFlow<StoreLoadError?>(null)
    val loadError: StateFlow<StoreLoadError?> = _loadError.asStateFlow()

    /** Busca da última carga, para o vazio dizer "nada para X" e oferecer limpar. */
    private val _lastSearch = MutableStateFlow<String?>(null)
    val lastSearch: StateFlow<String?> = _lastSearch.asStateFlow()

    private val _installMessage = MutableStateFlow<WatchFaceMessage?>(null)
    val installMessage: StateFlow<WatchFaceMessage?> = _installMessage.asStateFlow()
    fun clearInstallMessage() { _installMessage.value = null }

    /** publicationId da instalação em andamento, se houver — desabilita o botão só daquele item. */
    private val _installingId = MutableStateFlow<String?>(null)
    val installingId: StateFlow<String?> = _installingId.asStateFlow()

    init { load() }

    fun load(search: String? = null) {
        viewModelScope.launch {
            _isLoading.value = true
            _loadError.value = null
            val query = search?.trim()?.ifBlank { null }
            _lastSearch.value = query
            repository.listStore(query).fold(
                onSuccess = { _listings.value = it },
                onFailure = { e ->
                    _listings.value = emptyList()
                    _loadError.value = if (e is StoreAccessDeniedException) StoreLoadError.CLOSED_BETA else StoreLoadError.FAILED
                }
            )
            _isLoading.value = false
        }
    }

    /** Wearables deste anúncio (principal + compatíveis) que este app sabe instalar via Bluetooth hoje. */
    fun installableWearableIds(listing: WatchfaceStoreListingDto): List<String> {
        val declared = listOf(listing.wearableId) + WatchFaceJson.decodeIdList(listing.compatibleWearableIdsJson)
        return declared.distinct().filter { repository.isInstallableWearable(it) }
    }

    fun install(listing: WatchfaceStoreListingDto, wearableId: String) {
        viewModelScope.launch {
            _installingId.value = listing.publicationId
            val result = repository.installFromStore(listing, wearableId) { }
            _installMessage.value = when (result) {
                is ApplyResult.Success -> WatchFaceMessage(UiText.Res(R.string.wf_msg_installed, listing.name))
                else -> result.toMessage()
            }
            _installingId.value = null
        }
    }
}
