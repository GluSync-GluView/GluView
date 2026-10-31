package com.gluview.app.ui.screen.alerthistory

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gluview.app.data.local.dao.AlertLogDao
import com.gluview.app.data.local.entity.AlertLogEntity
import com.gluview.app.service.GluSyncService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AlertHistoryViewModel @Inject constructor(
    private val dao: AlertLogDao,
    @ApplicationContext private val context: Context
) : ViewModel() {
    /** null = ainda carregando (antes nascia vazio e "Nenhum alerta" piscava antes do Room). */
    val alerts: StateFlow<List<AlertLogEntity>?> = dao.observeRecent()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Marca todos os alertas como dispensados — zera o badge do sino. */
    fun dismissAll() {
        viewModelScope.launch { dao.dismissAll() }
    }

    /** Dispensa um alerta específico (toque). */
    fun dismissOne(id: Long) {
        viewModelScope.launch { dao.markDismissed(id) }
    }

    /** Apaga todo o histórico de alertas. */
    fun clearAll() {
        viewModelScope.launch { dao.deleteAll() }
    }

    /** Botão do alerta "upload_error": pede ao serviço em execução para reconectar e reenviar. */
    fun forceUploadReconnect() {
        context.startService(
            Intent(context, GluSyncService::class.java)
                .setAction(GluSyncService.ACTION_FORCE_UPLOAD_RECONNECT)
        )
    }
}
