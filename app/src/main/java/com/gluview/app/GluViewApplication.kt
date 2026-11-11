package com.gluview.app

import android.app.Application
import android.os.Build
import android.util.Log
import com.gluview.app.data.donation.DonationTracker
import com.gluview.app.data.update.PostUpdateVerifier
import com.gluview.app.service.UpdateCheckWorker
import com.gluview.app.util.RxErrorHandlers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.gluview.app.util.RxUndeliverableClassifier
import dagger.hilt.android.HiltAndroidApp
import io.reactivex.plugins.RxJavaPlugins
import io.reactivex.rxjava3.plugins.RxJavaPlugins as Rx3Plugins
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@HiltAndroidApp
class GluViewApplication : Application() {

    @Inject lateinit var postUpdateVerifier: PostUpdateVerifier
    @Inject lateinit var donationTracker: DonationTracker
    // Plano 17 — lembretes (canal + remarcar alarmes) e volta periódica do diário (só age com liberação de nuvem).
    @Inject lateinit var reminderNotifier: com.gluview.app.notification.ReminderNotifier
    @Inject lateinit var reminderScheduler: com.gluview.app.data.reminder.ReminderScheduler
    @Inject lateinit var diarySyncScheduler: com.gluview.app.data.diary.DiarySyncScheduler
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        // Instalado ANTES do super.onCreate() para capturar crash no Hilt e libs nativas.
        installCrashLogger()
        installRxJavaErrorHandler()
        super.onCreate()
        // Plano OTA (E6/E7): a checagem de versão nova vive aqui, fora do ciclo do GluSyncService e
        // do logout (KEEP: reabrir o app não reinicia o relógio). E o processo novo, depois de uma
        // atualização, reconcilia o marcador gravado antes da instalação.
        runCatching { UpdateCheckWorker.schedule(this) }
            .onFailure { Log.w("UPDATE", "Falha ao agendar a checagem de versão", it) }
        appScope.launch { runCatching { postUpdateVerifier.reconcile() } }
        // Doação via Pix: o processo pode ter morrido com um Pix pendente — volta a acompanhar.
        donationTracker.resume()
        runCatching { reminderNotifier.createChannels() }
        runCatching { diarySyncScheduler.schedulePeriodic() }
        appScope.launch { runCatching { reminderScheduler.rescheduleAll() } }
    }

    /**
     * Sem isto, um erro que chega depois que a cadeia Rx já foi descartada (ex.: BLE reporta
     * desconexão bem na hora em que MiBandUploader.connectAndUpload faz
     * disposables.clear() no fim do upload — GATT_SUCCESS, desconexão normal, não falha de
     * verdade) vira UndeliverableException sem ninguém pra tratar, sobe como exceção não
     * tratada e mata o processo (visto em crash.log de 2026-08-13, MiBandUploader.kt:167).
     * Handler recomendado pela própria doc do RxJava2 (link no texto do UndeliverableException):
     * https://github.com/ReactiveX/RxJava/wiki/What's-different-in-2.0#error-handling
     *
     * Classificação em [RxUndeliverableClassifier] (função pura, testada sem depender de
     * Application/Robolectric); aqui só sobra o efeito colateral (log ou repassar pro handler
     * padrão de crash).
     */
    private fun installRxJavaErrorHandler() {
        RxJavaPlugins.setErrorHandler(RxErrorHandlers.rx2(::handleRxError))
        // L29 — o SignalR (envio ao GluSync e seguidor ao vivo) usa RxJava 3, que tem o próprio
        // RxJavaPlugins. Sem tratador ali, qualquer erro sem destino derrubava o processo — visto
        // no S23 FE em 30/09, 11/10 e 14/10/2026 (NPE do SignalR ao parar uma conexão que nunca abriu).
        Rx3Plugins.setErrorHandler(RxErrorHandlers.rx3(::handleRxError))
    }

    private fun handleRxError(action: RxUndeliverableClassifier.Action, e: Throwable, cause: Throwable?) {
        when (action) {
            RxUndeliverableClassifier.Action.IGNORE ->
                Log.i("RxErrorHandler", "Undeliverable ignorado (${cause?.javaClass?.simpleName}): ${cause?.message}")
            RxUndeliverableClassifier.Action.CRASH ->
                Thread.currentThread().uncaughtExceptionHandler
                    ?.uncaughtException(Thread.currentThread(), cause ?: e)
            RxUndeliverableClassifier.Action.LOG_UNKNOWN ->
                Log.w("RxErrorHandler", "Undeliverable exception não categorizada", cause ?: e)
        }
    }

    private fun installCrashLogger() {
        val default = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, ex ->
            try {
                val dir = getExternalFilesDir("logs") ?: File(filesDir, "logs")
                dir.mkdirs()
                val ts = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())
                val sw = StringWriter()
                ex.printStackTrace(PrintWriter(sw))
                File(dir, "crash.log").writeText(
                    buildString {
                        appendLine("=== CRASH $ts ===")
                        appendLine("Thread  : ${thread.name}")
                        appendLine("Android : ${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE})")
                        appendLine("Device  : ${Build.MANUFACTURER} ${Build.MODEL}")
                        appendLine()
                        appendLine(sw.toString())
                    }
                )
            } catch (_: Throwable) {}
            default?.uncaughtException(thread, ex)
        }
    }
}
