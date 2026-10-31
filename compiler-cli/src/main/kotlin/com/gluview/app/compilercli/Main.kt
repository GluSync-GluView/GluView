package com.gluview.app.compilercli

import android.content.Context
import com.gluview.app.data.sibionics.SibionicsSensorStatus
import com.gluview.app.domain.model.GlucoseReading
import com.gluview.app.domain.model.GlucoseZone
import com.gluview.app.ui.screen.watchface.WatchFaceRuntimeData
import com.gluview.app.util.GlucoseUnit
import com.gluview.app.util.TimeFormat
import com.gluview.app.watchface.WatchFaceDesign
import com.gluview.app.watchface.WatchFaceJson
import com.gluview.app.watchface.WearableKind
import com.gluview.app.watchface.WearableShape
import com.gluview.app.watchface.WearableSpec
import com.gluview.app.watchface.miband.MiBandTemplateSpec
import com.gluview.app.watchface.miband.WatchFaceDesignGenerator
import com.gluview.app.watchface.resolvedFor
import org.json.JSONObject
import java.io.File
import kotlin.system.exitProcess

/**
 * Entrada do compilador "oficial" da loja (Fase 6, plano 06_watchfaces_v2_arquitetura.md) — processo
 * companheiro invocado pelo GluSync (`java -jar compiler-cli.jar <request.json> <saida.bin>`).
 * Contrato deliberadamente simples (2 arquivos, sem stdin/stdout binário) — fácil de invocar via
 * `System.Diagnostics.Process` no .NET sem lidar com encoding de binário em pipes.
 *
 * O runtime da glicemia é SEMPRE simulado — nunca existe dado real de glicemia no processo de
 * compilação server-side (P6 do plano: "não exponha dados reais de glicemia"). O request só carrega
 * os VALORES simulados a usar (mesmo princípio do SimulatedPreviewInput do editor no app/web).
 */
fun main(args: Array<String>) {
    if (args.size != 2) {
        System.err.println("Uso: compiler-cli <request.json> <saida.bin>")
        exitProcess(2)
    }
    val requestFile = File(args[0])
    val outputFile = File(args[1])

    try {
        val bytes = compile(JSONObject(requestFile.readText()))
        outputFile.writeBytes(bytes)
        System.err.println("OK: ${bytes.size} bytes escritos em ${outputFile.path}")
    } catch (e: Exception) {
        System.err.println("Falha na compilação: ${e.javaClass.simpleName}: ${e.message}")
        e.printStackTrace()
        exitProcess(1)
    }
}

/**
 * O request separa o wearable de AUTORIA (`project*`, a resolução em que os componentes foram
 * posicionados no editor) do wearable ALVO (`target*`, o que a loja está compilando agora — pode
 * ser o mesmo, ou um dos `compatibleWearableIds` do projeto). Quando diferem, usa o mesmo
 * `WatchFaceDesign.resolvedFor()` que o app usa ao trocar de wearable no editor ou ao aplicar
 * pra um wearable compatível — reaproveitado aqui de propósito, não reimplementado.
 */
fun compile(request: JSONObject): ByteArray {
    val projectWearable = WearableSpec(
        id = request.getString("projectWearableId"),
        brand = "",
        name = request.optString("projectWearableId"),
        widthPx = request.getInt("projectWearableWidthPx"),
        heightPx = request.getInt("projectWearableHeightPx"),
        kind = WearableKind.SMARTBAND,
        shape = WearableShape.RECTANGLE
    )
    val targetWearableId = request.getString("targetWearableId")
    val targetWearable = if (targetWearableId == projectWearable.id) {
        projectWearable
    } else {
        WearableSpec(
            id = targetWearableId,
            brand = "",
            name = targetWearableId,
            widthPx = request.getInt("targetWearableWidthPx"),
            heightPx = request.getInt("targetWearableHeightPx"),
            kind = WearableKind.SMARTBAND,
            shape = WearableShape.RECTANGLE
        )
    }

    val authoredDesign = WatchFaceDesign(
        wearable = projectWearable,
        components = WatchFaceJson.decodeComponents(request.getString("componentsJson"))
    )
    val design = if (targetWearable.id == projectWearable.id) authoredDesign else authoredDesign.resolvedFor(targetWearable)

    val template = MiBandTemplateSpec(
        assetDir = request.getString("templateAssetDir"),
        canvasWidth = request.getInt("canvasWidth"),
        canvasHeight = request.getInt("canvasHeight")
    )
    val runtime = parseRuntime(request.getJSONObject("runtime"))

    val generator = WatchFaceDesignGenerator(Context(), template)
    return generator.generate(design, runtime)
}

private fun parseRuntime(r: JSONObject): WatchFaceRuntimeData {
    val sgv = r.optDouble("sgvMgDl", 120.0)
    val arrow = r.optString("arrow", "→")
    val isStale = r.optBoolean("isStale", false)
    val zone = GlucoseZone.fromStatus(r.optString("zoneStatus", "normal"), isStale, arrow)
    return WatchFaceRuntimeData(
        nowMs = r.optLong("nowMs", System.currentTimeMillis()),
        reading = GlucoseReading(
            sgvMgDl = sgv,
            direction = r.optString("direction", "Flat"),
            arrow = arrow,
            timestamp = r.optString("timestamp", ""),
            zone = zone,
            delta = r.optInt("delta", 0),
            isStale = isStale,
            minutesAgo = r.optInt("minutesAgo", 0)
        ),
        graphReadings = emptyList(),
        sensorStatus = SibionicsSensorStatus(connected = r.optBoolean("sensorConnected", true)),
        hipo = r.optDouble("hipo", 70.0).toFloat(),
        hiper = r.optDouble("hiper", 180.0).toFloat(),
        timeFormat = TimeFormat.fromString(r.optString("timeFormat", "H24")),
        batteryPercent = r.optInt("batteryPercent", 80),
        unit = GlucoseUnit.fromString(r.optString("unit", "MG_DL"))
    )
}
