package com.gluview.app.ui.screen.onboarding

import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.runtime.collectAsState
import android.provider.Settings
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.gluview.app.R
import com.gluview.app.ui.components.kit.GluCallout
import com.gluview.app.ui.components.kit.GluCard
import com.gluview.app.ui.components.kit.PeriodSelector
import com.gluview.app.ui.components.kit.Tone
import com.gluview.app.ui.theme.Spacing
import kotlinx.coroutines.launch

/** Permissão de notificação como a tela mostra: negada passa a ser diferente de "nunca pedida". */
internal enum class NotifPermission { NOT_ASKED, GRANTED, DENIED }

@Composable
fun OnboardingScreen(
    onComplete: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    var permission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED) NotifPermission.GRANTED
            else NotifPermission.NOT_ASKED
        )
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        permission = if (isGranted) NotifPermission.GRANTED else NotifPermission.DENIED
    }
    // Voltando dos ajustes do sistema com a notificação liberada.
    LifecycleResumeEffect(Unit) {
        if (permission == NotifPermission.DENIED &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED) permission = NotifPermission.GRANTED
        onPauseOrDispose {}
    }
    val language by viewModel.language.collectAsState()
    OnboardingContent(
        permission = permission,
        language = language,
        onLanguage = viewModel::setLanguage,
        onOpenSettings = {
            runCatching {
                context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            }
        },
        onAllow = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            } else {
                permission = NotifPermission.GRANTED
            }
        },
        onStart = { viewModel.complete(onComplete) }
    )
}

/**
 * Apresentação no primeiro uso (redesign visual, Fase 8): o slide fica centrado de verdade (o
 * Arrangement.Center dentro do verticalScroll não fazia efeito e o conteúdo grudava no topo);
 * barra de baixo com pontos centrados, botão principal em largura toda e "Voltar" a partir do 2º
 * slide (antes só o gesto); no slide de notificações só um botão principal por vez ("Permitir" e
 * "Começar" competiam) e aviso quando a permissão é negada (antes nada mudava); "Página N de 3" e
 * títulos como cabeçalho para o TalkBack.
 */
@Composable
internal fun OnboardingContent(
    permission: NotifPermission,
    onAllow: () -> Unit,
    onStart: () -> Unit,
    initialPage: Int = 0,
    language: String = "pt",
    onLanguage: (String) -> Unit = {},
    onOpenSettings: () -> Unit = {}
) {
    val pageCount = 3
    val pagerState = rememberPagerState(initialPage = initialPage, pageCount = { pageCount })
    val scope = rememberCoroutineScope()
    val page = pagerState.currentPage
    val last = page == pageCount - 1

    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            HorizontalPager(state = pagerState, modifier = Modifier.weight(1f)) { p ->
                when (p) {
                    0 -> WelcomeSlide(language, onLanguage)
                    1 -> ProfilesSlide()
                    else -> PermissionsSlide(permission)
                }
            }

            Column(
                Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter, vertical = Spacing.md),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                PageDots(page, pageCount)
                val (primaryLabel, primaryAction) = when {
                    !last -> stringResource(R.string.onboarding_next) to { scope.launch { pagerState.animateScrollToPage(page + 1) }; Unit }
                    permission == NotifPermission.NOT_ASKED -> stringResource(R.string.onboarding_permissions_allow) to onAllow
                    else -> stringResource(R.string.onboarding_start) to onStart
                }
                Button(onClick = primaryAction, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(primaryLabel, textAlign = TextAlign.Center)
                }
                when {
                    // Sem permissão ainda: "Começar" continua possível (a mesma ação de sempre).
                    last && permission == NotifPermission.NOT_ASKED ->
                        WideTextButton(stringResource(R.string.onboarding_start_without), onStart)
                    // Negada: depois de negar, o sistema costuma não perguntar de novo — o caminho é
                    // liberar nos ajustes do app.
                    last && permission == NotifPermission.DENIED ->
                        WideTextButton(stringResource(R.string.onboarding_open_settings), onOpenSettings)
                    page > 0 ->
                        WideTextButton(stringResource(R.string.login_back)) { scope.launch { pagerState.animateScrollToPage(page - 1) } }
                }
            }
        }
    }
}

@Composable
private fun WideTextButton(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(label, textAlign = TextAlign.Center) }
}

@Composable
private fun PageDots(page: Int, count: Int) {
    val description = stringResource(R.string.onboarding_page_of, page + 1, count)
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = Spacing.xxs).clearAndSetSemantics { contentDescription = description }
    ) {
        repeat(count) { index ->
            val selected = page == index
            Box(
                Modifier
                    .size(width = if (selected) 22.dp else 8.dp, height = 8.dp)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
            )
        }
    }
}

@Composable
private fun SlideTitle(text: String) {
    Text(text, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
        modifier = Modifier.semantics { heading() })
}

@Composable
private fun SlideBody(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun WelcomeSlide(language: String, onLanguage: (String) -> Unit) {
    SlideContainer {
        Box(
            Modifier.size(76.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.WaterDrop, contentDescription = null, modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.onPrimary)
        }
        SlideTitle(stringResource(R.string.onboarding_welcome_title))
        SlideBody(stringResource(R.string.onboarding_welcome_body))
        // L21: começa no idioma do aparelho e dá para trocar logo aqui.
        PeriodSelector(listOf("pt", "en"), language, onLanguage,
            { stringResource(if (it == "pt") R.string.settings_language_portuguese else R.string.settings_language_english) },
            modifier = Modifier.widthIn(max = 320.dp),
            title = stringResource(R.string.settings_language))
    }
}

@Composable
private fun ProfilesSlide() {
    SlideContainer {
        SlideIcon(Icons.Default.Groups)
        SlideTitle(stringResource(R.string.onboarding_profiles_title))
        SlideBody(stringResource(R.string.onboarding_profiles_body))
        // Os dois perfis com o que cada um faz (antes duas pílulas só com o nome).
        GluCard(Modifier.widthIn(max = 480.dp)) {
            RoleLine(Icons.Default.Person, stringResource(R.string.role_patient), stringResource(R.string.login_role_patient_desc))
            RoleLine(Icons.Default.Groups, stringResource(R.string.role_follower), stringResource(R.string.login_role_follower_desc))
        }
    }
}

@Composable
private fun RoleLine(icon: ImageVector, title: String, description: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.Top,
        modifier = Modifier.padding(vertical = Spacing.xxs)) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PermissionsSlide(permission: NotifPermission) {
    SlideContainer {
        SlideIcon(Icons.Default.Notifications)
        SlideTitle(stringResource(R.string.onboarding_permissions_title))
        SlideBody(stringResource(R.string.onboarding_permissions_body))
        when (permission) {
            NotifPermission.GRANTED -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                modifier = Modifier.semantics(mergeDescendants = true) {}
            ) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.onboarding_permissions_granted), color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.titleSmall)
            }
            // Para um app de alerta de hipo, ficar sem notificação precisa ser dito.
            NotifPermission.DENIED -> GluCallout(stringResource(R.string.onboarding_permissions_denied),
                Modifier.widthIn(max = 480.dp), tone = Tone.Warning, announce = true)
            NotifPermission.NOT_ASKED -> Unit
        }
    }
}

@Composable
private fun SlideIcon(icon: ImageVector) {
    Box(
        Modifier.size(76.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

@Composable
private fun SlideContainer(content: @Composable ColumnScope.() -> Unit) {
    // Altura mínima = a do slide: o conteúdo centra quando cabe e rola quando não cabe (fonte grande).
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight)
                .padding(horizontal = Spacing.lg, vertical = Spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.md, Alignment.CenterVertically),
            content = content
        )
    }
}
