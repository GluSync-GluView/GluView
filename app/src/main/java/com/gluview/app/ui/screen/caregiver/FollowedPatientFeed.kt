package com.gluview.app.ui.screen.caregiver

import com.gluview.app.data.datastore.AppPreferences
import com.gluview.app.data.remote.dto.GlucoseEntryDto
import com.gluview.app.data.remote.dto.NovaMedicaoDto
import com.gluview.app.data.repository.CaregiverRepository
import com.gluview.app.domain.model.GlucoseThresholds
import com.gluview.app.util.directionToArrow
import com.gluview.app.util.liveArrow
import com.gluview.app.util.GraphReading
import com.microsoft.signalr.HubConnection
import com.microsoft.signalr.HubConnectionBuilder
import io.reactivex.rxjava3.core.Single
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** O que se sabe do paciente acompanhado agora. */
data class FollowedFeedState(
    val loading: Boolean = true,
    val error: String? = null,
    val sgv: Int? = null,
    val arrow: String = "",
    val delta: Int = 0,
    /** Hora da MEDIÇÃO (L7; antes a hora em que o app consultou, e 40 min atrás virava "agora"). */
    val measuredAtMs: Long = 0L,
    val readings: List<GraphReading> = emptyList(),
    /** Limites de alerta do PACIENTE (L1, decisão do mantenedor); padrão até o GluSync responder. */
    val thresholds: GlucoseThresholds = GlucoseThresholds.DEFAULT
)

/**
 * Leitura de um paciente acompanhado, comum à Home do seguidor e ao detalhe do acompanhado (antes
 * cada tela tinha a sua; o detalhe só consultava a cada 30 s, sem delta nem tempo real — L10).
 * Carga inicial (atual + 24 h + limites do paciente), SignalR (NovaMedicao, datado pela medição
 * que o GluSync manda — L8) e consulta a cada 30 s como rede de segurança se o SignalR cair.
 */
class FollowedPatientFeed(
    private val scope: CoroutineScope,
    private val preferences: AppPreferences,
    private val repo: CaregiverRepository
) {
    private val _state = MutableStateFlow(FollowedFeedState(loading = false))
    val state: StateFlow<FollowedFeedState> = _state.asStateFlow()

    private var job: Job? = null
    private var hub: HubConnection? = null
    private var patientId: String = ""

    /** Passa a acompanhar [id] (vazio = ninguém). */
    fun watch(id: String) {
        if (id == patientId && job != null) return
        stop()
        patientId = id
        if (id.isBlank()) {
            _state.value = FollowedFeedState(loading = false)
            return
        }
        _state.value = FollowedFeedState(loading = true)
        job = scope.launch {
            launch { repo.patientThresholds(id)?.let { t -> _state.update { it.copy(thresholds = t) } } }
            connectRealtime(id)
            while (true) {
                load(id)
                delay(30_000L)
            }
        }
    }

    fun refresh() {
        val id = patientId.takeIf { it.isNotBlank() } ?: return
        scope.launch { load(id) }
    }

    fun stop() {
        job?.cancel(); job = null
        hub?.let { conn ->
            patientId.takeIf { it.isNotBlank() }?.let { runCatching { conn.send("UnsubscribePatient", it) } }
            conn.stop()
        }
        hub = null
    }

    private suspend fun load(id: String) {
        runCatching {
            val now = System.currentTimeMillis()
            repo.patientCurrent(id) to repo.patientRange(id, now - 24L * 60L * 60L * 1000L, now)
        }.onSuccess { (current, range) ->
            if (id != patientId) return
            val sorted = range.sortedBy { it.date }
            _state.update { it.copy(loading = false, error = null).withCurrent(current, sorted) }
        }.onFailure { e ->
            if (id != patientId) return
            _state.update { it.copy(loading = false, error = friendly(e)) }
        }
    }

    private fun FollowedFeedState.withCurrent(current: GlucoseEntryDto, sorted: List<GlucoseEntryDto>): FollowedFeedState {
        val sgv = current.sgv.toInt()
        // Delta vs a leitura anterior à atual no período (a atual pode ser a última da lista).
        val previous = sorted.lastOrNull { e -> e.date < current.date }?.sgv?.toInt()
        return copy(
            sgv = sgv,
            arrow = directionToArrow(current.direction),
            delta = previous?.let { sgv - it } ?: 0,
            measuredAtMs = current.date,
            readings = sorted.map { e -> GraphReading(e.date, e.sgv.toFloat()) }
        )
    }

    private fun connectRealtime(id: String) {
        scope.launch(Dispatchers.IO) {
            val baseUrl = preferences.baseUrl.first().trimEnd('/')
            if (baseUrl.isBlank()) return@launch
            val connection = HubConnectionBuilder.create("$baseUrl/ws/live")
                .withServerTimeout(300_000)
                .withKeepAliveInterval(15_000)
                .withAccessTokenProvider(Single.fromCallable { runBlocking { preferences.accessToken.first() } })
                .build()
            connection.on("NovaMedicao", { dto: NovaMedicaoDto ->
                if (id != patientId) return@on
                // L8: a hora da medição que o GluSync manda (GlucoseEntry.date); o relógio do
                // celular só quando a fonte não manda.
                val at = dto.date.takeIf { it > 0 } ?: System.currentTimeMillis()
                _state.update {
                    if (at <= it.measuredAtMs) return@update it // a consulta já trouxe esta (ou uma mais nova)
                    val sgv = dto.sgv.toInt()
                    it.copy(
                        sgv = sgv,
                        // L30: o NovaMedicao pode vir sem "arrow"; a direção convertida cobre.
                        arrow = liveArrow(dto.arrow, dto.direction),
                        delta = it.sgv?.let { prev -> sgv - prev } ?: 0,
                        measuredAtMs = at,
                        readings = it.readings + GraphReading(at, dto.sgv.toFloat())
                    )
                }
            }, NovaMedicaoDto::class.java)
            hub = connection
            connection.start().subscribe(
                { runCatching { connection.send("SubscribePatient", id) } },
                { /* silencioso — a consulta de 30 s cobre */ }
            )
        }
    }

    private fun friendly(e: Throwable): String = when {
        e.message?.contains("403") == true -> "Sem acesso aos dados deste paciente."
        e.message?.contains("401") == true -> "Entre na sua conta do servidor (GluSync)."
        else -> e.message ?: "Erro de rede."
    }
}
