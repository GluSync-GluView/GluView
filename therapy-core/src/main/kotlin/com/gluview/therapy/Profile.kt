package com.gluview.therapy

/**
 * Tipo de pessoa e os LIMITES FIXOS do AndroidAPS (estudo 22, §6). O GluView copia sem afrouxar,
 * inclusive na calculadora para caneta.
 */
enum class PersonType(val maxBolus: Double, val maxIobSmb: Double, val maxBasal: Double, val maxDiaHours: Double, val minIc: Double) {
    CHILD(5.0, 7.0, 2.0, 9.0, 2.0),
    TEEN(10.0, 13.0, 5.0, 9.0, 2.0),
    ADULT(17.0, 22.0, 10.0, 9.0, 2.0),
    INSULIN_RESISTANT(25.0, 30.0, 12.0, 9.0, 2.0),
    PREGNANT(60.0, 70.0, 25.0, 10.0, 0.3);

    val minDiaHours: Double get() = 5.0
    val maxIc: Double get() = 100.0
}

/** Um valor que vale a partir de [startMin] (minuto do dia, 0–1439) até o próximo bloco. */
data class Block(val startMin: Int, val value: Double)

/** Lista de blocos por horário; o primeiro sempre começa em 0 h. */
data class Schedule(val blocks: List<Block>) {
    init {
        require(blocks.isNotEmpty() && blocks.first().startMin == 0) { "o primeiro horário tem de começar à 0 h" }
        require(blocks.zipWithNext().all { (x, y) -> y.startMin > x.startMin }) { "horários fora de ordem ou repetidos" }
        require(blocks.all { it.startMin in 0..1439 && it.value.isFinite() }) { "horário ou valor inválido" }
    }

    fun at(minuteOfDay: Int): Double = blocks.last { it.startMin <= ((minuteOfDay % 1440) + 1440) % 1440 }.value

    companion object { fun single(v: Double) = Schedule(listOf(Block(0, v))) }
}

/**
 * Perfil terapêutico (F4.1). Unidades internas sempre em mg/dL (a tela converte para mmol/L).
 * @param isf quanto 1 U baixa a glicemia (mg/dL por U)
 * @param ic gramas de carboidrato cobertas por 1 U
 * @param targetLow/targetHigh faixa-alvo da correção (mg/dL)
 * @param penStep passo da caneta (0,5 ou 1 U) — a dose sugerida é arredondada PARA BAIXO nele
 */
data class TherapyProfile(
    val name: String = "Perfil",
    val person: PersonType = PersonType.ADULT,
    val isf: Schedule = Schedule.single(50.0),
    val ic: Schedule = Schedule.single(10.0),
    val targetLow: Schedule = Schedule.single(100.0),
    val targetHigh: Schedule = Schedule.single(120.0),
    val insulin: InsulinPreset = InsulinPreset.NOVORAPID,
    val customPeakMin: Double = 75.0,
    val diaHours: Double = 5.0,
    val carbAbsorptionMin: Int = 180,
    val penStep: Double = 0.5,
    /** Limite de bolus escolhido pela pessoa; nunca passa do limite fixo do [person]. */
    val maxBolus: Double = 10.0,
    /** Concentração (U100 = 1, U200 = 2): só muda o que a caneta mostra, não a dose em unidades. */
    val concentration: Int = 1
) {
    val curve: InsulinCurve get() = InsulinCurve(if (insulin == InsulinPreset.CUSTOM) customPeakMin else insulin.peakMin, diaHours)
    val effectiveMaxBolus: Double get() = minOf(maxBolus, person.maxBolus)

    /** Problemas do perfil em palavras (vazio = válido). A calculadora não sugere dose com perfil inválido. */
    fun problems(): List<ProfileProblem> = buildList {
        if (isf.blocks.any { it.value !in 2.0..1000.0 }) add(ProfileProblem.ISF_RANGE)
        if (ic.blocks.any { it.value !in person.minIc..person.maxIc }) add(ProfileProblem.IC_RANGE)
        if (targetLow.blocks.any { it.value !in 72.0..180.0 } || targetHigh.blocks.any { it.value !in 72.0..180.0 }) add(ProfileProblem.TARGET_RANGE)
        for (m in (targetLow.blocks + targetHigh.blocks).map { it.startMin }.distinct()) {
            if (targetLow.at(m) > targetHigh.at(m)) { add(ProfileProblem.TARGET_ORDER); break }
        }
        if (diaHours < person.minDiaHours || diaHours > person.maxDiaHours) add(ProfileProblem.DIA_RANGE)
        if (insulin == InsulinPreset.CUSTOM && customPeakMin !in 35.0..120.0) add(ProfileProblem.PEAK_RANGE)
        if (carbAbsorptionMin !in 60..480) add(ProfileProblem.ABSORPTION_RANGE)
        if (penStep !in listOf(0.05, 0.1, 0.5, 1.0)) add(ProfileProblem.PEN_STEP)
        if (maxBolus <= 0.0) add(ProfileProblem.MAX_BOLUS)
        if (concentration !in listOf(1, 2, 5)) add(ProfileProblem.CONCENTRATION)
    }
}

enum class ProfileProblem { ISF_RANGE, IC_RANGE, TARGET_RANGE, TARGET_ORDER, DIA_RANGE, PEAK_RANGE, ABSORPTION_RANGE, PEN_STEP, MAX_BOLUS, CONCENTRATION }

/** Formato de texto do perfil (guardado nas preferências e no backup): `chave=valor` por linha. */
object ProfileCodec {
    fun encode(p: TherapyProfile): String = listOf(
        "v=1", "name=${p.name.replace('\n', ' ')}", "person=${p.person.name}",
        "isf=${sched(p.isf)}", "ic=${sched(p.ic)}", "tlow=${sched(p.targetLow)}", "thigh=${sched(p.targetHigh)}",
        "insulin=${p.insulin.name}", "peak=${p.customPeakMin}", "dia=${p.diaHours}", "abs=${p.carbAbsorptionMin}",
        "step=${p.penStep}", "maxbolus=${p.maxBolus}", "conc=${p.concentration}"
    ).joinToString("\n")

    private fun sched(s: Schedule) = s.blocks.joinToString(",") { "${it.startMin}:${it.value}" }

    /** null se o texto estiver corrompido (nunca inventa perfil a partir de lixo). */
    fun decode(text: String?): TherapyProfile? = runCatching {
        if (text.isNullOrBlank()) return null
        val m = text.lines().mapNotNull { l -> l.indexOf('=').takeIf { it > 0 }?.let { l.substring(0, it) to l.substring(it + 1) } }.toMap()
        if (m["v"] != "1") return null
        fun s(k: String) = Schedule(m.getValue(k).split(',').map { b -> b.split(':').let { Block(it[0].toInt(), it[1].toDouble()) } })
        TherapyProfile(
            name = m["name"].orEmpty(), person = PersonType.valueOf(m.getValue("person")),
            isf = s("isf"), ic = s("ic"), targetLow = s("tlow"), targetHigh = s("thigh"),
            insulin = InsulinPreset.valueOf(m.getValue("insulin")), customPeakMin = m.getValue("peak").toDouble(),
            diaHours = m.getValue("dia").toDouble(), carbAbsorptionMin = m.getValue("abs").toInt(),
            penStep = m.getValue("step").toDouble(), maxBolus = m.getValue("maxbolus").toDouble(),
            concentration = m["conc"]?.toInt() ?: 1
        )
    }.getOrNull()
}
