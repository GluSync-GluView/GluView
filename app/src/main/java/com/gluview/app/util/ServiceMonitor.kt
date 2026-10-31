package com.gluview.app.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ServiceMonitor @Inject constructor() {

    private val _lastSyncAt       = MutableStateFlow(0L)
    private val _signalRConnected = MutableStateFlow(false)
    private val _serviceRunning   = MutableStateFlow(false)
    private val _syncError        = MutableStateFlow<String?>(null)

    val lastSyncAt:       StateFlow<Long>    = _lastSyncAt.asStateFlow()
    val signalRConnected: StateFlow<Boolean> = _signalRConnected.asStateFlow()
    val serviceRunning:   StateFlow<Boolean> = _serviceRunning.asStateFlow()
    val syncError:        StateFlow<String?> = _syncError.asStateFlow()

    /** O serviço subiu. Antes "rodando" só nascia do 1º envio/busca ok — o seguidor (que não envia) e
     *  o paciente fora do Wi-Fi apareciam como "Parado" com o serviço no ar (L32). */
    fun recordStarted()                  { _serviceRunning.value = true }
    fun recordSync()                     { _lastSyncAt.value = System.currentTimeMillis(); _serviceRunning.value = true; _syncError.value = null }
    fun recordError(msg: String)         { _syncError.value = msg }
    fun recordSignalR(connected: Boolean){ _signalRConnected.value = connected }
    fun recordStopped()                  { _serviceRunning.value = false }
}
