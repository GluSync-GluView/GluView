package com.gluview.app.ui.navigation

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Vaccines
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.hilt.navigation.compose.hiltViewModel
import com.gluview.app.R
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.gluview.app.ui.components.kit.CappedFontScale
import com.gluview.app.ui.screen.alerthistory.AlertHistoryScreen
import com.gluview.app.ui.screen.alertsettings.AlertIftttScreen
import com.gluview.app.ui.screen.alertsettings.AlertPushoverScreen
import com.gluview.app.ui.screen.alertsettings.AlertSettingsScreen
import com.gluview.app.ui.screen.apikeys.ApiKeysScreen
import com.gluview.app.ui.screen.accessrequests.AccessRequestStatusScreen
import com.gluview.app.ui.screen.accessrequests.MyAccessRequestsScreen
import com.gluview.app.ui.screen.passkeys.PasskeysScreen
import com.gluview.app.ui.screen.caregiver.CaregiverScreen
import com.gluview.app.ui.screen.caregiver.FollowedPatientScreen
import com.gluview.app.ui.screen.applog.LogScreen
import com.gluview.app.ui.screen.diagnostic.DiagnosticScreen
import com.gluview.app.ui.screen.eventlog.EventLogScreen
import com.gluview.app.ui.screen.history.HistoryScreen
import com.gluview.app.ui.screen.home.HomeScreen
import com.gluview.app.ui.screen.insulinpump.InsulinPumpScreen
import com.gluview.app.ui.screen.libre2.Libre2ActivationScreen
import com.gluview.app.ui.screen.login.LoginScreen
import com.gluview.app.ui.screen.miband.MiBandScreen
import com.gluview.app.ui.screen.onboarding.OnboardingScreen
import com.gluview.app.ui.screen.report.ReportScreen
import com.gluview.app.ui.screen.sensorscan.SensorScannerScreen
import com.gluview.app.ui.screen.sensorsetup.SensorSetupScreen
import com.gluview.app.ui.screen.updates.UpdatesScreen
import com.gluview.app.ui.screen.donation.DonationScreen
import com.gluview.app.ui.screen.settings.AccountSettingsScreen
import com.gluview.app.ui.screen.settings.GeneralSettingsScreen
import com.gluview.app.ui.screen.settings.SettingsScreen
import com.gluview.app.ui.screen.settings.SensorSettingsScreen
import com.gluview.app.ui.screen.settings.SupportSettingsScreen
import com.gluview.app.ui.screen.settings.SyncSettingsScreen
import com.gluview.app.ui.screen.terms.TermsScreen
import com.gluview.app.ui.screen.watchface.WatchFaceEditorScreen
import com.gluview.app.ui.screen.watchface.WatchFaceManagerScreen
import com.gluview.app.ui.screen.watchface.WatchfaceStoreScreen
import com.gluview.app.ui.screen.integrations.ExternalIntegrationScreen

@Composable
fun AppNavigation(
    startDestination: String,
    pendingDeepLink: String?  = null,
    // F7 — destino após aceitar os termos: Home (se já configurado) ou Login.
    configured: Boolean = false,
    onDeepLinkHandled: () -> Unit = {},
    onStartService: () -> Unit = {}
) {
    val nav = rememberNavController()
    val navVm: AppNavigationViewModel = hiltViewModel()
    val isFollowerOnly by navVm.isFollowerOnly.collectAsState()
    val needsSensorSetup by navVm.needsSensorSetup.collectAsState()
    // ADR-0029 — aba "Bomba" no lugar de Wearables (que migrou pra Ajustes); flag de rollout
    // gradual, independente do modo de cadastro.
    val insulinPumpTabEnabled by navVm.insulinPumpTabEnabled.collectAsState()

    // S34 — processa deep links (ex: gluview://diagnostic) passados pela MainActivity
    LaunchedEffect(pendingDeepLink) {
        if (pendingDeepLink != null) {
            nav.navigate(pendingDeepLink)
            onDeepLinkHandled()
        }
    }

    // Destinos principais com barra inferior (auto-explicativa, com rótulos).
    val mainRoutes = setOf(Screen.Home.route, Screen.SettingsSensor.route, Screen.InsulinPump.route, Screen.Chart.route, Screen.Settings.route)
    val backEntry by nav.currentBackStackEntryAsState()
    val currentRoute = backEntry?.destination?.route
    val showBottomBar = currentRoute in mainRoutes

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                MainBottomBar(
                    currentRoute          = currentRoute,
                    isFollowerOnly        = isFollowerOnly,
                    insulinPumpTabEnabled = insulinPumpTabEnabled,
                    onNavigate            = { route -> nav.navigateToTab(route) }
                )
            }
        }
    ) { innerPadding ->
    NavHost(
        navController = nav,
        startDestination = startDestination,
        // Scaffold aninhado: consumir os insets aqui evita que o Scaffold de cada tela
        // reaplique o padding das barras de sistema (faixas/espaços pretos duplicados).
        modifier = Modifier
            .padding(innerPadding)
            .consumeWindowInsets(innerPadding)
    ) {
        composable(Screen.Login.route) {
            LoginScreen(
                onCheckAccessRequestStatus = { nav.navigate(Screen.AccessRequestStatus.route) },
                onLoginSuccess = {
                    // B3 — inicia o serviço de background na primeira vez que o usuário faz login
                    onStartService()
                    // Primeira entrada sem sensor: leva direto à configuração. Sem isto, quem
                    // acabou de instalar chega numa Home vazia e precisa adivinhar que o caminho
                    // está em Ajustes > Sensor. Ao concluir (ou voltar), o wizard cai na Home.
                    val destino = if (needsSensorSetup) Screen.SensorSetup.route else Screen.Home.route
                    nav.navigate(destino) {
                        popUpTo(Screen.Login.route) { inclusive = true }
                    }
                }
            )
        }
        composable(Screen.Home.route) {
            HomeScreen(
                onNavigateToSettings     = { nav.navigate(Screen.Settings.route) },
                onNavigateToAlertHistory = { nav.navigate(Screen.AlertHistory.route) }
            )
        }
        composable(Screen.Settings.route) {
            // Aba principal: sem seta de voltar (onBack = null).
            SettingsScreen(
                onGeneralSettings = { nav.navigate(Screen.SettingsGeneral.route) },
                onAlertSettings   = { nav.navigate(Screen.AlertSettings.route) },
                onSyncSettings    = { nav.navigate(Screen.SettingsSync.route) },
                onSupportSettings = { nav.navigate(Screen.SettingsSupport.route) },
                onAccountSettings = { nav.navigate(Screen.SettingsAccount.route) },
                onCaregiver       = { nav.navigate(Screen.Caregiver.route) },
                // ADR-0029 — Wearables saiu da barra inferior (deu lugar à Bomba), mas continua
                // acessível daqui — a tela em si (WatchFaceManagerScreen) não muda em nada.
                onWatchFaces      = { nav.navigate(Screen.WatchFaces.route) },
                onIntegrations    = { nav.navigate(Screen.ExternalIntegrations.route) },
                onUpdates         = { nav.navigate(Screen.SettingsUpdates.route) },
                onDonate          = { nav.navigate(Screen.Donation.route) },
                onLogout          = {
                    nav.navigate(Screen.Login.route) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }
        composable(Screen.InsulinPump.route) {
            InsulinPumpScreen()
        }
        composable(Screen.SettingsGeneral.route) {
            GeneralSettingsScreen(onBack = { nav.popBackStack() })
        }
        composable(Screen.ExternalIntegrations.route) {
            // Integrações (decisão 5.5 #4): externas + chaves de API.
            ExternalIntegrationScreen(onBack = { nav.popBackStack() }, onApiKeys = { nav.navigate(Screen.ApiKeys.route) })
        }
        // Achado 2026-09-26 — histórico de candidaturas (bomba/sensor beta)
        composable(Screen.MyAccessRequests.route) {
            MyAccessRequestsScreen(onBack = { nav.popBackStack() })
        }
        // Status do pedido de acesso ao app (ainda sem conta), reachável direto do Login
        composable(Screen.AccessRequestStatus.route) {
            AccessRequestStatusScreen(onBack = { nav.popBackStack() })
        }
        // Aba "Sensor" da barra inferior — fonte de dados/BLE/NFC do sensor em si.
        composable(Screen.SettingsSensor.route) {
            // Aba principal: sem seta de voltar (onBack = null).
            SensorSettingsScreen(
                onSensorSetup      = { nav.navigate(Screen.SensorSetup.route) },
                onSensorScanner    = { nav.navigate(Screen.SensorScanner.route) },
                onLibre2Activation = { nav.navigate(Screen.Libre2Activation.route) }
            )
        }
        // Submenu de Ajustes — envio ao GluSync, polling, retenção, horas dos gráficos.
        composable(Screen.SettingsSync.route) {
            SyncSettingsScreen(onBack = { nav.popBackStack() })
        }
        // Submenu de Ajustes — perfil, senha, chaves de acesso, candidaturas e servidor.
        composable(Screen.SettingsAccount.route) {
            AccountSettingsScreen(
                onBack             = { nav.popBackStack() },
                onPasskeys         = { nav.navigate(Screen.Passkeys.route) },
                onMyAccessRequests = { nav.navigate(Screen.MyAccessRequests.route) }
            )
        }
        // Plano OTA (E6/E7) — versão instalada, versão nova e instalação (sempre por toque).
        composable(Screen.SettingsUpdates.route) {
            UpdatesScreen(onBack = { nav.popBackStack() })
        }
        // Doação via Pix — aviso, valor, QR e agradecimento (o acompanhamento vive no DonationTracker).
        composable(Screen.Donation.route) {
            DonationScreen(onBack = { nav.popBackStack() })
        }
        composable(Screen.SettingsSupport.route) {
            SupportSettingsScreen(
                onBack       = { nav.popBackStack() },
                onDiagnostic = { nav.navigate(Screen.Diagnostic.route) },
                onEventLog   = { nav.navigate(Screen.EventLog.route) },
                onReport     = { nav.navigate(Screen.Report.route) },
                onAppLogs    = { nav.navigate(Screen.AppLogs.route) }
            )
        }
        // Caminho principal: o usuário escaneia a caixa e o app acha o sensor sozinho.
        composable(Screen.SensorSetup.route) {
            SensorSetupScreen(
                onDone = {
                    // Vindo do login não existe tela anterior para voltar; vindo dos Ajustes,
                    // existe. popBackStack() devolve false no primeiro caso — aí vamos à Home.
                    if (!nav.popBackStack()) {
                        nav.navigate(Screen.Home.route) {
                            popUpTo(Screen.SensorSetup.route) { inclusive = true }
                        }
                    }
                },
                onAdvanced = { nav.navigate(Screen.SensorScanner.route) },
                // L19: Libre 2 Plus no assistente vai para a ativação por NFC.
                onLibre2Activation = {
                    nav.navigate(Screen.Libre2Activation.route) {
                        popUpTo(Screen.SensorSetup.route) { inclusive = true }
                    }
                }
            )
        }
        composable(Screen.SensorScanner.route) {
            SensorScannerScreen(onBack = { nav.popBackStack() })
        }
        composable(Screen.Libre2Activation.route) {
            // Vindo do assistente logo após o login não há tela anterior: volta à Home.
            Libre2ActivationScreen(onBack = { if (!nav.popBackStack()) nav.navigate(Screen.Home.route) })
        }
        // F1/T08 — Logs do app
        composable(Screen.AppLogs.route) {
            LogScreen(onBack = { nav.popBackStack() })
        }
        composable(Screen.ApiKeys.route) {
            ApiKeysScreen(onBack = { nav.popBackStack() })
        }
        // T85 — Passkeys (beta)
        composable(Screen.Passkeys.route) {
            PasskeysScreen(onBack = { nav.popBackStack() })
        }
        // F6 — Acompanhamento
        composable(Screen.Caregiver.route) {
            CaregiverScreen(
                onBack = { nav.popBackStack() },
                onOpenPatient = { patientId, name -> nav.navigate(Screen.FollowedPatient.createRoute(patientId, name)) }
            )
        }
        composable(
            route = Screen.FollowedPatient.route,
            arguments = listOf(
                navArgument("patientId") { },
                navArgument("name") { defaultValue = "" }
            )
        ) { entry ->
            val name = android.net.Uri.decode(entry.arguments?.getString("name") ?: "")
            FollowedPatientScreen(patientName = name, onBack = { nav.popBackStack() })
        }
        composable(Screen.MiBand.route) {
            MiBandScreen(onBack = { nav.popBackStack() })
        }
        composable(Screen.WatchFaces.route) {
            WatchFaceManagerScreen(
                onBack = { nav.popBackStack() },
                onCreate = { nav.navigate(Screen.WatchFaceEditor.createRoute()) },
                onEdit = { id -> nav.navigate(Screen.WatchFaceEditor.createRoute(id)) },
                onMiBand = { nav.navigate(Screen.MiBand.route) },
                onStore = { nav.navigate(Screen.WatchfaceStore.route) }
            )
        }
        composable(
            route = Screen.WatchFaceEditor.route,
            arguments = listOf(navArgument("faceId") { defaultValue = "0" })
        ) {
            WatchFaceEditorScreen(onBack = { nav.popBackStack() })
        }
        composable(Screen.WatchfaceStore.route) {
            WatchfaceStoreScreen(onBack = { nav.popBackStack() })
        }
        composable(Screen.AlertSettings.route) {
            AlertSettingsScreen(
                onBack     = { nav.popBackStack() },
                onIfttt    = { nav.navigate(Screen.AlertIfttt.route) },
                onPushover = { nav.navigate(Screen.AlertPushover.route) }
            )
        }
        composable(Screen.AlertIfttt.route) {
            AlertIftttScreen(onBack = { nav.popBackStack() })
        }
        composable(Screen.AlertPushover.route) {
            AlertPushoverScreen(onBack = { nav.popBackStack() })
        }
        composable(Screen.AlertHistory.route) {
            AlertHistoryScreen(
                onBack          = { nav.popBackStack() },
                onOpenCaregiver = { nav.navigate(Screen.Caregiver.route) }
            )
        }
        // Sprint 4 — Histórico unificado: Gráfico/TIR/Padrão viraram abas de uma tela só.
        composable(
            route     = Screen.Chart.route,
            arguments = listOf(navArgument("tab") { type = NavType.StringType; defaultValue = "chart" })
        ) { backStackEntry ->
            HistoryScreen(
                // Aba principal: sem seta de voltar (onBack = null).
                initialTab = backStackEntry.arguments?.getString("tab") ?: "chart"
            )
        }
        composable(Screen.EventLog.route) {
            EventLogScreen(onBack = { nav.popBackStack() })
        }
        composable(Screen.Diagnostic.route) {
            DiagnosticScreen(onBack = { nav.popBackStack() })
        }
        composable(Screen.Report.route) {
            ReportScreen(onBack = { nav.popBackStack() })
        }
        // Sprint 6
        composable(Screen.Onboarding.route) {
            OnboardingScreen(
                onComplete = {
                    // F7 — após o onboarding, exige aceite dos termos antes do login.
                    nav.navigate(Screen.Terms.route) {
                        popUpTo(Screen.Onboarding.route) { inclusive = true }
                    }
                }
            )
        }
        // F7 — Termos de uso (aceite no onboarding e em atualizações da versão)
        composable(Screen.Terms.route) {
            TermsScreen(
                onAccepted = {
                    if (configured) {
                        onStartService()
                        nav.navigate(Screen.Home.route) {
                            popUpTo(Screen.Terms.route) { inclusive = true }
                        }
                    } else {
                        nav.navigate(Screen.Login.route) {
                            popUpTo(Screen.Terms.route) { inclusive = true }
                        }
                    }
                }
            )
        }
    }
    }
}


/**
 * Troca de aba da barra inferior. Lê a rota atual do NavController NA HORA do toque: não pode
 * depender de valor capturado na composição. A NavigationBar memoriza o onClick de cada item pela
 * igualdade do onNavigate, e uma referência a função local (::goTo) é "igual" entre composições
 * mesmo com valores capturados diferentes — o item Início ficava com a rota da 1ª composição
 * ("home"), caía no "já estou aqui" e não voltava para a Home (bug de 04/10, desde 859898a).
 */
internal fun NavController.navigateToTab(route: String) {
    if (route == currentBackStackEntry?.destination?.route) return
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/**
 * Rótulo da barra inferior: 1 linha, escala de fonte até 1,3× (o máximo do Moto Z2) e reduzida
 * até o rótulo mais largo caber. Com 5 abas cada item tem ~72–77dp, e com fonte 1,3 os rótulos
 * quebravam no meio da palavra ("Históric/o" no S23 FE, medido pelo harness visual). O ícone
 * acima continua identificando a aba.
 */
@Composable
private fun NavLabel(text: String, allLabels: List<String>) {
    // Escala única para a barra toda: a maior (até o teto) em que o rótulo MAIS LARGO cabe na
    // largura de um item. Assim "Histórico" não vira "Históric/o" (quebra) nem "Históri…" com
    // fonte grande, e todos os rótulos ficam do mesmo tamanho. Cálculo direto, sem 2ª passada.
    BoxWithConstraints {
        val density = LocalDensity.current
        val style = LocalTextStyle.current
        val measurer = rememberTextMeasurer()
        val maxWidthPx = constraints.maxWidth
        val scale = remember(allLabels, style, density, maxWidthPx) {
            var s = density.fontScale.coerceAtMost(NAV_LABEL_MAX_FONT_SCALE)
            fun fits(scale: Float) = allLabels.all { label ->
                measurer.measure(label, style, softWrap = false, maxLines = 1,
                    density = Density(density.density, scale)).size.width <= maxWidthPx
            }
            while (s > NAV_LABEL_MIN_FONT_SCALE && !fits(s)) s = (s - 0.05f).coerceAtLeast(NAV_LABEL_MIN_FONT_SCALE)
            s
        }
        CappedFontScale(scale) {
            Text(text, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
        }
    }
}

private const val NAV_LABEL_MAX_FONT_SCALE = 1.3f
/** Piso da escala dos rótulos (≈ 10sp): abaixo disso, reticências e a auditoria acusa. */
private const val NAV_LABEL_MIN_FONT_SCALE = 0.85f

/** Barra inferior das abas principais — stateless, pra o harness visual capturar as telas com a
 *  mesma altura útil do aparelho (a Home decide rolar ou não pela altura que sobra). */
@Composable
internal fun MainBottomBar(
    currentRoute: String?,
    isFollowerOnly: Boolean,
    insulinPumpTabEnabled: Boolean,
    onNavigate: (String) -> Unit
) {
    val home = stringResource(R.string.nav_home)
    val sensor = stringResource(R.string.nav_sensor)
    val history = stringResource(R.string.nav_history)
    val pump = stringResource(R.string.nav_insulin_pump)
    val settings = stringResource(R.string.nav_settings)
    val labels = listOfNotNull(home, sensor.takeIf { !isFollowerOnly }, history,
        pump.takeIf { insulinPumpTabEnabled }, settings)
    NavigationBar {
        NavigationBarItem(
            selected = currentRoute == Screen.Home.route,
            onClick = { onNavigate(Screen.Home.route) },
            icon = { Icon(Icons.Default.Home, contentDescription = null) },
            label = { NavLabel(home, labels) }
        )
        // Acesso rápido ao Sensor — só existe pra quem tem sensor próprio.
        if (!isFollowerOnly) {
            NavigationBarItem(
                selected = currentRoute == Screen.SettingsSensor.route,
                onClick = { onNavigate(Screen.SettingsSensor.route) },
                icon = { Icon(Icons.Default.Sensors, contentDescription = null) },
                label = { NavLabel(sensor, labels) }
            )
        }
        NavigationBarItem(
            selected = currentRoute == Screen.Chart.route,
            onClick = { onNavigate(Screen.Chart.createRoute()) },
            icon = { Icon(Icons.AutoMirrored.Filled.ShowChart, contentDescription = null) },
            label = { NavLabel(history, labels) }
        )
        // ADR-0029 — aba "Bomba" no lugar de Wearables (migrou pra Ajustes),
        // gated por Features:InsulinPumpTabEnabled (rollout gradual).
        if (insulinPumpTabEnabled) {
            NavigationBarItem(
                selected = currentRoute == Screen.InsulinPump.route,
                onClick = { onNavigate(Screen.InsulinPump.route) },
                icon = { Icon(Icons.Default.Vaccines, contentDescription = null) },
                label = { NavLabel(pump, labels) }
            )
        }
        NavigationBarItem(
            selected = currentRoute == Screen.Settings.route,
            onClick = { onNavigate(Screen.Settings.route) },
            icon = { Icon(Icons.Default.Settings, contentDescription = null) },
            label = { NavLabel(settings, labels) }
        )
    }
}
