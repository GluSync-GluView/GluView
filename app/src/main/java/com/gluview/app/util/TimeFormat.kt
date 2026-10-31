package com.gluview.app.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Formato de hora escolhido pelo usuário para exibir o horário da medição (Home, widgets, Mi Band). */
enum class TimeFormat {
    H24, H12;

    companion object {
        fun fromString(s: String) = entries.firstOrNull { it.name == s } ?: H24
    }
}

private val FORMATTER_24H = DateTimeFormatter.ofPattern("HH:mm")
private val FORMATTER_12H = DateTimeFormatter.ofPattern("h:mm a", Locale.US)

/** Formata o instante (epoch ms) em que a medição ocorreu como horário local, no formato
 *  escolhido pelo usuário (24 horas ou 12 horas com AM/PM). [zone] só muda em teste e no
 *  formatDateTime, que precisa da hora no mesmo fuso da data. */
fun epochMsToTimeOfDay(epochMs: Long, format: TimeFormat, zone: ZoneId = ZoneId.systemDefault()): String {
    if (epochMs <= 0L) return "–"
    val zoned = Instant.ofEpochMilli(epochMs).atZone(zone)
    return zoned.format(if (format == TimeFormat.H24) FORMATTER_24H else FORMATTER_12H)
}
