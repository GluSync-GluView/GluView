package com.gluview.app.ui.screen.diagnostic

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gluview.app.data.datastore.AppPreferences
import com.gluview.app.data.local.dao.FingerstickLogDao
import com.gluview.app.data.local.entity.FingerstickLogEntity
import com.gluview.app.data.repository.GlucoseRepository
import com.gluview.app.data.sibionics.SibionicsSensorStatus
import com.gluview.app.data.sibionics.SibionicsStatusHolder
import com.gluview.app.data.source.DataSourceType
import com.gluview.app.domain.model.GlucoseReading
import com.gluview.app.util.ServiceMonitor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.abs

data class FingerstickPair(
    val ts: Long,
    val sensorMgDl: Double,
    val fingerstickMgDl: Double,
    val diff: Double,
    val diffPct: Double
)

data class DiagnosticUiState(
    // Serviço / conectividade
    val serviceRunning:   Boolean = false,
    val signalRConnected: Boolean = false,
    val lastSyncAt:       Long    = 0L,
    val syncError:        String? = null,
    // T27 — sensor Sibionics
    val isSibionicsActive: Boolean = false,
    val sensorConnected:   Boolean = false,
    val sensorDaysElapsed: Int?    = null,
    val sensorLastMin:     Long?   = null,
    val sensorRssi:        Int?    = null,
    val sensorBatteryMv:   Int     = 0,
    // T28 — fingerstick
    val currentSgvMgDl: Double?          = null,
    val pairs:          List<FingerstickPair> = emptyList(),
    val mard:           Double?          = null
)

@HiltViewModel
class DiagnosticViewModel @Inject constructor(
    private val monitor: ServiceMonitor,
    private val sibionicsHolder: SibionicsStatusHolder,
    private val preferences: AppPreferences,
    private val repository: GlucoseRepository,
    private val fingerstickDao: FingerstickLogDao
) : ViewModel() {

    private val _pairs = MutableStateFlow<List<FingerstickPair>>(emptyList())

    init {
        viewModelScope.launch {
            fingerstickDao.observeRecent().collect { entities ->
                _pairs.value = entities.map {
                    FingerstickPair(it.ts, it.sensorMgDl, it.fingerstickMgDl, it.diff, it.diffPct)
                }
            }
        }
    }

    // Parte 1: campos do ServiceMonitor (4 flows)
    private val _monitorState = combine(
        monitor.serviceRunning,
        monitor.signalRConnected,
        monitor.lastSyncAt,
        monitor.syncError
    ) { running, connected, lastSync, err ->
        MonitorPart(running, connected, lastSync, err)
    }

    // Parte 2: sensor Sibionics (2 flows)
    private val _sensorState = combine(
        sibionicsHolder.status,
        preferences.dataSource
    ) { sensor, srcId ->
        // isLocalSensor: mostra o painel de sensor também para o Libre 2 (mesmo status holder).
        SensorPart(sensor, DataSourceType.fromId(srcId).isLocalSensor)
    }

    // Parte 3: glicemia atual + fingerstick (2 flows)
    private val _readingState = combine(
        repository.currentReading,
        _pairs
    ) { reading, pairs ->
        ReadingPart(reading, pairs)
    }

    /** null = ainda carregando (antes o 1º quadro mostrava "Parado"/"Desconectado" em vermelho). */
    val uiState: StateFlow<DiagnosticUiState?> = combine(
        _monitorState,
        _sensorState,
        _readingState
    ) { mon, sen, rdg ->
        val mard = if (rdg.pairs.size >= 3) rdg.pairs.map { abs(it.diffPct) }.average() else null
        DiagnosticUiState(
            serviceRunning    = mon.running,
            signalRConnected  = mon.connected,
            lastSyncAt        = mon.lastSync,
            syncError         = mon.err,
            isSibionicsActive = sen.isSibionics,
            sensorConnected   = sen.sensor.connected,
            sensorDaysElapsed = sen.sensor.ageDays(),
            sensorLastMin     = sen.sensor.minutesSinceReading(),
            sensorRssi        = sen.sensor.rssi,
            sensorBatteryMv   = sen.sensor.batteryMv,
            currentSgvMgDl    = rdg.reading?.sgvMgDl,
            pairs             = rdg.pairs,
            mard              = mard
        )
    }.stateIn<DiagnosticUiState?>(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // T28 — registrar par fingerstick vs sensor
    fun recordFingerstick(fingerstickMgDl: Double) {
        val sensorMgDl = repository.currentReading.value?.sgvMgDl ?: return
        viewModelScope.launch {
            val diff    = fingerstickMgDl - sensorMgDl
            val diffPct = if (fingerstickMgDl > 0) (diff / fingerstickMgDl) * 100.0 else 0.0
            fingerstickDao.insert(
                FingerstickLogEntity(
                    ts              = System.currentTimeMillis(),
                    fingerstickMgDl = fingerstickMgDl,
                    sensorMgDl      = sensorMgDl,
                    diff            = diff,
                    diffPct         = diffPct
                )
            )
        }
    }

    // ── Tipos intermediários (escopo privado) ─────────────────────────────────
    private data class MonitorPart(
        val running: Boolean, val connected: Boolean, val lastSync: Long, val err: String?
    )
    private data class SensorPart(
        val sensor: SibionicsSensorStatus, val isSibionics: Boolean
    )
    private data class ReadingPart(
        val reading: GlucoseReading?, val pairs: List<FingerstickPair>
    )
}
