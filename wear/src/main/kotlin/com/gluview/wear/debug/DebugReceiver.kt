package com.gluview.wear.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.gluview.wear.BuildConfig
import com.gluview.wear.contract.WearToken
import com.gluview.wear.data.CloudFetcher
import com.gluview.wear.data.TokenStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * SÓ em builds de teste (debug/demo — registrado no manifesto desses source sets e ainda checa
 * [BuildConfig.DEBUG_TOOLS]): aponta o plano B para um GluSync local e força uma busca, para validar
 * a Fase 7 no emulador sem depender do celular.
 *
 *   adb shell am broadcast -n <pacote>/com.gluview.wear.debug.DebugReceiver \
 *       -a com.gluview.wear.DEBUG_SET_TOKEN --es token <jwt> --es baseUrl http://localhost:5000 --el expiresAt <ms>
 *   adb shell am broadcast -n <pacote>/com.gluview.wear.debug.DebugReceiver -a com.gluview.wear.DEBUG_FETCH
 */
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.DEBUG_TOOLS) return
        when (intent.action) {
            "com.gluview.wear.DEBUG_SET_TOKEN" -> {
                TokenStore.save(context, WearToken(
                    intent.getStringExtra("token").orEmpty(),
                    intent.getStringExtra("baseUrl").orEmpty().trimEnd('/'),
                    intent.getLongExtra("expiresAt", 0L),
                ))
                Log.i("GluViewWear", "DEBUG: token definido para ${intent.getStringExtra("baseUrl")}")
            }
            "com.gluview.wear.DEBUG_FETCH" -> {
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    val r = CloudFetcher.fetch(context)
                    Log.i("GluViewWear", "DEBUG: busca na nuvem -> $r")
                    pending.finish()
                }
            }
        }
    }
}
