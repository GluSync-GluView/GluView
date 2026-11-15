package com.gluview.app.ui.screen.sensorsetup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gluview.app.data.datastore.AppPreferences
import com.gluview.app.data.sensor.ScanNotice
import com.gluview.app.data.sensor.SensorSetupCoordinator
import com.gluview.app.data.sensor.SetupState
import com.gluview.app.data.source.DataSourceType
import com.gluview.app.data.source.SensorMaturity
import com.gluview.app.data.source.SensorVariant
import com.gluview.app.data.source.SensorVariantCatalog
import dagger.hilt.android.lifecycle.HiltViewModel
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
    val variant: SensorVariant = SensorVariantCatalog.defaultFor(type),
    /** Quanto o sensor foi validado ("Funciona", "Em teste", "Sem teste com sensor real"). */
    val maturity: SensorMaturity = variant.maturity
)

/**
 * Monta a lista "qual sensor você está usando?" — função pura, sem conta nem nuvem.
 *
 * Nenhum sensor depende de liberação do GluSync (plano de sensores, PT-1): toda variante
 * implementada que este aparelho roda entra como disponível, para qualquer conta, com o rótulo
 * honesto de [SensorMaturity]. Ficam em "Em breve" só as variantes cujo protocolo (ou passo do
 * assistente) ainda não existe e as de biblioteca nativa num aparelho que não as roda
 * ([DataSourceType.isUsableOnThisDevice], com o rótulo "indisponível neste aparelho"). A nuvem e o
 * acompanhamento continuam por pedido, mas não entram aqui: escolher e usar um sensor é 100% local.
 * GluSync não entra na lista — é a fonte de nuvem, não um sensor que se coloca no corpo.
 */
internal fun buildSensorOptions(
    currentId: String?,
    variantId: String?,
    usableOnDevice: (DataSourceType) -> Boolean = { it.isUsableOnThisDevice }
): List<SensorOption> {
    val current = SensorVariantCatalog.resolve(variantId, currentId)
    return SensorVariantCatalog.all
        .filter { it.source != DataSourceType.GLUSYNC }
        .map { variant ->
            val type = variant.source
            val usable = usableOnDevice(type)
            SensorOption(
                type                = type,
                isCurrent           = type.id == currentId && variant.id == current.id,
                isSupported         = variant.implemented && usable,
                unavailableOnDevice = !usable,
                variant             = variant
            )
        }
        .sortedByDescending { it.isCurrent }
}

@HiltViewModel
class SensorSetupViewModel @Inject constructor(
    private val coordinator: SensorSetupCoordinator,
    private val preferences: AppPreferences
) : ViewModel() {

    val state: StateFlow<SetupState> = coordinator.state

    /** Sensores oferecidos, com o atual em primeiro lugar ([buildSensorOptions]). */
    val options: StateFlow<List<SensorOption>> =
        combine(preferences.dataSource, preferences.sensorVariantId) { currentId, variantId ->
            buildSensorOptions(currentId, variantId)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun start()                        = coordinator.startSensorChange()
    fun choose(type: DataSourceType)   = coordinator.chooseSensor(type)

    /**
     * Guarda o modelo + região escolhidos — só para o Libre, onde a ativação por NFC precisa saber
     * o modelo (2 ou 2 Plus) que a pessoa escolheu ([SensorVariantCatalog.libreVariant]). Os demais
     * sensores têm uma variante por implementação, e [SensorVariantCatalog.resolve] a tira da fonte
     * gravada no fim do pareamento; gravar aqui deixava, quando a pessoa cancelava, a variante de um
     * sensor que nunca foi configurado (plano 15, E2).
     */
    fun rememberVariant(option: SensorOption) {
        if (option.type != DataSourceType.LIBRE2_PLUS) return
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
