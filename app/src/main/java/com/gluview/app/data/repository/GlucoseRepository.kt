package com.gluview.app.data.repository

import com.gluview.app.BuildConfig
import com.gluview.app.data.datastore.AppPreferences
import com.gluview.app.data.local.dao.GlucoseDao
import com.gluview.app.data.local.entity.DayStats
import com.gluview.app.data.local.entity.GlucoseEntity
import com.gluview.app.data.remote.GluSyncApi
import com.gluview.app.data.remote.dto.GlucoseEntryDto
import com.gluview.app.domain.model.GlucoseReading
import com.gluview.app.domain.model.GlucoseThresholds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.mapNotNull
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class GlucoseRepository @Inject constructor(
    private val api: GluSyncApi,
    private val dao: GlucoseDao,
    private val preferences: AppPreferences,
    // Plano 17: o diário segue a mesma regra de conta dona (claimUnownedReadings reclama os dois).
    private val eventLogDao: com.gluview.app.data.local.dao.EventLogDao
) {
    // StateFlow compartilhado entre Service e ViewModels
    private val _currentReading = MutableStateFlow<GlucoseReading?>(null)
    val currentReading: StateFlow<GlucoseReading?> = _currentReading.asStateFlow()

    // Isolamento por conta (achado 2026-09-26): tudo que lê do Room reage à conta atualmente
    // logada (AppPreferences.userEmail) via flatMapLatest — troca de conta re-consulta sozinho,
    // sem listener manual. Gravação tagueia ownerEmail na hora de montar o GlucoseEntity (ver
    // fetchAndStore/storeExternalReading/backfillIfNeeded abaixo).
    // Zona recalculada com os limites ATUAIS (mudar um limite em Alertas reclassifica na hora).
    val latestFromDb: Flow<GlucoseReading> =
        combine(preferences.userEmail.flatMapLatest { owner -> dao.observeLatest(owner) }, preferences.glucoseThresholds) { e, t ->
            e?.toDomain(t)
        }.mapNotNull { it }

    // S27 — estatísticas do dia para o HomeScreen
    fun observeDayStats(startMs: Long): Flow<DayStats> =
        preferences.userEmail.flatMapLatest { owner -> dao.observeDayStats(owner, startMs) }

    fun observeRecentReadings(fromMs: Long): Flow<List<GlucoseEntity>> =
        preferences.userEmail.flatMapLatest { owner -> dao.observeFrom(owner, fromMs) }

    suspend fun getRecentReadings(fromMs: Long): List<GlucoseEntity> =
        dao.getFrom(preferences.userEmail.first(), fromMs)

    /** A leitura mais nova do banco, ou null se não há nenhuma (consulta única; L5). */
    suspend fun getLatestOnce(): GlucoseReading? =
        dao.getLatest(preferences.userEmail.first())?.toDomain(preferences.glucoseThresholds.first())

    /** Leituras locais em [fromMs, toMs) (Histórico › Gráfico, L17). */
    suspend fun getReadingsBetween(fromMs: Long, toMs: Long): List<GlucoseEntity> =
        dao.getBetween(preferences.userEmail.first(), fromMs, toMs)

    /** Zera o estado em memória na troca de conta (login/logout) — sem isso a Home continuaria
     *  mostrando a última leitura da conta anterior até a 1ª leitura nova sobrescrever. */
    fun clearCurrentReadingCache() {
        _currentReading.value = null
        followerRecentReadings.clear()
    }

    /** Reclama o histórico local gravado antes do isolamento por conta existir (migration
     *  12→13) para a conta que acabou de logar. Idempotente — chamar em todo login é seguro. */
    suspend fun claimUnownedReadings(owner: String) {
        if (owner.isNotBlank()) {
            dao.claimUnownedRows(owner)
            eventLogDao.claimUnownedRows(owner)
        }
    }

    // ── Modo Seguidor ─────────────────────────────────────────────────────────
    // As leituras do paciente acompanhado NÃO vão pro Room local (que é só do sensor próprio)
    // — ficam num buffer em memória, usado pela notificação persistente e pelo Mi Band pra
    // desenhar o mini-gráfico sem precisar misturar dado de terceiro no banco local.
    private val followerRecentReadings = java.util.Collections.synchronizedList(mutableListOf<GlucoseEntity>())

    /** Preenche o buffer com o histórico já conhecido (evita gráfico vazio logo após conectar). */
    fun seedFollowerReadings(entries: List<GlucoseEntryDto>) {
        followerRecentReadings.clear()
        followerRecentReadings.addAll(entries.map {
            GlucoseEntity(
                dateMs = it.date, sgvMgDl = it.sgv, direction = it.direction, arrow = it.direction,
                status = "", delta = 0, isStale = false, minutesAgo = 0
            )
        })
    }

    /** Chamado a cada NovaMedicao do paciente acompanhado — mantém só as últimas 24h. */
    fun recordFollowerReading(reading: GlucoseReading) {
        val dateMs = reading.epochMs.takeIf { it > 0 } ?: System.currentTimeMillis()
        followerRecentReadings.removeAll { it.dateMs == dateMs }
        followerRecentReadings.add(
            GlucoseEntity(
                dateMs = dateMs, sgvMgDl = reading.sgvMgDl, direction = reading.direction,
                arrow = reading.arrow, status = reading.zone.name, delta = reading.delta,
                isStale = reading.isStale, minutesAgo = reading.minutesAgo
            )
        )
        val cutoff = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(24)
        followerRecentReadings.removeAll { it.dateMs < cutoff }
    }

    fun clearFollowerReadings() = followerRecentReadings.clear()

    /** Glicemia da última leitura do buffer do seguidor antes de [ms] (delta da 1ª leitura ao conectar). */
    fun lastFollowerSgvBefore(ms: Long): Double? =
        synchronized(followerRecentReadings) { followerRecentReadings.filter { it.dateMs < ms }.maxByOrNull { it.dateMs }?.sgvMgDl }

    /** Como [getRecentReadings], mas usa o buffer do paciente acompanhado quando ele tem dado —
     *  notificação, widgets, Auto, Dream e Mi Band (L9: no seguidor eles ficavam sem gráfico). */
    suspend fun getRecentReadingsForNotification(fromMs: Long): List<GlucoseEntity> =
        if (followerRecentReadings.isNotEmpty()) followerRecentReadings.filter { it.dateMs >= fromMs }
        else getRecentReadings(fromMs)

    suspend fun fetchAndStore(): GlucoseReading? = runCatching {
        val dto = api.getWearData()
        // Zona pelos limites do usuário (L2) — o status do servidor usa 55/70/180/250 fixos.
        val thresholds = preferences.glucoseThresholds.first()
        val zone = thresholds.classify(dto.sgv, dto.arrow, dto.stale)
        val entity = GlucoseEntity(
            dateMs     = System.currentTimeMillis(),
            sgvMgDl   = dto.sgv,
            direction = dto.arrow,
            arrow     = dto.arrow,
            status    = thresholds.status(dto.sgv),
            delta     = dto.delta,
            isStale   = dto.stale,
            minutesAgo = dto.minutosAtras ?: 0,
            ownerEmail = preferences.userEmail.first()
        )
        dao.insertReading(entity)

        val reading = GlucoseReading(
            sgvMgDl   = dto.sgv,
            direction = dto.arrow,
            arrow     = dto.arrow,
            timestamp = dto.time,
            zone      = zone,
            delta     = dto.delta,
            isStale   = dto.stale,
            minutesAgo = dto.minutosAtras ?: 0,
            // Tempo real da medição = agora menos a idade informada pelo servidor.
            epochMs   = System.currentTimeMillis() - (dto.minutosAtras ?: 0) * 60_000L
        )
        _currentReading.value = reading
        reading
    }.getOrNull()

    fun updateReading(reading: GlucoseReading) { _currentReading.value = reading }

    /**
     * Ingestão genérica de uma leitura externa (fonte BLE Sibionics ou ponte xDrip).
     * Persiste no Room (mesmo pipeline do polling) e, se for a mais recente, vira a atual.
     * Sem qualquer teto de vida — aceita qualquer dateMs/idade.
     * Usa IGNORE no conflito: leitura existente com mesmo dateMs é preservada.
     */
    suspend fun storeExternalReading(
        dateMs: Long,
        sgvMgDl: Double,
        arrow: String,
        delta: Int,
        makeCurrent: Boolean,
        rawMmol: Double? = null,
        calibFactor: Double? = null,
        sensorMac: String? = null,
        sensorAgeMin: Int? = null,
        // Modos de leitura Sibionics — sempre gravado quando disponível, independente do modo
        // escolhido pelo usuário. `makeCurrent` já vem com o gate do modo aplicado pelo chamador
        // (ver SibionicsBleSource.persist()) — esta função é agnóstica ao modo.
        confirmed: Boolean? = null
    ): GlucoseReading {
        val thresholds = preferences.glucoseThresholds.first()
        val status = thresholds.status(sgvMgDl)
        val zone   = thresholds.classify(sgvMgDl, arrow)
        dao.insertIfNotExists(
            GlucoseEntity(
                dateMs       = dateMs,
                sgvMgDl      = sgvMgDl,
                direction    = arrow,
                arrow        = arrow,
                status       = status,
                delta        = delta,
                isStale      = false,
                minutesAgo   = 0,
                rawValue     = rawMmol,
                calibFactor  = calibFactor,
                sensorMac    = sensorMac,
                sensorAgeMin = sensorAgeMin,
                confirmed    = confirmed,
                ownerEmail   = preferences.userEmail.first()
            )
        )
        val reading = GlucoseReading(
            sgvMgDl    = sgvMgDl,
            direction  = arrow,
            arrow      = arrow,
            timestamp  = "",
            zone       = zone,
            delta      = delta,
            isStale    = false,
            minutesAgo = 0,
            epochMs    = dateMs
        )
        if (makeCurrent) _currentReading.value = reading
        return reading
    }

    // Retenção local: parametrizável (ver AppPreferences.dataRetentionDays). Hard delete por
    // data da medição. Consultas além disso (relatórios, padrão, TIR) vêm da API quando há
    // GluSync — o servidor guarda o histórico completo independente do que fica no aparelho.
    suspend fun pruneOldReadings(retentionDays: Int = 100) {
        if (retentionDays < 0) return // -1 = sem limite — nunca apaga
        val safeDays = retentionDays.coerceAtLeast(30) // piso de 1 mês, mesmo se o chamador mandar menos
        val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(safeDays.toLong())
        dao.deleteOlderThan(cutoff)
    }

    /**
     * O relógio do celular voltou [shiftMs] (negativo): leva para a hora certa as leituras de
     * [sensorMac] que ficaram depois de [nowMs]; as que colidirem com uma leitura já existente
     * na hora certa são descartadas. Devolve quantas foram reposicionadas.
     */
    suspend fun shiftFutureSensorReadings(sensorMac: String, nowMs: Long, shiftMs: Long): Int {
        if (sensorMac.isBlank() || shiftMs >= 0) return 0
        val afterMs = nowMs + TimeUnit.MINUTES.toMillis(1)
        val moved = dao.shiftSensorReadingsAfter(sensorMac, afterMs, shiftMs)
        dao.deleteSensorReadingsAfter(sensorMac, afterMs)
        return moved
    }

    // ── Backfill ──────────────────────────────────────────────────────────────
    //
    // Problema: quando o serviço fica offline (app fechado, SO matou o processo),
    // o xDrip continua enviando leituras ao GluSync. Ao reiniciar, só a leitura
    // mais recente é buscada via /glucose/wear → buraco no gráfico local.
    //
    // Solução: ao iniciar o serviço, verificar o timestamp da última entrada no Room.
    // Se houver gap > 5 min, buscar as leituras faltantes via /glucose/range e
    // inserir no Room. Falha silenciosa — o gap fica, mas não impede o app de funcionar.

    private val isoFmt: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)

    suspend fun backfillIfNeeded() {
        val owner = preferences.userEmail.first()
        val now  = System.currentTimeMillis()
        // Build demo: banco vazio na primeira abertura também puxa as últimas 24h, para o gráfico
        // já nascer cheio (no app real, sem leitura local não há buraco a preencher).
        val lastMs = dao.getLatest(owner)?.dateMs
            ?: if (BuildConfig.DEMO_MODE) now - TimeUnit.HOURS.toMillis(24) - 60_000L else return
        val gapMs = now - lastMs

        // Nenhum buraco significativo — não vale a chamada de rede
        if (gapMs < TimeUnit.MINUTES.toMillis(5)) return

        // Cap em 24h: não faz sentido buscar mais do que o período exibido nos gráficos
        val fromMs = maxOf(lastMs + 60_000L, now - TimeUnit.HOURS.toMillis(24))

        val entries = runCatching {
            api.getRange(
                from     = isoFmt.format(Instant.ofEpochMilli(fromMs)),
                to       = isoFmt.format(Instant.ofEpochMilli(now)),
                pageSize = 2000,
                order    = "asc"
            ).data
        }.getOrNull() ?: return

        if (entries.isEmpty()) return

        val sorted = entries.sortedBy { it.date }
        sorted.forEachIndexed { i, entry ->
            val prev  = if (i > 0) sorted[i - 1] else null
            val delta = if (prev != null) (entry.sgv - prev.sgv).roundToInt() else 0
            val arrow = entry.direction.toArrow()

            dao.insertReading(
                GlucoseEntity(
                    dateMs     = entry.date,
                    sgvMgDl   = entry.sgv,
                    direction  = entry.direction,
                    arrow      = arrow,
                    status     = GlucoseThresholds.DEFAULT.status(entry.sgv),
                    delta      = delta,
                    isStale    = false,
                    minutesAgo = 0,
                    ownerEmail = owner
                )
            )
        }
    }

    // GapBackfillWorker (validador noturno) — reenvia leituras locais que faltam no GluSync,
    // preservando o horário real de cada uma (o servidor grava o Date informado, não "agora").
    suspend fun sendReadingsToCloud(readings: List<GlucoseEntity>): Boolean {
        if (readings.isEmpty()) return true
        // ADR-0030 — sem a nuvem liberada, nada sai do aparelho (o lote fica para depois).
        if (!preferences.cloudUploadAllowed.first()) return false
        val entries = readings.map {
            GlucoseEntryDto(date = it.dateMs, sgv = it.sgvMgDl, direction = it.direction, device = "GluView-Backfill")
        }
        return runCatching {
            val response = api.postEntries(entries)
            // 403 do controle de acesso: a liberação foi revogada — para de tentar até reconsultar.
            if (response.code() == 403) preferences.saveCloudSyncState(CloudAccessRepository.STATE_REVOKED)
            response.isSuccessful
        }.getOrDefault(false)
    }

    private fun Double.roundToInt(): Int = Math.round(this).toInt()

    // Converte tanto texto Nightscout ("Flat", "FortyFiveUp") quanto Unicode direto ("→", "↑")
    private fun String.toArrow(): String = when (this.lowercase()) {
        "doubledown",   "↓↓" -> "↓↓"
        "singledown",   "↓"  -> "↓"
        "fortyfivedown","↘"  -> "↘"
        "flat",         "→"  -> "→"
        "fortyfiveup",  "↗"  -> "↗"
        "singleup",     "↑"  -> "↑"
        "doubleup",     "↑↑" -> "↑↑"
        else                  -> "→"
    }

    // Zona pela regra única (GlucoseThresholds, L2): o status gravado é só registro; a zona sai
    // do valor + limites atuais (o status antigo usava 55/70/180/250 fixos).
    private fun GlucoseEntity.toDomain(thresholds: GlucoseThresholds) = GlucoseReading(
        sgvMgDl    = sgvMgDl,
        direction  = direction,
        arrow      = arrow,
        timestamp  = "",
        zone       = thresholds.classify(sgvMgDl, arrow, isStale),
        delta      = delta,
        isStale    = isStale,
        minutesAgo = minutesAgo,
        epochMs    = dateMs
    )
}
