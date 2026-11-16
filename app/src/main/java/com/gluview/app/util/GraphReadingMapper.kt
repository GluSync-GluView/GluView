package com.gluview.app.util

import com.gluview.app.data.local.entity.GlucoseEntity
import com.gluview.app.data.sibionics.GlucoseDisplayMode

/**
 * Converte uma leitura armazenada em ponto do gráfico.
 *
 * IMPORTANTE: plota sempre o [GlucoseEntity.sgvMgDl] — o valor JÁ calibrado e gravado,
 * o mesmo que o cabeçalho, os alertas e os widgets simples exibem. NÃO re-derivamos a
 * partir de `rawValue × fator`, porque o fator de calibração global pode ter mudado
 * depois da gravação (ou o BLE pode ter ficado offline), o que reescalava todo o
 * histórico e fazia o gráfico divergir do valor exibido (ex.: cabeçalho 76, linha ~270).
 *
 * O parâmetro [currentCalFactor] é mantido por compatibilidade com os call sites; a
 * calibração já está embutida em [sgvMgDl] no momento da ingestão ([GlucoseRepository]).
 */
@Suppress("UNUSED_PARAMETER")
fun GlucoseEntity.toGraphReading(currentCalFactor: Double): GraphReading =
    GraphReading(dateMs, sgvMgDl.toFloat(), confirmed)

/**
 * Aplica o modo de leitura Sibionics (Ajustes > Sensor) a uma lista já convertida — usado pelos
 * gráficos que refletem esse modo (Home, notificação persistente, widgets). Gráficos de dados
 * de terceiros (visão do seguidor) nunca chamam isto — a API remota não tem o conceito.
 *
 * - ALL: comportamento de sempre — remove a distinção (ninguém desenha diferente).
 * - CONFIRMED_ONLY: só os pontos confirmados aparecem (visão "limpa" a cada ~5min).
 * - ALL_WITH_MARKERS: mantém todos os pontos com seu `confirmed` real — o renderer distingue.
 */
fun List<GraphReading>.applyDisplayMode(mode: GlucoseDisplayMode): List<GraphReading> = when (mode) {
    GlucoseDisplayMode.ALL             -> map { it.copy(confirmed = null) }
    GlucoseDisplayMode.CONFIRMED_ONLY  -> filter { it.confirmed != false }
    GlucoseDisplayMode.ALL_WITH_MARKERS -> this
}

/**
 * Plano 21, F3.2 — suavização da curva (Ajustes › Calibração e suavização). Só muda o DESENHO do
 * gráfico; valor atual, alertas, envio e relatórios continuam com o valor medido.
 */
fun List<GraphReading>.smoothed(method: com.gluview.therapy.SmoothingMethod): List<GraphReading> {
    if (method == com.gluview.therapy.SmoothingMethod.OFF || size < 3) return this
    val sorted = sortedBy { it.timestampMs }
    val s = com.gluview.therapy.Smoothing.smooth(sorted.map { it.timestampMs to it.sgvMgDl.toDouble() }, method)
    return sorted.mapIndexed { i, r -> r.copy(sgvMgDl = s[i].second.toFloat()) }
}
