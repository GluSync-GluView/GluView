package com.gluview.app.watchface

import com.gluview.app.domain.model.GlucoseReading
import com.gluview.app.ui.screen.watchface.WatchFaceRuntimeData
import com.gluview.app.util.TimeFormat
import com.gluview.app.util.epochMsToTimeOfDay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Registro de tipos de componente — substitui o antigo `enum class WatchFaceComponentType`
 * (achado P3 do plano 06_watchfaces_v2_arquitetura.md: enum fechado exigia tocar 4 arquivos pra
 * adicionar um tipo novo). Um [ComponentKind] novo, do tipo [TextComponentKind], não exige
 * nenhuma mudança em WatchFaceRenderer/WatchFaceDesignGenerator/WatchFaceComponentLibrary — só
 * registrar em [ComponentRegistry.all]. Só tipos com desenho de verdade (não-texto), como o
 * gráfico, continuam exigindo código específico por renderer — isso é inerente (desenhar um
 * gráfico não dá pra generalizar em uma interface), não uma limitação da arquitetura.
 *
 * `sealed interface` nos 3 papéis (texto/gráfico/status) mantém o `when` de despacho exaustivo
 * (o compilador garante que os 3 casos são tratados), mas cada papel é livremente implementável
 * por quantos `object` quiser — só os 3 papéis em si são fechados, não os tipos concretos.
 */
data class SizeF(val width: Float, val height: Float)

sealed interface ComponentKind {
    val id: String
    val label: String
    fun baseSize(variation: Int): SizeF
}

/**
 * A maioria dos tipos: um texto formatado a partir do runtime (e, quando o tipo precisar, do
 * próprio componente — ex.: FixedTextKind lê `component.customText`). Novo tipo = um object novo
 * aqui, registrado em [ComponentRegistry.all].
 */
interface TextComponentKind : ComponentKind {
    fun textFor(component: WatchFaceComponent, runtime: WatchFaceRuntimeData): String
}

/** Tipos com desenho próprio (não é texto) — hoje só o gráfico de glicemia. */
interface ChartComponentKind : ComponentKind

/** Texto + cor derivados de um estado (não só formatação) — hoje só o status do sensor. */
interface StatusComponentKind : ComponentKind {
    fun statusText(runtime: WatchFaceRuntimeData): String
    fun statusColor(component: WatchFaceComponent, runtime: WatchFaceRuntimeData): Long
}

private fun now(runtime: WatchFaceRuntimeData) = Date(runtime.nowMs)
private fun locale() = Locale.getDefault()

/** "há Xmin" / "agora" — usado por ReadingAgeKind. */
private fun ageText(minutesAgo: Int): String = when {
    minutesAgo <= 0 -> "agora"
    else -> "há ${minutesAgo}min"
}

object TimeHmsKind : TextComponentKind {
    override val id = "time_hms"
    override val label = "Hora, minuto e segundo"
    override fun baseSize(variation: Int) = SizeF(180f + variation * 4f, 54f)
    override fun textFor(component: WatchFaceComponent, runtime: WatchFaceRuntimeData) =
        SimpleDateFormat("HH:mm:ss", locale()).format(now(runtime))
}

object TimeHmKind : TextComponentKind {
    override val id = "time_hm"
    override val label = "Hora e minuto"
    override fun baseSize(variation: Int) = SizeF(150f + variation * 4f, 62f)
    override fun textFor(component: WatchFaceComponent, runtime: WatchFaceRuntimeData) =
        SimpleDateFormat("HH:mm", locale()).format(now(runtime))
}

object SecondsKind : TextComponentKind {
    override val id = "seconds"
    override val label = "Segundos"
    override fun baseSize(variation: Int) = SizeF(60f + variation * 2f, 34f)
    override fun textFor(component: WatchFaceComponent, runtime: WatchFaceRuntimeData) =
        SimpleDateFormat("ss", locale()).format(now(runtime))
}

object DateDmKind : TextComponentKind {
    override val id = "date_dm"
    override val label = "Dia/Mes"
    override fun baseSize(variation: Int) = SizeF(130f + variation * 3f, 38f)
    override fun textFor(component: WatchFaceComponent, runtime: WatchFaceRuntimeData) =
        SimpleDateFormat("dd/MM", locale()).format(now(runtime))
}

object DateDmyKind : TextComponentKind {
    override val id = "date_dmy"
    override val label = "Dia/Mes/Ano"
    override fun baseSize(variation: Int) = SizeF(130f + variation * 3f, 38f)
    override fun textFor(component: WatchFaceComponent, runtime: WatchFaceRuntimeData) =
        SimpleDateFormat("dd/MM/yyyy", locale()).format(now(runtime))
}

object DateYmdKind : TextComponentKind {
    override val id = "date_ymd"
    override val label = "Ano/Mes/Dia"
    override fun baseSize(variation: Int) = SizeF(130f + variation * 3f, 38f)
    override fun textFor(component: WatchFaceComponent, runtime: WatchFaceRuntimeData) =
        SimpleDateFormat("yyyy/MM/dd", locale()).format(now(runtime))
}

object DateMdKind : TextComponentKind {
    override val id = "date_md"
    override val label = "Mes/Dia"
    override fun baseSize(variation: Int) = SizeF(130f + variation * 3f, 38f)
    override fun textFor(component: WatchFaceComponent, runtime: WatchFaceRuntimeData) =
        SimpleDateFormat("MM/dd", locale()).format(now(runtime))
}

object MonthKind : TextComponentKind {
    override val id = "month"
    override val label = "Mes"
    override fun baseSize(variation: Int) = SizeF(70f + variation * 2f, 34f)
    override fun textFor(component: WatchFaceComponent, runtime: WatchFaceRuntimeData) =
        SimpleDateFormat("MM", locale()).format(now(runtime))
}

object YearKind : TextComponentKind {
    override val id = "year"
    override val label = "Ano"
    override fun baseSize(variation: Int) = SizeF(90f + variation * 2f, 34f)
    override fun textFor(component: WatchFaceComponent, runtime: WatchFaceRuntimeData) =
        SimpleDateFormat("yyyy", locale()).format(now(runtime))
}

object WeekdayKind : TextComponentKind {
    override val id = "weekday"
    override val label = "Dia da semana"
    override fun baseSize(variation: Int) = SizeF(130f + variation * 3f, 38f)
    override fun textFor(component: WatchFaceComponent, runtime: WatchFaceRuntimeData) =
        SimpleDateFormat("EEEE", locale()).format(now(runtime)).replaceFirstChar { it.titlecase(locale()) }
}

object GlucoseCurrentKind : TextComponentKind {
    override val id = "glucose_current"
    override val label = "Glicemia atual"
    override fun baseSize(variation: Int) = SizeF(150f + variation * 5f, 70f)
    override fun textFor(component: WatchFaceComponent, runtime: WatchFaceRuntimeData): String =
        runtime.reading?.let { "${it.sgvMgDl.toInt()} ${it.arrow}" } ?: "--"
}

object GlucoseDeltaKind : TextComponentKind {
    override val id = "glucose_delta"
    override val label = "Variacao (delta)"
    override fun baseSize(variation: Int) = SizeF(130f + variation * 3f, 34f)
    override fun textFor(component: WatchFaceComponent, runtime: WatchFaceRuntimeData): String =
        runtime.reading?.let { deltaText(it, runtime.nowMs, runtime.timeFormat) } ?: "--"
}

object GlucoseUnitKind : TextComponentKind {
    override val id = "glucose_unit"
    override val label = "Unidade de glicemia"
    override fun baseSize(variation: Int) = SizeF(90f + variation * 2f, 28f)
    override fun textFor(component: WatchFaceComponent, runtime: WatchFaceRuntimeData): String =
        runtime.unit.label
}

object LastReadingTimeKind : TextComponentKind {
    override val id = "last_reading_time"
    override val label = "Horario da ultima leitura"
    override fun baseSize(variation: Int) = SizeF(120f + variation * 3f, 34f)
    override fun textFor(component: WatchFaceComponent, runtime: WatchFaceRuntimeData): String {
        val reading = runtime.reading ?: return "--"
        val measuredAtMs = if (reading.epochMs > 0L) reading.epochMs else runtime.nowMs - reading.minutesAgo * 60_000L
        return epochMsToTimeOfDay(measuredAtMs, runtime.timeFormat)
    }
}

object ReadingAgeKind : TextComponentKind {
    override val id = "reading_age"
    override val label = "Idade da leitura"
    override fun baseSize(variation: Int) = SizeF(110f + variation * 3f, 32f)
    override fun textFor(component: WatchFaceComponent, runtime: WatchFaceRuntimeData): String {
        val reading = runtime.reading ?: return "--"
        return ageText(reading.minutesAgo)
    }
}

object TargetRangeKind : TextComponentKind {
    override val id = "target_range"
    override val label = "Faixa de meta"
    override fun baseSize(variation: Int) = SizeF(110f + variation * 3f, 32f)
    override fun textFor(component: WatchFaceComponent, runtime: WatchFaceRuntimeData): String =
        "${runtime.hipo.toInt()}-${runtime.hiper.toInt()}"
}

object HipoAlertKind : TextComponentKind {
    override val id = "hipo_alert"
    override val label = "Alerta de hipoglicemia"
    override fun baseSize(variation: Int) = SizeF(130f + variation * 3f, 34f)
    override fun textFor(component: WatchFaceComponent, runtime: WatchFaceRuntimeData): String {
        val sgv = runtime.reading?.sgvMgDl ?: return ""
        return if (sgv < runtime.hipo) "⚠ Baixo" else ""
    }
}

object HiperAlertKind : TextComponentKind {
    override val id = "hiper_alert"
    override val label = "Alerta de hiperglicemia"
    override fun baseSize(variation: Int) = SizeF(130f + variation * 3f, 34f)
    override fun textFor(component: WatchFaceComponent, runtime: WatchFaceRuntimeData): String {
        val sgv = runtime.reading?.sgvMgDl ?: return ""
        return if (sgv > runtime.hiper) "⚠ Alto" else ""
    }
}

object FixedTextKind : TextComponentKind {
    override val id = "fixed_text"
    override val label = "Texto fixo"
    override fun baseSize(variation: Int) = SizeF(120f + variation * 4f, 36f)
    override fun textFor(component: WatchFaceComponent, runtime: WatchFaceRuntimeData): String =
        component.customText ?: "Texto"
}

object SensorLifeKind : TextComponentKind {
    override val id = "sensor_life"
    override val label = "Tempo de vida do sensor"
    override fun baseSize(variation: Int) = SizeF(160f + variation * 3f, 42f)
    override fun textFor(component: WatchFaceComponent, runtime: WatchFaceRuntimeData): String =
        runtime.sensorStatus.ageDays(runtime.nowMs)?.let { "Sensor ${it}d" } ?: "Sensor --"
}

object PhoneBatteryKind : TextComponentKind {
    override val id = "phone_battery"
    override val label = "Bateria do celular"
    override fun baseSize(variation: Int) = SizeF(120f + variation * 3f, 34f)
    override fun textFor(component: WatchFaceComponent, runtime: WatchFaceRuntimeData): String = "${runtime.batteryPercent}%"
}

object GlucoseChartKind : ChartComponentKind {
    override val id = "glucose_chart"
    override val label = "Grafico de glicemia"
    override fun baseSize(variation: Int) = SizeF(230f + variation * 4f, 92f)
}

object SensorConnectedKind : StatusComponentKind {
    override val id = "sensor_connected"
    override val label = "Sensor conectado"
    override fun baseSize(variation: Int) = SizeF(130f + variation * 3f, 42f)
    override fun statusText(runtime: WatchFaceRuntimeData): String =
        if (runtime.sensorStatus.connected) "Sensor ON" else "Sensor OFF"
    override fun statusColor(component: WatchFaceComponent, runtime: WatchFaceRuntimeData): Long =
        if (runtime.sensorStatus.connected) component.color else 0xFFFF5252L
}

// ── Bomba de insulina (beta) ────────────────────────────────────────────────────────────────────
// A "bomba de insulina" hoje é só uma LISTA DE ESPERA de beta (feature_key=insulin_pump_beta, ver
// InsulinPumpViewModel.kt) — não existe NENHUM dado real de bomba em lugar nenhum do sistema ainda
// (sem IOB, taxa basal, bolus, reservatório). Estes tipos existem pro CATÁLOGO/DESIGN já ficar
// pronto pra receber esse dado quando o pipeline de verdade existir (GluSync + sync pro relógio,
// iniciativa separada e maior); até lá, sempre "--"/desconectado, igual GlucoseCurrentKind sem
// leitura ou SensorConnectedKind sem sensor — nunca um placeholder "fake" com número inventado.

object InsulinIobKind : TextComponentKind {
    override val id = "insulin_iob"
    override val label = "Insulina ativa (IOB)"
    override fun baseSize(variation: Int) = SizeF(120f + variation * 3f, 40f)
    override fun textFor(component: WatchFaceComponent, runtime: WatchFaceRuntimeData): String = "--"
}

object InsulinBasalRateKind : TextComponentKind {
    override val id = "insulin_basal_rate"
    override val label = "Taxa basal atual"
    override fun baseSize(variation: Int) = SizeF(130f + variation * 3f, 34f)
    override fun textFor(component: WatchFaceComponent, runtime: WatchFaceRuntimeData): String = "--"
}

object InsulinLastBolusKind : TextComponentKind {
    override val id = "insulin_last_bolus"
    override val label = "Último bolus"
    override fun baseSize(variation: Int) = SizeF(120f + variation * 3f, 34f)
    override fun textFor(component: WatchFaceComponent, runtime: WatchFaceRuntimeData): String = "--"
}

object InsulinReservoirKind : TextComponentKind {
    override val id = "insulin_reservoir"
    override val label = "Reservatório restante"
    override fun baseSize(variation: Int) = SizeF(140f + variation * 3f, 34f)
    override fun textFor(component: WatchFaceComponent, runtime: WatchFaceRuntimeData): String = "--"
}

object InsulinPumpBatteryKind : TextComponentKind {
    override val id = "insulin_pump_battery"
    override val label = "Bateria da bomba"
    override fun baseSize(variation: Int) = SizeF(120f + variation * 3f, 34f)
    override fun textFor(component: WatchFaceComponent, runtime: WatchFaceRuntimeData): String = "--"
}

object InsulinPumpConnectedKind : StatusComponentKind {
    override val id = "insulin_pump_connected"
    override val label = "Bomba conectada"
    override fun baseSize(variation: Int) = SizeF(130f + variation * 3f, 42f)
    override fun statusText(runtime: WatchFaceRuntimeData): String = "Bomba OFF"
    override fun statusColor(component: WatchFaceComponent, runtime: WatchFaceRuntimeData): Long = 0xFFFF5252L
}

/** "+3 · 14:32" — usado só por [GlucoseDeltaKind]; antes duplicado em WatchFaceRenderer.kt e WatchFaceDesignGenerator.kt. */
private fun deltaText(reading: GlucoseReading, nowMs: Long, timeFormat: TimeFormat): String {
    val sign = if (reading.delta > 0) "+" else ""
    val measuredAtMs = if (reading.epochMs > 0L) reading.epochMs else nowMs - reading.minutesAgo * 60_000L
    val timeText = epochMsToTimeOfDay(measuredAtMs, timeFormat)
    return "$sign${reading.delta} · $timeText"
}

object ComponentRegistry {
    val all: List<ComponentKind> = listOf(
        TimeHmsKind, TimeHmKind, SecondsKind,
        DateDmKind, DateDmyKind, DateYmdKind, DateMdKind, MonthKind, YearKind, WeekdayKind,
        GlucoseCurrentKind, GlucoseDeltaKind, GlucoseUnitKind,
        LastReadingTimeKind, ReadingAgeKind, TargetRangeKind, HipoAlertKind, HiperAlertKind,
        FixedTextKind, SensorLifeKind, PhoneBatteryKind,
        GlucoseChartKind,
        SensorConnectedKind,
        // Beta — ver comentário acima de InsulinIobKind.
        InsulinIobKind, InsulinBasalRateKind, InsulinLastBolusKind,
        InsulinReservoirKind, InsulinPumpBatteryKind, InsulinPumpConnectedKind
    )

    private val byIdMap: Map<String, ComponentKind> = all.associateBy { it.id }

    /** Mesmo fallback do antigo WatchFaceComponentType.fromId(): id desconhecido cai em "hora e minuto". */
    fun byId(id: String): ComponentKind = byIdMap[id] ?: TimeHmKind
}
