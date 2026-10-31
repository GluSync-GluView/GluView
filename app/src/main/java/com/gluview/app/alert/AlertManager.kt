package com.gluview.app.alert

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.speech.tts.TextToSpeech
import android.telephony.SmsManager
import com.gluview.app.data.datastore.AppPreferences
import com.gluview.app.data.local.dao.AlertLogDao
import com.gluview.app.data.local.entity.AlertLogEntity
import com.gluview.app.data.remote.IftttWebhookClient
import com.gluview.app.data.repository.GlucoseRepository
import com.gluview.app.domain.model.GlucoseReading
import com.gluview.app.domain.model.GlucoseZone
import com.gluview.app.notification.NotificationHelper
import com.gluview.app.util.AppLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

val ALERT_ZONES = listOf(
    GlucoseZone.URGENT_LOW, GlucoseZone.LOW, GlucoseZone.HIGH,
    GlucoseZone.URGENT_HIGH, GlucoseZone.STALE, GlucoseZone.QUICK_DROP
)

@Singleton
class AlertManager @Inject constructor(
    private val notificationHelper: NotificationHelper,
    private val preferences: AppPreferences,
    private val glucoseRepository: GlucoseRepository,
    private val alertLogDao: AlertLogDao,
    private val iftttClient: IftttWebhookClient,
    private val appLogger: AppLogger,
    @ApplicationContext private val context: Context
) {
    private val scope  = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mutex  = Mutex()

    @Volatile private var lastZone: GlucoseZone? = null
    @Volatile private var silencedUntil: Long = 0L

    private var reAlarmJob: Job? = null
    private var callJob: Job? = null

    private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false

    init {
        // Plano OTA (E7) — a soneca sobrevive à troca de processo (atualização do app, reinício):
        // restaura o valor salvo. Só a soneca é persistida; lastZone continua em memória de
        // propósito (persistir mudaria quando o alarme da zona atual volta a tocar).
        scope.launch {
            runCatching { preferences.alertSilencedUntilMs.first() }.getOrNull()
                ?.let { saved -> if (saved > silencedUntil) silencedUntil = saved }
        }
    }

    // Chamado do GluSyncService (polling ou SignalR) — thread-safe
    fun evaluate(reading: GlucoseReading) {
        scope.launch { mutex.withLock { evaluateInternal(reading) } }
    }

    fun silenceFor(hours: Int) {
        silencedUntil = System.currentTimeMillis() + TimeUnit.HOURS.toMillis(hours.toLong())
        val until = silencedUntil
        scope.launch { runCatching { preferences.saveAlertSilencedUntilMs(until) } }
    }

    fun isSilenced() = System.currentTimeMillis() < silencedUntil
    fun clearSilence() {
        silencedUntil = 0L
        scope.launch { runCatching { preferences.saveAlertSilencedUntilMs(0L) } }
    }

    // ── Lógica central ────────────────────────────────────────────────────────

    private suspend fun evaluateInternal(reading: GlucoseReading) {
        val zone = reading.zone
        val previous = lastZone

        // S14 — preditivo: roda em toda leitura NORMAL, independente de mudança de zona.
        // Só faz sentido quando a glicemia ainda está dentro da faixa mas em queda.
        if (zone == GlucoseZone.NORMAL) {
            checkPredictive(reading)
        }

        if (zone == previous) return  // mesma zona — re-alarme já agendado se configurado

        lastZone = zone
        reAlarmJob?.cancel()
        callJob?.cancel()

        if (zone == GlucoseZone.NORMAL) {
            notificationHelper.cancelAllAlerts()
            return
        }

        // Dispara alerta para a nova zona
        fireZoneAlert(reading, zone)

        // S14 — agenda re-alarme se configurado
        if (preferences.reAlarmEnabled.first()) {
            val interval = preferences.reAlarmInterval.first()
            reAlarmJob = scope.launch {
                delay(TimeUnit.MINUTES.toMillis(interval.toLong()))
                mutex.withLock {
                    if (lastZone == zone) fireZoneAlert(reading, zone)
                }
            }
        }

        // S16 — agenda ligação em hipo urgente
        if (zone == GlucoseZone.URGENT_LOW && preferences.callAlertEnabled.first()) {
            val delayMin = preferences.callAlertDelay.first()
            callJob = scope.launch {
                delay(TimeUnit.MINUTES.toMillis(delayMin.toLong()))
                if (lastZone == GlucoseZone.URGENT_LOW) makeCall()
            }
        }

        // S17 — envia SMS em hipo (urgente ou simples)
        if ((zone == GlucoseZone.URGENT_LOW || zone == GlucoseZone.LOW)
            && preferences.smsAlertEnabled.first()) {
            sendSms(reading)
        }
    }

    // ── Disparo por zona ──────────────────────────────────────────────────────

    private suspend fun fireZoneAlert(reading: GlucoseReading, zone: GlucoseZone) {
        val title   = zoneTitle(zone)
        val message = buildMessage(reading)
        val silenced = isSilenced()
        val quiet    = isInQuietPeriod()

        var fired = false
        var highestType = AlertType.NOTIFICATION

        // Cada tipo é independente — todos os habilitados disparam
        if (!silenced && preferences.isAlertEnabled(zone, AlertType.DND)) {
            notificationHelper.showAlertNotification(title, message, NotificationHelper.CHANNEL_DND, NotificationHelper.NOTIF_ID_ALERT_DND)
            fired = true; highestType = AlertType.DND
        }
        if (!silenced && preferences.isAlertEnabled(zone, AlertType.ALARM)) {
            notificationHelper.showAlertNotification(title, message, NotificationHelper.CHANNEL_ALARM, NotificationHelper.NOTIF_ID_ALERT_ALARM)
            fired = true; if (highestType == AlertType.NOTIFICATION) highestType = AlertType.ALARM
        }
        if (!silenced && !quiet && preferences.isAlertEnabled(zone, AlertType.NOTIFICATION)) {
            notificationHelper.showAlertNotification(title, message, NotificationHelper.CHANNEL_ALERT, NotificationHelper.NOTIF_ID_ALERT_NOTIFICATION)
            fired = true
        }

        if (!fired) return

        // S15 — TTS
        if (preferences.ttsEnabled.first()) {
            speak("Glicemia ${reading.sgvMgDl.toInt()}, ${zoneName(zone)}")
        }

        // S18 — log no Room
        alertLogDao.insert(AlertLogEntity(
            sgvMgDl   = reading.sgvMgDl,
            zone      = zone.name,
            alertType = highestType.name.lowercase()
        ))

        // IFTTT — best-effort, nunca bloqueia alerta local
        runCatching { iftttClient.triggerZone(zone, reading) }
    }

    // ── S14 — Alerta preditivo ────────────────────────────────────────────────

    private suspend fun checkPredictive(reading: GlucoseReading) {
        if (!preferences.predictiveEnabled.first()) return
        val thresholdMin = preferences.predictiveMinutes.first()

        val recent = glucoseRepository.getRecentReadings(System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(20))
        if (recent.size < 2) return

        val newest = recent.last()
        val older  = recent[recent.size - 2]
        val dtMs   = (newest.dateMs - older.dateMs).toFloat()
        if (dtMs <= 0) return

        val ratePerMin = ((newest.sgvMgDl - older.sgvMgDl) / dtMs * 60_000f).toFloat()
        if (ratePerMin >= 0) return  // não está caindo

        // Até o limite "Urgente baixo" do usuário (L2; antes 55 fixo).
        val urgentLow = preferences.glucoseThresholds.first().urgentLow
        val minutesToHypo = ((reading.sgvMgDl - urgentLow) / -ratePerMin).toInt()
        if (minutesToHypo !in 1..thresholdMin) return

        val msg = "${reading.sgvMgDl.toInt()} mg/dL ${reading.arrow}, −${String.format("%.1f", -ratePerMin)} mg/dL/min"
        notificationHelper.showAlertNotification(
            title   = "Hipo prevista em ~$minutesToHypo min",
            message = msg,
            channel = NotificationHelper.CHANNEL_ALARM,
            notifId = NotificationHelper.NOTIF_ID_PREDICTIVE
        )
        alertLogDao.insert(AlertLogEntity(
            sgvMgDl   = reading.sgvMgDl,
            zone      = "PREDICTIVE",
            alertType = AlertType.ALARM.name.lowercase()
        ))
        runCatching { iftttClient.triggerPredictive(reading, minutesToHypo) }
    }

    // ── S16 — Ligação ─────────────────────────────────────────────────────────

    private suspend fun makeCall() {
        val phone = preferences.callAlertPhone.first()
        if (phone.isBlank()) return
        if (context.checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) return
        context.startActivity(
            Intent(Intent.ACTION_CALL, Uri.parse("tel:$phone"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    // ── S17 — SMS ─────────────────────────────────────────────────────────────

    private suspend fun sendSms(reading: GlucoseReading) {
        val contact = preferences.smsContact.first()
        if (contact.isBlank()) return
        val template = preferences.smsMessage.first().ifBlank {
            "GluView: glicemia baixa {sgv} mg/dL {arrow}"
        }
        val message = template
            .replace("{sgv}", reading.sgvMgDl.toInt().toString())
            .replace("{arrow}", reading.arrow)
        // Plano OTA (E7) — antes a falta de permissão caía no catch vazio e o SMS de hipo deixava
        // de sair sem nenhum rastro. Uma atualização fora de loja pode revogar SEND_SMS; agora fica
        // registrado (log e verificação pós-atualização mostram na tela Atualizações).
        if (context.checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            appLogger.error("ALERT", "SMS de alerta não enviado: sem permissão SEND_SMS")
            return
        }
        try {
            val sms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            sms?.sendTextMessage(contact, null, message, null, null)
            appLogger.usage("ALERT", "SMS de alerta enviado")
        } catch (e: Exception) {
            appLogger.error("ALERT", "SMS de alerta falhou", mapOf("erro" to e.javaClass.simpleName))
        }
    }

    // ── S15 — Período de silêncio ─────────────────────────────────────────────

    private suspend fun isInQuietPeriod(): Boolean {
        if (!preferences.quietPeriodEnabled.first()) return false
        val hour  = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val start = preferences.quietStartHour.first()
        val end   = preferences.quietEndHour.first()
        return if (start <= end) hour in start until end
               else hour >= start || hour < end  // período noturno que passa da meia-noite
    }

    // ── S15 — TTS ─────────────────────────────────────────────────────────────

    private fun speak(text: String) {
        if (tts == null) {
            tts = TextToSpeech(context) { status ->
                ttsReady = status == TextToSpeech.SUCCESS
                if (ttsReady) {
                    tts?.language = Locale("pt", "BR")
                    tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "gv")
                }
            }
        } else if (ttsReady) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "gv")
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun buildMessage(reading: GlucoseReading): String {
        val delta = when {
            reading.delta > 0 -> " +${reading.delta}"
            reading.delta < 0 -> " ${reading.delta}"
            else              -> ""
        }
        return "${reading.sgvMgDl.toInt()} mg/dL ${reading.arrow}$delta"
    }

    private fun zoneTitle(zone: GlucoseZone): String = when (zone) {
        GlucoseZone.URGENT_LOW  -> "Hipoglicemia urgente"
        GlucoseZone.LOW         -> "Hipoglicemia"
        GlucoseZone.HIGH        -> "Hiperglicemia"
        GlucoseZone.URGENT_HIGH -> "Hiperglicemia urgente"
        GlucoseZone.STALE       -> "Sensor sem sinal"
        GlucoseZone.QUICK_DROP  -> "Queda rápida"
        GlucoseZone.NORMAL      -> ""
    }

    private fun zoneName(zone: GlucoseZone): String = when (zone) {
        GlucoseZone.URGENT_LOW  -> "hipoglicemia urgente"
        GlucoseZone.LOW         -> "hipoglicemia"
        GlucoseZone.HIGH        -> "hiperglicemia"
        GlucoseZone.URGENT_HIGH -> "hiperglicemia urgente"
        GlucoseZone.STALE       -> "sem sinal"
        GlucoseZone.QUICK_DROP  -> "queda rápida"
        GlucoseZone.NORMAL      -> "normal"
    }
}
