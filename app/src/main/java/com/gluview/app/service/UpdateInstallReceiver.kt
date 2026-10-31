package com.gluview.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.content.IntentCompat
import com.gluview.app.data.update.UpdateInstaller
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Resultado da sessão do PackageInstaller (plano OTA, E7). NÃO exportado: só o PendingIntent
 * explícito criado pelo próprio [UpdateInstaller] chega aqui, e o installer ainda confere se o
 * sessionId é o da sessão que ele criou. Nada aqui baixa ou instala por conta própria.
 */
@AndroidEntryPoint
class UpdateInstallReceiver : BroadcastReceiver() {

    @Inject lateinit var installer: UpdateInstaller

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_STATUS) return
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try { installer.onSessionStatus(sessionId, status, confirm, message) } finally { pending.finish() }
        }
    }

    companion object {
        const val ACTION_STATUS = "com.gluview.app.UPDATE_INSTALL_STATUS"
    }
}
