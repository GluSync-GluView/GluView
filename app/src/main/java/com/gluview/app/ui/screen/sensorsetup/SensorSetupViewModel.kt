package com.gluview.app.ui.screen.sensorsetup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gluview.app.data.datastore.AppPreferences
import com.gluview.app.data.sensor.ScanNotice
import com.gluview.app.data.sensor.SensorSetupCoordinator
import com.gluview.app.data.sensor.SetupState
import com.gluview.app.data.source.DataSourceType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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
    val unavailableOnDevice: Boolean = false
)

@HiltViewModel
class SensorSetupViewModel @Inject constructor(
    private val coordinator: SensorSetupCoordinator,
    preferences: AppPreferences
) : ViewModel() {

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
    val options: StateFlow<List<SensorOption>> = preferences.dataSource
        .map { currentId ->
            DataSourceType.entries
                .filter { it != DataSourceType.GLUSYNC }
                .map { type ->
                    SensorOption(
                        type                = type,
                        isCurrent           = type.id == currentId,
                        isSupported         = !type.comingSoon && type.isUsableOnThisDevice,
                        unavailableOnDevice = !type.isUsableOnThisDevice
                    )
                }
                .sortedByDescending { it.isCurrent }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun start()                        = coordinator.startSensorChange()
    fun choose(type: DataSourceType)   = coordinator.chooseSensor(type)
    fun readyToScan()                  = coordinator.readyToScan()
    fun onPackageScanned(raw: String)  = coordinator.onPackageScanned(raw)
    /** true = a tela precisa abrir a câmera (sem caixa lida). */
    fun retry(): Boolean               = coordinator.retry()
    fun scanAborted(notice: ScanNotice) = coordinator.scanAborted(notice)
    fun cancel()                       = coordinator.cancel()
}
