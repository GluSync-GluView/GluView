package com.gluview.app.ui.screen.sensorsetup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gluview.app.data.datastore.AppPreferences
import com.gluview.app.data.remote.dto.AccessRequestFeatures
import com.gluview.app.data.repository.AccessRequestRepository
import com.gluview.app.data.sensor.ScanNotice
import com.gluview.app.data.sensor.SensorSetupCoordinator
import com.gluview.app.data.sensor.SetupState
import com.gluview.app.data.source.DataSourceType
import com.gluview.app.data.source.SensorVariant
import com.gluview.app.data.source.SensorVariantCatalog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Um item da lista "qual sensor você está usando?".
 *
 * [isCurrent] traz o sensor em uso para o topo — na troca, é quase sempre a resposta certa, e
 * quem está trocando pelo mesmo modelo não precisa procurar nada na lista.
 */
data class SensorOption(
    val type: DataSourceType,
    val isCurrent: Boolean,
    val isSupported: Boolean,
    /** O sensor existe, mas este aparelho (32 bits) não roda a biblioteca nativa dele. */
    val unavailableOnDevice: Boolean = false,
    /** Modelo + região mostrados ("FreeStyle Libre 2 Plus SA"); [type] é a implementação. */
    val variant: SensorVariant = SensorVariantCatalog.defaultFor(type)
)

@HiltViewModel
class SensorSetupViewModel @Inject constructor(
    private val coordinator: SensorSetupCoordinator,
    private val preferences: AppPreferences,
    private val accessRequests: AccessRequestRepository
) : ViewModel() {

    /**
     * Sensores "Em breve" com beta aprovado para esta conta (ADR-0029, `sensor_beta:<id>`): para
     * quem foi liberado, o sensor entra na lista como disponível — é assim que um sensor ainda não
     * validado é testado em campo antes de sair do "Em breve" para todos.
     */
    private val betaApproved = MutableStateFlow<Set<String>>(emptySet())

    init {
        viewModelScope.launch {
            accessRequests.getStatus().onSuccess { states ->
                betaApproved.value = DataSourceType.entries
                    .filter { it.comingSoon && states[AccessRequestFeatures.sensorBeta(it.id)] == "approved" }
                    .map { it.id }
                    .toSet()
            }
        }
    }

    val state: StateFlow<SetupState> = coordinator.state

    /**
     * Sensores oferecidos, com o atual em primeiro lugar.
     *
     * Os que ainda não foram validados em hardware real ([DataSourceType.comingSoon]) aparecem
     * desabilitados em vez de sumirem: some a promessa de que funcionam, fica a informação de que
     * existem. Idem para os de biblioteca nativa num aparelho 32 bits
     * ([DataSourceType.isUsableOnThisDevice]), com o rótulo "indisponível neste aparelho".
     * GluSync não entra — é a fonte de nuvem, não um sensor que se coloca no corpo.
     */
    val options: StateFlow<List<SensorOption>> = combine(preferences.dataSource, preferences.sensorVariantId, betaApproved) { currentId, variantId, approved ->
            val current = SensorVariantCatalog.resolve(variantId, currentId)
            SensorVariantCatalog.all
                .filter { it.source != DataSourceType.GLUSYNC }
                .map { variant ->
                    val type = variant.source
                    SensorOption(
                        type                = type,
                        isCurrent           = type.id == currentId && variant.id == current.id,
                        isSupported         = variant.implemented && type.isUsableOnThisDevice &&
                                              (!type.comingSoon || type.id in approved),
                        unavailableOnDevice = !type.isUsableOnThisDevice,
                        variant             = variant
                    )
                }
                .sortedByDescending { it.isCurrent }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun start()                        = coordinator.startSensorChange()
    fun choose(type: DataSourceType)   = coordinator.chooseSensor(type)

    /** Guarda o modelo + região escolhidos antes de seguir o fluxo da implementação. */
    fun rememberVariant(option: SensorOption) {
        viewModelScope.launch { preferences.saveSensorVariantId(option.variant.id) }
    }
    fun readyToScan()                  = coordinator.readyToScan()
    fun onPackageScanned(raw: String)  = coordinator.onPackageScanned(raw)
    /** true = a tela precisa abrir a câmera (sem caixa lida). */
    fun retry(): Boolean               = coordinator.retry()
    fun scanAborted(notice: ScanNotice) = coordinator.scanAborted(notice)
    fun cancel()                       = coordinator.cancel()
    fun enterSerialManually()          = coordinator.enterSerialManually()
    fun searchNearby()                 = coordinator.searchNearby()
    fun onSerialEntered(text: String)  = coordinator.onSerialEntered(text)
    fun onPinEntered(pin: String)      = coordinator.onPinEntered(pin)
}
