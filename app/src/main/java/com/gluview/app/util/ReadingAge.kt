package com.gluview.app.util

import com.gluview.app.domain.model.GlucoseReading

/**
 * A leitura vira "sem sinal" pela IDADE real (agora − hora da medição), no limiar de Ajustes >
 * Sensor ([noSignalMin]). Fontes locais gravam isStale=false (verdade só naquele instante); sem
 * isto, notificação, Auto e Dream mostravam o último valor como atual para sempre (L12).
 */
fun GlucoseReading.agedFor(noSignalMin: Int, nowMs: Long = System.currentTimeMillis()): GlucoseReading {
    if (isStale || epochMs <= 0L) return this
    return if (nowMs - epochMs >= noSignalMin * 60_000L) copy(isStale = true) else this
}
