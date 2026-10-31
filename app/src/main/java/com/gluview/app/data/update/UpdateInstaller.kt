package com.gluview.app.data.update

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.gluview.app.BuildConfig
import com.gluview.app.data.remote.UpdateApi
import com.gluview.app.data.remote.dto.UpdateDownloadLinkRequestDto
import com.gluview.app.di.UpdateDownloadHttp
import com.gluview.app.service.PostUpdateCheckWorker
import com.gluview.app.service.UpdateInstallReceiver
import com.gluview.app.util.AppLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import retrofit2.HttpException
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Baixa, confere e instala uma versão nova do GluView (plano OTA, E7, seção 5.3). Tudo começa por
 * um toque do usuário na tela Atualizações — nada aqui roda sozinho.
 *
 * Falha fechado em cada passo: porteiro de segurança, espaço, link (JWT + direito no servidor),
 * host do link, SHA-256, pacote, versionCode, minSdk e certificado contra a lista embutida
 * ([UpdateTrust.TRUSTED_CERT_SHA256]). O próprio Android também recusa outra chave e downgrade.
 *
 * Vive no escopo da aplicação (não do ViewModel) para o download continuar se a tela fechar.
 */
@Singleton
class UpdateInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: UpdateApi,
    @UpdateDownloadHttp private val downloadClient: OkHttpClient,
    private val gate: UpdateSafetyGate,
    private val updatePreferences: UpdatePreferences,
    private val appLogger: AppLogger
) {
    enum class FailReason {
        NO_SPACE, LINK_DENIED, LINK_NOT_FOUND, LINK_RATE_LIMITED, LINK_UNAVAILABLE, NETWORK,
        BAD_LINK, HASH_MISMATCH, BAD_PACKAGE, NOT_NEWER, UNTRUSTED_CERT, UNSUPPORTED_ANDROID,
        INSTALL_BLOCKED, INSTALL_CONFLICT, INSTALL_STORAGE, INSTALL_INCOMPATIBLE, INSTALL_ABORTED,
        INSTALL_FAILED
    }

    sealed interface State {
        data object Idle : State
        /** O porteiro achou motivos para talvez esperar: a tela pergunta "Atualizar agora" ou
         *  "Mais tarde" ([confirm] / [postpone]). Nada é bloqueado. */
        data class NeedsConfirmation(val reasons: List<UpdateSafetyRules.Reason>) : State
        data class Downloading(val readBytes: Long, val totalBytes: Long) : State
        data object Verifying : State
        /** Falta "Instalar apps desconhecidos" para o GluView. O arquivo já está conferido. */
        data object NeedsInstallPermission : State
        data object Installing : State
        /** O sistema pede confirmação (sempre no Android 9). A tela abre o [intent]. */
        data class WaitingUserAction(val intent: Intent) : State
        data object Installed : State
        data class Failed(val reason: FailReason) : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private var job: Job? = null
    @Volatile private var activeSessionId: Int = -1
    @Volatile private var verifiedTarget: Long = 0L
    @Volatile private var lastFailureMs = 0L

    /** Motivos que o usuário já confirmou nesta tentativa. Um motivo NOVO pergunta de novo. */
    @Volatile private var acceptedReasons: Set<UpdateSafetyRules.Reason> = emptySet()

    /** Em que etapa a pergunta do porteiro surgiu — para retomar dali ([confirm]) ou desistir
     *  sem efeito ([postpone]). */
    private sealed interface PendingStep {
        data class BeforeDownload(val target: UpdatePreferences.KnownLatest) : PendingStep
        data object BeforeCommit : PendingStep
        data class BeforeSystemDialog(val sessionId: Int, val intent: Intent) : PendingStep
    }
    @Volatile private var pendingStep: PendingStep? = null

    private val dir get() = File(context.cacheDir, "update")
    private val apkFile get() = File(dir, "GluView.apk")

    val isBusy: Boolean
        get() = _state.value is State.Downloading || _state.value is State.Verifying ||
            _state.value is State.Installing || job?.isActive == true

    fun reset() {
        if (isBusy) return
        _state.value = State.Idle
    }

    /** Toque em "Atualizar". Começa uma tentativa nova, sem motivos confirmados. */
    fun start(target: UpdatePreferences.KnownLatest) {
        if (isBusy) return
        // Sem retry apertado depois de uma falha de instalação (plano, seção 5.3, passo 5).
        if (System.currentTimeMillis() - lastFailureMs < 30_000) return
        acceptedReasons = emptySet()
        pendingStep = null
        job = scope.launch { runCatching { run(target) }.onFailure { fail(FailReason.NETWORK, it) } }
    }

    /** "Atualizar agora" na pergunta do porteiro: aceita os motivos mostrados e segue da etapa
     *  em que parou. */
    fun confirm() {
        val asked = _state.value as? State.NeedsConfirmation ?: return
        val step = pendingStep ?: return
        acceptedReasons = acceptedReasons + asked.reasons
        pendingStep = null
        appLogger.usage("UPDATE", "Usuario confirmou atualizar agora", mapOf("motivos" to asked.reasons.joinToString()))
        when (step) {
            is PendingStep.BeforeDownload ->
                job = scope.launch { runCatching { run(step.target) }.onFailure { fail(FailReason.NETWORK, it) } }
            PendingStep.BeforeCommit ->
                job = scope.launch { runCatching { install(verifiedTarget) }.onFailure { fail(FailReason.INSTALL_FAILED, it) } }
            is PendingStep.BeforeSystemDialog -> _state.value = State.WaitingUserAction(step.intent)
        }
    }

    /** "Mais tarde": desiste desta tentativa sem nenhuma consequência. A versão nova continua
     *  disponível na tela e o botão "Atualizar" volta. */
    fun postpone() {
        val step = pendingStep
        pendingStep = null
        acceptedReasons = emptySet()
        appLogger.usage("UPDATE", "Usuario deixou para mais tarde")
        scope.launch {
            when (step) {
                is PendingStep.BeforeSystemDialog -> {
                    runCatching { context.packageManager.packageInstaller.abandonSession(step.sessionId) }
                    clearPendingAfterFailure("postponed")
                }
                PendingStep.BeforeCommit -> apkFile.delete()
                else -> Unit
            }
            _state.value = State.Idle
        }
    }

    /** Volta da tela de permissão de apps desconhecidos. */
    fun continueAfterPermission() {
        if (isBusy || _state.value != State.NeedsInstallPermission) return
        // Voltou sem conceder: continua pedindo (a tela explica Bloqueador automático/Proteção avançada).
        if (!context.packageManager.canRequestPackageInstalls()) return
        job = scope.launch { runCatching { install(verifiedTarget) }.onFailure { fail(FailReason.INSTALL_FAILED, it) } }
    }

    private suspend fun run(target: UpdatePreferences.KnownLatest) {
        // ── 1ª avaliação do porteiro ─────────────────────────────────────────────────────────
        if (!passesGate(PendingStep.BeforeDownload(target))) return
        if (target.minSdk > Build.VERSION.SDK_INT) return fail(FailReason.UNSUPPORTED_ANDROID)

        // ── Espaço ───────────────────────────────────────────────────────────────────────────
        dir.mkdirs()
        val free = StatFs(context.cacheDir.path).availableBytes
        if (free < UpdateTrust.requiredFreeBytes(target.sizeBytes)) {
            appLogger.usage("UPDATE", "Sem espaço", mapOf("livre" to free, "tamanho" to target.sizeBytes))
            return fail(FailReason.NO_SPACE)
        }

        // ── Link temporário (JWT + direito conferido no servidor) ───────────────────────────
        _state.value = State.Downloading(0, target.sizeBytes)
        val link = try {
            api.downloadLink(UpdateDownloadLinkRequestDto(target.versionCode))
        } catch (e: HttpException) {
            return fail(when (e.code()) {
                401, 403 -> FailReason.LINK_DENIED
                404 -> FailReason.LINK_NOT_FOUND
                429 -> FailReason.LINK_RATE_LIMITED
                else -> FailReason.LINK_UNAVAILABLE
            }, e)
        }
        if (link.versionCode != target.versionCode || !UpdateTrust.isValidSha256(link.sha256) ||
            !UpdateTrust.isAllowedDownloadUrl(link.url) || link.sizeBytes <= 0) {
            appLogger.error("UPDATE", "Link recusado", mapOf("host" to hostOf(link.url)))
            return fail(FailReason.BAD_LINK)
        }

        // ── Download (arquivo privado de nome fixo, retomável) ──────────────────────────────
        val part = File(dir, "GluView-${target.versionCode}.apk.part")
        dir.listFiles()?.filter { it != part }?.forEach { it.delete() }
        download(link.url, part, link.sizeBytes)
        if (!part.renameTo(apkFile)) return fail(FailReason.INSTALL_STORAGE)

        // ── Conferência ──────────────────────────────────────────────────────────────────────
        _state.value = State.Verifying
        val sha = sha256(apkFile)
        if (sha != link.sha256) {
            appLogger.error("UPDATE", "SHA-256 não confere", mapOf("versionCode" to target.versionCode))
            apkFile.delete()
            return fail(FailReason.HASH_MISMATCH)
        }
        verifyArchive(apkFile, target.versionCode)?.let { reason ->
            apkFile.delete()
            return fail(reason)
        }
        verifiedTarget = target.versionCode
        appLogger.usage("UPDATE", "APK baixado e conferido", mapOf(
            "versionCode" to target.versionCode, "host" to hostOf(link.url), "bytes" to apkFile.length()
        ))

        // ── Permissão de instalar ────────────────────────────────────────────────────────────
        if (!context.packageManager.canRequestPackageInstalls()) {
            _state.value = State.NeedsInstallPermission
            return
        }
        install(target.versionCode)
    }

    /** true = segue. false = parou para perguntar (motivo ainda não confirmado nesta tentativa);
     *  [step] guarda de onde retomar. */
    private suspend fun passesGate(step: PendingStep): Boolean {
        val d = gate.evaluate()
        if (d !is UpdateSafetyRules.Decision.Confirm) return true
        if (acceptedReasons.containsAll(d.reasons)) return true
        appLogger.usage("UPDATE", "Porteiro pediu confirmacao", mapOf("motivos" to d.reasons.joinToString()))
        pendingStep = step
        _state.value = State.NeedsConfirmation(d.reasons)
        return false
    }

    private fun download(url: String, part: File, expectedSize: Long) {
        var already = if (part.exists()) part.length() else 0L
        if (already >= expectedSize) { part.delete(); already = 0L }
        val request = Request.Builder().url(url).apply {
            if (already > 0) header("Range", "bytes=$already-")
        }.build()
        downloadClient.newCall(request).execute().use { resp ->
            // O destino final (depois de qualquer redirect) também precisa ser um host permitido.
            if (!UpdateTrust.isAllowedDownloadUrl(resp.request.url.toString())) throw IllegalStateException("host")
            val append = resp.code == 206
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            if (!append) already = 0L
            val body = resp.body ?: throw IllegalStateException("sem corpo")
            FileOutputStream(part, append).use { out ->
                val buf = ByteArray(64 * 1024)
                var read = already
                var lastEmit = 0L
                body.byteStream().use { input ->
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        read += n
                        if (read > expectedSize + 1024 * 1024) throw IllegalStateException("maior que o esperado")
                        out.write(buf, 0, n)
                        if (read - lastEmit > 256 * 1024) { lastEmit = read; _state.value = State.Downloading(read, expectedSize) }
                    }
                }
                _state.value = State.Downloading(read, expectedSize)
            }
        }
    }

    /** Pacote, versão, minSdk e certificado do APK baixado. null = ok. */
    private fun verifyArchive(file: File, targetCode: Long): FailReason? {
        val pm = context.packageManager
        val info: PackageInfo = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            pm.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES)
        else
            @Suppress("DEPRECATION") pm.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNATURES))
            ?: return FailReason.BAD_PACKAGE

        if (info.packageName != UpdateTrust.APP_PACKAGE) return FailReason.BAD_PACKAGE
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
                   else @Suppress("DEPRECATION") info.versionCode.toLong()
        if (code != targetCode || code <= BuildConfig.VERSION_CODE) return FailReason.NOT_NEWER
        val minSdk = info.applicationInfo?.minSdkVersion ?: 0
        if (minSdk > Build.VERSION.SDK_INT) return FailReason.UNSUPPORTED_ANDROID

        // Android 9 (API 28): getPackageArchiveInfo com GET_SIGNING_CERTIFICATES devolve um APK em
        // ARQUIVO sem signingInfo (visto no Moto Z2 Play em 24/10/2026: lista vazia, update recusado).
        // Nesse caso, lê pelo caminho antigo (GET_SIGNATURES), que preenche. Se nenhum dos dois
        // trouxer certificado, continua falhando fechado.
        val certs = signingCertsSha256(info).ifEmpty {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNATURES)?.signatures.orEmpty().let { sigs ->
                val md = MessageDigest.getInstance("SHA-256")
                sigs.map { UpdateTrust.hex(md.digest(it.toByteArray())) }
            }
        }
        if (certs.isEmpty() || !UpdateTrust.isTrusted(certs)) {
            appLogger.error("UPDATE", "Certificado não confiável", mapOf("certs" to certs.joinToString()))
            return FailReason.UNTRUSTED_CERT
        }
        return null
    }

    private fun signingCertsSha256(info: PackageInfo): List<String> {
        val sigs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val si = info.signingInfo ?: return emptyList()
            if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
        } else @Suppress("DEPRECATION") info.signatures
        val md = MessageDigest.getInstance("SHA-256")
        return sigs.orEmpty().map { UpdateTrust.hex(md.digest(it.toByteArray())) }
    }

    // ── Instalação ───────────────────────────────────────────────────────────────────────────
    private suspend fun install(targetCode: Long) {
        if (!apkFile.exists()) return fail(FailReason.INSTALL_STORAGE)
        // ── 2ª avaliação: logo antes do commit ───────────────────────────────────────────────
        if (!passesGate(PendingStep.BeforeCommit)) return
        _state.value = State.Installing

        // Marcador e snapshot ANTES do commit: o processo morre na instalação e o resultado pode
        // nunca chegar ao processo novo. O PostUpdateCheckWorker sobrevive à morte do processo.
        updatePreferences.savePending(
            UpdatePreferences.Pending(targetCode, BuildConfig.VERSION_CODE.toLong(), System.currentTimeMillis()),
            grantedPermissions(context)
        )
        WorkManager.getInstance(context).enqueueUniqueWork(
            PostUpdateCheckWorker.WORK_NAME, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<PostUpdateCheckWorker>().setInitialDelay(10, TimeUnit.MINUTES).build()
        )

        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(UpdateTrust.APP_PACKAGE)
            setSize(apkFile.length())
            setInstallReason(PackageManager.INSTALL_REASON_USER)
            // Android 12+: sem diálogo do sistema quando o próprio app é o instalador de registro.
            // A instalação AINDA só acontece porque o usuário tocou em "Atualizar".
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        activeSessionId = sessionId
        try {
            withContext(Dispatchers.IO) {
                installer.openSession(sessionId).use { session ->
                    session.openWrite("GluView.apk", 0, apkFile.length()).use { out ->
                        apkFile.inputStream().use { it.copyTo(out, 64 * 1024) }
                        session.fsync(out)
                    }
                    val intent = Intent(context, UpdateInstallReceiver::class.java)
                        .setAction(UpdateInstallReceiver.ACTION_STATUS)
                        .setPackage(context.packageName)
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
                    val pending = PendingIntent.getBroadcast(context, sessionId, intent, flags)
                    appLogger.usage("UPDATE", "Instalando", mapOf("versionCode" to targetCode, "sessao" to sessionId))
                    session.commit(pending.intentSender)
                }
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(sessionId) }
            onInstallFinished(FailReason.INSTALL_FAILED, e.javaClass.simpleName)
        } finally {
            apkFile.delete()
        }
    }

    /** Resultado da sessão — chamado pelo [UpdateInstallReceiver]. Ignora sessões que não são a
     *  criada por este processo (receiver não exportado + sessionId conferido). */
    suspend fun onSessionStatus(sessionId: Int, status: Int, confirmIntent: Intent?, message: String?) {
        if (sessionId != activeSessionId) return
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // ── 3ª avaliação: antes de mostrar a confirmação do sistema ──────────────────
                // Depois que o usuário confirma no diálogo do Android não há como reavaliar. Se
                // surgiu motivo novo, pergunta antes; "Mais tarde" abandona a sessão (postpone).
                if (confirmIntent == null) return onInstallFinished(FailReason.INSTALL_FAILED, "sem intent")
                if (!passesGate(PendingStep.BeforeSystemDialog(sessionId, confirmIntent))) return
                _state.value = State.WaitingUserAction(confirmIntent)
            }
            PackageInstaller.STATUS_SUCCESS -> {
                appLogger.usage("UPDATE", "Instalação concluída", mapOf("sessao" to sessionId))
                _state.value = State.Installed
            }
            else -> onInstallFinished(
                when (status) {
                    PackageInstaller.STATUS_FAILURE_BLOCKED -> FailReason.INSTALL_BLOCKED
                    PackageInstaller.STATUS_FAILURE_CONFLICT -> FailReason.INSTALL_CONFLICT
                    PackageInstaller.STATUS_FAILURE_STORAGE -> FailReason.INSTALL_STORAGE
                    PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> FailReason.INSTALL_INCOMPATIBLE
                    PackageInstaller.STATUS_FAILURE_ABORTED -> FailReason.INSTALL_ABORTED
                    else -> FailReason.INSTALL_FAILED
                }, "status=$status ${message.orEmpty()}"
            )
        }
    }

    /** A tela abriu a confirmação do sistema: o resultado chega de novo pelo receiver. */
    fun onUserActionLaunched() {
        if (_state.value is State.WaitingUserAction) _state.value = State.Installing
    }

    private suspend fun onInstallFinished(reason: FailReason, detail: String) {
        appLogger.error("UPDATE", "Instalação não concluída", mapOf("motivo" to reason.name, "detalhe" to detail))
        clearPendingAfterFailure(reason.name)
        fail(reason)
    }

    private suspend fun clearPendingAfterFailure(result: String) {
        updatePreferences.clearPending("failed: $result")
        WorkManager.getInstance(context).cancelUniqueWork(PostUpdateCheckWorker.WORK_NAME)
        activeSessionId = -1
    }

    private fun fail(reason: FailReason, e: Throwable? = null) {
        if (e != null) appLogger.error("UPDATE", "Falha", mapOf("motivo" to reason.name, "erro" to e.javaClass.simpleName))
        lastFailureMs = System.currentTimeMillis()
        _state.value = State.Failed(reason)
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return UpdateTrust.hex(md.digest())
    }

    private fun hostOf(url: String) = runCatching { java.net.URI(url).host }.getOrNull() ?: "?"

    companion object {
        /** Permissões cuja perda silenciosa depois de atualizar é grave (SMS e ligação de alerta,
         *  Bluetooth do sensor, notificações, isenção de bateria). */
        fun grantedPermissions(context: Context): Set<String> {
            val candidates = buildList {
                add(Manifest.permission.SEND_SMS)
                add(Manifest.permission.CALL_PHONE)
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    add(Manifest.permission.BLUETOOTH_CONNECT); add(Manifest.permission.BLUETOOTH_SCAN)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
            }
            val granted = candidates.filter {
                context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
            }.toMutableSet()
            val pm = context.getSystemService(PowerManager::class.java)
            if (pm?.isIgnoringBatteryOptimizations(context.packageName) == true) granted += BATTERY_EXEMPTION
            return granted
        }

        const val BATTERY_EXEMPTION = "battery_optimization_exempt"
    }
}
