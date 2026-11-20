package com.gluview.wearables.core

import kotlinx.coroutines.flow.Flow

/** Transporte/aparelho concreto. Implementações reais falam BLE/protocolo; testes usam transporte falso. */
interface WearableTransport {
    val info: WearableDeviceInfo
    val events: Flow<WearableEvent>

    suspend fun connect(): Boolean
    suspend fun disconnect()
    suspend fun isConnected(): Boolean

    /** Envia um comando; retorna true se aceito/enviado. */
    suspend fun send(command: WearableCommand): Boolean
}
