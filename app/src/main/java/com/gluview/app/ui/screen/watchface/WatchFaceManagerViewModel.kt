package com.gluview.app.ui.screen.watchface

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gluview.app.R
import com.gluview.app.data.local.entity.WatchFaceEntity
import com.gluview.app.data.repository.GlucoseRepository
import com.gluview.app.data.repository.WatchFaceRepository
import com.gluview.app.data.sibionics.SibionicsStatusHolder
import com.gluview.app.util.UiText
import com.gluview.app.watchface.apply.WatchFaceApplierRegistry
import com.gluview.app.watchface.toDesign
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class WatchFaceManagerViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val watchFaceRepository: WatchFaceRepository,
    glucoseRepository: GlucoseRepository,
    statusHolder: SibionicsStatusHolder,
    preferences: com.gluview.app.data.datastore.AppPreferences,
    private val applierRegistry: WatchFaceApplierRegistry,
    private val wearBridge: com.gluview.app.wear.WearBridge
) : ViewModel() {

    /** Fase 8 (plano 14): situação do relógio Wear OS no card da tela; null = ainda verificando. */
    private val _wearStatus = MutableStateFlow<com.gluview.app.wear.WearStatus?>(null)
    val wearStatus: StateFlow<com.gluview.app.wear.WearStatus?> = _wearStatus.asStateFlow()

    fun refreshWearStatus() {
        viewModelScope.launch { _wearStatus.value = wearBridge.status() }
    }

    /** Toque no card: reenvia a leitura atual ao relógio e atualiza a situação. */
    fun sendToWatch() {
        viewModelScope.launch {
            val status = wearBridge.status()
            _wearStatus.value = status
            if (status !is com.gluview.app.wear.WearStatus.Ready) return@launch
            val sent = wearBridge.resendCurrent()
            _applyMessage.value = WatchFaceMessage(UiText.Res(
                if (sent) R.string.wear_msg_sent else R.string.wear_msg_not_sent
            ))
        }
    }

    private val _applyMessage = MutableStateFlow<WatchFaceMessage?>(null)
    val applyMessage: StateFlow<WatchFaceMessage?> = _applyMessage.asStateFlow()
    fun clearApplyMessage() { _applyMessage.value = null }

    /** Visor sendo enviado por Bluetooth (o botão mostra progresso e não aceita outro toque). */
    private val _applyingFaceId = MutableStateFlow<Long?>(null)
    val applyingFaceId: StateFlow<Long?> = _applyingFaceId.asStateFlow()

    /** null = ainda lendo do Room (antes "Nenhum visor criado ainda." piscava no 1º quadro). */
    val watchFaces: StateFlow<List<WatchFaceEntity>?> = watchFaceRepository.observeAll()
        .stateIn<List<WatchFaceEntity>?>(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val runtime: StateFlow<WatchFaceRuntimeData> =
        watchFaceRuntimeFlow(viewModelScope, context, glucoseRepository, statusHolder, preferences)

    init {
        // Best-effort — pura leitura, nunca bloqueia a tela se o GluSync estiver fora do ar
        // (mesmo padrão de CaregiverRepository/ApiKeyRepository).
        viewModelScope.launch { watchFaceRepository.pullFromServer() }
        refreshWearStatus()
    }

    fun delete(face: WatchFaceEntity) {
        viewModelScope.launch { watchFaceRepository.delete(face) }
    }

    fun duplicate(face: WatchFaceEntity) {
        viewModelScope.launch { watchFaceRepository.duplicate(face) }
    }

    fun requestApply(face: WatchFaceEntity, targetWearableId: String? = null) {
        if (_applyingFaceId.value != null) return
        viewModelScope.launch {
            _applyingFaceId.value = face.id
            _applyMessage.value = watchFaceRepository.applyAndPersist(face, applierRegistry, targetWearableId).toMessage()
            _applyingFaceId.value = null
        }
    }

    /** Fase 6 — submete pra revisão da loja. Reusa o mesmo canal de snackbar do envio por Bluetooth. */
    fun publish(face: WatchFaceEntity) {
        viewModelScope.launch {
            _applyMessage.value = watchFaceRepository.publishToStore(face).fold(
                onSuccess = { WatchFaceMessage(UiText.Res(R.string.wf_msg_published)) },
                onFailure = { WatchFaceMessage(UiText.Res(R.string.wf_msg_publish_failed)) }
            )
        }
    }

    fun designOf(face: WatchFaceEntity) = face.toDesign()
}
