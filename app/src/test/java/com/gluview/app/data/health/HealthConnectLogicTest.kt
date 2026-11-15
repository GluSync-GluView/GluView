package com.gluview.app.data.health

import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.metadata.Device
import com.gluview.app.data.datastore.HealthConnectToggles
import com.gluview.app.data.local.entity.EventLogEntity
import com.gluview.app.data.local.entity.HealthRecordEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/** Plano 17, Fase 3 — conversões do Health Connect e cruzamentos. */
class HealthConnectLogicTest {
    private val zone = ZoneId.of("America/Sao_Paulo")
    private val device = Device(Device.TYPE_PHONE, "Samsung", "SM-S711B")
    private val h = 3_600_000L
    private val d = 24 * h

    @Test fun `leitura do sensor vira glicemia intersticial com id estavel`() {
        val r = HealthConnectMapper.sensorGlucose(1_700_000_000_000, 123.0, device, zone)!!
        assertEquals(BloodGlucoseRecord.SPECIMEN_SOURCE_INTERSTITIAL_FLUID, r.specimenSource)
        assertEquals(123.0, r.level.inMilligramsPerDeciliter, 1e-9)
        assertEquals("gluview-sgv-1700000000000", r.metadata.clientRecordId)
        assertNull(HealthConnectMapper.sensorGlucose(1, 5.0, device, zone)) // fora da faixa aceita
    }

    @Test fun `ponta de dedo e refeicao do diario`() {
        val f = HealthConnectMapper.fingerstick(EventLogEntity(type = "fingerstick", value = 110.0, uuid = "u1", updatedAt = 7), zone)!!
        assertEquals(BloodGlucoseRecord.SPECIMEN_SOURCE_CAPILLARY_BLOOD, f.specimenSource)
        assertEquals("gluview-diary-u1", f.metadata.clientRecordId)
        assertEquals(7L, f.metadata.clientRecordVersion)
        val m = HealthConnectMapper.meal(EventLogEntity(type = "meal", value = 45.0, kcal = 400.0, uuid = "u2"), zone)!!
        assertEquals(45.0, m.totalCarbohydrate!!.inGrams, 1e-9)
        assertEquals(400.0, m.energy!!.inKilocalories, 1e-6)
        assertNull(HealthConnectMapper.meal(EventLogEntity(type = "meal", value = 0.0), zone))
    }

    @Test fun `permissoes so do que esta ligado`() {
        assertTrue(HealthConnectGateway.permissionsFor(HealthConnectToggles(), true).isEmpty())
        val p = HealthConnectGateway.permissionsFor(HealthConnectToggles(writeGlucose = true, readCycle = true), true)
        assertEquals(setOf(
            "android.permission.health.WRITE_BLOOD_GLUCOSE", "android.permission.health.READ_MENSTRUATION",
            HealthConnectGateway.READ_BACKGROUND
        ), p)
        // Só gravar não pede leitura em segundo plano.
        assertEquals(setOf("android.permission.health.WRITE_BLOOD_GLUCOSE"),
            HealthConnectGateway.permissionsFor(HealthConnectToggles(writeGlucose = true), true))
    }

    private fun pts(vararg p: Pair<Long, Double>) = p.map { HealthInsights.Point(it.first, it.second) }

    @Test fun `exercicio agrupa por tipo e conta hipo nas 24h seguintes`() {
        val s1 = HealthRecordEntity(hcId = "a", kind = "exercise", startMs = 0, endMs = h, value = 60.0, subtype = "running")
        val s2 = HealthRecordEntity(hcId = "b", kind = "exercise", startMs = 2 * d, endMs = 2 * d + h, value = 60.0, subtype = "running")
        val points = pts(h / 2 to 150.0, 2 * h to 65.0, 2 * d + h / 2 to 130.0, 2 * d + 2 * h to 110.0)
        val g = HealthInsights.exercise(listOf(s1, s2), points, 70.0, 180.0).single()
        assertEquals(2, g.sessions)
        assertEquals(140.0, g.during.avg, 1e-9)
        assertEquals(65.0, g.after.min, 1e-9)
        assertEquals(1, g.sessionsWithHypoAfter)
    }

    @Test fun `sono curto contra sono longo`() {
        val short = HealthRecordEntity(hcId = "s", kind = "sleep", startMs = 0, endMs = 5 * h, value = 300.0)
        val long = HealthRecordEntity(hcId = "l", kind = "sleep", startMs = d, endMs = d + 8 * h, value = 480.0)
        val points = pts(h to 200.0, 2 * h to 210.0, d + h to 120.0, d + 2 * h to 60.0)
        val s = HealthInsights.sleep(listOf(short, long), points, 70.0, 180.0)
        assertEquals(2, s.nights)
        assertEquals(1, s.shortNights)
        assertEquals(0.0, s.shortSleep.tirPct, 1e-9)
        assertEquals(50.0, s.longSleep.tirPct, 1e-9)
        assertEquals(1, s.nightsWithHypo)
    }

    @Test fun `fases do ciclo pelos dias desde o inicio`() {
        val starts = listOf(10 * d)
        assertNull(HealthInsights.phaseAt(5 * d, starts))
        assertEquals(HealthInsights.CyclePhase.MENSTRUAL, HealthInsights.phaseAt(10 * d + h, starts))
        assertEquals(HealthInsights.CyclePhase.FOLLICULAR, HealthInsights.phaseAt(16 * d, starts))
        assertEquals(HealthInsights.CyclePhase.LUTEAL, HealthInsights.phaseAt(25 * d, starts))
        assertNull(HealthInsights.phaseAt(50 * d, starts))
        // Período informado + fluxo do mesmo ciclo viram um início só.
        val p = HealthRecordEntity(hcId = "p", kind = "period", startMs = 10 * d, endMs = 14 * d)
        val f = HealthRecordEntity(hcId = "f", kind = "flow", startMs = 10 * d + h, endMs = 10 * d + h, value = 2.0)
        assertEquals(listOf(10 * d), HealthInsights.periodStarts(listOf(p), listOf(f)))
    }
}
