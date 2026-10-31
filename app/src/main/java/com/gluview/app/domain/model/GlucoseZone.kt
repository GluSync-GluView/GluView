package com.gluview.app.domain.model

enum class GlucoseZone {
    URGENT_LOW,   // < 55
    LOW,          // 55–69
    NORMAL,       // 70–180
    HIGH,         // 181–250
    URGENT_HIGH,  // > 250
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
