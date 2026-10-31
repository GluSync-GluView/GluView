package com.gluview.app.data.repository

import com.gluview.app.alert.AlertType
import com.gluview.app.data.datastore.AppPreferences
import com.gluview.app.data.remote.GluSyncApi
import com.gluview.app.data.remote.dto.AlertPreferencesDto
import com.gluview.app.domain.model.GlucoseZone
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AlertPreferencesRepository @Inject constructor(
    private val api: GluSyncApi,
    private val prefs: AppPreferences
) {
    // Zonas que aparecem na coluna Pushover da matriz
    private val alertZones = listOf(
        GlucoseZone.URGENT_LOW, GlucoseZone.LOW, GlucoseZone.HIGH,
        GlucoseZone.URGENT_HIGH, GlucoseZone.STALE, GlucoseZone.QUICK_DROP
    )

    // ── Serializar preferências locais → DTO ──────────────────────────────────

    suspend fun buildDto(): AlertPreferencesDto {
        val toggles = buildMap<String, Boolean> {
            for (zone in alertZones) {
                for (type in AlertType.entries) {
                    val key = "${zone.name.lowercase()}_${type.name.lowercase()}"
                    put(key, prefs.isAlertEnabled(zone, type))
                }
            }
        }
        val followerIds = try {
            val arr = JSONArray(prefs.notifyFollowersIdsJson.first())
            (0 until arr.length()).map { arr.getString(it) }
        } catch (_: Exception) { emptyList() }

        return AlertPreferencesDto(
            urgentLow  = prefs.urgentLowThreshold.first(),
            low        = prefs.lowThreshold.first(),
            high       = prefs.highThreshold.first(),
            urgentHigh = prefs.urgentHighThreshold.first(),
            zoneToggles = toggles,
            pushoverEnabled  = prefs.pushoverEnabled.first(),
            pushoverUserKey  = prefs.pushoverUserKey.first(),
            notifyFollowersEnabled  = prefs.notifyFollowersEnabled.first(),
            notifyFollowersSpecific = followerIds,
            reAlarmIntervalMin = prefs.reAlarmInterval.first()
        )
    }

    // ── Aplicar DTO recebido do servidor → preferências locais ────────────────

    private suspend fun applyDto(dto: AlertPreferencesDto) {
        prefs.saveThresholds(dto.urgentLow, dto.low, dto.high, dto.urgentHigh)

        for (zone in alertZones) {
            for (type in AlertType.entries) {
                val key = "${zone.name.lowercase()}_${type.name.lowercase()}"
                val enabled = dto.zoneToggles[key]
                    ?: AppPreferences.defaultAlertEnabled(zone, type)
                prefs.setAlertEnabled(zone, type, enabled)
            }
        }

        val zonesJson = JSONArray(
            alertZones.filter { z ->
                dto.zoneToggles["${z.name.lowercase()}_pushover"] != false
            }.map { it.name }
        ).toString()

        prefs.savePushover(dto.pushoverEnabled, dto.pushoverUserKey, zonesJson)

        val idsJson = JSONArray(dto.notifyFollowersSpecific).toString()
        prefs.saveNotifyFollowers(dto.notifyFollowersEnabled, idsJson)
        prefs.saveReAlarm(prefs.reAlarmEnabled.first(), dto.reAlarmIntervalMin)
    }

    // ── API pública ───────────────────────────────────────────────────────────

    suspend fun syncToServer(): Result<Unit> = runCatching {
        val dto = buildDto()
        val resp = api.putAlertPreferences(dto)
        if (!resp.isSuccessful) error("PUT /alerts/preferences falhou: ${resp.code()}")
    }

    suspend fun syncFromServer(): Result<Unit> = runCatching {
        val dto = api.getAlertPreferences()
        applyDto(dto)
    }

    suspend fun testPushover(userKey: String): Result<String> = runCatching {
        api.testPushover(
            com.gluview.app.data.remote.dto.TestPushoverRequestDto(userKey)
        ).message
    }
}
