package com.gluview.therapy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TherapyCoreTest {
    @Test fun formatoInicial() = assertEquals(1, TherapyCore.FORMAT_VERSION)
}

class InsulinCurveTest {
    private val c = InsulinCurve(75.0, 5.0)

    @Test fun iobComecaEm1ETerminaEm0() {
        assertEquals(1.0, c.iobFraction(0.0), 1e-9)
        assertEquals(0.0, c.iobFraction(300.0), 1e-9)
        assertEquals(0.0, c.iobFraction(400.0), 1e-9)
    }

    @Test fun iobSoDesce() {
        var prev = 1.0
        for (t in 5..300 step 5) { val v = c.iobFraction(t.toDouble()); assertTrue("t=$t", v <= prev + 1e-12); prev = v }
    }

    @Test fun atividadeTemPicoNoPico() {
        val peak = (1..299).maxByOrNull { c.activity(it.toDouble()) }!!
        assertTrue("pico em $peak", peak in 70..80)
    }

    @Test fun integralDaAtividadeDaUmaUnidade() {
        val sum = (0 until 300).sumOf { c.activity(it + 0.5) }
        assertEquals(1.0, sum, 0.01)
    }

    @Test fun iobBateComAIntegralDaAtividade() {
        val absorbed = (0 until 120).sumOf { c.activity(it + 0.5) }
        assertEquals(1.0 - absorbed, c.iobFraction(120.0), 0.01)
    }

    @Test fun insulinaMaisRapidaAgeAntes() =
        assertTrue(InsulinCurve(45.0, 5.0).iobFraction(60.0) < c.iobFraction(60.0))
}

class ProfileTest {
    @Test fun blocosPorHorario() {
        val s = Schedule(listOf(Block(0, 40.0), Block(360, 50.0), Block(1320, 45.0)))
        assertEquals(40.0, s.at(0), 0.0); assertEquals(40.0, s.at(359), 0.0)
        assertEquals(50.0, s.at(360), 0.0); assertEquals(45.0, s.at(1439), 0.0)
    }

    @Test fun perfilPadraoValidoEProblemas() {
        assertTrue(TherapyProfile().problems().isEmpty())
        assertTrue(ProfileProblem.DIA_RANGE in TherapyProfile(diaHours = 4.0).problems())
        assertTrue(ProfileProblem.DIA_RANGE in TherapyProfile(diaHours = 10.0).problems())
        assertTrue(TherapyProfile(diaHours = 10.0, person = PersonType.PREGNANT).problems().isEmpty())
        assertTrue(ProfileProblem.TARGET_ORDER in TherapyProfile(targetLow = Schedule.single(130.0)).problems())
        assertTrue(ProfileProblem.IC_RANGE in TherapyProfile(ic = Schedule.single(1.0)).problems())
    }

    @Test fun bolusMaximoNuncaPassaDoLimiteFixo() {
        assertEquals(5.0, TherapyProfile(person = PersonType.CHILD, maxBolus = 30.0).effectiveMaxBolus, 0.0)
        assertEquals(8.0, TherapyProfile(person = PersonType.ADULT, maxBolus = 8.0).effectiveMaxBolus, 0.0)
    }

    @Test fun codecIdaEVoltaELixo() {
        val p = TherapyProfile(name = "Dia", person = PersonType.TEEN, isf = Schedule(listOf(Block(0, 40.0), Block(600, 55.5))),
            insulin = InsulinPreset.CUSTOM, customPeakMin = 60.0, penStep = 1.0, concentration = 2)
        assertEquals(p, ProfileCodec.decode(ProfileCodec.encode(p)))
        assertNull(ProfileCodec.decode("v=1\nname=x"))
        assertNull(ProfileCodec.decode(null))
    }
}

class OnBoardTest {
    private val now = 1_800_000_000_000L
    private val curve = InsulinCurve(75.0, 5.0)

    @Test fun iobSomaDoses() {
        val r = OnBoard.iob(listOf(Dose(now, 2.0), Dose(now - 300 * 60_000L, 5.0)), now, curve)
        assertEquals(2.0, r.iob, 1e-9)
    }

    @Test fun cobLinear() {
        val c = listOf(Carbs(now - 90 * 60_000L, 60.0))
        assertEquals(30.0, OnBoard.cob(c, now, 180), 1e-9)
        assertEquals(0.0, OnBoard.cob(c, now + 91 * 60_000L, 180), 1e-9)
        assertEquals(60.0, OnBoard.cob(listOf(Carbs(now + 60_000L, 60.0)), now, 180), 1e-9)
    }
}

class BolusCalculatorTest {
    private val now = 1_800_000_000_000L
    private val p = TherapyProfile(isf = Schedule.single(50.0), ic = Schedule.single(10.0),
        targetLow = Schedule.single(100.0), targetHigh = Schedule.single(120.0), penStep = 0.5, maxBolus = 10.0)
    private fun calc(carbs: Double, bg: Double?, age: Int = 2, doses: List<Dose> = emptyList(), warm: Boolean = false, prof: TherapyProfile = p) =
        BolusCalculator.calculate(BolusInput(carbs, bg?.let { CurrentGlucose(it, age) }, sensorWarmup = warm), prof, doses, emptyList(), now, 600)

    @Test fun soCarbo() { val r = calc(45.0, 110.0); assertEquals(4.5, r.suggestedUnits, 0.0); assertEquals(0.0, r.fromCorrection, 0.0) }

    @Test fun correcaoAcimaDoAlvo() {
        val r = calc(0.0, 220.0) // (220−120)/50 = 2
        assertEquals(2.0, r.fromCorrection, 1e-9); assertEquals(2.0, r.suggestedUnits, 0.0); assertEquals(120.0, r.target, 0.0)
    }

    @Test fun iobDescontado() {
        val r = calc(30.0, 110.0, doses = listOf(Dose(now, 1.0)))
        assertEquals(2.0, r.suggestedUnits, 0.0)
    }

    @Test fun glicemiaBaixaNuncaSugereInsulina() {
        val r = calc(60.0, 65.0)
        assertEquals(0.0, r.suggestedUnits, 0.0)
        assertTrue(BolusWarning.LOW_GLUCOSE_NO_INSULIN in r.warnings)
    }

    @Test fun abaixoDoAlvoComIobPedeCarbo() {
        val r = calc(0.0, 90.0, doses = listOf(Dose(now, 1.0)))
        assertEquals(0.0, r.suggestedUnits, 0.0)
        assertTrue(r.carbsNeededG > 10.0)
        assertTrue(BolusWarning.MORE_CARBS_SUGGESTED in r.warnings)
    }

    @Test fun glicemiaVelhaOuAusenteOuAquecendoSemCorrecao() {
        assertTrue(BolusWarning.GLUCOSE_OLD in calc(0.0, 250.0, age = 20).warnings)
        assertEquals(0.0, calc(0.0, 250.0, age = 20).suggestedUnits, 0.0)
        assertTrue(BolusWarning.NO_GLUCOSE in calc(30.0, null).warnings)
        assertEquals(3.0, calc(30.0, null).suggestedUnits, 0.0)
        assertTrue(BolusWarning.SENSOR_WARMUP in calc(0.0, 250.0, warm = true).warnings)
    }

    @Test fun arredondaParaBaixoELimita() {
        val r = calc(47.0, 110.0) // 4,7 → 4,5
        assertEquals(4.5, r.suggestedUnits, 0.0); assertTrue(BolusWarning.ROUNDED_DOWN in r.warnings)
        val big = calc(200.0, 110.0)
        assertEquals(10.0, big.suggestedUnits, 0.0); assertTrue(BolusWarning.CAPPED_AT_MAX in big.warnings)
        val child = calc(200.0, 110.0, prof = p.copy(person = PersonType.CHILD, maxBolus = 20.0))
        assertEquals(5.0, child.suggestedUnits, 0.0)
    }

    @Test fun perfilInvalidoNaoCalcula() {
        val r = calc(50.0, 200.0, prof = p.copy(diaHours = 3.0))
        assertEquals(setOf(BolusWarning.PROFILE_INVALID), r.warnings); assertEquals(0.0, r.suggestedUnits, 0.0)
    }
}

class PredictionTest {
    private val now = 1_800_000_000_000L
    private val p = TherapyProfile()

    @Test fun semInsulinaNemCarboEEstavelFicaIgual() {
        val r = Prediction.predict(now, 120.0, 0.0, p, 600, emptyList(), emptyList())
        assertEquals(36, r.iob.size)
        assertEquals(120.0, r.iob.last().mgDl, 1e-6)
    }

    @Test fun insulinaDerrubaECarboSobe() {
        val ins = Prediction.predict(now, 150.0, 0.0, p, 600, listOf(Dose(now, 2.0)), emptyList())
        assertTrue(ins.iob.last().mgDl < 150.0 - 80.0) // 2 U × 50 = 100 mg/dL ao longo do DIA (parte em 3 h)
        val both = Prediction.predict(now, 150.0, 0.0, p, 600, listOf(Dose(now, 2.0)), listOf(Carbs(now, 20.0)))
        assertTrue(both.cob.last().mgDl > ins.iob.last().mgDl)
        assertFalse(both.low.zip(both.high).any { (l, h) -> l.mgDl > h.mgDl })
    }

    @Test fun refeicaoComBolusNaoAssusta() {
        // 4,5 U para 45 g (IC 10): a curva só-insulina despenca, mas a faixa (com carbo) não.
        val r = Prediction.predict(now, 120.0, 0.0, p, 600, listOf(Dose(now, 4.5)), listOf(Carbs(now, 45.0)))
        assertTrue(r.iob.minOf { it.mgDl } < 60.0)
        assertTrue(r.low.minOf { it.mgDl } > 80.0)
    }

    @Test fun calculadoraLogoDepoisDeComerNaoPedeCarbo() {
        val r = BolusCalculator.calculate(BolusInput(0.0, CurrentGlucose(120.0, 1)), p,
            listOf(Dose(now, 4.5)), listOf(Carbs(now, 45.0)), now + 60_000L, 600)
        assertTrue(r.carbsNeededG < 1.0)
        assertEquals(0.0, r.fromCob, 0.0) // COB fora da dose por padrão (como no AAPS)
    }

    @Test fun mesmaRefeicaoDigitadaDeNovoNaoDobraPorPadrao() {
        // A refeição de 45 g já está no diário e a pessoa digita 45 g de novo na calculadora.
        val r = BolusCalculator.calculate(BolusInput(45.0, CurrentGlucose(110.0, 1)), p, emptyList(), listOf(Carbs(now, 45.0)), now, 600)
        assertEquals(4.5, r.suggestedUnits, 0.0)
    }

    @Test fun limitesEAlertaPreditivo() {
        val r = Prediction.predict(now, 80.0, -6.0, p, 600, listOf(Dose(now, 5.0)), emptyList())
        assertTrue(r.low.all { it.mgDl >= 39.0 })
        val m = Prediction.minutesUntilBelow(r, now, 70.0)
        assertTrue(m != null && m <= 30)
    }
}
