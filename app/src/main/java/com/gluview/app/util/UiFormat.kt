package com.gluview.app.util

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.gluview.app.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/*
 * Formatadores únicos de apresentação (redesign visual, Fase 1) — o equivalente ao
 * formatElapsed/formatDateTime do portal. Antes havia 13 padrões fixos ("dd/MM", "HH:mm",
 * Locale("pt","BR") forçado) espalhados pelas telas; tela nova usa só daqui.
 */

/** Glicemia na unidade do usuário, sem a unidade ("135" ou "7,5"). */
fun formatGlucose(mgdl: Double, unit: GlucoseUnit): String = with(unit) { mgdl.toDisplay() }

/** Glicemia com a unidade ("135 mg/dL" ou "7,5 mmol/L"). */
fun formatGlucoseWithUnit(mgdl: Double, unit: GlucoseUnit): String = "${formatGlucose(mgdl, unit)} ${unit.label}"

/** Sem leitura: o app usa epoch 0 (ou negativo) como "não há"; todos os formatadores mostram isto. */
const val NO_TIME = "–"

/** Hora no formato do usuário (24h ou 12h). */
fun formatTime(epochMs: Long, format: TimeFormat, zone: ZoneId = ZoneId.systemDefault()): String =
    epochMsToTimeOfDay(epochMs, format, zone)

/** Data no formato do idioma (ex.: "3 de out. de 2026" / "Oct 3, 2026"). */
fun formatDate(
    epochMs: Long,
    locale: Locale = Locale.getDefault(),
    zone: ZoneId = ZoneId.systemDefault(),
    style: FormatStyle = FormatStyle.MEDIUM
): String = if (epochMs <= 0L) NO_TIME else Instant.ofEpochMilli(epochMs).atZone(zone)
    .format(DateTimeFormatter.ofLocalizedDate(style).withLocale(locale))

/** Data curta no idioma + hora no formato do usuário ("03/10/2026 14:37" / "10/3/26 2:37 PM"),
 *  as duas no mesmo fuso. */
fun formatDateTime(
    epochMs: Long,
    format: TimeFormat,
    locale: Locale = Locale.getDefault(),
    zone: ZoneId = ZoneId.systemDefault()
): String = if (epochMs <= 0L) NO_TIME
    else "${formatDate(epochMs, locale, zone, FormatStyle.SHORT)} ${formatTime(epochMs, format, zone)}"

/** Tempo decorrido, em faixas — função pura (testável), separada do texto. */
sealed interface Elapsed {
    /** Sem leitura (epoch 0): nunca "há 20717 dias". */
    data object Unknown : Elapsed
    data object Now : Elapsed
    data class Minutes(val n: Int) : Elapsed
    data class Hours(val n: Int) : Elapsed
    data class Days(val n: Int) : Elapsed
}

/** Menos de 1 min = agora; até 59 min; até 23 h; depois, dias. Futuro (relógio adiantado) = agora. */
fun elapsedBetween(thenMs: Long, nowMs: Long): Elapsed {
    if (thenMs <= 0L) return Elapsed.Unknown
    val min = ((nowMs - thenMs) / 60_000L).coerceAtLeast(0L)
    return when {
        min < 1         -> Elapsed.Now
        min < 60        -> Elapsed.Minutes(min.toInt())
        min < 24 * 60   -> Elapsed.Hours((min / 60).toInt())
        else            -> Elapsed.Days((min / (24 * 60)).toInt())
    }
}

/** "agora" / "há 5 min" / "há 3 h" / "há 2 dias" no idioma do app. */
@Composable
@ReadOnlyComposable
fun formatElapsed(thenMs: Long, nowMs: Long): String = when (val e = elapsedBetween(thenMs, nowMs)) {
    Elapsed.Unknown    -> NO_TIME
    Elapsed.Now        -> stringResource(R.string.elapsed_now)
    is Elapsed.Minutes -> pluralStringResource(R.plurals.elapsed_minutes, e.n, e.n)
    is Elapsed.Hours   -> pluralStringResource(R.plurals.elapsed_hours, e.n, e.n)
    is Elapsed.Days    -> pluralStringResource(R.plurals.elapsed_days, e.n, e.n)
}

/** Mesma coisa fora do Compose (notificação, widget). */
fun Context.formatElapsed(thenMs: Long, nowMs: Long): String = when (val e = elapsedBetween(thenMs, nowMs)) {
    Elapsed.Unknown    -> NO_TIME
    Elapsed.Now        -> getString(R.string.elapsed_now)
    is Elapsed.Minutes -> resources.getQuantityString(R.plurals.elapsed_minutes, e.n, e.n)
    is Elapsed.Hours   -> resources.getQuantityString(R.plurals.elapsed_hours, e.n, e.n)
    is Elapsed.Days    -> resources.getQuantityString(R.plurals.elapsed_days, e.n, e.n)
}

/**
 * E-mail que quebra em bons pontos em vez de cortar com reticências — o BreakableEmail do portal:
 * espaço de largura zero ANTES do "@" e antes de cada "." do domínio. A parte local fica inteira
 * (sem "rafael." no fim da linha, que lia como fim de frase). Só para exibição: o texto ganha
 * caracteres invisíveis, não use em campo editável nem para copiar.
 */
fun breakableEmail(email: String): String {
    val at = email.indexOf('@')
    if (at <= 0) return email
    return email.substring(0, at) + "\u200B@" + email.substring(at + 1).replace(".", "\u200B.")
}

/**
 * Texto que o ViewModel entrega à tela sem resolvê-lo: um id de string (traduzido na tela, no
 * idioma do app) ou um texto pronto (ex.: nome digitado pelo usuário). Substitui as mensagens em
 * português montadas dentro dos ViewModels.
 */
sealed interface UiText {
    data class Raw(val value: String) : UiText

    class Res(@StringRes val id: Int, vararg val args: Any) : UiText {
        // Igualdade por conteúdo (como Raw): estado com a mesma mensagem não recompõe à toa.
        override fun equals(other: Any?): Boolean = other is Res && id == other.id && args.contentEquals(other.args)
        override fun hashCode(): Int = 31 * id + args.contentHashCode()
    }

    // Sem argumentos, NÃO formata: getString(id, *vazio) passa por String.format e quebra com "%"
    // literal ("100% local").
    @Composable
    fun asString(): String = when (this) {
        is Raw -> value
        is Res -> if (args.isEmpty()) stringResource(id) else stringResource(id, *args)
    }

    fun asString(context: Context): String = when (this) {
        is Raw -> value
        is Res -> if (args.isEmpty()) context.getString(id) else context.getString(id, *args)
    }
}

/** Cabeçalho de dia das listas (eventos, alertas): "Hoje", "Ontem" ou a data no idioma. */
fun Context.dayLabel(epochMs: Long, nowMs: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String {
    val day = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    return when (day) {
        today -> getString(R.string.common_today)
        today.minusDays(1) -> getString(R.string.common_yesterday)
        else -> formatDate(epochMs, Locale.getDefault(), zone)
    }
}

/** Dia (no fuso) de um instante — chave de agrupamento das listas por dia. */
fun epochDay(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
    Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate().toEpochDay()
