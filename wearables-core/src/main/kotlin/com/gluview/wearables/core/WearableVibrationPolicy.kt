package com.gluview.wearables.core

import com.gluview.wearables.domain.GlucoseZone

/** Mapeia zona/tipo de alerta do app para vibração no aparelho (F7.1). */
object WearableVibrationPolicy {
    fun kindFor(zone: GlucoseZone): WearableAlertKind = when (zone) {
        GlucoseZone.URGENT_LOW -> WearableAlertKind.URGENT_LOW
        GlucoseZone.LOW -> WearableAlertKind.LOW
        GlucoseZone.URGENT_HIGH -> WearableAlertKind.URGENT_HIGH
        GlucoseZone.HIGH -> WearableAlertKind.HIGH
        GlucoseZone.QUICK_DROP -> WearableAlertKind.QUICK_DROP
        GlucoseZone.STALE -> WearableAlertKind.STALE
        GlucoseZone.NORMAL -> WearableAlertKind.NORMAL
    }

    /** Padrão [delay, vibra, pausa, vibra, ...] em ms; vazio = não vibrar. */
    fun pattern(kind: WearableAlertKind): LongArray = when (kind) {
        WearableAlertKind.URGENT_LOW, WearableAlertKind.URGENT_HIGH ->
            longArrayOf(0, 500, 150, 500, 150, 500)
        WearableAlertKind.LOW, WearableAlertKind.HIGH, WearableAlertKind.QUICK_DROP ->
            longArrayOf(0, 350, 200, 350)
        WearableAlertKind.STALE -> longArrayOf(0, 250, 150, 250)
        WearableAlertKind.PREDICTIVE -> longArrayOf(0, 300, 150, 300)
        WearableAlertKind.NORMAL -> longArrayOf()
    }
}
