package com.gluview.app.demo

import java.time.Instant
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Glicemia sintética do modo demonstração (build `demo`): determinística, função só do horário,
 * sem Random — o mesmo instante dá sempre o mesmo valor, então gráfico, leitura atual, seguidor e
 * relatórios concordam entre si. Serve para gravar vídeos e telas de divulgação sem dado real.
 *
 * A curva imita um dia comum: base perto de 115, três refeições com subida e descida suaves, um
 * leve efeito do amanhecer e uma variação lenta de um dia para o outro. Fica quase sempre no alvo,
 * com altas moderadas depois das refeições e nenhuma baixa urgente (é material de divulgação, não
 * teste de alerta).
 */
object DemoGlucose {

    /** Intervalo entre leituras sintéticas. */
    const val STEP_MS = 5 * 60_000L

    private data class Refeicao(val hora: Double, val pico: Double)

    private val refeicoes = listOf(Refeicao(7.5, 70.0), Refeicao(12.6, 85.0), Refeicao(19.8, 75.0))

    /** mg/dL no instante [epochMs], no fuso [zone] (o do aparelho, por padrão). */
    fun mgdl(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): Double {
        val t = Instant.ofEpochMilli(epochMs).atZone(zone)
        val hora = t.hour + t.minute / 60.0 + t.second / 3600.0
        val dia = t.toLocalDate().toEpochDay().toDouble()

        var v = 115.0
        // Variação lenta entre dias (cada dia um pouco diferente, sempre igual para o mesmo dia).
        v += 8 * sin(dia * 1.7)
        // Amanhecer: sobe um pouco entre 4h e 8h.
        v += 14 * gauss(hora, 6.0, 1.3)
        for (r in refeicoes) {
            val dt = horasDesde(hora, r.hora)
            if (dt in 0.0..4.0) v += r.pico * (1 + 0.15 * sin(dia + r.hora)) * curvaRefeicao(dt)
        }
        // Ondulação curta para o traço não parecer régua.
        v += 4 * sin(2 * PI * hora / 1.7 + dia) + 2 * sin(2 * PI * hora / 0.45)
        return v.coerceIn(72.0, 230.0).roundToInt().toDouble()
    }

    /** Leituras alinhadas à grade de [STEP_MS] entre [fromMs] e [toMs] (inclusive). */
    fun serie(fromMs: Long, toMs: Long, zone: ZoneId = ZoneId.systemDefault()): List<Pair<Long, Double>> {
        if (toMs < fromMs) return emptyList()
        val inicio = (fromMs + STEP_MS - 1) / STEP_MS * STEP_MS
        return generateSequence(inicio) { it + STEP_MS }.takeWhile { it <= toMs }
            .map { it to mgdl(it, zone) }.toList()
    }

    /** Última leitura da grade em ou antes de [nowMs]. */
    fun ultimaLeitura(nowMs: Long): Long = nowMs / STEP_MS * STEP_MS

    /** Tendência no padrão Nightscout pela variação dos últimos 15 minutos. */
    fun direcao(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val porMinuto = (mgdl(epochMs, zone) - mgdl(epochMs - 3 * STEP_MS, zone)) / 15.0
        return when {
            porMinuto >= 3 -> "DoubleUp"
            porMinuto >= 2 -> "SingleUp"
            porMinuto >= 1 -> "FortyFiveUp"
            porMinuto > -1 -> "Flat"
            porMinuto > -2 -> "FortyFiveDown"
            porMinuto > -3 -> "SingleDown"
            else -> "DoubleDown"
        }
    }

    fun seta(direcao: String): String = when (direcao) {
        "DoubleUp" -> "↑↑"; "SingleUp" -> "↑"; "FortyFiveUp" -> "↗"
        "FortyFiveDown" -> "↘"; "SingleDown" -> "↓"; "DoubleDown" -> "↓↓"
        else -> "→"
    }

    private fun horasDesde(hora: Double, inicio: Double): Double = (hora - inicio + 24) % 24

    // Sobe em ~1h, desce até ~3h30.
    private fun curvaRefeicao(dt: Double): Double = (dt / 1.0) * exp(1 - dt / 1.0) * exp(-dt * dt / 18)

    private fun gauss(x: Double, mu: Double, sigma: Double): Double = exp(-(x - mu) * (x - mu) / (2 * sigma * sigma))
}
