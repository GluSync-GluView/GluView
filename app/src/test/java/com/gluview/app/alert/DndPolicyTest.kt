package com.gluview.app.alert

import com.gluview.app.data.datastore.AppPreferences
import com.gluview.app.domain.model.GlucoseZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PT-5 — matriz (tipo de alerta × configuração × estado do Não Perturbe × permissão). Cada célula
 * tem o resultado esperado; e em nenhuma célula o app diz que furou o DND sem poder furar, nem
 * deixa de registrar o que o DND fez com o alerta.
 */
class DndPolicyTest {

    private val bools = listOf(false, true)

    private fun expected(dnd: DndState, wantsBypass: Boolean, vibrate: Boolean, ready: Boolean, alarms: Boolean): DndOutcome = when {
        !dnd.active                                       -> DndOutcome.NORMAL
        wantsBypass && dnd == DndState.ALARMS_ONLY        -> DndOutcome.BYPASS
        wantsBypass && dnd == DndState.PRIORITY && (ready || alarms) -> DndOutcome.BYPASS
        wantsBypass && dnd == DndState.TOTAL_SILENCE      -> DndOutcome.TOTAL_SILENCE_BLOCKED
        wantsBypass                                       -> DndOutcome.BYPASS_UNAVAILABLE
        vibrate                                           -> DndOutcome.VIBRATE_ONLY
        else                                              -> DndOutcome.SUPPRESSED
    }

    @Test fun `matriz completa por faixa de alerta`() {
        var cells = 0
        for (zone in ALERT_ZONES) for (dnd in DndState.entries) for (wants in bools) for (vib in bools) for (ready in bools) for (alarms in bools) {
            val d = DndPolicy.decide(dnd, wants, vib, ready, alarms)
            val cell = "$zone dnd=$dnd furar=$wants vibrar=$vib pronto=$ready alarmes=$alarms"
            assertEquals(cell, expected(dnd, wants, vib, ready, alarms), d.outcome)
            cells++
        }
        assertEquals(ALERT_ZONES.size * DndState.entries.size * 16, cells)
    }

    @Test fun `sem Nao Perturbe nada muda em relacao ao que ja acontecia`() {
        for (wants in bools) for (vib in bools) for (ready in bools) {
            val d = DndPolicy.decide(DndState.OFF, wants, vib, ready)
            assertEquals(DndOutcome.NORMAL, d.outcome)
            assertEquals(wants, d.postBypassChannel)
            assertFalse(d.vibrate)
        }
    }

    @Test fun `nunca finge que furou o Nao Perturbe`() {
        // Prioridade com alarmes bloqueados e sem acesso: não fura.
        for (wants in bools) for (vib in bools) {
            val d = DndPolicy.decide(DndState.PRIORITY, wants, vib, bypassReady = false, alarmsAllowed = false)
            assertTrue(d.outcome != DndOutcome.BYPASS)
        }
        for (wants in bools) for (vib in bools) for (ready in bools) for (alarms in bools) {
            assertTrue(DndPolicy.decide(DndState.TOTAL_SILENCE, wants, vib, ready, alarms).outcome != DndOutcome.BYPASS)
        }
    }

    @Test fun `com Nao Perturbe ligado o historico sempre diz o que aconteceu`() {
        for (dnd in DndState.entries.filter { it.active }) for (wants in bools) for (vib in bools) for (ready in bools) for (alarms in bools) {
            assertNotNull(DndPolicy.decide(dnd, wants, vib, ready, alarms).outcome.logType)
        }
    }

    @Test fun `alerta critico passa sem acesso quando o DND deixa alarmes`() {
        // Visto no emulador (15/11/2026): prioridade com alarmes liberados, sem acesso -> mIntercept=false.
        assertEquals(DndOutcome.BYPASS, DndPolicy.decide(DndState.PRIORITY, true, false, bypassReady = false, alarmsAllowed = true).outcome)
        assertEquals(DndOutcome.BYPASS, DndPolicy.decide(DndState.ALARMS_ONLY, true, false, bypassReady = false, alarmsAllowed = false).outcome)
    }

    @Test fun `quis furar e nao pode - publica no canal e tenta vibrar`() {
        val d = DndPolicy.decide(DndState.PRIORITY, wantsBypass = true, vibrateWhenQuiet = false, bypassReady = false)
        assertEquals(DndOutcome.BYPASS_UNAVAILABLE, d.outcome)
        assertTrue(d.postBypassChannel)
        assertTrue(d.vibrate)
    }

    @Test fun `respeitar o Nao Perturbe e escolha da pessoa`() {
        val mute = DndPolicy.decide(DndState.PRIORITY, wantsBypass = false, vibrateWhenQuiet = false, bypassReady = true)
        assertEquals(DndOutcome.SUPPRESSED, mute.outcome)
        assertFalse(mute.postBypassChannel); assertFalse(mute.vibrate)
        val vib = DndPolicy.decide(DndState.ALARMS_ONLY, wantsBypass = false, vibrateWhenQuiet = true, bypassReady = true)
        assertEquals(DndOutcome.VIBRATE_ONLY, vib.outcome)
        assertTrue(vib.vibrate)
    }

    @Test fun `padroes - hipo urgente fura e vibrar nasce desligado`() {
        assertTrue(AppPreferences.defaultAlertEnabled(GlucoseZone.URGENT_LOW, AlertType.DND))
        ALERT_ZONES.forEach { assertFalse(AppPreferences.defaultAlertEnabled(it, AlertType.DND_VIBRATE)) }
        // As demais faixas continuam como antes (respeitam o DND).
        listOf(GlucoseZone.LOW, GlucoseZone.HIGH, GlucoseZone.URGENT_HIGH, GlucoseZone.STALE, GlucoseZone.QUICK_DROP)
            .forEach { assertFalse(AppPreferences.defaultAlertEnabled(it, AlertType.DND)) }
    }
}
