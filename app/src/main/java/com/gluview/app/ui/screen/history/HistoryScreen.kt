package com.gluview.app.ui.screen.history

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.gluview.app.R
import com.gluview.app.ui.components.FollowedSubjectBanner
import com.gluview.app.ui.components.kit.AppTopBar
import com.gluview.app.ui.components.rememberFollowedSubject
import com.gluview.app.ui.screen.chart.ChartScreen
import com.gluview.app.ui.screen.dailypattern.DailyPatternScreen
import com.gluview.app.ui.screen.tir.TirScreen
import kotlinx.coroutines.launch

private val TAB_KEYS = listOf("chart", "tir", "pattern")

/**
 * Histórico unificado — antes eram 3 telas separadas (Gráfico/TIR/Padrão), cada uma com sua
 * própria navegação de volta; comparar visão geral com padrão por dia exigia sair e entrar
 * de novo em cada uma. Agora é uma Scaffold só com abas — Gráfico/TIR/Padrão viram conteúdo
 * de HorizontalPager, sem Scaffold própria (ver ChartScreen/TirScreen/DailyPatternScreen).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    initialTab: String,
    onBack: (() -> Unit)? = null,
    // Conteúdo de cada aba. O padrão liga cada uma ao seu ViewModel; o harness visual passa as
    // versões stateless (ChartContent/TirContent/DailyPatternContent) com dados fixos.
    chartTab:   @Composable () -> Unit = { ChartScreen() },
    tirTab:     @Composable () -> Unit = { TirScreen() },
    patternTab: @Composable () -> Unit = { DailyPatternScreen() },
    // Modo seguidor: nome do paciente cujos dados aparecem (null = os do próprio usuário).
    subjectName: String? = rememberFollowedSubject()
) {
    val startPage = TAB_KEYS.indexOf(initialTab).coerceAtLeast(0)
    val pagerState = rememberPagerState(initialPage = startPage) { TAB_KEYS.size }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            // Aba principal da barra inferior: sem seta (o voltar do sistema continua).
            AppTopBar(title = stringResource(R.string.history_title), onBack = onBack)
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            subjectName?.let { FollowedSubjectBanner(it) }
            SecondaryTabRow(selectedTabIndex = pagerState.currentPage) {
                Tab(
                    selected = pagerState.currentPage == 0,
                    onClick  = { scope.launch { pagerState.animateScrollToPage(0) } },
                    text     = { Text(stringResource(R.string.home_shortcut_chart)) }
                )
                Tab(
                    selected = pagerState.currentPage == 1,
                    onClick  = { scope.launch { pagerState.animateScrollToPage(1) } },
                    text     = { Text(stringResource(R.string.home_shortcut_tir)) }
                )
                Tab(
                    selected = pagerState.currentPage == 2,
                    onClick  = { scope.launch { pagerState.animateScrollToPage(2) } },
                    text     = { Text(stringResource(R.string.home_shortcut_pattern)) }
                )
            }
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                when (TAB_KEYS[page]) {
                    "chart"   -> chartTab()
                    "tir"     -> tirTab()
                    "pattern" -> patternTab()
                }
            }
        }
    }
}
