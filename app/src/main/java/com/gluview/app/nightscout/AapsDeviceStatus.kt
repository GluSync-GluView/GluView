package com.gluview.app.nightscout

import org.json.JSONObject
import java.time.Instant

/**
 * Plano 21, F5.2 — o que o AndroidAPS manda no `devicestatus` (formato Nightscout/openaps),
 * lido com tolerância: qualquer campo pode faltar. Puro (org.json), testado em AapsDeviceStatusTest.
 */
data class AapsStatus(
    val atMs: Long,
    val device: String?,
    val reservoirU: Double?,
    val pumpBatteryPct: Int?,
    val pumpStatus: String?,
    val suspended: Boolean,
    val iob: Double?,
    val basalIob: Double?,
    val cob: Double?,
    val tempBasalRate: Double?,
    val tempBasalMin: Int?,
    val eventualBg: Double?,
    /** Último ciclo da alça (enacted, ou a última sugestão). */
    val loopAtMs: Long?,
    val enacted: Boolean,
    val reason: String?,
    val uploaderBatteryPct: Int?
)

object AapsDeviceStatus {
    /** O registro mais novo do array, ou null se não houver nada útil. */
    fun parseLatest(body: String): AapsStatus? = runCatching {
        val arr = org.json.JSONArray(body)
        (0 until arr.length()).mapNotNull { parse(arr.optJSONObject(it)) }.maxByOrNull { it.atMs }
    }.getOrNull()

    fun parse(o: JSONObject?): AapsStatus? {
        o ?: return null
        val at = o.optLong("mills").takeIf { it > 0 } ?: time(o.optString("created_at")) ?: return null
        val pump = o.optJSONObject("pump")
        val openaps = o.optJSONObject("openaps")
        val iobObj = openaps?.optJSONObject("iob") ?: openaps?.optJSONArray("iob")?.optJSONObject(0)
        val suggested = openaps?.optJSONObject("suggested")
        val enactedObj = openaps?.optJSONObject("enacted")
        val enactedOk = enactedObj != null && (enactedObj.optBoolean("received", true) || enactedObj.optBoolean("recieved", false))
        val loopObj = if (enactedOk) enactedObj else suggested
        if (pump == null && openaps == null) return null
        return AapsStatus(
            atMs = at,
            device = o.optString("device").takeIf { it.isNotBlank() },
            reservoirU = pump?.optDouble("reservoir")?.takeIf { !it.isNaN() },
            pumpBatteryPct = pump?.optJSONObject("battery")?.optInt("percent", -1)?.takeIf { it >= 0 },
            pumpStatus = pump?.optJSONObject("status")?.optString("status")?.takeIf { it.isNotBlank() },
            suspended = pump?.optJSONObject("status")?.optBoolean("suspended", false) ?: false,
            iob = iobObj?.optDouble("iob")?.takeIf { !it.isNaN() } ?: suggested?.optDouble("IOB")?.takeIf { !it.isNaN() },
            basalIob = iobObj?.optDouble("basaliob")?.takeIf { !it.isNaN() },
            cob = suggested?.optDouble("COB")?.takeIf { !it.isNaN() },
            tempBasalRate = enactedObj?.optDouble("rate")?.takeIf { !it.isNaN() },
            tempBasalMin = enactedObj?.optInt("duration", -1)?.takeIf { it >= 0 },
            eventualBg = suggested?.optDouble("eventualBG")?.takeIf { !it.isNaN() },
            loopAtMs = loopObj?.let { time(it.optString("timestamp")) ?: it.optLong("deliverAt").takeIf { v -> v > 0 } },
            enacted = enactedOk,
            reason = (loopObj?.optString("reason"))?.takeIf { it.isNotBlank() },
            uploaderBatteryPct = o.optJSONObject("uploader")?.optInt("battery", -1)?.takeIf { it >= 0 }
                ?: o.optInt("uploaderBattery", -1).takeIf { it >= 0 }
        )
    }

    private fun time(s: String?): Long? = s?.takeIf { it.isNotBlank() }?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
}
