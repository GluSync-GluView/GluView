package com.gluview.app.visualaudit

import com.gluview.app.data.local.entity.DayStats
import com.gluview.app.data.remote.dto.HbA1cResultDto
import com.gluview.app.data.remote.dto.MonthlyStatsDto
import com.gluview.app.data.remote.dto.SummaryCabecalhoDto
import com.gluview.app.data.remote.dto.SummaryReportDto
import com.gluview.app.data.remote.dto.TirDayDto
import com.gluview.app.data.remote.dto.TirReportDto
import com.gluview.app.data.repository.DailyPatternPoint
import com.gluview.app.data.sibionics.SensorWarning
import com.gluview.app.data.sibionics.SibionicsSensorStatus
import com.gluview.app.domain.model.GlucoseReading
import com.gluview.app.domain.model.GlucoseZone
import com.gluview.app.ui.screen.caregiver.FollowedPatientUiState
import com.gluview.app.ui.screen.home.FollowerHomeUiState
import com.gluview.app.ui.screen.home.HomeUiState
import com.gluview.app.ui.screen.home.PatientHomeState
import com.gluview.app.ui.screen.home.SensorAgingAlert
import com.gluview.app.ui.screen.home.SensorWarmup
import com.gluview.app.ui.screen.tir.TirPeriod
import com.gluview.app.ui.screen.tir.TirUiState
import com.gluview.app.util.GlucoseUnit
import com.gluview.app.util.TimeFormat
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Estados de tela montados só com dados fixos ([GlucoseFixtures]) — os cenários do harness. */
object Scenarios {

    private val now = GlucoseFixtures.FIXED_NOW
    private const val MIN = 60_000L
    private const val DAY = 24 * 3_600_000L

    fun zoneOf(v: Double) = when {
        v < 55 -> GlucoseZone.URGENT_LOW
        v < 70 -> GlucoseZone.LOW
        v > 250 -> GlucoseZone.URGENT_HIGH
        v > 180 -> GlucoseZone.HIGH
        else -> GlucoseZone.NORMAL
    }

    fun reading(
        v: Double,
        arrow: String = "→",
        direction: String = "Flat",
        delta: Int = 2,
        minutesAgo: Int = 1,
        stale: Boolean = false
    ) = GlucoseReading(v, direction, arrow, "", zoneOf(v), delta, stale, minutesAgo, now - minutesAgo * MIN)

    private val day = GlucoseFixtures.series(now, hours = 24)
    private val dayStats = DayStats(
        minSgv = day.minOf { it.sgvMgDl }.toDouble(),
        maxSgv = day.maxOf { it.sgvMgDl }.toDouble(),
        avgSgv = day.map { it.sgvMgDl.toDouble() }.average(),
        count = day.size
    )
    private val current = day.last().sgvMgDl.toDouble()

    // ------------------------------------------------------------------ Home do paciente
    private fun home(dark: Boolean) = PatientHomeState(
        uiState = HomeUiState(
            reading = reading(current), unit = GlucoseUnit.MG_DL, dayStats = dayStats,
            isLoading = false, lastSyncMs = now - MIN, timeFormat = TimeFormat.H24
        ),
        homeReadings = GlucoseFixtures.series(now, hours = 3),
        homeHours = 3,
        isSensorSource = true,
        sensorStatus = SibionicsSensorStatus(connected = true, sensorStartMs = now - 6 * DAY, lastReadingMs = now - MIN),
        sensorAgingAlert = SensorAgingAlert(),
        sensorWarning = SensorWarning.NONE,
        sensorWarmup = SensorWarmup(),
        alertCount = 2,
        isRefreshing = false,
        hipoThreshold = 70f,
        hiperThreshold = 180f,
        darkGraph = dark
    )

    /** nome → estado (o tema do gráfico segue o do app, como no padrão FOLLOW_APP). */
    internal val homeScenarios: Map<String, (Boolean) -> PatientHomeState> = linkedMapOf(
        "normal_sensor" to { d -> home(d) },
        "fonte_glusync" to { d -> home(d).copy(isSensorSource = false) },
        "sem_sinal_22min" to { d ->
            home(d).copy(
                uiState = home(d).uiState.copy(reading = reading(current, minutesAgo = 22, stale = true), lastSyncMs = now - 22 * MIN),
                sensorStatus = SibionicsSensorStatus(connected = false, sensorStartMs = now - 6 * DAY, lastReadingMs = now - 22 * MIN)
            )
        },
        "aquecendo_sem_leitura" to { d ->
            home(d).copy(
                uiState = HomeUiState(reading = null, isLoading = true, timeFormat = TimeFormat.H24),
                homeReadings = emptyList(),
                sensorStatus = SibionicsSensorStatus(connected = true, sensorStartMs = now - 20 * MIN),
                sensorWarmup = SensorWarmup(active = true, elapsedMin = 20, minutesLeft = 40)
            )
        },
        "sem_leitura_nenhuma" to { d ->
            home(d).copy(uiState = HomeUiState(reading = null, isLoading = true), homeReadings = emptyList())
        },
        "hipo_urgente" to { d ->
            home(d).copy(uiState = home(d).uiState.copy(reading = reading(48.0, arrow = "↓↓", direction = "DoubleDown", delta = -6)))
        },
        "hiper_urgente_340" to { d ->
            home(d).copy(uiState = home(d).uiState.copy(reading = reading(340.0, arrow = "↑", direction = "SingleUp", delta = 4)),
                homeReadings = GlucoseFixtures.outOfScale(now))
        },
        "verifique_sensor" to { d ->
            home(d).copy(
                uiState = home(d).uiState.copy(reading = reading(current, minutesAgo = 14, stale = true), lastSyncMs = now - 14 * MIN),
                sensorWarning = SensorWarning.CHECK_SENSOR
            )
        },
        "sensor_envelhecido_15d" to { d ->
            home(d).copy(
                sensorStatus = SibionicsSensorStatus(connected = true, sensorStartMs = now - 15 * DAY, lastReadingMs = now - MIN),
                sensorAgingAlert = SensorAgingAlert(visible = true, days = 15, agingDays = 14)
            )
        },
        "mmol_12h" to { d ->
            home(d).copy(uiState = home(d).uiState.copy(unit = GlucoseUnit.MMOL_L, timeFormat = TimeFormat.H12))
        },
        "janela_24h_sibionics" to { d ->
            home(d).copy(homeReadings = GlucoseFixtures.sibionics1Min(now), homeHours = 24)
        },
        "buraco_2h_6h" to { d ->
            home(d).copy(homeReadings = GlucoseFixtures.withGap(GlucoseFixtures.series(now, hours = 6)), homeHours = 6)
        },
        "alertas_12" to { d -> home(d).copy(alertCount = 12) }
    )

    // ------------------------------------------------------------------ Seguidor
    private val followerBase = FollowerHomeUiState(
        loading = false, patientId = "p1", patientName = "Ana",
        sgv = current.toInt(), arrow = "→", delta = 3, lastUpdatedMs = now - 2 * MIN, readings = day
    )
    val followerScenarios: Map<String, FollowerHomeUiState> = linkedMapOf(
        "com_dados" to followerBase,
        "sem_paciente" to FollowerHomeUiState(loading = false),
        "carregando" to followerBase.copy(loading = true),
        "erro" to followerBase.copy(error = "Sem acesso aos dados deste paciente."),
        "sem_leituras_24h" to followerBase.copy(sgv = null, arrow = "", delta = 0, lastUpdatedMs = 0, readings = emptyList()),
        "nome_enorme" to followerBase.copy(patientName = "Maria Eduarda de Albuquerque Cavalcanti Figueiredo")
    )

    // ------------------------------------------------------------------ Paciente acompanhado
    val followedScenarios: Map<String, FollowedPatientUiState> = linkedMapOf(
        "com_dados" to FollowedPatientUiState(loading = false, sgv = current.toInt(), arrow = "→", readings = day),
        "erro" to FollowedPatientUiState(loading = false, error = "Paciente inválido"),
        "sem_leituras" to FollowedPatientUiState(loading = false, sgv = null, readings = emptyList())
    )

    // ------------------------------------------------------------------ Histórico
    fun tirReport(days: Int = 30): TirReportDto {
        val list = (days - 1 downTo 0).map { i ->
            val date = Instant.ofEpochMilli(now - i * DAY).atZone(ZoneOffset.UTC).toLocalDate()
            val hipo = 3.0 + (i * 7 % 6)
            val hiper = 18.0 + (i * 11 % 17)
            TirDayDto(date.format(DateTimeFormatter.ofPattern("dd/MM/yyyy")), 100.0 - hipo - hiper, 6.8 + (i % 5) / 10.0, hipo, hiper)
        }
        return TirReportDto("Últimos $days dias", list.map { it.tir }.average().takeIf { list.isNotEmpty() } ?: 0.0, 6.9, list)
    }
    /** Relatório como o GluSync manda (cabeçalho, HbA1c de 90 dias e 3 meses de resumo). */
    fun summaryReport(): SummaryReportDto = SummaryReportDto(
        cabecalho = SummaryCabecalhoDto(
            periodo = "10/2026", gerado = "2026-10-18T14:30:00Z", totalDias = 87,
            hbA1c90dias = HbA1cResultDto("últimos 90 dias", 151.0, 6.9, "Bom", 24_812, "good")
        ),
        tabelaPivot = emptyList(),
        resumoMensal = listOf(
            MonthlyStatsDto(2026, 10, 148.0, 6.8, 71.4, 18, 5_120),
            MonthlyStatsDto(2026, 9, 153.0, 7.0, 68.2, 30, 8_544),
            MonthlyStatsDto(2026, 8, 156.0, 7.1, 66.9, 31, 8_802)
        )
    )
    val tirState = TirUiState(report = tirReport(), period = TirPeriod.D30)

    /** Média por faixa de 3h (00..21h), como o pivot do servidor. */
    val patternPoints: List<DailyPatternPoint> = (0 until 8).map { slot ->
        val hour = slot * 3
        val avg = (0 until 180 step 5).map { GlucoseFixtures.profileAt(hour * 60.0 + it).toDouble() }.average()
        DailyPatternPoint(hour, avg, String.format("%02d:00", hour))
    }
}
