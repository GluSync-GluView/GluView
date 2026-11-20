package com.gluview.wearables.core

import com.gluview.wearables.domain.GlucoseReading

/** Tipo lógico de alerta para vibrar no aparelho; independe do canal Android. */
enum class WearableAlertKind { NORMAL, LOW, URGENT_LOW, HIGH, URGENT_HIGH, QUICK_DROP, STALE, PREDICTIVE }

data class GlucosePayload(
    val reading: GlucoseReading?,
    val displayValue: String,
    val deltaText: String,
    val arrow: String,
    val minutesAgo: Int,
    val stale: Boolean
)

sealed interface WearableCommand {
    data class SendGlucose(val payload: GlucosePayload) : WearableCommand
    data class Vibrate(val kind: WearableAlertKind, val patternMs: LongArray = longArrayOf(0, 400, 200, 400)) : WearableCommand
    data object SnoozeAlerts : WearableCommand
    data object RequestBattery : WearableCommand
    data object Ping : WearableCommand
}

data class BatteryStatus(val percent: Int?, val charging: Boolean = false, val atMs: Long = System.currentTimeMillis())

sealed interface WearableEvent {
    data class Connected(val deviceId: String) : WearableEvent
    data class Disconnected(val deviceId: String, val reason: String? = null) : WearableEvent
    data class Battery(val deviceId: String, val status: BatteryStatus) : WearableEvent
    /** Soneca pedida no próprio aparelho (F7.1). */
    data class SnoozeRequested(val deviceId: String) : WearableEvent
    data class Error(val deviceId: String, val message: String, val recoverable: Boolean = true) : WearableEvent
}
