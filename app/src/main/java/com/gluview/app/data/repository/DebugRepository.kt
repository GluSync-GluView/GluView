package com.gluview.app.data.repository

import com.gluview.app.data.remote.GluSyncApi
import com.gluview.app.data.remote.dto.DebugToggleRequestDto
import javax.inject.Inject
import javax.inject.Singleton

/** Modo Debug/Admin — gerador de dados sintéticos (consome /debug/seed no GluSync). */
@Singleton
class DebugRepository @Inject constructor(
    private val api: GluSyncApi
) {
    suspend fun backfillToday(): Int = api.debugSeedBackfill().geradas
    suspend fun setContinuousEnabled(enabled: Boolean) { api.debugSeedToggle(DebugToggleRequestDto(enabled)) }
    suspend fun isContinuousEnabled(): Boolean = api.debugSeedStatus().enabled
}
