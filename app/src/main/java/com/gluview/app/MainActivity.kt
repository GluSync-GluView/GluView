package com.gluview.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.nfc.NfcAdapter
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.DisposableEffect
import androidx.core.os.LocaleListCompat
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.gluview.app.data.datastore.AppPreferences
import com.gluview.app.data.libre2.Libre2NfcController
import com.gluview.app.service.GluSyncService
import com.gluview.app.ui.navigation.AppNavigation
import com.gluview.app.ui.navigation.Screen
import com.gluview.app.ui.screen.terms.TERMS_VERSION
import com.gluview.app.ui.theme.GluViewTheme
import com.gluview.app.ui.theme.ThemeMode
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject lateinit var preferences: AppPreferences
    @Inject lateinit var nfcController: Libre2NfcController
    @Inject lateinit var updateChecker: com.gluview.app.data.update.UpdateChecker

    private var deepLinkRoute: String? by mutableStateOf(null)
    private val nfcAdapter: NfcAdapter? by lazy { NfcAdapter.getDefaultAdapter(this) }

    // Solicita POST_NOTIFICATIONS (obrigatório Android 13+, targetSdk 33+)
    private val requestNotifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* sem-op */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        // installSplashScreen DEVE vir antes de super.onCreate()
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Sem essa permissão concedida em runtime no Android 13+,
        // startForeground() é silenciado pelo sistema mesmo com declaração no manifesto
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // Lê preferências de forma síncrona APÓS injeção do Hilt e ANTES do setContent.
        // Isso garante que startDestination está correto quando o NavHost compõe pela primeira vez.
        val isConfigured   = runBlocking { preferences.isConfigured.first() }
        val onboardingDone = runBlocking { preferences.onboardingDone.first() }
        val termsAccepted  = runBlocking { preferences.termsAcceptedVersion.first() } >= TERMS_VERSION
        val initialTheme   = ThemeMode.valueOf(runBlocking { preferences.themeMode.first() })
        // Idioma salvo — aplicado ANTES do setContent para já abrir na língua certa.
        AppCompatDelegate.setApplicationLocales(
            LocaleListCompat.forLanguageTags(runBlocking { preferences.appLanguage.first() })
        )
        // F7 — gate de termos: vale para novos usuários e para quem precisa reaceitar após
        // uma atualização da versão dos termos (mesmo já configurado).
        // ADR-0030 — a conta (grátis) é sempre o começo. Quem já usava o app sem conta antes desta
        // versão (isConfigured pelo modo local) é convidado a criar uma, podendo adiar: a pergunta
        // volta depois de ACCOUNT_PROMPT_INTERVAL_MS.
        val hasAccount  = runBlocking { preferences.hasAccount.first() }
        val postponedAt = runBlocking { preferences.accountPromptPostponedAtMs.first() }
        val accountPromptDue = !hasAccount &&
            (!isConfigured || System.currentTimeMillis() - postponedAt !in 0 until ACCOUNT_PROMPT_INTERVAL_MS)
        val startDestination = when {
            !onboardingDone  -> Screen.Onboarding.route
            !termsAccepted   -> Screen.Terms.route
            accountPromptDue -> Screen.Login.route
            else             -> Screen.Home.route
        }

        // O sensor de quem usava sem conta continua lendo (e alertando) enquanto a tela pede a
        // conta: pedir conta nunca pode interromper a monitoração.
        if (onboardingDone && termsAccepted && isConfigured) startGluSyncService()
        if (startDestination == Screen.Home.route) requestBatteryOptExemption()

        handleDeepLink(intent)

        // Plano OTA (E6) — checagem de versão ao abrir o app (o UpdateChecker impõe intervalo
        // mínimo de 1 h). Só consulta metadados; o resultado aparece em Ajustes › Atualizações.
        lifecycleScope.launch { runCatching { updateChecker.check(force = false) } }

        // Libre 2 — reader-mode do NFC controlado pela tela de ativação via Libre2NfcController.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                nfcController.readerMode.collect { on ->
                    if (on) enableNfcReader() else disableNfcReader()
                }
            }
        }

        setContent {
            // Observa mudanças de tema em tempo real — recompoõe imediatamente ao salvar
            val themeMode by preferences.themeMode
                .map { ThemeMode.valueOf(it) }
                .collectAsStateWithLifecycle(initialValue = initialTheme)

            // Ícones das barras de status/navegação no contraste do tema do APP (não do sistema):
            // com app escuro e sistema claro (ou o contrário), relógio e bateria sumiam.
            val darkTheme = when (themeMode) {
                ThemeMode.DARK   -> true
                ThemeMode.LIGHT  -> false
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }
            DisposableEffect(darkTheme) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(
                        android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT
                    ) { darkTheme },
                    navigationBarStyle = SystemBarStyle.auto(NAV_SCRIM_LIGHT, NAV_SCRIM_DARK) { darkTheme }
                )
                onDispose {}
            }

            GluViewTheme(themeMode = themeMode) {
                AppNavigation(
                    startDestination  = startDestination,
                    pendingDeepLink   = deepLinkRoute,
                    configured        = isConfigured,
                    onDeepLinkHandled = { deepLinkRoute = null },
                    onStartService    = ::startGluSyncService
                )
            }
        }
    }

    // S34 — trata deep link quando o app já está em foreground
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
    }

    // S34 — interpreta URI gluview://diagnostic (e F6 gluview://caregiver) e injeta na navegação
    private fun handleDeepLink(intent: Intent) {
        val data: Uri? = intent.data
        if (data?.scheme != "gluview") return
        deepLinkRoute = when (data.host) {
            "diagnostic"   -> Screen.Diagnostic.route
            "caregiver"    -> Screen.Caregiver.route
            "alerthistory" -> Screen.AlertHistory.route
            // Plano OTA (E6) — só navega. Nenhum parâmetro do link dispara download ou instalação.
            "updates"      -> Screen.SettingsUpdates.route
            // Doação via Pix: toque na notificação de agradecimento. Só navega.
            "donation"     -> Screen.Donation.route
            else           -> return
        }
    }

    override fun onPause() {
        super.onPause()
        // Reader-mode não deve ficar ativo com o app em background (evita conflito NFC↔BLE).
        disableNfcReader()
    }

    /** Ativa o reader-mode NfcV (ISO 15693) para ler o sensor Libre 2 encostado no celular. */
    private fun enableNfcReader() {
        val adapter = nfcAdapter ?: return
        val flags = NfcAdapter.FLAG_READER_NFC_V or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK
        adapter.enableReaderMode(this, { tag -> nfcController.onTag(tag) }, flags, null)
    }

    private fun disableNfcReader() {
        runCatching { nfcAdapter?.disableReaderMode(this) }
    }

    private fun startGluSyncService() {
        val intent = Intent(this, GluSyncService::class.java)
        startForegroundService(intent)
    }

    // Pede isenção de otimização de bateria UMA vez.
    // Sem isso, Samsung One UI mata o serviço de foreground a cada ~15 min.
    private fun requestBatteryOptExemption() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        lifecycleScope.launch {
            if (!preferences.batteryOptAsked.first()) {
                preferences.saveBatteryOptAsked()
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                )
            }
        }
    }
}

/** Scrims padrão do androidx.activity para a barra de navegação de 3 botões (claro/escuro). */
private val NAV_SCRIM_LIGHT = android.graphics.Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
private val NAV_SCRIM_DARK  = android.graphics.Color.argb(0x80, 0x1b, 0x1b, 0x1b)

/** ADR-0030 — intervalo para voltar a pedir a conta a quem usava o app sem conta e escolheu "agora não". */
private const val ACCOUNT_PROMPT_INTERVAL_MS = 7L * 24 * 60 * 60 * 1000
