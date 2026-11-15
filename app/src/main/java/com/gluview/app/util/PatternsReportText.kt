package com.gluview.app.util

import android.content.Context
import com.gluview.app.R
import com.gluview.app.data.health.HealthConnectSync
import com.gluview.app.data.health.HealthInsights
import com.gluview.app.data.health.PatternsResult
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Seção "Padrões" dos PDFs (plano 17): exercício, sono e ciclo cruzados com a glicemia. Só
 * observação — a mesma ressalva da tela vai junto. Puro (sem I/O).
 */
class PatternsReportText(private val res: Context, private val unit: GlucoseUnit) {
    private val locale: Locale get() = res.resources.configuration.locales[0] ?: Locale.getDefault()
    private fun s(id: Int, vararg a: Any) = res.getString(id, *a)
    private fun pct(v: Double) = String.format(locale, "%.0f%%", v)
    private fun tir(st: HealthInsights.Stats) = if (st.count > 0) pct(st.tirPct) else "—"
    private fun avg(st: HealthInsights.Stats) = if (st.count > 0) formatGlucoseWithUnit(st.avg, unit) else "—"

    fun lines(p: PatternsResult): List<Pair<String, Boolean>> = buildList {
        add(s(R.string.patterns_pdf_title, p.days) to true)
        add(s(R.string.patterns_disclaimer) to false)
        if (p.readings == 0) { add(s(R.string.patterns_no_glucose) to false); return@buildList }

        add(s(R.string.patterns_exercise) to true)
        if (p.exercise.isEmpty()) add("    " + s(R.string.patterns_pdf_no_data) to false)
        p.exercise.forEach { g ->
            add("    " + HealthConnectSync.exerciseLabel(res, g.typeKey) + " — " +
                s(R.string.patterns_sessions_value, g.sessions, g.minutes.roundToInt()) to false)
            add("        " + s(R.string.patterns_avg_during) + ": " + avg(g.during) + " · " +
                s(R.string.patterns_avg_after) + ": " + avg(g.after) to false)
            add("        " + s(R.string.patterns_hypo_after) + ": " + s(R.string.patterns_of_sessions, g.sessionsWithHypoAfter, g.sessions) to false)
        }

        add(s(R.string.patterns_sleep) to true)
        val sl = p.sleep
        if (sl.nights == 0) add("    " + s(R.string.patterns_pdf_no_data) to false)
        else {
            add("    " + s(R.string.patterns_nights) + ": " + sl.nights + " · " + s(R.string.patterns_night_hypos) + ": " +
                s(R.string.patterns_of_nights, sl.nightsWithHypo, sl.nights) to false)
            add("    " + s(R.string.patterns_tir_short) + ": " + tir(sl.shortSleep) + " · " + s(R.string.patterns_tir_long) + ": " + tir(sl.longSleep) to false)
        }

        add(s(R.string.patterns_cycle) to true)
        if (!p.hasCycleData) add("    " + s(R.string.patterns_pdf_no_data) to false)
        else p.cycle.forEach { ph ->
            val label = s(when (ph.phase) {
                HealthInsights.CyclePhase.MENSTRUAL -> R.string.patterns_phase_menstrual
                HealthInsights.CyclePhase.FOLLICULAR -> R.string.patterns_phase_follicular
                HealthInsights.CyclePhase.LUTEAL -> R.string.patterns_phase_luteal
            })
            add("    $label: " + s(R.string.patterns_avg) + " " + avg(ph.stats) + " · " + s(R.string.patterns_tir) + " " + tir(ph.stats) to false)
        }
    }
}
