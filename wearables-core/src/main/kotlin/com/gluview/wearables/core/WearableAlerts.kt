package com.gluview.wearables.core

/**
 * Plano 21, F7.1 (pedido do Felipe, 22/11/2026): a vibração de cada tipo de alerta é configurável em
 * qualquer pulseira ou relógio. Um estilo é uma sequência [liga, pausa, liga, …] em ms; CALL é a "chamada"
 * das Huami (toca até a pessoa recusar — e recusar é a soneca pela pulseira); onde não existe, vira INSISTENT.
 */
enum class VibrationStyle(val id: String, val segmentsMs: LongArray) {
    /** Só o texto, sem vibração a mais (a pulseira pode dar o toque curto dela de notificação). */
    OFF("off", longArrayOf()),
    SHORT("short", longArrayOf(300)),
    DOUBLE("double", longArrayOf(400, 250, 400)),
    TRIPLE("triple", longArrayOf(500, 200, 500, 200, 500)),
    LONG("long", longArrayOf(1500)),
    INSISTENT("insistent", longArrayOf(800, 300, 800, 300, 800, 300, 800, 300, 800, 300, 800)),
    CALL("call", longArrayOf(1000, 500, 1000, 500, 1000, 500, 1000, 500, 1000, 500, 1000));

    /** Vezes que vibra (trechos "liga"). */
    val pulses: Int get() = (segmentsMs.size + 1) / 2
    val onMs: Long get() = segmentsMs.firstOrNull() ?: 0L
    val offMs: Long get() = segmentsMs.getOrNull(1) ?: 0L

    companion object {
        fun fromId(id: String?): VibrationStyle? = entries.firstOrNull { it.id == id }
        /** Os que todo aparelho que vibra consegue tocar (CALL só onde houver). */
        val BASIC: Set<VibrationStyle> = setOf(OFF, SHORT, DOUBLE, TRIPLE, LONG, INSISTENT)
    }
}

data class AlertRule(val enabled: Boolean, val style: VibrationStyle)

/** Ajustes de alerta de UM tipo de aparelho: ligado e uma regra por tipo de alerta. */
data class WearableAlertSettings(val enabled: Boolean, val rules: Map<WearableAlertKind, AlertRule>) {

    fun ruleFor(kind: WearableAlertKind): AlertRule = rules[kind] ?: defaultRule(kind, supportsCall = false)

    fun with(kind: WearableAlertKind, rule: AlertRule) = copy(rules = rules + (kind to rule))

    /** Texto para guardar nas preferências: "1;URGENT_LOW=1:call;LOW=1:triple;…". */
    fun encode(): String = (listOf(if (enabled) "1" else "0") +
        KINDS.map { k -> ruleFor(k).let { r -> "${k.name}=${if (r.enabled) 1 else 0}:${r.style.id}" } }).joinToString(";")

    companion object {
        /** Tipos que a pessoa configura (NORMAL não alerta). */
        val KINDS = listOf(
            WearableAlertKind.URGENT_LOW, WearableAlertKind.LOW, WearableAlertKind.HIGH, WearableAlertKind.URGENT_HIGH,
            WearableAlertKind.QUICK_DROP, WearableAlertKind.STALE, WearableAlertKind.PREDICTIVE
        )

        fun defaultRule(kind: WearableAlertKind, supportsCall: Boolean): AlertRule = when (kind) {
            WearableAlertKind.URGENT_LOW -> AlertRule(true, if (supportsCall) VibrationStyle.CALL else VibrationStyle.INSISTENT)
            WearableAlertKind.URGENT_HIGH -> AlertRule(true, VibrationStyle.INSISTENT)
            WearableAlertKind.LOW, WearableAlertKind.PREDICTIVE -> AlertRule(true, VibrationStyle.TRIPLE)
            WearableAlertKind.HIGH, WearableAlertKind.QUICK_DROP -> AlertRule(true, VibrationStyle.DOUBLE)
            WearableAlertKind.STALE -> AlertRule(true, VibrationStyle.SHORT)
            WearableAlertKind.NORMAL -> AlertRule(false, VibrationStyle.OFF)
        }

        fun defaults(supportsCall: Boolean, enabled: Boolean = false) =
            WearableAlertSettings(enabled, KINDS.associateWith { defaultRule(it, supportsCall) })

        /** Lê o texto salvo; o que faltar ou vier inválido fica no padrão. */
        fun decode(text: String?, supportsCall: Boolean): WearableAlertSettings {
            val base = defaults(supportsCall)
            val parts = text?.split(';')?.filter { it.isNotBlank() } ?: return base
            if (parts.isEmpty()) return base
            var out = base.copy(enabled = parts[0] == "1")
            for (p in parts.drop(1)) {
                val (name, value) = p.split('=', limit = 2).takeIf { it.size == 2 } ?: continue
                val kind = WearableAlertKind.entries.firstOrNull { it.name == name } ?: continue
                val (on, styleId) = value.split(':', limit = 2).takeIf { it.size == 2 } ?: continue
                val style = VibrationStyle.fromId(styleId) ?: continue
                out = out.with(kind, AlertRule(on == "1", style))
            }
            return out
        }
    }
}

/**
 * Decisão pura: este alerta vai para este aparelho? Com qual estilo (já adaptado ao que ele toca)?
 * Regra desligada = não vai; estilo OFF = vai só o texto, sem vibrar.
 */
object WearableAlertRouter {
    fun decide(
        settings: WearableAlertSettings,
        kind: WearableAlertKind,
        supported: Set<VibrationStyle>,
        snoozedUntilMs: Long,
        nowMs: Long
    ): VibrationStyle? {
        if (!settings.enabled || nowMs < snoozedUntilMs) return null
        val rule = settings.ruleFor(kind)
        if (!rule.enabled) return null
        if (rule.style == VibrationStyle.OFF || rule.style in supported) return rule.style
        if (rule.style == VibrationStyle.CALL && VibrationStyle.INSISTENT in supported) return VibrationStyle.INSISTENT
        // Aparelho que não escolhe o padrão (só notificação): manda com o padrão dele.
        return supported.firstOrNull { it != VibrationStyle.OFF }
    }
}
