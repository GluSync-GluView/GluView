package com.gluview.wear.watchface

import android.content.Context
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.wear.watchfacepush.WatchFacePushManagerFactory
import com.gluview.wear.contract.FaceAck
import java.io.File

/**
 * Instala/atualiza no relógio o visor desenhado pelo usuário (plano 16, W3), via Watch Face Push.
 *
 * Só existe no Wear OS 6+ (biblioteca de sistema "wear-sdk", declarada opcional no manifesto): fora
 * disso [isSupported] é false e o relógio responde [FaceAck.Unsupported]. O GluView ocupa um slot
 * só (Wear OS 6 tem 1 por app): se já tem visor instalado, atualiza esse em vez de pedir outro.
 * Sempre com o applicationContext (achado do W0: o contexto de um receiver não pode ligar a serviço).
 */
object FacePusher {
    private const val TAG = "GluViewWear"
    private const val PREFS = "gluview_face"
    private const val K_ACTIVATION_ASKED = "activation_asked"

    fun isSupported(): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false
        return runCatching { WatchFacePushManagerFactory.isSupported() }
            .onFailure { Log.w(TAG, "Watch Face Push indisponível: ${it::class.java.name}: ${it.message}") }
            .getOrDefault(false)
    }

    suspend fun install(context: Context, apk: File, token: String): FaceAck {
        if (!isSupported() || Build.VERSION.SDK_INT < 33) return FaceAck.Unsupported
        return installSupported(context.applicationContext, apk, token)
    }

    @RequiresApi(33)
    private suspend fun installSupported(app: Context, apk: File, token: String): FaceAck = runCatching {
        val manager = WatchFacePushManagerFactory.createWatchFacePushManager(app)
        val list = manager.listWatchFaces()
        val ours = list.installedWatchFaceDetails.firstOrNull()
        val details = ParcelFileDescriptor.open(apk, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            if (ours != null) manager.updateWatchFace(ours.slotId, fd, token)
            else if (list.remainingSlotCount > 0) manager.addWatchFace(fd, token)
            else return FaceAck.NoSlot
        }
        val active = runCatching { manager.isWatchFaceActive(details.packageName) }.getOrDefault(false)
        Log.i(TAG, "Visor ${if (ours != null) "atualizado" else "instalado"} no slot ${details.slotId}, ativo=$active")
        FaceAck.Installed(updated = ours != null, active = active)
    }.getOrElse {
        Log.w(TAG, "Falha no Watch Face Push: ${it::class.java.simpleName}: ${it.message}")
        FaceAck.Failed("${it::class.java.simpleName}: ${it.message}")
    }

    /** Há visor do GluView instalado e ele NÃO é o mostrador atual? (a tela oferece "Usar"). */
    suspend fun installedButInactive(context: Context): Boolean {
        if (!isSupported() || Build.VERSION.SDK_INT < 33) return false
        return runCatching {
            val m = WatchFacePushManagerFactory.createWatchFacePushManager(context.applicationContext)
            val ours = m.listWatchFaces().installedWatchFaceDetails.firstOrNull() ?: return false
            !m.isWatchFaceActive(ours.packageName)
        }.getOrDefault(false)
    }

    /** Põe o visor do GluView como mostrador atual. Exige a permissão (concedida na tela). */
    suspend fun activate(context: Context): Boolean {
        if (!isSupported() || Build.VERSION.SDK_INT < 33) return false
        return runCatching {
            val m = WatchFacePushManagerFactory.createWatchFacePushManager(context.applicationContext)
            val ours = m.listWatchFaces().installedWatchFaceDetails.firstOrNull() ?: return false
            m.setWatchFaceAsActive(ours.slotId)
            true
        }.getOrElse { Log.w(TAG, "Não ativou o visor: ${it.message}"); false }
    }

    /** A permissão de ativar só pode ser pedida UMA vez (se negada, não volta): guardamos que já pedimos. */
    fun activationAsked(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(K_ACTIVATION_ASKED, false)

    fun markActivationAsked(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(K_ACTIVATION_ASKED, true).apply()
}
