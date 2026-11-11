package com.gluview.app.data.reminder

import com.gluview.app.data.local.entity.ReminderEntity
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Tipos de lembrete (F2.1). */
enum class ReminderKind(val key: String) {
    MEASURE("measure"),
    INSULIN("insulin"),
    MEDICATION("medication"),
    /** Só avisa se a basal do dia ainda não foi registrada (F2.4). */
    BASAL_CHECK("basal_check"),
    CUSTOM("custom");

    companion object {
        fun of(key: String): ReminderKind = entries.firstOrNull { it.key == key } ?: CUSTOM
    }
}

/** Horários e dias de um lembrete. Puro: testável sem Android. */
object ReminderSchedule {
    const val ALL_DAYS = 0b111_1111

    fun parseTimes(csv: String): List<Int> =
        csv.split(',').mapNotNull { it.trim().toIntOrNull() }.filter { it in 0 until 24 * 60 }.distinct().sorted()

    fun encodeTimes(minutes: List<Int>): String = minutes.filter { it in 0 until 24 * 60 }.distinct().sorted().joinToString(",")

    /** bit 0 = segunda … bit 6 = domingo. */
    fun dayBit(day: DayOfWeek): Int = 1 shl (day.value - 1)

    fun isActiveOn(mask: Int, day: DayOfWeek): Boolean = mask and dayBit(day) != 0

    /**
     * Próximo disparo estritamente depois de [nowMs] — null se não há horário ou dia marcado.
     * Usa a hora local do fuso (mudar de fuso ou o horário de verão remarcam pela hora do relógio).
     */
    fun nextTrigger(r: ReminderEntity, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long? {
        val times = parseTimes(r.timesCsv)
        if (!r.enabled || times.isEmpty() || r.daysMask and ALL_DAYS == 0) return null
        val now = Instant.ofEpochMilli(nowMs).atZone(zone)
        var day: LocalDate = now.toLocalDate()
        repeat(8) {
            if (isActiveOn(r.daysMask, day.dayOfWeek)) {
                for (m in times) {
                    val at = day.atTime(LocalTime.of(m / 60, m % 60)).atZone(zone).toInstant().toEpochMilli()
                    if (at > nowMs) return at
                }
            }
            day = day.plusDays(1)
        }
        return null
    }

    fun formatMinutes(m: Int): LocalTime = LocalTime.of(m / 60, m % 60)
}
