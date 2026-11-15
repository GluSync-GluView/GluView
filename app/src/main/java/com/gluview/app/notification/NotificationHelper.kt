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
        // PT-5: canal novo para furar o Não Perturbe. O antigo ("gluview_dnd") tocava com som de
        // NOTIFICAÇÃO (mudo no modo "só alarmes") e o app nem declarava o acesso ao Não Perturbe,
        // então o setBypassDnd nunca valia sem a pessoa mexer no canal. Som e vibração de um canal
        // não mudam depois de criado: por isso um id novo, com som de ALARME.
        const val CHANNEL_DND        = "gluview_dnd_v2"
        private const val CHANNEL_DND_LEGACY = "gluview_dnd"
        const val CHANNEL_ERROR      = "gluview_error"
        const val CHANNEL_SENSOR     = "gluview_sensor"
        const val CHANNEL_CAREGIVER  = "gluview_caregiver"
        // Plano OTA (E6): aviso de versão nova. IMPORTANCE_LOW (sem som nem vibração) — e a
        // importância de um canal não muda depois de criado, por isso já nasce baixa.
        const val CHANNEL_UPDATE     = "gluview_update"
        // Doação via Pix: agradecimento quando o Pix é confirmado. Sem som nem vibração, para nunca
        // ser confundido com alarme de glicemia (importância de canal não muda depois de criado).
        const val CHANNEL_DONATION   = "gluview_donation"

        /** Pedido de promoção (Live Update): valor de Notification.EXTRA_REQUEST_PROMOTED_ONGOING (API 36.1). */
        private const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"

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
        private const val REQUEST_CODE_QUICK_ADD = 101

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
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
                )
                enableVibration(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                // O Android só aceita isto de um app com acesso ao Não Perturbe (ACCESS_NOTIFICATION_POLICY);
                // recriar o canal depois de a pessoa conceder o acesso atualiza o canal existente.
                setBypassDnd(true)
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
        runCatching { manager.deleteNotificationChannel(CHANNEL_DND_LEGACY) }
    }

    // ── PT-5: Não Perturbe ────────────────────────────────────────────────────

    /** Estado atual do Não Perturbe do aparelho. */
    fun currentDndState(): com.gluview.app.alert.DndState =
        runCatching { com.gluview.app.alert.DndState.fromInterruptionFilter(manager.currentInterruptionFilter) }
            .getOrDefault(com.gluview.app.alert.DndState.OFF)

    /** A política do Não Perturbe deixa alarmes passarem no modo prioridade (padrão do Android). */
    fun dndAllowsAlarms(): Boolean = runCatching {
        (manager.notificationPolicy.priorityCategories and NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS) != 0
    }.getOrDefault(false)

    /** O GluView tem acesso ao Não Perturbe (Ajustes do Android › Acesso ao Não Perturbe). */
    fun hasDndAccess(): Boolean = runCatching { manager.isNotificationPolicyAccessGranted }.getOrDefault(false)

    /**
     * O canal de furar o Não Perturbe pode, de fato, ignorar o DND agora. É a verdade que importa
     * (o Android decide): sem isso, "furar o Não Perturbe" não toca e o app avisa em vez de fingir.
     * Recria os canais antes de olhar, para o acesso concedido há pouco já valer.
     */
    fun dndBypassReady(): Boolean = runCatching {
        if (hasDndAccess()) createChannels()
        manager.getNotificationChannel(CHANNEL_DND)?.canBypassDnd() == true
    }.getOrDefault(false)

    /**
     * Vibração direta, com atributos de alarme — para o alerta que respeita o Não Perturbe com
     * "vibrar", e como tentativa quando "furar" não está disponível. Padrão longo e repetido 3x.
     */
    fun vibrateAlert() {
        runCatching {
            val pattern = longArrayOf(0, 800, 400, 800, 400, 800)
            val effect = android.os.VibrationEffect.createWaveform(pattern, -1)
            val vibrator: android.os.Vibrator? = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                context.getSystemService(android.os.VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(android.os.Vibrator::class.java)
            }
            if (vibrator?.hasVibrator() != true) return
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                vibrator.vibrate(effect, android.os.VibrationAttributes.createForUsage(android.os.VibrationAttributes.USAGE_ALARM))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
            }
        }
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
        dotsBitmap: Bitmap?,    // recolhida
        /** Plano 17: rótulo do botão "Registrar no diário"; null = sem botão (desligado em Ajustes). */
        quickAddLabel: String? = null
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

        if (quickAddLabel != null) {
            val quick = PendingIntent.getActivity(
                context, REQUEST_CODE_QUICK_ADD,
                Intent(context, com.gluview.app.QuickAddActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(R.drawable.ic_diary_edit, quickAddLabel, quick)
        }

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

    // ── PT-6: Live Update (Android 16) ────────────────────────────────────────

    /** O aparelho tem Live Updates (Android 16, API 36). */
    fun liveUpdatesSupported(): Boolean = android.os.Build.VERSION.SDK_INT >= 36

    /** A pessoa deixa o GluView mostrar notificação promovida (Ajustes do Android). */
    fun canPostPromoted(): Boolean =
        android.os.Build.VERSION.SDK_INT >= 36 && runCatching { manager.canPostPromotedNotifications() }.getOrDefault(false)

    /**
     * Notificação persistente como Live Update (Android 16): estilo padrão do sistema com barra de
     * progresso (posição da glicemia nas faixas) e texto curto no chip da barra de status. Pede a
     * promoção pelo extra "android.requestPromotedOngoing" — o mesmo que o setRequestPromotedOngoing
     * da API 36.1 grava; o método não existe na 36.0, o extra funciona nas duas. Sem layout próprio
     * (RemoteViews impede a promoção), então aqui não vai o gráfico. Em Android < 16 (ou se algo
     * falhar) devolve null e o serviço usa a notificação de sempre ([buildPersistentNotification]).
     */
    fun buildLiveUpdateNotification(live: LiveUpdateText, quickAddLabel: String?): Notification? {
        if (android.os.Build.VERSION.SDK_INT < 36) return null
        return runCatching {
            val pending = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val builder = Notification.Builder(context, CHANNEL_PERSISTENT)
                .setSmallIcon(R.drawable.ic_glucose)
                .setContentTitle(live.title)
                .setContentText(live.text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setContentIntent(pending)
                .setShortCriticalText(live.chip)
                .addExtras(android.os.Bundle().apply { putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true) })
            if (live.progress != null) {
                val style = Notification.ProgressStyle()
                    .setStyledByProgress(false)
                    .setProgressSegments(live.segments.map { seg ->
                        Notification.ProgressStyle.Segment(seg.length).setColor(when (seg.zone) {
                            LiveSegment.Zone.LOW     -> com.gluview.app.util.graph.AgpPalette.LOW
                            LiveSegment.Zone.TARGET  -> com.gluview.app.util.graph.AgpPalette.TARGET
                            LiveSegment.Zone.HIGH    -> com.gluview.app.util.graph.AgpPalette.HIGH
                        })
                    })
                    .setProgress(live.progress)
                // Marca onde está a glicemia agora (sem isso a barra só mostra as faixas).
                style.setProgressPoints(listOf(Notification.ProgressStyle.Point(live.progress)
                    .setColor(android.graphics.Color.WHITE)))
                builder.setStyle(style)
            }
            if (quickAddLabel != null) {
                val quick = PendingIntent.getActivity(
                    context, REQUEST_CODE_QUICK_ADD,
                    Intent(context, com.gluview.app.QuickAddActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                builder.addAction(Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(context, R.drawable.ic_diary_edit), quickAddLabel, quick).build())
            }
            builder.build()
        }.getOrNull()
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
            // Canal de furar o DND: categoria alarme, para passar também no modo "só alarmes".
            .apply { if (channel == CHANNEL_DND) setCategory(NotificationCompat.CATEGORY_ALARM) }
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
