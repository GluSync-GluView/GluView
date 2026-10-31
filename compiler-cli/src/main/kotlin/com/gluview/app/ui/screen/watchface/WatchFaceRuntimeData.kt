package com.gluview.app.ui.screen.watchface

import com.gluview.app.data.sibionics.SibionicsSensorStatus
import com.gluview.app.domain.model.GlucoseReading
import com.gluview.app.util.GlucoseUnit
import com.gluview.app.util.GraphReading
import com.gluview.app.util.TimeFormat

/**
 * Cópia local (NÃO compartilhada via srcDir) do mesmo tipo definido em WatchFaceRenderer.kt no
 * módulo :app — mesmo pacote/nome/campos, sem a anotação @Immutable (é dica de performance do
 * Compose, sem sentido aqui). Motivo de não compartilhar o arquivo original: ele mistura a classe
 * de dados com um monte de composables do editor (import androidx.compose.*), que não fazem
 * sentido/não existem no classpath JVM puro do compiler-cli. Ver spike da Fase 6 (plano
 * 06_watchfaces_v2_arquitetura.md) — MiBandCanvasCompiler.kt/ComponentKind.kt (esses sim
 * compartilhados via srcDir) só enxergam o formato desta classe, não a origem do arquivo.
 */
data class WatchFaceRuntimeData(
    val nowMs: Long,
    val reading: GlucoseReading?,
    val graphReadings: List<GraphReading>,
    val sensorStatus: SibionicsSensorStatus,
    val hipo: Float = 70f,
    val hiper: Float = 180f,
    val timeFormat: TimeFormat = TimeFormat.H24,
    val batteryPercent: Int = 0,
    val unit: GlucoseUnit = GlucoseUnit.MG_DL
)
