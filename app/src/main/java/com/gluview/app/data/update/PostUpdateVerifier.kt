package com.gluview.app.data.update

import android.content.Context
import com.gluview.app.BuildConfig
import com.gluview.app.R
import com.gluview.app.data.datastore.AppPreferences
import com.gluview.app.notification.NotificationHelper
import com.gluview.app.util.AppLogger
import com.gluview.app.util.withAppLanguage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reconcilia o marcador de atualização pendente (plano OTA, E7, seção 5.3 passo 6). Idempotente:
 * roda no início do processo novo e de novo no [com.gluview.app.service.PostUpdateCheckWorker].
 * Não depende do STATUS_SUCCESS chegar — o processo antigo morre na instalação.
 *
 * Também compara as permissões concedidas com o retrato tirado antes do commit: perder SMS ou
 * ligação de alerta em silêncio é o pior caso, então vira notificação e aviso na tela.
 */
@Singleton
class PostUpdateVerifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val updatePreferences: UpdatePreferences,
    private val preferences: AppPreferences,
    private val notificationHelper: NotificationHelper,
    private val appLogger: AppLogger
) {
    enum class Outcome { NONE, UPDATED, FAILED, STILL_PENDING }

    suspend fun reconcile(): Outcome {
        val pending = updatePreferences.pending() ?: return Outcome.NONE
        val current = BuildConfig.VERSION_CODE.toLong()
        return when {
            current >= pending.targetCode -> {
                val lost = updatePreferences.permissionSnapshot() - UpdateInstaller.grantedPermissions(context)
                updatePreferences.setLostPermissions(lost)
                updatePreferences.setConfirmedAtMs(System.currentTimeMillis())
                updatePreferences.clearPending("ok: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                File(context.cacheDir, "update").deleteRecursively()
                notificationHelper.cancelUpdateAvailable()
                appLogger.usage("UPDATE", "Atualização confirmada", mapOf(
                    "de" to pending.fromCode, "para" to current, "permissoesPerdidas" to lost.joinToString()
                ))
                if (lost.isNotEmpty()) {
                    val ctx = context.withAppLanguage(preferences.appLanguage.first())
                    notificationHelper.showUpdateProblem(
                        ctx.getString(R.string.notif_update_permissions_title),
                        ctx.getString(R.string.notif_update_permissions_text)
                    )
                }
                Outcome.UPDATED
            }
            System.currentTimeMillis() - pending.sinceMs > TimeUnit.MINUTES.toMillis(30) -> {
                updatePreferences.clearPending("failed: version unchanged")
                File(context.cacheDir, "update").deleteRecursively()
                appLogger.error("UPDATE", "Atualização não aconteceu", mapOf("alvo" to pending.targetCode))
                Outcome.FAILED
            }
            else -> Outcome.STILL_PENDING
        }
    }
}
