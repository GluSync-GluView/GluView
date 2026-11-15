package com.gluview.app.overlay

import com.gluview.app.domain.model.GlucoseReading
import com.gluview.app.domain.model.GlucoseZone
import com.gluview.app.util.GlucoseUnit

/**
 * Plano 21, F1 — janela flutuante de glicemia. Regras puras (sem Android) do que a janela mostra,
 * testadas em FloatingFormatTest. Regra de saúde: leitura velha NUNCA aparece como atual — passou
 * do limiar de "sem sinal" (o mesmo de Ajustes › Sensor, usado pela notificação e pelos widgets),
 * o valor some e fica só "Sem sinal" com a idade da última leitura.
 */
enum class FloatingTone { NORMAL, WARN, URGENT, QUICK_DROP, STALE }

data class FloatingState(
    /** Valor na unidade do usuário, ou null quando não há leitura atual (sem sinal / nunca leu). */
    val value: String?,
    val arrow: String,
    /** Delta já formatado (com sinal), ou null quando escondido ou sem sinal. */
    val delta: String?,
    /** Minutos desde a medição; null quando nunca houve leitura. */
    val ageMinutes: Int?,
    val stale: Boolean,
    val tone: FloatingTone
)

object FloatingFormat {

    fun toneOf(zone: GlucoseZone): FloatingTone = when (zone) {
        GlucoseZone.URGENT_LOW, GlucoseZone.URGENT_HIGH -> FloatingTone.URGENT
        GlucoseZone.LOW, GlucoseZone.HIGH -> FloatingTone.WARN
        GlucoseZone.QUICK_DROP -> FloatingTone.QUICK_DROP
        GlucoseZone.STALE -> FloatingTone.STALE
        GlucoseZone.NORMAL -> FloatingTone.NORMAL
    }

    /**
     * @param reading a leitura atual (paciente ou pessoa seguida), ou null se não houver nenhuma.
     * @param noSignalMin limiar de "sem sinal" (Ajustes › Sensor).
     */
    fun build(
        reading: GlucoseReading?,
        nowMs: Long,
        noSignalMin: Int,
        unit: GlucoseUnit,
        showArrow: Boolean = true,
        showDelta: Boolean = true
    ): FloatingState {
        if (reading == null || reading.epochMs <= 0L) {
            return FloatingState(null, "", null, null, stale = true, tone = FloatingTone.STALE)
        }
        val ageMin = ((nowMs - reading.epochMs).coerceAtLeast(0L) / 60_000L).toInt()
        val stale = reading.isStale || reading.zone == GlucoseZone.STALE ||
            nowMs - reading.epochMs >= noSignalMin * 60_000L
        if (stale) return FloatingState(null, "", null, ageMin, stale = true, tone = FloatingTone.STALE)
        return with(unit) {
            FloatingState(
                value = reading.sgvMgDl.toDisplay(),
                arrow = if (showArrow) reading.arrow else "",
                delta = if (showDelta) reading.sgvMgDl.deltaToDisplay(reading.delta) else null,
                ageMinutes = ageMin,
                stale = false,
                tone = toneOf(reading.zone)
            )
        }
    }
}

/**
 * Ajustes da janela flutuante, guardados numa preferência só ("k=v;k=v") para não espalhar 13
 * chaves pelo AppPreferences e caber inteiro no backup de configurações.
 */
data class FloatingConfig(
    val enabled: Boolean = false,
    /** Escala do tamanho (1 = padrão). */
    val scale: Float = 1f,
    /** Opacidade (0,3 a 1). */
    val alpha: Float = 0.9f,
    val showArrow: Boolean = true,
    val showDelta: Boolean = true,
    val showAge: Boolean = true,
    /** Fundo na cor da faixa (true) ou neutro escuro (false). */
    val zoneBackground: Boolean = true,
    val locked: Boolean = false,
    val hideWhenLocked: Boolean = true,
    /** Toque longo esconde por este tempo. */
    val hideMinutes: Int = 30,
    /** Borda e pulso quando a faixa é urgente ou queda rápida. */
    val highlightAlerts: Boolean = true,
    /** Posição salva; -1 = ainda não arrastada (canto superior direito). */
    val x: Int = -1,
    val y: Int = -1
) {
    fun encode(): String = listOf(
        "enabled" to enabled, "scale" to scale, "alpha" to alpha, "showArrow" to showArrow,
        "showDelta" to showDelta, "showAge" to showAge, "zoneBackground" to zoneBackground,
        "locked" to locked, "hideWhenLocked" to hideWhenLocked, "hideMinutes" to hideMinutes,
        "highlightAlerts" to highlightAlerts, "x" to x, "y" to y
    ).joinToString(";") { (k, v) -> "$k=$v" }

    companion object {
        const val MIN_SCALE = 0.6f
        const val MAX_SCALE = 2.0f
        const val MIN_ALPHA = 0.3f

        /** Lê o texto salvo; chave desconhecida ou valor inválido fica no padrão (nunca quebra). */
        fun decode(raw: String?): FloatingConfig {
            if (raw.isNullOrBlank()) return FloatingConfig()
            val m = raw.split(';').mapNotNull { p ->
                val i = p.indexOf('=')
                if (i <= 0) null else p.substring(0, i) to p.substring(i + 1)
            }.toMap()
            val d = FloatingConfig()
            fun b(k: String, def: Boolean) = m[k]?.toBooleanStrictOrNull() ?: def
            fun f(k: String, def: Float) = m[k]?.toFloatOrNull()?.takeIf { it.isFinite() } ?: def
            fun i(k: String, def: Int) = m[k]?.toIntOrNull() ?: def
            return FloatingConfig(
                enabled = b("enabled", d.enabled),
                scale = f("scale", d.scale).coerceIn(MIN_SCALE, MAX_SCALE),
                alpha = f("alpha", d.alpha).coerceIn(MIN_ALPHA, 1f),
                showArrow = b("showArrow", d.showArrow),
                showDelta = b("showDelta", d.showDelta),
                showAge = b("showAge", d.showAge),
                zoneBackground = b("zoneBackground", d.zoneBackground),
                locked = b("locked", d.locked),
                hideWhenLocked = b("hideWhenLocked", d.hideWhenLocked),
                hideMinutes = i("hideMinutes", d.hideMinutes).coerceIn(1, 24 * 60),
                highlightAlerts = b("highlightAlerts", d.highlightAlerts),
                x = i("x", d.x),
                y = i("y", d.y)
            )
        }
    }
}
