package com.gluview.app.data.sibionics

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Seam observável do estado do sensor Sibionics (Frente B).
 *
 * A fonte BLE (`SibionicsBleSource`) escreve aqui a cada leitura/evento; a UI
 * (Home/Diagnóstico) e o avaliador de aviso de saúde leem deste StateFlow.
 * Singleton para ser compartilhado entre o serviço e os ViewModels via Hilt.
 */
@Singleton
class SibionicsStatusHolder @Inject constructor() {
    private val _status = MutableStateFlow(SibionicsSensorStatus())
    val status: StateFlow<SibionicsSensorStatus> = _status.asStateFlow()

    fun set(status: SibionicsSensorStatus) { _status.value = status }
    fun update(transform: (SibionicsSensorStatus) -> SibionicsSensorStatus) = _status.update(transform)

    /** Registra uma leitura recebida agora (atualiza início/index/último-dado). */
    fun onReading(
        index: Int,
        sensorStartMs: Long,
        nowMs: Long = System.currentTimeMillis(),
        batteryMv: Int = 0
    ) = _status.update {
        it.copy(
            connected     = true,
            lastReadingMs = nowMs,
            lastIndex     = index,
            // Mantém o início já conhecido; só adota o reconstruído se ainda não temos um
            sensorStartMs = if (it.sensorStartMs > 0L) it.sensorStartMs else sensorStartMs,
            batteryMv     = if (batteryMv > 0) batteryMv else it.batteryMv
        )
    }

    fun onConnectionChanged(connected: Boolean) = _status.update { it.copy(connected = connected) }
    fun onRssi(rssi: Int) = _status.update { it.copy(rssi = rssi) }

    /**
     * Descarta todo o estado acumulado (início/index/leitura/bateria) e passa a rastrear [serial].
     * Necessário porque [onReading] mantém o início já conhecido indefinidamente (para sobreviver
     * a reconexões do MESMO sensor) — sem isto, trocar de sensor físico herda o início antigo e
     * data as leituras novas com a idade do sensor anterior.
     */
    fun resetForNewSensor(serial: String) { _status.value = SibionicsSensorStatus(serial = serial) }

    /**
     * Restaura [serial] + a âncora de início PERSISTIDA (ver `AppPreferences.sibionicsSensorStartMs`)
     * depois que o PROCESSO reinicia — este holder é um singleton em memória, então todo restart
     * o recria zerado, o que antes era confundido com "sensor físico diferente" (ver
     * [resetForNewSensor]). Usar isto em vez de [resetForNewSensor] quando já existe uma âncora
     * persistida do mesmo sensor evita reancorar o início com o índice do primeiro pacote de
     * backfill (sempre atrasado em relação ao "ao vivo"), que empurrava as leituras para o futuro.
     */
    fun restoreAnchor(serial: String, sensorStartMs: Long) {
        _status.value = SibionicsSensorStatus(serial = serial, sensorStartMs = sensorStartMs)
    }

    /** Registra a última glicemia bruta do algoritmo (para a tela de calibração). */
    fun onRawMmol(mmol: Double) = _status.update { it.copy(lastRawMmol = mmol) }
}
