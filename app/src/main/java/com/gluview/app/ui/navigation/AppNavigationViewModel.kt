package com.gluview.app.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gluview.app.data.datastore.AppPreferences
import com.gluview.app.data.repository.AppConfigRepository
import com.gluview.app.data.source.DataSourceType
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Estado global da navegação — se a aba "Sensor" deve aparecer (não existe pra conta
 * seguidor-apenas) e, ADR-0029, se a aba "Bomba" deve aparecer (flag de rollout gradual,
 * independente do modo de cadastro — Features:InsulinPumpTabEnabled).
 */
@HiltViewModel
class AppNavigationViewModel @Inject constructor(
    preferences: AppPreferences,
    private val appConfigRepository: AppConfigRepository
) : ViewModel() {
    val isFollowerOnly: StateFlow<Boolean> = preferences.isFollowerOnly
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /**
     * Primeira entrada sem sensor configurado: quem acabou de instalar cai direto no fluxo de
     * configuração em vez de precisar descobrir sozinho, em Ajustes, onde se conecta um sensor.
     *
     * Só vale para quem usa sensor próprio e escolheu uma fonte lida por Bluetooth — conta
     * seguidora e fonte de nuvem não têm sensor para configurar. Assim que existir um sensor
     * salvo, deixa de valer e o login segue direto para a Home.
     */
    val needsSensorSetup: StateFlow<Boolean> = combine(
        preferences.isFollowerOnly,
        preferences.dataSource,
        preferences.sibionicsMac,
        preferences.libre2Mac
    ) { followerOnly, sourceId, sibionicsMac, libre2Mac ->
        val source = DataSourceType.fromId(sourceId)
        !followerOnly && source.isLocalSensor && source.isUsableOnThisDevice &&
            sibionicsMac.isBlank() && libre2Mac.isBlank()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _insulinPumpTabEnabled = MutableStateFlow(false)
    val insulinPumpTabEnabled: StateFlow<Boolean> = _insulinPumpTabEnabled.asStateFlow()

    init {
        viewModelScope.launch {
            appConfigRepository.getFeatureFlags().onSuccess { flags ->
                _insulinPumpTabEnabled.value = flags.insulinPumpTabEnabled
            }
        }
    }
}
