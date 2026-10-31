package com.gluview.app.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.media.AudioManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.util.TypedValue
import android.widget.RemoteViews
import androidx.car.app.notification.CarAppExtender
import androidx.car.app.notification.CarNotificationManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.gluview.app.MainActivity
import com.gluview.app.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        const val CHANNEL_PERSISTENT = "gluview_persistent_v2"
        const val CHANNEL_ALERT      = "gluview_alert"
        const val CHANNEL_ALARM      = "gluview_alarm"
        const val CHANNEL_DND        = "gluview_dnd"
        const val CHANNEL_ERROR      = "gluview_error"
        const val CHANNEL_SENSOR     = "gluview_sensor"
        const val CHANNEL_CAREGIVER  = "gluview_caregiver"
        // Plano OTA (E6): aviso de versão nova. IMPORTANCE_LOW (sem som nem vibração) — e a
        // importância de um canal não muda depois de criado, por isso já nasce baixa.
        const val CHANNEL_UPDATE     = "gluview_update"
        // Doação via Pix: agradecimento quando o Pix é confirmado. Sem som nem vibração, para nunca
        // ser confundido com alarme de glicemia (importância de canal não muda depois de criado).
        const val CHANNEL_DONATION   = "gluview_donation"

        const val NOTIF_ID_PERSISTENT           = 1
        const val NOTIF_ID_ALERT_NOTIFICATION   = 2
        const val NOTIF_ID_ALERT_ALARM          = 3
        const val NOTIF_ID_ALERT_DND            = 4
        const val NOTIF_ID_PREDICTIVE           = 5
        const val NOTIF_ID_ERROR                = 6
        const val NOTIF_ID_SENSOR               = 7
        const val NOTIF_ID_CAREGIVER_INVITE     = 8
        const val NOTIF_ID_CAREGIVER_ACCEPTED   = 9
        const val NOTIF_ID_UPLOAD_ERROR         = 10
        const val NOTIF_ID_UPDATE               = 11 // plano OTA: versão nova disponível
        const val NOTIF_ID_UPDATE_PROBLEM       = 12 // plano OTA: monitoramento/permissão não voltou após atualizar
        const val NOTIF_ID_DONATION             = 13 // doação via Pix confirmada

        private const val REQUEST_CODE_ERROR = 100

        /** Teto da fonte na recolhida (2 linhas em ~48dp) e no valor da expandida. */
        private const val COLLAPSED_MAX_FONT_SCALE = 1.3f
        private const val EXPANDED_MAX_FONT_SCALE = 1.5f
    }

    private val manager = context.getSystemService(NotificationManager::class.java)

    fun createChannels() {
        val channels = listOf(
            NotificationChannel(
                CHANNEL_PERSISTENT,
                context.getString(R.string.notif_channel_persistent_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = context.getString(R.string.notif_channel_persistent_desc)
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
                // Lock screen: exibe a notificação completa (valor + seta) na tela bloqueada e AOD
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
            NotificationChannel(
                CHANNEL_ALERT,
                context.getString(R.string.notif_channel_alert_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = context.getString(R.string.notif_channel_alert_desc) },
            NotificationChannel(
                CHANNEL_ALARM,
                context.getString(R.string.notif_channel_alarm_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.notif_channel_alarm_desc)
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
                )
            },
            NotificationChannel(
                CHANNEL_DND,
                context.getString(R.string.notif_channel_dnd_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.notif_channel_dnd_desc)
                enableVibration(true); setBypassDnd(true)
            },
            NotificationChannel(
                CHANNEL_ERROR,
                context.getString(R.string.notif_channel_error_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = context.getString(R.string.notif_channel_error_desc) },
            NotificationChannel(
                CHANNEL_SENSOR,
                context.getString(R.string.notif_channel_sensor_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = context.getString(R.string.notif_channel_sensor_desc) },
            NotificationChannel(
                CHANNEL_CAREGIVER,
                context.getString(R.string.notif_channel_caregiver_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = context.getString(R.string.notif_channel_caregiver_desc) },
            NotificationChannel(
                CHANNEL_UPDATE,
                context.getString(R.string.notif_channel_update_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.notif_channel_update_desc)
                setShowBadge(true)
                setSound(null, null)
                enableVibration(false)
            },
            NotificationChannel(
                CHANNEL_DONATION,
                context.getString(R.string.notif_channel_donation_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = context.getString(R.string.notif_channel_donation_desc)
                setSound(null, null)
                enableVibration(false)
            }
        )
        channels.forEach { manager.createNotificationChannel(it) }
    }

    /** Doação via Pix confirmada. Toque abre a tela de doação (deep link que só navega). */
    fun showDonationThanks(amount: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("gluview://donation")).apply {
            setClass(context, MainActivity::class.java)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val message = context.getString(R.string.notif_donation_text, amount)
        val notification = NotificationCompat.Builder(context, CHANNEL_DONATION)
            .setSmallIcon(R.drawable.ic_glucose)
            .setContentTitle(context.getString(R.string.notif_donation_title))
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    context, NOTIF_ID_DONATION, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()
        manager.notify(NOTIF_ID_DONATION, notification)
    }

    /** Toque abre a tela Atualizações. Intent EXPLÍCITO (setClass) e deep link que só navega:
     *  nada vindo de fora consegue disparar download ou instalação por aqui (plano, seção 5.7). */
    private fun updatesScreenIntent(requestCode: Int): PendingIntent {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("gluview://updates")).apply {
            setClass(context, MainActivity::class.java)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * Plano OTA (E6) — versão nova. "Recomendada" vai no canal baixo (sem som); "segurança" vai no
     * canal de erro, com prioridade padrão. Nenhuma das duas instala nada: só leva à tela.
     */
    fun showUpdateAvailable(title: String, message: String, security: Boolean) {
        val notification = NotificationCompat.Builder(context, if (security) CHANNEL_ERROR else CHANNEL_UPDATE)
            .setSmallIcon(R.drawable.ic_glucose)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(if (security) NotificationCompat.PRIORITY_DEFAULT else NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(updatesScreenIntent(NOTIF_ID_UPDATE))
            .build()
        manager.notify(NOTIF_ID_UPDATE, notification)
    }

    fun cancelUpdateAvailable() = manager.cancel(NOTIF_ID_UPDATE)

    /** Plano OTA (E7) — depois de atualizar, o monitoramento não voltou ou uma permissão sumiu. */
    fun showUpdateProblem(title: String, message: String) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ERROR)
            .setSmallIcon(R.drawable.ic_glucose)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(updatesScreenIntent(NOTIF_ID_UPDATE_PROBLEM))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        manager.notify(NOTIF_ID_UPDATE_PROBLEM, notification)
    }

    /** Notificação simples (sem dados ainda — usada no startForeground inicial). */
    fun buildPersistentNotification(text: String): Notification =
        buildPersistentNotification(text, null, null, null, null)

    /**
     * Notificação persistente (redesign visual, Fase 5):
     *  - recolhida: "135 ↗" e "+3 mg/dL · 14:05" (esquerda) + miniatura do gráfico (direita);
     *  - expandida: o gráfico da Home no tamanho real + a mesma linha embaixo.
     * Sem fundo nem cor de texto próprios: o layout usa os estilos de texto do sistema e segue o
     * tema do cartão (antes: caixa escura em cartão claro, ou a hora invisível no tema claro).
     * @param sgvArrowText "135 ↗", ou "Sem sinal"; null = só o [text] (sem dado ainda).
     * @param detailText "+3 mg/dL · 14:05", ou "Última leitura 14:05".
     */
    fun buildPersistentNotification(
        text: String,
        sgvArrowText: String?,
        detailText: String?,
        graphBitmap: Bitmap?,   // expandida
        dotsBitmap: Bitmap?     // recolhida
    ): Notification {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pending = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_PERSISTENT)
            .setSmallIcon(R.drawable.ic_glucose)
            .setContentTitle(sgvArrowText ?: context.getString(R.string.app_name))
            .setContentText(detailText ?: text)
            .setOngoing(true)
            .setContentIntent(pending)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            // Categoria SERVICE garante exibição correta no AOD do Samsung One UI
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            // Sem horário "X min ago" na lock screen — confuso para uma leitura contínua
            .setShowWhen(false)
            // Não emite som/vibração nas atualizações de valor (só na criação inicial)
            .setOnlyAlertOnce(true)

        // Collapsed + Expanded com RemoteViews quando há dados
        if (sgvArrowText != null) {
            val collapsed = RemoteViews(context.packageName, R.layout.notification_collapsed)
            collapsed.setTextViewText(R.id.tv_notif_sgv, sgvArrowText)
            collapsed.setTextViewText(R.id.tv_notif_detail, detailText.orEmpty())
            // A recolhida tem ~48dp: com a fonte do sistema em 2,0 a 2ª linha cortava.
            collapsed.setTextViewTextSize(R.id.tv_notif_sgv, TypedValue.COMPLEX_UNIT_PX, cappedPx(14f, COLLAPSED_MAX_FONT_SCALE))
            collapsed.setTextViewTextSize(R.id.tv_notif_detail, TypedValue.COMPLEX_UNIT_PX, cappedPx(12f, COLLAPSED_MAX_FONT_SCALE))
            if (dotsBitmap != null) collapsed.setImageViewBitmap(R.id.iv_notif_dots, dotsBitmap)

            val expanded = RemoteViews(context.packageName, R.layout.notification_persistent)
            expanded.setTextViewText(R.id.tv_sgv_arrow, sgvArrowText)
            expanded.setTextViewText(R.id.tv_delta, detailText.orEmpty())
            expanded.setTextViewTextSize(R.id.tv_sgv_arrow, TypedValue.COMPLEX_UNIT_PX, cappedPx(18f, EXPANDED_MAX_FONT_SCALE))
            if (graphBitmap != null) expanded.setImageViewBitmap(R.id.iv_graph, graphBitmap)

            builder
                .setCustomContentView(collapsed)
                .setCustomBigContentView(expanded)
                .setStyle(NotificationCompat.DecoratedCustomViewStyle())
        }

        return builder.build()
    }

    /** [sp] em px com a escala de fonte do sistema limitada a [maxScale]. */
    private fun cappedPx(sp: Float, maxScale: Float): Float {
        val res = context.resources
        return sp * res.displayMetrics.density * minOf(res.configuration.fontScale, maxScale)
    }

    /**
     * Alertas de hipo/hiperglicemia — estendidos com [CarAppExtender] pra aparecer também na
     * tela do carro (Android Auto), com prioridade alta (heads-up). É o uso de maior valor de
     * segurança da integração: avisar a pessoa enquanto ela dirige. Precisa ser postada via
     * [CarNotificationManager] (não o NotificationManager cru) — é o próprio requisito
     * documentado da lib pra a extensão funcionar; ele já cobre o envio normal ao telefone.
     */
    fun showAlertNotification(
        title: String,
        message: String,
        channel: String = CHANNEL_ALERT,
        notifId: Int = NOTIF_ID_ALERT_NOTIFICATION
    ) {
        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_glucose)
            .setContentTitle(title)
            .setContentText(message)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .extend(
                CarAppExtender.Builder()
                    .setContentTitle(title)
                    .setContentText(message)
                    .setSmallIcon(R.drawable.ic_glucose)
                    .setImportance(NotificationManagerCompat.IMPORTANCE_HIGH)
                    .build()
            )
        CarNotificationManager.from(context).notify(notifId, builder)
    }

    fun cancelAllAlerts() {
        manager.cancel(NOTIF_ID_ALERT_NOTIFICATION)
        manager.cancel(NOTIF_ID_ALERT_ALARM)
        manager.cancel(NOTIF_ID_ALERT_DND)
        manager.cancel(NOTIF_ID_PREDICTIVE)
    }

    fun showErrorNotification(sessionExpired: Boolean = false) {
        val deepLinkIntent = Intent(Intent.ACTION_VIEW, Uri.parse("gluview://diagnostic")).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pending = PendingIntent.getActivity(
            context, REQUEST_CODE_ERROR, deepLinkIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = context.getString(if (sessionExpired) R.string.notif_error_session_title else R.string.notif_error_sync_title)
        val text  = context.getString(if (sessionExpired) R.string.notif_error_session_text else R.string.notif_error_sync_text)
        val notification = NotificationCompat.Builder(context, CHANNEL_ERROR)
            .setSmallIcon(R.drawable.ic_glucose)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        manager.notify(NOTIF_ID_ERROR, notification)
    }

    fun cancelError() = manager.cancel(NOTIF_ID_ERROR)

    /**
     * Aviso de saúde do sensor (Frente B), estilo "bateria fraca". É só informativo —
     * NUNCA implica parar a leitura. Abre o app ao tocar.
     */
    fun showSensorWarning(title: String, message: String) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pending = PendingIntent.getActivity(
            context, 7, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_SENSOR)
            .setSmallIcon(R.drawable.ic_glucose)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pending)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        manager.notify(NOTIF_ID_SENSOR, notification)
    }

    fun cancelSensorWarning() = manager.cancel(NOTIF_ID_SENSOR)

    /**
     * F6 — convite de acompanhamento recebido ou aceito. Toque abre direto a tela
     * "Acompanhamento" via deep link (gluview://caregiver), igual ao showErrorNotification.
     */
    fun showCaregiverNotification(title: String, message: String, notifId: Int) {
        val deepLinkIntent = Intent(Intent.ACTION_VIEW, Uri.parse("gluview://caregiver")).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pending = PendingIntent.getActivity(
            context, notifId, deepLinkIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_CAREGIVER)
            .setSmallIcon(R.drawable.ic_glucose)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .setContentIntent(pending)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        manager.notify(notifId, notification)
    }

    /**
     * Erro de gravação persistente (>1h sem publicar nenhuma leitura no GluSync). Toque abre
     * direto o histórico de alertas (gluview://alerthistory), onde fica o botão de reconexão manual.
     */
    fun showUploadErrorNotification(title: String, message: String) {
        val deepLinkIntent = Intent(Intent.ACTION_VIEW, Uri.parse("gluview://alerthistory")).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pending = PendingIntent.getActivity(
            context, NOTIF_ID_UPLOAD_ERROR, deepLinkIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ERROR)
            .setSmallIcon(R.drawable.ic_glucose)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pending)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        manager.notify(NOTIF_ID_UPLOAD_ERROR, notification)
    }
}
