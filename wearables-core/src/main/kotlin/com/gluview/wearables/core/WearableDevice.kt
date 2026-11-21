package com.gluview.wearables.core

/** Porte fiel do contrato descrito no plano 21/F7.1: identidade e capacidades de um aparelho. */
data class WearableCapabilities(
    val canShowGlucose: Boolean = false,
    val canShowGraph: Boolean = false,
    val canVibrate: Boolean = false,
    val canReceiveSnooze: Boolean = false,
    val canReportBattery: Boolean = false,
    val canSendWatchface: Boolean = false
)

data class WearableDeviceInfo(
    val id: String,
    val displayName: String,
    val family: String,
    val capabilities: WearableCapabilities,
    /** "Sem teste com hardware real" para modelos ainda não validados em aparelho físico. */
    val maturityLabel: String = "Sem teste com hardware real"
)
