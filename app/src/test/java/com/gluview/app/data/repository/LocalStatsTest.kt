package com.gluview.app.data.repository

import com.gluview.app.data.repository.LocalStats.Sample
import com.gluview.app.ui.screen.alertsettings.AlertSettingsViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/** Cálculo local das estatísticas (ADR-0030) com as mesmas regras do GluSync. */
class LocalStatsTest {

    private val zone = ZoneId.of("America/Sao_Paulo")
    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int = 0) =
        LocalDateTime.of(y, m, d, h, min).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `stats diarias agrupam pelo dia do fuso e usam faixa 70-180`() {
        val s = listOf(
            Sample(at(2026, 11, 1, 8), 60.0),   // baixa
            Sample(at(2026, 11, 1, 12), 120.0), // alvo
            Sample(at(2026, 11, 1, 18), 180.0), // alvo (inclusivo)
            Sample(at(2026, 11, 1, 22), 240.0), // alta
            Sample(at(2026, 11, 2, 0, 30), 100.0)
        )
        val d = LocalStats.dailyStats(s, zone)
        assertEquals(listOf("01/11/2026", "02/11/2026"), d.map { it.dia })
        val d1 = d[0]
        assertEquals(4, d1.totalLeituras)
        assertEquals(1, d1.leiturasBaixas)
        assertEquals(1, d1.leiturasAltas)
        assertEquals(50.0, d1.percentNoAlvo, 0.0)
        assertEquals(150.0, d1.media, 0.0)
        assertEquals(6.9, d1.hbA1cEst, 0.0) // (150 + 46,7) / 28,7 = 6,85…
    }

    @Test
    fun `relatorio de TIR faz media dos dias e porcentagens de hipo e hiper`() {
        val s = listOf(
            Sample(at(2026, 11, 1, 8), 60.0), Sample(at(2026, 11, 1, 9), 100.0),
            Sample(at(2026, 11, 2, 8), 200.0), Sample(at(2026, 11, 2, 9), 100.0)
        )
        val r = LocalStats.tirReport(s, 7, zone)
        assertEquals("últimos 7 dias", r.periodo)
        assertEquals(50.0, r.mediaTir, 0.0)
        assertEquals(50.0, r.dias[0].hipoglicemia, 0.0)
        assertEquals(0.0, r.dias[0].hiperglicemia, 0.0)
        assertEquals(50.0, r.dias[1].hiperglicemia, 0.0)
    }

    @Test
    fun `relatorio de TIR vazio sem leituras`() {
        val r = LocalStats.tirReport(emptyList(), 30, zone)
        assertTrue(r.dias.isEmpty())
        assertEquals(0.0, r.mediaTir, 0.0)
    }

    @Test
    fun `pivo manda as 23h para o slot 00h do dia seguinte`() {
        val s = listOf(
            Sample(at(2026, 11, 1, 23, 10), 200.0),
            Sample(at(2026, 11, 2, 0, 10), 100.0),
            Sample(at(2026, 11, 2, 13), 150.0)
        )
        val rows = LocalStats.pivot(s, zone)
        // 01/11 só tinha a leitura das 23h, que foi para 02/11 → só sobra o dia 02.
        assertEquals(listOf("02/11/2026"), rows.map { it.dia })
        val r = rows.single()
        assertEquals(150.0, r.h00, 0.0)  // média de 200 (23h do dia 1) e 100
        assertEquals(150.0, r.h12, 0.0)  // 13h cai no slot 12
        assertEquals(0.0, r.h03, 0.0)
        assertEquals(2, r.totalLeituras) // total/média são do dia do calendário
        assertEquals(125.0, r.mediaDia, 0.0)
    }

    @Test
    fun `padrao diario ignora slots sem dado`() {
        val rows = LocalStats.pivot(listOf(Sample(at(2026, 11, 2, 9), 120.0), Sample(at(2026, 11, 3, 9), 140.0)), zone)
        val p = LocalStats.dailyPattern(rows)
        assertEquals(8, p.size)
        assertEquals(130.0, p.first { it.slotHour == 9 }.avgSgv, 0.0)
        assertEquals(0.0, p.first { it.slotHour == 0 }.avgSgv, 0.0)
    }

    @Test
    fun `janela do mes corta em agora e recusa mes futuro`() {
        val now = at(2026, 11, 8, 10)
        val w = LocalStats.monthWindow(11, 2026, now, zone)!!
        assertEquals(at(2026, 11, 1, 0), w.first)
        assertEquals(now, w.second)
        assertEquals(at(2026, 11, 1, 0), LocalStats.monthWindow(10, 2026, now, zone)!!.second)
        assertNull(LocalStats.monthWindow(12, 2026, now, zone))
    }

    @Test
    fun `resumo do mes com pivo, mensal e HbA1c`() {
        val now = at(2026, 11, 8, 10)
        val w = LocalStats.monthWindow(11, 2026, now, zone)!!
        val month = listOf(
            Sample(at(2026, 10, 31, 23, 30), 90.0), // 23h do mês anterior → slot 00h de 01/11
            Sample(at(2026, 11, 1, 10), 110.0),
            Sample(at(2026, 11, 5, 10), 250.0)
        )
        val r = LocalStats.summary(11, 2026, w, month, month, zone, now)
        assertEquals("11/2026", r.cabecalho.periodo)
        assertEquals(listOf("01/11/2026", "05/11/2026"), r.tabelaPivot.map { it.dia })
        assertEquals(2, r.cabecalho.totalDias)
        val m = r.resumoMensal.single()
        assertEquals(2, m.totalLeituras) // a leitura de outubro não entra no mês
        assertEquals(2, m.totalDias)
        assertEquals(50.0, m.tirPercent, 0.0)
        assertNotNull(r.cabecalho.hbA1c90dias)
    }

    @Test
    fun `HbA1c nula sem leituras e classificacao igual ao servidor`() {
        assertNull(LocalStats.hbA1c(emptyList(), 90))
        val h = LocalStats.hbA1c(listOf(Sample(1L, 154.0)), 90)!! // (154+46,7)/28,7 = 6,99
        assertEquals(7.0, h.hbA1cEst, 0.0)
        assertEquals("good", h.classificacaoCode)
    }

    @Test
    fun `avisos pela nuvem so com envio liberado`() {
        assertTrue(AlertSettingsViewModel.cloudAlertsAllowedFor("approved"))
        assertTrue(AlertSettingsViewModel.cloudAlertsAllowedFor(""))
        assertFalse(AlertSettingsViewModel.cloudAlertsAllowedFor("pending"))
        assertFalse(AlertSettingsViewModel.cloudAlertsAllowedFor("none"))
        assertFalse(AlertSettingsViewModel.cloudAlertsAllowedFor("revoked"))
    }
}
