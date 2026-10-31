package com.gluview.app.widget

import android.content.Context
import androidx.compose.ui.graphics.Color
import com.gluview.app.util.GraphReading
import com.gluview.app.util.withAppLanguage

/*
 * Comum aos widgets (redesign visual, Fase 5). O fundo pela zona continua sendo o sinal principal
 * (decisão 5.4 #2), agora no tema do SISTEMA (5.4 #1) e com a queda rápida na cor própria — antes
 * QUICK_DROP caía no "else" e saía verde, como glicemia normal.
 */

/** Fundo do widget pela zona; [noData] = sem sinal ou sem leitura (neutro, nunca verde). */
internal fun widgetZoneBackground(zone: String, noData: Boolean, dark: Boolean): Color = when {
    noData                                        -> if (dark) Color(0xFF2B2B2B) else Color(0xFFEEEDF4)
    zone == "URGENT_LOW" || zone == "URGENT_HIGH" -> if (dark) Color(0xFF7B1111) else Color(0xFFFFDAD6)
    zone == "QUICK_DROP"                          -> if (dark) Color(0xFF6B3A00) else Color(0xFFFFE0B2)
    zone == "LOW"        || zone == "HIGH"        -> if (dark) Color(0xFF7A2B00) else Color(0xFFFFDDB3)
    else                                          -> if (dark) Color(0xFF1A2E1A) else Color(0xFFD7F0D1)
}

/** Fundo do widget simples (cores cheias com texto branco, nos dois temas). */
internal fun simpleWidgetBackground(zone: String, noData: Boolean): Color = when {
    noData                                        -> Color(0xFF424242)
    zone == "URGENT_LOW" || zone == "URGENT_HIGH" -> Color(0xFFC62828)
    zone == "QUICK_DROP"                          -> Color(0xFF9A4A00)
    zone == "LOW"        || zone == "HIGH"        -> Color(0xFFE65100)
    else                                          -> Color(0xFF2E7D32)
}

/**
 * Leituras compactas para o state do widget ("ms,sgv,c;…", c = 1/0/- para confirmada/provisória/
 * sem o conceito): o widget desenha o gráfico no SEU tamanho, em vez de reescalar um bitmap fixo.
 */
internal fun encodeWidgetPoints(readings: List<GraphReading>): String =
    readings.joinToString(";") { r ->
        "${r.timestampMs},${r.sgvMgDl},${when (r.confirmed) { true -> "1"; false -> "0"; null -> "-" }}"
    }

internal fun decodeWidgetPoints(text: String): List<GraphReading> =
    if (text.isBlank()) emptyList() else text.split(';').mapNotNull { item ->
        val p = item.split(',')
        val ms = p.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
        val sgv = p.getOrNull(1)?.toFloatOrNull() ?: return@mapNotNull null
        val confirmed = when (p.getOrNull(2)) { "1" -> true; "0" -> false; else -> null }
        GraphReading(ms, sgv, confirmed)
    }

/** Contexto no idioma do app gravado no state ([lang] vazio = o do sistema). */
internal fun Context.widgetContext(lang: String?): Context =
    if (lang.isNullOrBlank()) this else withAppLanguage(lang)
