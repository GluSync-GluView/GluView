package com.gluview.app.therapy

import com.gluview.app.data.datastore.AppPreferences
import com.gluview.app.data.local.dao.GlucoseDao
import com.gluview.app.data.local.dao.SensorHistoryDao
import com.gluview.therapy.CalFitProblem
import com.gluview.therapy.CalPair
import com.gluview.therapy.CalRejection
import com.gluview.therapy.Calibration
import com.gluview.therapy.LinearCalibration
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/** Par guardado, amarrado ao sensor (o MAC gravado nas leituras). */
data class StoredCalPair(val sensorKey: String, val pair: CalPair)

sealed interface CalAddResult {
    data class Added(val calibration: LinearCalibration?, val fitProblem: CalFitProblem?) : CalAddResult
    data class Rejected(val reason: CalRejection) : CalAddResult
}

/**
 * Plano 21, F3.1 — calibração com glicemia capilar (desligada por padrão). Vale só para os
 * sensores lidos pelo próprio celular (é aplicada em GlucoseRepository.storeExternalReading, o ponto
 * único de gravação deles) e só para o sensor em que os pares foram feitos: trocou o sensor,
 * recomeça do zero. Leituras vindas da nuvem não são recalibradas.
 *
 * Depende do DAO (não do repositório) para não criar dependência circular com o repositório.
 */
@Singleton
class CalibrationManager @Inject constructor(
    private val preferences: AppPreferences,
    private val glucoseDao: GlucoseDao,
    private val sensorHistoryDao: SensorHistoryDao
) {
    @Volatile private var cacheKey: String? = null
    @Volatile private var cacheRaw: String? = null
    @Volatile private var cacheModel: LinearCalibration? = null

    suspend fun enabled(): Boolean = preferences.calibrationEnabled.first()

    suspend fun pairs(): List<StoredCalPair> = decode(preferences.calibrationPairs.first())

    /** Calibração em uso para o sensor, ou null (desligada, poucos pares, ou fora das travas). */
    suspend fun modelFor(sensorKey: String?): LinearCalibration? {
        if (sensorKey.isNullOrBlank() || !enabled()) return null
        val raw = preferences.calibrationPairs.first()
        if (sensorKey == cacheKey && raw == cacheRaw) return cacheModel
        val fit = Calibration.fit(decode(raw).filter { it.sensorKey == sensorKey }.map { it.pair }, System.currentTimeMillis())
        val model = (fit as? Calibration.Fit.Ok)?.calibration
        cacheKey = sensorKey; cacheRaw = raw; cacheModel = model
        return model
    }

    /** Sensor atual: o MAC da leitura mais nova gravada por sensor local. */
    suspend fun currentSensorKey(): String? =
        glucoseDao.getFrom(preferences.userEmail.first(), System.currentTimeMillis() - 30 * 60_000L)
            .filter { !it.sensorMac.isNullOrBlank() }.maxByOrNull { it.dateMs }?.sensorMac

    /** Acrescenta uma ponta de dedo, se passar nas travas. */
    suspend fun add(fingerstickMgDl: Double, atMs: Long = System.currentTimeMillis()): CalAddResult {
        val key = currentSensorKey() ?: return CalAddResult.Rejected(CalRejection.NO_RECENT_SENSOR)
        val rows = glucoseDao.getFrom(preferences.userEmail.first(), atMs - 30 * 60_000L).filter { it.sensorMac == key }
        val sensor = rows.map { it.dateMs to (it.uncalibratedMgDl ?: it.sgvMgDl) }
        val start = sensorHistoryDao.observeActive().first()?.takeIf { it.mac == key }?.startedAt
        Calibration.check(atMs, fingerstickMgDl, start, sensor)?.let { return CalAddResult.Rejected(it) }
        val sensorValue = Calibration.sensorAt(atMs, sensor) ?: return CalAddResult.Rejected(CalRejection.NO_RECENT_SENSOR)
        // (o modelo em cache por sensor é refeito no próximo modelFor: a chave de cache inclui o texto dos pares)
        val all = pairs() + StoredCalPair(key, CalPair(atMs, fingerstickMgDl, sensorValue))
        preferences.saveCalibrationPairs(encode(all.takeLast(MAX_STORED)))
        return when (val f = Calibration.fit(all.filter { it.sensorKey == key }.map { it.pair }, atMs)) {
            is Calibration.Fit.Ok -> CalAddResult.Added(f.calibration, null)
            is Calibration.Fit.Rejected -> CalAddResult.Added(null, f.problem)
        }
    }

    suspend fun undoLast() {
        val key = currentSensorKey()
        val all = pairs()
        val idx = all.indexOfLast { it.sensorKey == key }
        if (idx >= 0) preferences.saveCalibrationPairs(encode(all.filterIndexed { i, _ -> i != idx }))
    }

    suspend fun clearCurrent() {
        val key = currentSensorKey()
        preferences.saveCalibrationPairs(encode(pairs().filterNot { it.sensorKey == key }))
    }

    companion object {
        private const val MAX_STORED = 60

        fun encode(list: List<StoredCalPair>): String = list.joinToString(";") {
            "${it.sensorKey.replace('|', '_').replace(';', '_')}|${it.pair.atMs}|${it.pair.fingerstickMgDl}|${it.pair.sensorMgDl}"
        }

        fun decode(raw: String?): List<StoredCalPair> = raw.orEmpty().split(';').mapNotNull { e ->
            val p = e.split('|')
            if (p.size != 4) return@mapNotNull null
            val at = p[1].toLongOrNull() ?: return@mapNotNull null
            val f = p[2].toDoubleOrNull() ?: return@mapNotNull null
            val s = p[3].toDoubleOrNull() ?: return@mapNotNull null
            StoredCalPair(p[0], CalPair(at, f, s))
        }
    }
}
