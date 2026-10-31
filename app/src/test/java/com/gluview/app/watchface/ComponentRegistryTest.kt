package com.gluview.app.watchface

import com.gluview.app.data.sibionics.SibionicsSensorStatus
import com.gluview.app.domain.model.GlucoseReading
import com.gluview.app.domain.model.GlucoseZone
import com.gluview.app.ui.screen.watchface.WatchFaceRuntimeData
import com.gluview.app.util.GlucoseUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regressão da troca do enum WatchFaceComponentType pelo registro ComponentKind/ComponentRegistry
 * (achado P3 do plano 06_watchfaces_v2_arquitetura.md) + os componentes de texto novos (seção 4
 * do pedido original: segundos, mês, ano, unidade, horário/idade da leitura, faixa de meta,
 * alertas hipo/hiper, texto fixo).
 */
class ComponentRegistryTest {

    private val allIds = setOf(
        "time_hms", "time_hm", "seconds",
        "date_dm", "date_dmy", "date_ymd", "date_md", "month", "year", "weekday",
        "glucose_current", "glucose_delta", "glucose_unit",
        "last_reading_time", "reading_age", "target_range", "hipo_alert", "hiper_alert",
        "fixed_text", "sensor_life", "phone_battery",
        "glucose_chart", "sensor_connected",
        // Beta (bomba de insulina) — ver comentário em ComponentKind.kt acima de InsulinIobKind.
        "insulin_iob", "insulin_basal_rate", "insulin_last_bolus",
        "insulin_reservoir", "insulin_pump_battery", "insulin_pump_connected"
    )

    private fun runtimeWith(
        reading: GlucoseReading? = null,
        hipo: Float = 70f,
        hiper: Float = 180f,
        batteryPercent: Int = 0,
        unit: GlucoseUnit = GlucoseUnit.MG_DL,
        nowMs: Long = 0L
    ) = WatchFaceRuntimeData(
        nowMs = nowMs, reading = reading, graphReadings = emptyList(),
        sensorStatus = SibionicsSensorStatus(), hipo = hipo, hiper = hiper,
        batteryPercent = batteryPercent, unit = unit
    )

    private fun reading(sgv: Double, delta: Int = 0, minutesAgo: Int = 0, epochMs: Long = 0L) = GlucoseReading(
        sgvMgDl = sgv, direction = "flat", arrow = "→", timestamp = "", zone = GlucoseZone.NORMAL,
        delta = delta, isStale = false, minutesAgo = minutesAgo, epochMs = epochMs
    )

    private fun component(kind: ComponentKind, customText: String? = null) = WatchFaceComponent(
        typeId = kind.id, variation = 1, label = kind.label,
        x = 0f, y = 0f, width = 100f, height = 40f, color = 0xFFFFFFFFL, font = "Sans",
        customText = customText
    )

    @Test
    fun `todos os ids continuam registrados, incluindo os 10 componentes de texto novos`() {
        assertEquals(allIds, ComponentRegistry.all.map { it.id }.toSet())
    }

    @Test
    fun `byId encontra cada kind pelo proprio id`() {
        ComponentRegistry.all.forEach { kind ->
            assertSame(kind, ComponentRegistry.byId(kind.id))
        }
    }

    @Test
    fun `byId com id desconhecido cai em TimeHmKind (mesmo fallback do antigo fromId)`() {
        assertSame(TimeHmKind, ComponentRegistry.byId("id_que_nao_existe"))
    }

    @Test
    fun `baseSize bate com as formulas antigas do WatchFaceComponentLibrary`() {
        assertEquals(SizeF(184f, 54f), TimeHmsKind.baseSize(1))
        assertEquals(SizeF(220f, 54f), TimeHmsKind.baseSize(10))
        assertEquals(SizeF(154f, 62f), TimeHmKind.baseSize(1))
        assertEquals(SizeF(133f, 38f), DateDmKind.baseSize(1))
        assertEquals(SizeF(133f, 38f), DateDmyKind.baseSize(1))
        assertEquals(SizeF(133f, 38f), DateYmdKind.baseSize(1))
        assertEquals(SizeF(133f, 38f), DateMdKind.baseSize(1))
        assertEquals(SizeF(133f, 38f), WeekdayKind.baseSize(1))
        assertEquals(SizeF(234f, 92f), GlucoseChartKind.baseSize(1))
        assertEquals(SizeF(155f, 70f), GlucoseCurrentKind.baseSize(1))
        assertEquals(SizeF(200f, 70f), GlucoseCurrentKind.baseSize(10))
        assertEquals(SizeF(133f, 34f), GlucoseDeltaKind.baseSize(1))
        assertEquals(SizeF(133f, 42f), SensorConnectedKind.baseSize(1))
        assertEquals(SizeF(163f, 42f), SensorLifeKind.baseSize(1))
    }

    @Test
    fun `WatchFaceComponentLibrary gera 10 definicoes por kind registrado`() {
        assertEquals(ComponentRegistry.all.size * 10, WatchFaceComponentLibrary.definitions.size)
    }

    @Test
    fun `definitionsFor filtra corretamente por kind`() {
        val defs = WatchFaceComponentLibrary.definitionsFor(GlucoseChartKind)
        assertEquals(10, defs.size)
        assertTrue(defs.all { it.kind === GlucoseChartKind })
    }

    @Test
    fun `PhoneBatteryKind formata o texto como porcentagem`() {
        val runtime = runtimeWith(batteryPercent = 42)
        assertEquals("42%", PhoneBatteryKind.textFor(component(PhoneBatteryKind), runtime))
    }

    @Test
    fun `GlucoseUnitKind mostra o rotulo da unidade configurada`() {
        assertEquals("mg/dL", GlucoseUnitKind.textFor(component(GlucoseUnitKind), runtimeWith(unit = GlucoseUnit.MG_DL)))
        assertEquals("mmol/L", GlucoseUnitKind.textFor(component(GlucoseUnitKind), runtimeWith(unit = GlucoseUnit.MMOL_L)))
    }

    @Test
    fun `TargetRangeKind formata hipo-hiper`() {
        val runtime = runtimeWith(hipo = 70f, hiper = 180f)
        assertEquals("70-180", TargetRangeKind.textFor(component(TargetRangeKind), runtime))
    }

    @Test
    fun `ReadingAgeKind mostra agora ou ha X min`() {
        assertEquals("agora", ReadingAgeKind.textFor(component(ReadingAgeKind), runtimeWith(reading = reading(100.0, minutesAgo = 0))))
        assertEquals("há 7min", ReadingAgeKind.textFor(component(ReadingAgeKind), runtimeWith(reading = reading(100.0, minutesAgo = 7))))
        assertEquals("--", ReadingAgeKind.textFor(component(ReadingAgeKind), runtimeWith(reading = null)))
    }

    @Test
    fun `HipoAlertKind so aparece abaixo do limiar`() {
        val runtime = runtimeWith(hipo = 70f, reading = reading(65.0))
        assertEquals("⚠ Baixo", HipoAlertKind.textFor(component(HipoAlertKind), runtime))
        assertEquals("", HipoAlertKind.textFor(component(HipoAlertKind), runtimeWith(hipo = 70f, reading = reading(75.0))))
        assertEquals("", HipoAlertKind.textFor(component(HipoAlertKind), runtimeWith(reading = null)))
    }

    @Test
    fun `HiperAlertKind so aparece acima do limiar`() {
        val runtime = runtimeWith(hiper = 180f, reading = reading(210.0))
        assertEquals("⚠ Alto", HiperAlertKind.textFor(component(HiperAlertKind), runtime))
        assertEquals("", HiperAlertKind.textFor(component(HiperAlertKind), runtimeWith(hiper = 180f, reading = reading(150.0))))
    }

    @Test
    fun `FixedTextKind mostra o customText do componente ou um padrao`() {
        val runtime = runtimeWith()
        assertEquals("Feliz aniversário!", FixedTextKind.textFor(component(FixedTextKind, customText = "Feliz aniversário!"), runtime))
        assertEquals("Texto", FixedTextKind.textFor(component(FixedTextKind), runtime))
    }

    @Test
    fun `LastReadingTimeKind usa epochMs quando presente`() {
        val runtime = runtimeWith(reading = reading(100.0, epochMs = 1_700_000_000_000L))
        // Só confere que não cai no fallback "--" quando há leitura.
        assertTrue(LastReadingTimeKind.textFor(component(LastReadingTimeKind), runtime) != "--")
        assertEquals("--", LastReadingTimeKind.textFor(component(LastReadingTimeKind), runtimeWith(reading = null)))
    }

    // Bomba de insulina (beta) — sem pipeline de dado real ainda (só lista de espera, ver
    // InsulinPumpViewModel.kt), então o "--"/"Bomba OFF" AQUI é o comportamento real de hoje, não
    // um placeholder temporário de teste. Quando o dado de verdade existir, estes testes precisam
    // mudar junto com o ComponentKind correspondente.
    @Test
    fun `componentes de bomba de insulina sempre mostram fallback, sem pipeline de dado real ainda`() {
        val runtime = runtimeWith()
        assertEquals("--", InsulinIobKind.textFor(component(InsulinIobKind), runtime))
        assertEquals("--", InsulinBasalRateKind.textFor(component(InsulinBasalRateKind), runtime))
        assertEquals("--", InsulinLastBolusKind.textFor(component(InsulinLastBolusKind), runtime))
        assertEquals("--", InsulinReservoirKind.textFor(component(InsulinReservoirKind), runtime))
        assertEquals("--", InsulinPumpBatteryKind.textFor(component(InsulinPumpBatteryKind), runtime))
        assertEquals("Bomba OFF", InsulinPumpConnectedKind.statusText(runtime))
        assertEquals(0xFFFF5252L, InsulinPumpConnectedKind.statusColor(component(InsulinPumpConnectedKind), runtime))
    }
}
