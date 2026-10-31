package com.gluview.app.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Observa o transporte da rede ativa (Wi-Fi vs. dados móveis/outro). Usado para poupar dados
 * móveis no envio de leituras ao GluSync — ver [com.gluview.app.service.GluSyncService].
 */
@Singleton
class NetworkMonitor @Inject constructor(
    @ApplicationContext context: Context
) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)

    fun isWifi(): Boolean {
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    /** Emite o estado (é Wi-Fi?) sempre que a rede ativa ou suas capacidades mudam. */
    val isWifiFlow: Flow<Boolean> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                trySend(caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
            }
            override fun onLost(network: Network) {
                trySend(false)
            }
        }
        cm?.registerDefaultNetworkCallback(callback)
        trySend(isWifi())
        awaitClose { cm?.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()
}
