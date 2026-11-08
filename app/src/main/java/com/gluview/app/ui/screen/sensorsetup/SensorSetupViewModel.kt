package com.gluview.app.ui.screen.sensorsetup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gluview.app.data.datastore.AppPreferences
import com.gluview.app.data.remote.dto.AccessRequestFeatures
import com.gluview.app.R
import com.gluview.app.data.repository.AccessRequestRepository
import com.gluview.app.data.repository.AppConfigRepository
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
import kotlinx.coroutines.flow.asStateFlow
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
    /** Candidatura de beta deste sensor (pending/waitlisted/approved/rejected/revoked; null = nunca). */
    val betaState: String? = null,
    /** Toque em "Em breve" abre a candidatura (beta ligada no servidor, sensor que este aparelho roda). */
    val canApplyBeta: Boolean = false
)

@HiltViewModel
class SensorSetupViewModel @Inject constructor(
    private val coordinator: SensorSetupCoordinator,
    private val preferences: AppPreferences,
    private val accessRequests: AccessRequestRepository,
    private val appConfig: AppConfigRepository
) : ViewModel() {

    /**
     * Candidaturas de beta desta conta (ADR-0029): chave `sensor_beta:<id>` → estado. Com
     * "approved", o sensor "Em breve" entra na lista como disponível — é assim que um sensor ainda
     * não validado é testado em campo antes de sair do "Em breve" para todos. Os outros estados
     * viram selo na seção "Em breve" (antes só o dropdown da aba Sensor mostrava, plano 15).
     */
    private val betaStatus = MutableStateFlow<Map<String, String>>(emptyMap())

    /** Rollout gradual (Features:SensorBetaEnabled): sem a flag, "Em breve" não abre candidatura. */
    private val betaEnabled = MutableStateFlow(false)

    private val _betaSubmitting = MutableStateFlow(false)
    val betaSubmitting: StateFlow<Boolean> = _betaSubmitting.asStateFlow()

    /** Resultado da candidatura (L22: a falha não pode passar em silêncio). */
    private val _betaMessage = MutableStateFlow<Int?>(null)
    val betaMessage: StateFlow<Int?> = _betaMessage.asStateFlow()
    fun clearBetaMessage() { _betaMessage.value = null }

    init {
        refreshBetaStatus()
        viewModelScope.launch {
            appConfig.getFeatureFlags().onSuccess { betaEnabled.value = it.sensorBetaEnabled }
        }
    }

    private fun refreshBetaStatus() {
        viewModelScope.launch { accessRequests.getStatus().onSuccess { betaStatus.value = it } }
    }

    /** Candidatura de beta de um sensor "Em breve". */
    fun applyBeta(type: DataSourceType) {
        viewModelScope.launch {
            _betaSubmitting.value = true
            val result = accessRequests.createFeatureAccessRequest(AccessRequestFeatures.sensorBeta(type.id))
            _betaMessage.value = if (result.isSuccess) R.string.settings_beta_applied_msg else R.string.settings_beta_apply_failed
            refreshBetaStatus()
            _betaSubmitting.value = false
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
    val options: StateFlow<List<SensorOption>> = combine(
            preferences.dataSource, preferences.sensorVariantId, betaStatus, betaEnabled
        ) { currentId, variantId, states, enabled ->
            val current = SensorVariantCatalog.resolve(variantId, currentId)
            SensorVariantCatalog.all
                .filter { it.source != DataSourceType.GLUSYNC }
                .map { variant ->
                    val type = variant.source
                    val beta = if (type.comingSoon) states[AccessRequestFeatures.sensorBeta(type.id)] else null
                    SensorOption(
                        type                = type,
                        isCurrent           = type.id == currentId && variant.id == current.id,
                        isSupported         = variant.implemented && type.isUsableOnThisDevice &&
                                              (!type.comingSoon || beta == "approved" || com.gluview.app.BuildConfig.DEV_SENSOR_UNLOCK),
                        unavailableOnDevice = !type.isUsableOnThisDevice,
                        variant             = variant,
                        betaState           = beta,
                        // Libre europeu (protocolo não implementado) e sensor de .so em 32 bits: nem a beta faz sentido.
                        canApplyBeta        = enabled && type.comingSoon && variant.implemented && type.isUsableOnThisDevice
                    )
                }
                .sortedByDescending { it.isCurrent }
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
