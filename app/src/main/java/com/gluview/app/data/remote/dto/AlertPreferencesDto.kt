package com.gluview.app.data.remote.dto

import com.google.gson.annotations.SerializedName

// Espelha AlertPreferencesDto do GluSync — serializado para GET/PUT /alerts/preferences.
data class AlertPreferencesDto(
    @SerializedName("UrgentLow")  val urgentLow: Float  = 55f,
    @SerializedName("Low")        val low: Float         = 70f,
    @SerializedName("High")       val high: Float        = 180f,
    @SerializedName("UrgentHigh") val urgentHigh: Float  = 250f,

    // "{zona}_{tipo}" → bool (ex.: "urgent_low_pushover")
    @SerializedName("ZoneToggles") val zoneToggles: Map<String, Boolean> = emptyMap(),

    @SerializedName("PushoverEnabled")  val pushoverEnabled: Boolean = false,
    @SerializedName("PushoverUserKey")  val pushoverUserKey: String  = "",

    @SerializedName("NotifyFollowersEnabled")  val notifyFollowersEnabled: Boolean = false,
    @SerializedName("NotifyFollowersSpecific") val notifyFollowersSpecific: List<String> = emptyList(),

    @SerializedName("ReAlarmIntervalMin") val reAlarmIntervalMin: Int = 5
)

data class TestPushoverRequestDto(
    @SerializedName("UserKey") val userKey: String
)

data class TestPushoverResponseDto(
    @SerializedName("message") val message: String
)
