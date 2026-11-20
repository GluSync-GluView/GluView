package com.gluview.wearables.domain

/**
 * F7.1 core: zonas de glicemia para decisão de vibração/alerta no wearable.
 * Independente do domínio do app.
 */
enum class GlucoseZone {
    URGENT_LOW,   // < 55 mg/dL
    LOW,          // 55–69 mg/dL
    NORMAL,       // 70–180 mg/dL
    HIGH,         // 181–250 mg/dL
    URGENT_HIGH,  // > 250 mg/dL
    QUICK_DROP,   // DoubleDown arrow — queda rápida independente do valor
    STALE;        // sensor sem sinal / dados antigos

    companion object {
        fun fromStatus(status: String, isStale: Boolean, arrow: String = ""): GlucoseZone {
            if (isStale) return STALE
            if (arrow == "↓↓") return QUICK_DROP
            return when (status) {
                "urgent_low"  -> URGENT_LOW
                "low"         -> LOW
                "high"        -> HIGH
                "urgent_high" -> URGENT_HIGH
                else          -> NORMAL
            }
        }
    }
}