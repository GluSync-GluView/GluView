package com.gluview.app.notification

import android.content.Context
import android.graphics.Bitmap
import com.gluview.app.R
import com.gluview.app.domain.model.GlucoseReading
import com.gluview.app.util.GlucoseGraphRenderer
import com.gluview.app.util.GlucoseUnit
import com.gluview.app.util.GraphReading
import com.gluview.app.util.TimeFormat
import com.gluview.app.util.epochMsToTimeOfDay
import com.gluview.app.util.graph.notificationChartSizes
import com.gluview.app.util.graph.surfaceLabelPx
import com.gluview.app.util.graph.surfaceSpec
import com.gluview.app.util.graph.systemIsDark

/** O que vai na notificação persistente de uma leitura (textos e os dois gráficos). */
data class PersistentNotificationContent(
    val text: String,
    val sgvArrowText: String,
    val detailText: String?,
    val graph: Bitmap?,
    val dots: Bitmap?
)

/**
 * Monta a notificação persistente (redesign visual, Fase 5) — separado do serviço para o harness
 * desenhar a notificação de verdade. O gráfico é o modelo da Home no tamanho REAL do cartão (antes
 * 800×289 reescalado, com rótulos de ~9,7dp), com fundo transparente, eixos na unidade e no
 * formato de hora do usuário, e no tema do SISTEMA (decisão 5.4 #1). Sem sinal vira texto
 * ("Sem sinal" / "Última leitura 14:05"), não "– mg/dL" e "Delta: –".
 * [reading] já vem com a idade aplicada (withDynamicStaleness do serviço).
 */
fun buildPersistentNotificationContent(
    context: Context,
    reading: GlucoseReading,
    graphReadings: List<GraphReading>,
    unit: GlucoseUnit,
    timeFormat: TimeFormat,
    hipo: Float,
    hiper: Float,
    hours: Int,
    nowMs: Long = System.currentTimeMillis(),
    urgentLow: Float = 55f,
    urgentHigh: Float = 250f
): PersistentNotificationContent {
    val res = context.resources
    val dark = systemIsDark(res)
    val sizes = notificationChartSizes(res.displayMetrics.widthPixels, res.displayMetrics.density)
    val graph = if (graphReadings.isNotEmpty()) GlucoseGraphRenderer.render(graphReadings, surfaceSpec(
        sizes.expandedW, sizes.expandedH, hours, nowMs, dark, hipo, hiper, unit, timeFormat,
        labelSizePx = surfaceLabelPx(res), urgentLow = urgentLow, urgentHigh = urgentHigh
    )) else null
    // Recolhida: o mesmo gráfico sem eixos (antes só pontos, sem as linhas de hipo/hiper).
    val dots = if (graphReadings.isNotEmpty()) GlucoseGraphRenderer.render(graphReadings, surfaceSpec(
        sizes.collapsedW, sizes.collapsedH, hours, nowMs, dark, hipo, hiper, unit, timeFormat,
        labelSizePx = null, forceAxes = false, urgentLow = urgentLow, urgentHigh = urgentHigh
    )) else null

    // Hora da MEDIÇÃO (não a do push), no formato do usuário.
    val time = if (reading.epochMs > 0) epochMsToTimeOfDay(reading.epochMs, timeFormat) else null
    val noSignal = context.getString(R.string.zone_stale)
    return with(unit) {
        val value = reading.sgvMgDl.toDisplay()
        val delta = reading.sgvMgDl.deltaToDisplay(reading.delta)
        if (reading.isStale) PersistentNotificationContent(
            text = noSignal,
            sgvArrowText = noSignal,
            detailText = time?.let { context.getString(R.string.notif_last_reading, it) },
            graph = graph, dots = dots
        ) else PersistentNotificationContent(
            text = "$value ${unit.label} ${reading.arrow}  $delta",
            sgvArrowText = "$value ${reading.arrow}",
            detailText = listOfNotNull("$delta ${unit.label}", time).joinToString(" · "),
            graph = graph, dots = dots
        )
    }
}
