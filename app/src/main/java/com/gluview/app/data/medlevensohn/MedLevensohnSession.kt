package com.gluview.app.data.medlevensohn

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Comandos que o app escreve em f002 (texto claro — o BLE manager cifra com a chave de sessão).
 * Todos levam CRC16-CCITT-false little-endian no fim (`aidexx/crc.cpp: addcrc16`). Os bytes fixos
 * citados no Juggluco (`aidexx/java.cpp`) são a prova do montador: ver `MedLevensohnSessionTest`.
 */
object MedLevensohnCommands {
    fun withCrc(payload: ByteArray): ByteArray {
        val crc = MedLevensohnCrc.crc16CcittFalse(payload)
        return payload + byteArrayOf((crc and 0xFF).toByte(), (crc ushr 8).toByte())
    }

    private fun cmd(vararg b: Int) = withCrc(ByteArray(b.size) { b[it].toByte() })

    /** Primeira resposta de uma sessão sem hora conhecida (`cmd0x10`). */
    val GREETING = cmd(0x10)
    /** Sessão com hora conhecida (`cmd0x35`) — também a resposta a uma hora de início nova. */
    val RESUME = cmd(0x35, 0x01)
    /** Pede a hora de início ao sensor (`0x21`). */
    val ASK_START_TIME = cmd(0x21)
    /** Pede o último ID guardado no sensor (`askLastID`, `0x22`). */
    val ASK_LAST_ID = cmd(0x22)
    /** Resposta a 0x110 quando o sensor está sem hora (`time0 == 1`). */
    val SENSOR_WITHOUT_TIME = cmd(0x31, 0x01)
    /** Resposta a 0x134. */
    val ANSWER_134 = cmd(0x11)
    /** Resposta a 0x135. */
    val ANSWER_135 = cmd(0x34, 0x01)
    /** Desparear (`unpaircmd`). */
    val UNPAIR = cmd(0xF2)
    /** Limpar o sensor (`clearCMD`). */
    val CLEAR = cmd(0xF3)

    /** Pede o histórico a partir do ID [fromId] (`askHistory`: `0x23` + u16 LE, mínimo 1). */
    fun askHistory(fromId: Int): ByteArray {
        val id = fromId.coerceIn(1, 0xFFFF)
        return withCrc(byteArrayOf(0x23, (id and 0xFF).toByte(), (id ushr 8).toByte()))
    }

    /** Resposta a 0x131: `0x20` + hora local ([LocalStartTime]) — `writeTime`. */
    fun writeTime(nowMs: Long, zone: ZoneId): ByteArray =
        withCrc(byteArrayOf(0x20) + LocalStartTime.fromEpoch(nowMs, zone).toBytes())
}

/**
 * Hora local com fuso e horário de verão em quartos de hora (`LocalStartTime` do Juggluco).
 * Layout: ano u16 LE, mês, dia, hora, min, seg, fuso (s8, quartos de hora, sem o verão), verão (u8).
 */
data class LocalStartTime(
    val year: Int, val month: Int, val day: Int,
    val hour: Int, val minute: Int, val second: Int,
    val timeZoneQuarterHours: Int, val dstQuarterHours: Int
) {
    /** Epoch em segundos; 0 quando o sensor ainda não tem hora (ano < 2010). */
    fun epochSeconds(): Long {
        if (year < 2010) return 0L
        val wall = runCatching { LocalDateTime.of(year, month, day, hour, minute, second) }.getOrNull() ?: return 0L
        val offsetMin = 15 * (timeZoneQuarterHours + dstQuarterHours)
        return wall.toEpochSecond(ZoneOffset.UTC) - offsetMin * 60L
    }

    fun toBytes(): ByteArray = byteArrayOf(
        (year and 0xFF).toByte(), (year ushr 8).toByte(), month.toByte(), day.toByte(),
        hour.toByte(), minute.toByte(), second.toByte(), timeZoneQuarterHours.toByte(), dstQuarterHours.toByte()
    )

    companion object {
        const val SIZE = 9

        fun parse(b: ByteArray, offset: Int): LocalStartTime? {
            if (b.size < offset + SIZE) return null
            return LocalStartTime(
                year = (b[offset].toInt() and 0xFF) or ((b[offset + 1].toInt() and 0xFF) shl 8),
                month = b[offset + 2].toInt() and 0xFF, day = b[offset + 3].toInt() and 0xFF,
                hour = b[offset + 4].toInt() and 0xFF, minute = b[offset + 5].toInt() and 0xFF,
                second = b[offset + 6].toInt() and 0xFF,
                timeZoneQuarterHours = b[offset + 7].toInt(), // com sinal
                dstQuarterHours = b[offset + 8].toInt() and 0xFF
            )
        }

        /** `from_t_time`: hora local do aparelho, verão = 4 quartos de hora quando ativo. */
        fun fromEpoch(epochMs: Long, zone: ZoneId): LocalStartTime {
            val instant = Instant.ofEpochMilli(epochMs)
            val local = LocalDateTime.ofInstant(instant, zone)
            val rules = zone.rules
            val dst = if (rules.isDaylightSavings(instant)) 4 else 0
            val totalQuarters = rules.getOffset(instant).totalSeconds / (15 * 60)
            return LocalStartTime(local.year, local.monthValue, local.dayOfMonth, local.hour, local.minute, local.second,
                totalQuarters - dst, dst)
        }
    }
}

/** Leitura que a sessão manda gravar. [id] = minutos desde o início do sensor. */
data class MedLevReading(
    val id: Int,
    val mgdl: Int,
    /** Tendência em décimos de mg/dL/min (null no histórico). */
    val trendTenths: Int?,
    /** Veio da leitura ao vivo (f003) ou da última leitura (0x111). */
    val live: Boolean
)

enum class MedLevNotice { SENSOR_ENDED, UNPAIRED, UNPAIR_FAILED, CLEARED, CLEAR_FAILED, START_TIME_CHANGED }

/**
 * Estado da sessão com o sensor (o que o Juggluco guarda em `aidexXstream` + `aidexXdat`).
 * [startSec] e [lastReceivedId] são persistidos entre conexões; o resto vale por conexão.
 */
data class MedLevState(
    val hasTime: Boolean = false,
    /** Início do sensor (epoch s), vindo do 0x121. 0 = desconhecido. */
    val startSec: Long = 0L,
    /** 0 = normal; 1 = sensor sem hora (0x121 com ano < 2010); 2 = depois de 0x120. */
    val time0: Int = 0,
    /** Sessão aberta sem chave salva + pareamento (`keywhenconnecting == false`). */
    val unbonded: Boolean = true,
    /** Último ID gravado (histórico contínuo). -1 = nenhum. */
    val lastReceivedId: Int = -1,
    /** Último ID que o sensor diz ter (0x122/0x111). */
    val lastHistoricPos: Int = 0,
    val unpairPending: Boolean = false,
    val clearPending: Boolean = false
)

/** Resultado de processar uma mensagem: novo estado, resposta (texto claro), leituras e avisos. */
data class MedLevStep(
    val state: MedLevState,
    val reply: ByteArray? = null,
    val disconnect: Boolean = false,
    val readings: List<MedLevReading> = emptyList(),
    val notice: MedLevNotice? = null,
    /** Apagar a chave-mestra (depois de "limpar" o sensor). */
    val forgetKey: Boolean = false
)

/**
 * Máquina de estados do canal f002 e da leitura ao vivo f003 — porte fiel de
 * `aidexx/java.cpp` (`aidexXprocessHistoryData`, `aidexXprocessCurrentData`, `aidexXstartCommand`
 * e auxiliares `deviceInfo`, `onceOld`, `receiveLocalStarttime`, `lastValue`, `getPastValues`,
 * `writeTime`, `whenPresent`). Pura: sem Bluetooth, sem relógio próprio (recebe [nowMs]).
 */
object MedLevensohnSession {
    const val LOWEST_MGDL = 18   // aidexXlowest
    const val HIGHEST_MGDL = 800 // aidexXhighest
    private const val CURRENT_SIZE = 15

    private fun u16(b: ByteArray, i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)

    /** true quando os 2 últimos bytes são o CRC16 (LE) do resto. */
    fun crcOk(plain: ByteArray): Boolean {
        if (plain.size < 3) return false
        val n = plain.size - 2
        return MedLevensohnCrc.crc16CcittFalse(plain, 0, n) == u16(plain, n)
    }

    /**
     * Comando depois de derivar a chave de sessão (`aidexXstartCommand`). [hasKey] = a conexão começou
     * com chave-mestra salva E pareado (`keywhenconnecting`).
     */
    fun startCommand(state: MedLevState, hasKey: Boolean): Pair<MedLevState, ByteArray> {
        val next = state.copy(unbonded = !hasKey)
        val cmd = when {
            state.clearPending -> MedLevensohnCommands.CLEAR
            state.unpairPending -> MedLevensohnCommands.UNPAIR
            hasKey && state.hasTime -> MedLevensohnCommands.RESUME
            else -> MedLevensohnCommands.GREETING
        }
        return next to cmd
    }

    /** Uma mensagem decifrada de f002. [bondedNow] = o Android já tem o pareamento. */
    fun onMain(plain: ByteArray, state: MedLevState, bondedNow: Boolean, nowMs: Long, zone: ZoneId): MedLevStep {
        // CRC errado: o Juggluco devolve nullptr, e a camada Java desconecta.
        if (plain.size < 2 || !crcOk(plain)) return MedLevStep(state, disconnect = true)
        return when (u16(plain, 0)) {
            0x110 -> MedLevStep(state, reply = deviceInfoReply(state))
            0x111 -> onceOld(plain, state)
            0x020, 0x120 -> MedLevStep(state.copy(time0 = 2), reply = MedLevensohnCommands.ASK_START_TIME)
            0x121 -> receiveStartTime(plain, state, bondedNow)
            0x122 -> lastValue(plain, state)
            0x123 -> pastValues(plain, state)
            0x124 -> MedLevStep(state)
            0x131 -> MedLevStep(state, reply = MedLevensohnCommands.writeTime(nowMs, zone))
            0x134 -> MedLevStep(state, reply = MedLevensohnCommands.ANSWER_134)
            0x135 -> MedLevStep(state, reply = MedLevensohnCommands.ANSWER_135)
            0x1F2 -> MedLevStep(state.copy(unpairPending = false), disconnect = true, notice = MedLevNotice.UNPAIRED)
            0x0F2 -> MedLevStep(state.copy(unpairPending = false), disconnect = true, notice = MedLevNotice.UNPAIR_FAILED)
            0x1F3 -> MedLevStep(state.copy(clearPending = false, hasTime = false), disconnect = true,
                notice = MedLevNotice.CLEARED, forgetKey = true)
            0x0F3 -> MedLevStep(state.copy(clearPending = false), disconnect = true, notice = MedLevNotice.CLEAR_FAILED)
            else -> MedLevStep(state) // opcode desconhecido: sizeZero (não responde, segue conectado)
        }
    }

    /** `deviceInfo` (0x110). */
    private fun deviceInfoReply(s: MedLevState): ByteArray = when {
        s.time0 == 1 -> MedLevensohnCommands.SENSOR_WITHOUT_TIME
        s.time0 == 0 && s.hasTime && !s.unbonded -> MedLevensohnCommands.RESUME
        else -> MedLevensohnCommands.ASK_START_TIME
    }

    /** Pede o próximo pedaço do histórico, se houver (`whenPresent`). */
    private fun whenPresent(s: MedLevState): ByteArray? {
        val nextId = s.lastReceivedId + 1
        return if (nextId < s.lastHistoricPos) MedLevensohnCommands.askHistory(nextId) else null
    }

    /** `onceOld` (0x111): última leitura guardada no sensor. */
    private fun onceOld(plain: ByteArray, state: MedLevState): MedLevStep {
        val cur = LastPastGlucose.parse(plain, 2) ?: return MedLevStep(state, disconnect = true)
        val id = cur.minFromStart
        var s = state
        val readings = mutableListOf<MedLevReading>()
        if (id > s.lastReceivedId) {
            if (s.hasTime && cur.valid && cur.glucoseMgDl < 1023) {
                readings += MedLevReading(id, cur.glucoseMgDl, cur.trend, live = true)
            }
            s = s.copy(lastHistoricPos = id)
        } else if (id < s.lastReceivedId) {
            s = s.copy(hasTime = false) // sensor andou para trás: pede a hora de novo
        } else {
            s = s.copy(lastHistoricPos = id)
        }
        if (cur.minFromStart == 0 && s.hasTime) return MedLevStep(s, disconnect = true, readings = readings)
        if (!s.hasTime) return MedLevStep(s, reply = MedLevensohnCommands.ASK_START_TIME, readings = readings)
        return MedLevStep(s, reply = whenPresent(s), readings = readings)
    }

    /** `receiveLocalStarttime` (0x121). */
    private fun receiveStartTime(plain: ByteArray, state: MedLevState, bondedNow: Boolean): MedLevStep {
        val lst = LocalStartTime.parse(plain, 2) ?: return MedLevStep(state, disconnect = true)
        val newStart = lst.epochSeconds()
        if (newStart == 0L) return MedLevStep(state.copy(time0 = 1), disconnect = true)
        var s = state.copy(time0 = 0)
        var newTime = false
        var notice: MedLevNotice? = null
        when {
            s.startSec == newStart -> Unit
            s.startSec == 0L || s.lastReceivedId < 0 -> {
                s = s.copy(startSec = newStart); newTime = true
            }
            s.startSec < newStart -> {
                // Sensor recontou a partir de um início mais novo: os IDs passam a valer a partir dele.
                val shift = ((newStart - s.startSec) / 60).toInt()
                s = s.copy(
                    startSec = newStart,
                    lastReceivedId = (s.lastReceivedId - shift).coerceAtLeast(-1),
                    lastHistoricPos = (s.lastHistoricPos - shift).coerceAtLeast(0)
                )
                newTime = true; notice = MedLevNotice.START_TIME_CHANGED
            }
            else -> return MedLevStep(s, disconnect = true) // início mais antigo que o salvo: ignora
        }
        s = s.copy(hasTime = true)
        if (s.unbonded && bondedNow) return MedLevStep(s, disconnect = true, notice = notice)
        return MedLevStep(s, reply = if (newTime) MedLevensohnCommands.RESUME else MedLevensohnCommands.ASK_LAST_ID, notice = notice)
    }

    /** `lastValue` (0x122): quantos IDs o sensor guarda. */
    private fun lastValue(plain: ByteArray, state: MedLevState): MedLevStep {
        if (plain.size < 8) return MedLevStep(state)
        val mid = u16(plain, 2) or (u16(plain, 4) shl 16)
        if (mid != 0x10001) return MedLevStep(state)
        val lastOnSensor = u16(plain, plain.size - 4)
        val s = state.copy(lastHistoricPos = lastOnSensor)
        return MedLevStep(s, reply = whenPresent(s))
    }

    /** `getPastValues` (0x123): um pedaço do histórico (1 valor por minuto a partir de startID). */
    private fun pastValues(plain: ByteArray, state: MedLevState): MedLevStep {
        if (plain.size < 6) return MedLevStep(state)
        val startId = u16(plain, 2)
        val count = (plain.size - 6) / 2
        var curId = startId
        if (curId <= state.lastReceivedId) {
            return if (startId != 0) MedLevStep(state) else MedLevStep(state.copy(hasTime = false), disconnect = true)
        }
        val readings = mutableListOf<MedLevReading>()
        for (i in 0 until count) {
            val word = u16(plain, 4 + i * 2)
            val valid = (word shr 15) and 1 != 0
            val mgdl = word and 0x3FF
            if (valid && mgdl in LOWEST_MGDL..HIGHEST_MGDL && state.hasTime) {
                readings += MedLevReading(curId, mgdl, null, live = false)
            }
            curId++
        }
        val s = state.copy(lastReceivedId = curId - 1)
        val reply = if (curId < s.lastHistoricPos) MedLevensohnCommands.askHistory(curId) else null
        return MedLevStep(s, reply = reply, readings = readings)
    }

    /**
     * Leitura ao vivo decifrada de f003 (`aidexXprocessCurrentData` + `saveCurrent` + `saveGlucose`).
     * Desconecta quando o CRC não bate, quando não há hora de início (o Juggluco faz o mesmo) e quando
     * o sensor avisa que acabou (tipo 3).
     */
    fun onCurrent(plain: ByteArray, state: MedLevState, nowMs: Long): MedLevStep {
        if (plain.size < 2 || !crcOk(plain)) return MedLevStep(state, disconnect = true)
        val type = plain[0].toInt() and 0xFF
        if (type != 1 && type != 3) return MedLevStep(state)
        if (plain.size - 2 != CURRENT_SIZE) return MedLevStep(state)
        val cur = CurrentGlucose.parse(plain) ?: return MedLevStep(state)
        if (!state.hasTime) return MedLevStep(state, disconnect = true)
        val nowSec = nowMs / 1000
        val eventSec = state.startSec + cur.minFromStart * 60L
        if (eventSec > nowSec + 30) return MedLevStep(state) // leitura "no futuro": relógio desalinhado
        var s = state
        val readings = mutableListOf<MedLevReading>()
        if (cur.valid && cur.glucoseMgDl < 1023) {
            readings += MedLevReading(cur.minFromStart, cur.glucoseMgDl, cur.trend, live = true)
            if (cur.minFromStart == s.lastReceivedId + 1) s = s.copy(lastReceivedId = cur.minFromStart)
        }
        return if (type == 3) MedLevStep(s, disconnect = true, readings = readings, notice = MedLevNotice.SENSOR_ENDED)
        else MedLevStep(s, readings = readings)
    }

    /** Hora de uma leitura: início + minutos, nunca mais que 30 s no futuro (`saveGlucose`). */
    fun readingTimeMs(state: MedLevState, id: Int, nowMs: Long): Long =
        minOf((state.startSec + id * 60L) * 1000L, nowMs)
}
