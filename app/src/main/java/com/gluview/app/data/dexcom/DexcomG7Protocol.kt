package com.gluview.app.data.dexcom

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * PT-3 — Dexcom G7, parte pura (sem Bluetooth). Porte do caminho G7 do driver nativo do xDrip
 * (NightscoutFoundation/xDrip, GPLv3, pacote `g5model`: `EGlucoseTxMessage(small=true)`,
 * `EGlucoseRxMessage2`, `BackFillTxMessage2`, `BackFillStream.pushNew(.., 1)`, `CalibrationState`;
 * commit de referência 7c14016d). A autenticação EC-JPAKE fica no módulo `:keks` (libkeks do xDrip).
 *
 * Depois de autenticado, o G7 fala em claro na característica Control: o registro 0x4E já vem
 * decifrado (o que o [DexcomG7Parser] lê) e o histórico chega na característica Backfill.
 */
object DexcomG7Tx {
    /** Pede a glicemia atual: `EGlucoseTxMessage(small = true)` = só o opcode 0x4E, sem CRC. */
    fun eGlucose(): ByteArray = byteArrayOf(0x4E)

    /** Histórico entre dois instantes do relógio do sensor (segundos desde o início): opcode 0x59. */
    fun backfill(startDexTime: Int, endDexTime: Int): ByteArray =
        ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN)
            .put(0x59).putInt(startDexTime).putInt(endDexTime).array()
}

/**
 * Estado do sensor informado pelo próprio G7 (byte "state" do registro; `CalibrationState` do xDrip).
 * Só [usable] vira glicemia: aquecimento, fim, expirado e falha NUNCA aparecem como glicemia válida.
 */
enum class G7SensorState {
    WARMING_UP, OK, EXPIRED, ENDED, FAILED, STOPPED, UNKNOWN;

    val usable: Boolean get() = this == OK

    companion object {
        private val failed = setOf(0x0b, 0x0c, 0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x19, 0x1b, 0x1c, 0x1d, 0x1e)

        fun fromByte(b: Int): G7SensorState = when (val v = b and 0xFF) {
            0x02 -> WARMING_UP
            // 0x06 OK, 0x07 "precisa calibrar" (o G7 não exige; o xDrip trata como usável).
            0x06, 0x07 -> OK
            0x0f -> ENDED
            0x18 -> EXPIRED
            0x01, 0x1a, 0xc2 -> STOPPED
            in failed -> FAILED
            else -> if (v == 0x03) FAILED else UNKNOWN // 0x03 = ruído excessivo
        }
    }
}

/**
 * Vida do G7: 30 min de aquecimento, 10 dias de uso + 12 h de tolerância. O estado do próprio sensor
 * manda; o relógio do sensor só reforça (nunca libera um estado que o sensor não liberou).
 */
object G7Lifecycle {
    const val WARMUP_SEC = 30 * 60
    const val LIFE_SEC = 10 * 86_400
    const val GRACE_SEC = 12 * 3_600

    fun classify(state: G7SensorState, secsSinceStart: Int): G7SensorState = when {
        !state.usable -> state
        secsSinceStart in 0 until WARMUP_SEC -> G7SensorState.WARMING_UP
        secsSinceStart >= LIFE_SEC + GRACE_SEC -> G7SensorState.EXPIRED
        else -> state
    }

    /** Ainda dentro dos 10 dias (fora da tolerância de 12 h). */
    fun inGrace(secsSinceStart: Int): Boolean = secsSinceStart in LIFE_SEC until LIFE_SEC + GRACE_SEC
}

/** Uma leitura do G7 com horário de parede já calculado. */
data class G7Reading(
    val atMs: Long,
    val mgdl: Int,
    /** Variação em mg/dL/min (o G7 manda ×10); null = sem tendência. */
    val ratePerMin: Double?,
    val state: G7SensorState,
    val sensorStartMs: Long,
    val sequence: Int,
    val predictedMgdl: Int?
) {
    val valid: Boolean get() = state.usable && mgdl in 40..400
}

object DexcomG7Data {

    /** Opcode do registro de glicemia na Control. */
    const val OPCODE_EGLUCOSE = 0x4E
    const val OPCODE_BACKFILL_CONTROL = 0x59

    /**
     * Registro 0x4E → leitura. Horário: agora − idade (o G7 manda há quantos segundos mediu); o
     * início do sensor sai do relógio do sensor (segundos desde o início).
     */
    fun reading(packet: ByteArray, nowMs: Long): G7Reading? {
        if (packet.isEmpty() || (packet[0].toInt() and 0xFF) != OPCODE_EGLUCOSE) return null
        val g = DexcomG7Parser.parse(packet) ?: return null
        val atMs = nowMs - g.ageSec * 1000L
        val start = atMs - g.secsSinceStart * 1000L
        val state = G7Lifecycle.classify(G7SensorState.fromByte(g.state), g.secsSinceStart)
        return G7Reading(
            atMs = atMs,
            mgdl = g.mgdl,
            ratePerMin = trendRate(g.trend),
            state = state,
            sensorStartMs = start,
            sequence = g.sequence,
            predictedMgdl = g.predictedMgdl.takeIf { it != 0x3FF && it > 0 }
        )
    }

    /** Tendência ×10 (byte com sinal); 0x7F = sem tendência. */
    fun trendRate(trend: Int): Double? = if (trend == 127 || trend == -128) null else trend / 10.0

    /** Seta pela taxa (mg/dL/min), nos cortes usuais do Dexcom (1, 2, 3 mg/dL/min). */
    fun arrow(ratePerMin: Double?): String = when {
        ratePerMin == null -> ""
        ratePerMin <= -3 -> "↓↓"
        ratePerMin <= -2 -> "↓"
        ratePerMin <= -1 -> "↘"
        ratePerMin < 1 -> "→"
        ratePerMin < 2 -> "↗"
        ratePerMin < 3 -> "↑"
        else -> "↑↑"
    }

    /**
     * Registros de histórico do G7 (`BackFillStream` tipo 1): 9 bytes cada — tempo do sensor (s,
     * int32), glicose (u16 & 0xFFF), estado, extra, tendência. Só entram os de estado usável.
     */
    fun backfill(stream: ByteArray, sensorStartMs: Long): List<G7Reading> {
        val out = mutableListOf<G7Reading>()
        val buf = ByteBuffer.wrap(stream).order(ByteOrder.LITTLE_ENDIAN)
        while (buf.remaining() >= 9) {
            val dexTime = buf.int
            val mgdl = buf.short.toInt() and 0x0FFF
            val stateByte = buf.get().toInt()
            buf.get() // extra
            val trend = buf.get().toInt()
            if (dexTime <= 0) continue
            val state = G7Lifecycle.classify(G7SensorState.fromByte(stateByte), dexTime)
            val r = G7Reading(sensorStartMs + dexTime * 1000L, mgdl, trendRate(trend), state, sensorStartMs, -1, null)
            if (r.valid) out += r
        }
        return out
    }

    /** Segundos do relógio do sensor para um instante de parede (para o pedido de histórico). */
    fun dexTime(sensorStartMs: Long, wallMs: Long): Int = ((wallMs - sensorStartMs) / 1000L).toInt()
}
