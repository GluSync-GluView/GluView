package com.gluview.app.ui.screen.watchface

import com.gluview.app.data.sibionics.SibionicsSensorStatus
import com.gluview.app.domain.model.GlucoseReading
import com.gluview.app.domain.model.GlucoseZone
import com.gluview.app.util.GraphReading
import java.util.Calendar
import kotlin.math.sin

/**
 * Valores simulados para a prévia do editor (achado P6 do plano 06_watchfaces_v2_arquitetura.md:
 * "dados de glicemia usados na prévia devem ser simulados por padrão"). [enabled] começa `true` —
 * dado real só aparece se o usuário desligar explicitamente. Nenhum campo de saúde real (glicemia)
 * é gerado aqui; `zone`/`isStale`/`direction`/`timestamp` no [GlucoseReading] resultante são
 * placeholders porque nenhum ComponentKind hoje os lê (confirmado por busca no pacote watchface) —
 * só sgvMgDl/arrow/delta/epochMs importam para o desenho.
 */
data class SimulatedPreviewInput(
    val enabled: Boolean = true,
    val glucoseMgDl: Int = 120,
    val arrow: String = "→",
    val deltaMgDl: Int = 0,
    val sensorConnected: Boolean = true,
    val sensorAgeDays: Int = 5,
    val batteryPercent: Int = 80,
    val useCustomTime: Boolean = false,
    val hour: Int = 12,
    val minute: Int = 0
) {
    companion object {
        val ARROWS = listOf("↑↑", "↑", "↗", "→", "↘", "↓", "↓↓")
    }
}

fun SimulatedPreviewInput.toRuntimeData(): WatchFaceRuntimeData {
    val nowMs = if (useCustomTime) {
        Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    } else {
        System.currentTimeMillis()
    }

    val reading = GlucoseReading(
        sgvMgDl = glucoseMgDl.toDouble(),
        direction = "flat",
        arrow = arrow,
        timestamp = "",
        zone = GlucoseZone.NORMAL,
        delta = deltaMgDl,
        isStale = false,
        minutesAgo = 0,
        epochMs = nowMs
    )
    val sensorStatus = SibionicsSensorStatus(
        connected = sensorConnected,
        sensorStartMs = nowMs - sensorAgeDays * 86_400_000L,
        lastReadingMs = nowMs,
        lastIndex = 0
    )
    return WatchFaceRuntimeData(
        nowMs = nowMs,
        reading = reading,
        graphReadings = simulatedGraphReadings(nowMs, glucoseMgDl),
        sensorStatus = sensorStatus,
        batteryPercent = batteryPercent
    )
}

/** Curva sintética suave em torno de [centerMgDl], só para o componente de gráfico ter o que desenhar. */
private fun simulatedGraphReadings(nowMs: Long, centerMgDl: Int): List<GraphReading> {
    val points = 36 // 3h a cada 5min, mesma janela do preview real (WatchFaceEditorViewModel)
    return (0 until points).map { i ->
        val ts = nowMs - (points - i) * 5 * 60_000L
        val wobble = (sin(i / 3.0) * 15).toInt()
        GraphReading(timestampMs = ts, sgvMgDl = (centerMgDl + wobble).toFloat().coerceIn(40f, 300f), confirmed = true)
    }
}
