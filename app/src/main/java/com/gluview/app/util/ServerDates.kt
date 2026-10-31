package com.gluview.app.util

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Datas geradas pelo GluSync ("gerado" do relatório).
 *
 * O servidor manda um instante absoluto em ISO-8601 UTC (ex: "2026-07-03T00:13:45Z") —
 * ele não sabe (e não deveria decidir) em que fuso o usuário está, então a conversão pro
 * horário local é sempre feita aqui, no cliente, usando o fuso atual do aparelho.
 */
object ServerDates {
    private val displayFormat = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")
    private val legacyServerFormat = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
    private val dayFormat = DateTimeFormatter.ofPattern("dd/MM/yyyy")

    /** Converte o instante enviado pelo GluSync para o horário local do aparelho. */
    fun utcToLocal(text: String): String =
        toEpochMs(text)?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(displayFormat) } ?: text

    /** O instante enviado pelo GluSync em epoch ms (para formatar no idioma do usuário); null se não reconhecer. */
    fun toEpochMs(text: String): Long? {
        val trimmed = text.trim()
        runCatching { Instant.parse(trimmed) }.getOrNull()?.let { return it.toEpochMilli() }
        // Fallback: versões antigas do GluSync mandavam "dd/MM/yyyy HH:mm" já formatado em UTC, sem offset.
        return runCatching {
            LocalDateTime.parse(trimmed, legacyServerFormat).toInstant(ZoneOffset.UTC).toEpochMilli()
        }.getOrNull()
    }

    /** Dia do GluSync ("dd/MM/yyyy", o TO_CHAR do servidor); aceita também ISO. null se não reconhecer. */
    fun parseDay(text: String): LocalDate? =
        runCatching { LocalDate.parse(text, dayFormat) }.getOrNull() ?: runCatching { LocalDate.parse(text) }.getOrNull()

    /** Formata um epoch (ms) do instante da medição como horário local "HH:mm" (notificação persistente). */
    fun epochToLocalTime(epochMs: Long): String =
        Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).format(timeFormat)
}
