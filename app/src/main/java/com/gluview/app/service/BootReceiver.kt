package com.gluview.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.gluview.app.data.datastore.AppPreferences
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var preferences: AppPreferences

    override fun onReceive(context: Context, intent: Intent) {
        // MY_PACKAGE_REPLACED: o Android mata o processo ao instalar uma versão nova (adb, Obtainium
        // ou o atualizador do próprio app). Sem isso o serviço — e com ele a leitura do sensor e os
        // alarmes — só voltava pelo ServiceWatchdogWorker, em até 15 min. As duas ações estão na
        // lista de exceções que podem iniciar serviço em primeiro plano a partir de segundo plano.
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val isConfigured = runBlocking { preferences.isConfigured.first() }
        if (isConfigured) {
            context.startForegroundService(Intent(context, GluSyncService::class.java))
        }
    }
}
