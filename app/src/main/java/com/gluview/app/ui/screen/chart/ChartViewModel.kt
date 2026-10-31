package com.gluview.app.ui.screen.chart

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gluview.app.data.datastore.AppPreferences
import com.gluview.app.data.repository.GlucoseRepository
import com.gluview.app.data.repository.StatsRepository
import com.gluview.app.data.sibionics.GlucoseDisplayMode
import com.gluview.app.util.GlucoseUnit
import com.gluview.app.util.GraphReading
import com.gluview.app.util.TimeFormat
import com.gluview.app.util.applyDisplayMode
import com.gluview.app.util.toGraphReading
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlinx.coroutines.delay

enum class ChartRange(val hours: Int, val label: String) {
    H3(3, "3h"), H6(6, "6h"), H12(12, "12h"), H24(24, "24h")
}

// Ou uma janela relativa a agora (ChartRange), ou um dia específico escolhido pelo usuário.
sealed interface ChartMode {
    data class Relative(val range: ChartRange) : ChartMode
    data class SpecificDay(val date: LocalDate) : ChartMode
}

// Limites reais do período pedido (não só do primeiro/último dado retornado) — usado para
// fixar o eixo X do gráfico, para que um buraco de leituras apareça como espaço vazio em vez
// de a curva "esticar" para preencher o gráfico inteiro.
data class ChartWindow(val startMs: Long, val endMs: Long)

@HiltViewModel
class ChartViewModel @Inject constructor(
    private val repository: StatsRepository,
    private val preferences: AppPreferences,
    private val glucoseRepository: GlucoseRepository
) : ViewModel() {

    // Modo seguidor: mostra o histórico do paciente ativo (via GluSync), não dados locais.
    private suspend fun activePatientId(): String? =
        if (preferences.isFollowerOnly.first()) preferences.activeFollowedPatientId.first().ifBlank { null }
        else null

    // L17: o dia no fuso do aparelho, o mesmo dos rótulos do gráfico (antes São Paulo fixo, e
    // num aparelho em outro fuso "hoje" e o eixo discordavam).
    private val zoneId: ZoneId get() = ZoneId.systemDefault()

    // Leituras da MESMA busca de sempre (getChartData), entregues ao gráfico novo (Fase 4).
    private val _points = MutableStateFlow<List<GraphReading>>(emptyList())
    val points: StateFlow<List<GraphReading>> = _points.asStateFlow()

    // Limiares configurados em Ajustes > Alertas ("Baixo"/"Alto") — linhas de referência do
    // gráfico devem refletir o que o usuário configurou, não um valor fixo.
    val hipoThreshold: StateFlow<Float> = preferences.lowThreshold
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 70f)
    val hiperThreshold: StateFlow<Float> = preferences.highThreshold
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 180f)

    // Unidade e formato de hora configurados em Ajustes — usados no balão de toque num ponto.
    val unit: StateFlow<GlucoseUnit> = preferences.glucoseUnit
        .map { GlucoseUnit.fromString(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GlucoseUnit.MG_DL)
    val timeFormat: StateFlow<TimeFormat> = preferences.timeFormat
        .map { TimeFormat.fromString(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TimeFormat.H24)

    private val _mode = MutableStateFlow<ChartMode>(ChartMode.Relative(ChartRange.H3))
    val mode: StateFlow<ChartMode> = _mode.asStateFlow()

    private val _window = MutableStateFlow(ChartWindow(0L, 0L))
    val window: StateFlow<ChartWindow> = _window.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    // Requisição OK mas sem nenhuma leitura no período — distinto de erro de rede.
    private val _isEmpty = MutableStateFlow(false)
    val isEmpty: StateFlow<Boolean> = _isEmpty.asStateFlow()

    fun today(): LocalDate = LocalDate.now(zoneId)

    init {
        val now = System.currentTimeMillis()
        loadWindow(now - TimeUnit.HOURS.toMillis(ChartRange.H3.hours.toLong()), now)
        // L17: nas faixas relativas ("últimas 3 h") a janela anda com o relógio, sem piscar o
        // esqueleto (antes ficava congelada na hora em que a tela abriu).
        viewModelScope.launch {
            while (true) {
                delay(60_000L)
                (_mode.value as? ChartMode.Relative)?.let { m ->
                    if (loadJob?.isActive != true) {
                        val t = System.currentTimeMillis()
                        loadWindow(t - TimeUnit.HOURS.toMillis(m.range.hours.toLong()), t, silent = true)
                    }
                }
            }
        }
    }

    fun onRangeSelected(r: ChartRange) {
        if (_mode.value == ChartMode.Relative(r)) return
        _mode.value = ChartMode.Relative(r)
        val now = System.currentTimeMillis()
        loadWindow(now - TimeUnit.HOURS.toMillis(r.hours.toLong()), now)
    }

    fun onDateSelected(date: LocalDate) {
        if (date.isAfter(today())) return
        _mode.value = ChartMode.SpecificDay(date)
        val startMs = date.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val endMs = if (date.isEqual(today())) {
            System.currentTimeMillis()
        } else {
            date.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
        }
        loadWindow(startMs, endMs)
    }

    /** "Tentar novamente" do estado de erro: refaz a busca da janela atual. */
    fun retry() {
        when (val m = _mode.value) {
            is ChartMode.Relative -> {
                val now = System.currentTimeMillis()
                loadWindow(now - TimeUnit.HOURS.toMillis(m.range.hours.toLong()), now)
            }
            is ChartMode.SpecificDay -> onDateSelected(m.date)
        }
    }

    // Navega ±1 dia a partir do dia atualmente selecionado (ou hoje, se ainda em modo relativo).
    fun onShiftDay(deltaDays: Long) {
        val current = (_mode.value as? ChartMode.SpecificDay)?.date ?: today()
        onDateSelected(current.plusDays(deltaDays))
    }

    // Guarda a corrotina em andamento: sem isso, trocar de faixa rápido (3h -> 24h -> 12h) podia
    // deixar uma resposta antiga (que demorou mais pra voltar) sobrescrever o gráfico com dados
    // de uma janela que não é mais a selecionada. Cancelar a anterior garante que só a última
    // seleção do usuário chega a aplicar resultado.
    private var loadJob: Job? = null

    private fun loadWindow(startMs: Long, endMs: Long, silent: Boolean = false) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            if (!silent) {
                _isLoading.value = true
                _error.value = null
                _isEmpty.value = false
            }
            val result = runCatching { readingsFor(startMs, endMs) }
            // Atualização silenciosa que falha mantém o gráfico que já está na tela.
            if (silent && result.isFailure) return@launch
            _window.value = ChartWindow(startMs, endMs)
            result.onFailure { _error.value = it.message ?: it.javaClass.simpleName } // detalhe técnico
            result.getOrNull()?.let { points ->
                _error.value = null
                _isEmpty.value = points.isEmpty()
                _points.value = points
            }
            _isLoading.value = false
        }
    }

    /**
     * L17: o próprio histórico vem do aparelho quando ele cobre a janela — com o modo de exibição
     * do Sibionics, igual à Home (antes sempre da nuvem, sem o modo); a nuvem fica para o seguidor
     * e para o que já saiu da retenção local.
     */
    private suspend fun readingsFor(startMs: Long, endMs: Long): List<GraphReading> {
        val patientId = activePatientId()
        if (patientId == null) {
            val local = glucoseRepository.getReadingsBetween(startMs, endMs)
            if (local.isNotEmpty() && local.first().dateMs <= startMs + LOCAL_COVERAGE_SLACK_MS) {
                val mode = GlucoseDisplayMode.fromId(preferences.glucoseDisplayMode.first())
                return local.map { it.toGraphReading(1.0) }.applyDisplayMode(mode)
            }
        }
        return repository.getChartData(startMs, endMs, patientId).map { GraphReading(it.timestampMs, it.sgvMgDl.toFloat()) }
    }

    private companion object {
        /** O aparelho "cobre" a janela se a 1ª leitura local está até 15 min depois do início. */
        const val LOCAL_COVERAGE_SLACK_MS = 15 * 60_000L
    }
}
