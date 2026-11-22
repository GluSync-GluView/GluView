package com.gluview.wear.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.gluview.wear.MainActivity
import com.gluview.wear.R
import com.gluview.wear.contract.WearAlert

/**
 * Plano 21, F7.1 — alerta vindo do celular: vibra no padrão que a pessoa escolheu para este tipo de alerta
 * (trechos liga/pausa) e mostra a notificação. O canal não vibra sozinho, para não somar duas vibrações.
 * Vibra como alarme (passa pelo modo silencioso do relógio, como o alerta do celular).
 */
object AlertNotifier {
    private const val TAG = "GluViewWear"
    private const val CHANNEL = "gluview_alerts"
    private const val NOTIF_ID = 7101

    fun show(context: Context, alert: WearAlert) {
        vibrate(context, alert)
        notify(context, alert)
        Log.i(TAG, "Alerta recebido: ${alert.kind} '${alert.title}' vibração ${alert.segmentsMs}")
    }

    private fun vibrate(context: Context, alert: WearAlert) {
        if (alert.segmentsMs.isEmpty()) return
        runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= 31)
                context.getSystemService(VibratorManager::class.java).defaultVibrator
            else @Suppress("DEPRECATION") context.getSystemService(Vibrator::class.java)
            val effect = VibrationEffect.createWaveform(alert.waveform(), -1)
            if (Build.VERSION.SDK_INT >= 33) vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
            else @Suppress("DEPRECATION") vibrator.vibrate(effect,
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
        }.onFailure { Log.w(TAG, "Vibração falhou: ${it.message}") }
    }

    private fun notify(context: Context, alert: WearAlert) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Sem permissão de notificação: só vibrou")
            return
        }
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Alertas de glicose", NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(false)
                setSound(null, null)
            })
        }
        val open = PendingIntent.getActivity(context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_gluview)
            .setContentTitle(alert.title)
            .setContentText(alert.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(alert.body))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        runCatching { nm.notify(NOTIF_ID, n) }.onFailure { Log.w(TAG, "Notificação falhou: ${it.message}") }
    }
}
