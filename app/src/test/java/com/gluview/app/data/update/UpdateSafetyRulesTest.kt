package com.gluview.app.data.update

import com.gluview.app.data.update.UpdateSafetyRules.Decision
import com.gluview.app.data.update.UpdateSafetyRules.Reason
import com.gluview.app.domain.model.GlucoseZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Porteiro do atualizador (plano OTA, seção 5.5). Decisão do mantenedor (24/10/2026): nunca bloqueia —
 * todo motivo vira pedido de confirmação ("Atualizar agora" / "Mais tarde").
 */
class UpdateSafetyRulesTest {

    private val now = 1_800_000_000_000L

    private fun input(
        zone: GlucoseZone? = GlucoseZone.NORMAL,
        ageMin: Long = 2,
        direction: String = "Flat",
        delta: Int = 0,
        recent: List<Double> = listOf(110.0, 112.0),
        hour: Int = 14,
        alert: Boolean = false,
        snoozed: Boolean = false,
        setup: Boolean = false
    ) = UpdateSafetyRules.Input(
        nowMs = now, hourOfDay = hour, latestZone = zone, latestEpochMs = now - ageMin * 60_000,
        latestDirection = direction, latestDelta = delta, recentSgv = recent, lowThreshold = 70.0,
        activeAlertNotification = alert, snoozed = snoozed, sensorSetupInProgress = setup
    )

    private fun reasons(d: Decision) = (d as? Decision.Confirm)?.reasons ?: emptyList()

    @Test fun estavelDeDia_naoPergunta() = assertEquals(Decision.Free, UpdateSafetyRules.evaluate(input()))

    @Test fun nadaBloqueia_tudoViraConfirmacao() {
        val pior = UpdateSafetyRules.evaluate(input(zone = GlucoseZone.URGENT_LOW, direction = "DoubleDown",
            recent = listOf(50.0), hour = 3, alert = true, snoozed = true, setup = true))
        assertTrue(pior is Decision.Confirm)
    }

    @Test fun hipo_pedeConfirmacao() {
        for (z in listOf(GlucoseZone.URGENT_LOW, GlucoseZone.LOW, GlucoseZone.QUICK_DROP)) {
            assertTrue("$z", Reason.LOW_GLUCOSE in reasons(UpdateSafetyRules.evaluate(input(zone = z))))
        }
    }

    @Test fun baixaRecenteMesmoComLeituraAtualNormal() =
        assertEquals(listOf(Reason.RECENT_LOW), reasons(UpdateSafetyRules.evaluate(input(recent = listOf(65.0, 80.0)))))

    @Test fun quedaPorSetaOuDelta() {
        assertTrue(Reason.FALLING in reasons(UpdateSafetyRules.evaluate(input(direction = "SingleDown"))))
        assertTrue(Reason.FALLING in reasons(UpdateSafetyRules.evaluate(input(direction = "DoubleDown"))))
        assertTrue(Reason.FALLING in reasons(UpdateSafetyRules.evaluate(input(delta = -12))))
        assertEquals(Decision.Free, UpdateSafetyRules.evaluate(input(direction = "FortyFiveDown", delta = -4)))
    }

    @Test fun alertaSonecaSetup() {
        assertTrue(Reason.ACTIVE_ALERT in reasons(UpdateSafetyRules.evaluate(input(alert = true))))
        assertTrue(Reason.SNOOZED in reasons(UpdateSafetyRules.evaluate(input(snoozed = true))))
        assertTrue(Reason.SENSOR_SETUP in reasons(UpdateSafetyRules.evaluate(input(setup = true))))
    }

    @Test fun madrugada_23hAte6h59() {
        for (h in listOf(23, 0, 3, 6)) assertTrue("$h", Reason.NIGHT in reasons(UpdateSafetyRules.evaluate(input(hour = h))))
        assertEquals(Decision.Free, UpdateSafetyRules.evaluate(input(hour = 7)))
        assertEquals(Decision.Free, UpdateSafetyRules.evaluate(input(hour = 22)))
    }

    @Test fun semLeituraRecente() {
        assertEquals(listOf(Reason.NO_RECENT_READING), reasons(UpdateSafetyRules.evaluate(input(ageMin = 40, recent = emptyList()))))
        assertEquals(listOf(Reason.NO_RECENT_READING), reasons(UpdateSafetyRules.evaluate(input(zone = null, recent = emptyList()))))
    }

    @Test fun altaESemSinal() {
        assertEquals(listOf(Reason.HIGH_GLUCOSE), reasons(UpdateSafetyRules.evaluate(input(zone = GlucoseZone.URGENT_HIGH))))
        assertEquals(listOf(Reason.STALE), reasons(UpdateSafetyRules.evaluate(input(zone = GlucoseZone.STALE))))
    }

    @Test fun motivosNaOrdemDeGravidade() {
        val d = UpdateSafetyRules.evaluate(input(zone = GlucoseZone.HIGH, snoozed = true, hour = 2, direction = "SingleDown"))
        assertEquals(listOf(Reason.FALLING, Reason.SNOOZED, Reason.NIGHT, Reason.HIGH_GLUCOSE), reasons(d))
    }
}
