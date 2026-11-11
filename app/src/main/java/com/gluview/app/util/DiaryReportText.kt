package com.gluview.app.util

import android.content.Context
import com.gluview.app.R
import com.gluview.app.data.diary.DaySummary
import com.gluview.app.data.local.entity.EventLogEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * Linhas do PDF do diário (plano 17, F1.7): por dia, os totais (carboidrato, insulina rápida e
 * basal) e cada registro com a hora e a glicemia do momento. Puro (sem I/O).
 */
class DiaryReportText(
    private val res: Context,
    private val unit: GlucoseUnit,
    private val timeFormat: TimeFormat,
    private val zone: ZoneId = ZoneId.systemDefault()
) {
    private val text = DiaryText(res, unit)
    private val locale: Locale get() = res.resources.configuration.locales[0] ?: Locale.getDefault()

    fun lines(entries: List<EventLogEntity>, first: LocalDate, last: LocalDate, includeTitle: Boolean = true): List<Pair<String, Boolean>> = buildList {
        if (includeTitle) {
            add(res.getString(R.string.diary_pdf_title) to true)
            add(res.getString(R.string.diary_pdf_period,
                formatDate(first.atStartOfDay(zone).toInstant().toEpochMilli(), locale, zone),
                formatDate(last.atStartOfDay(zone).toInstant().toEpochMilli(), locale, zone)) to false)
        } else add(res.getString(R.string.diary_pdf_section) to true)
        val total = DaySummary.of(entries)
        add(res.getString(R.string.diary_pdf_totals, text.amount(total.carbsG), text.amount(total.rapidU + total.mixedU), text.amount(total.basalU)) to false)
        entries.groupBy { Instant.ofEpochMilli(it.occurredAt).atZone(zone).toLocalDate() }.toSortedMap().forEach { (day, list) ->
            val s = DaySummary.of(list)
            add("${formatDate(day.atStartOfDay(zone).toInstant().toEpochMilli(), locale, zone)} — " +
                res.getString(R.string.diary_pdf_day, text.amount(s.carbsG), text.amount(s.rapidU + s.mixedU), text.amount(s.basalU)) to true)
            list.sortedBy { it.occurredAt }.forEach { e ->
                val glucose = if (e.sgvAtEvent > 0 && e.type != "fingerstick") " (${formatGlucoseWithUnit(e.sgvAtEvent, unit)})" else ""
                add("    ${formatTime(e.occurredAt, timeFormat, zone)}  ${text.describe(e)}$glucose" to false)
            }
        }
        add(res.getString(R.string.diary_pdf_disclaimer) to false)
    }
}
