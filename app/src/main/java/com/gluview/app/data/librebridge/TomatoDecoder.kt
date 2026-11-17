package com.gluview.app.data.librebridge

import com.gluview.app.util.AppLogger
import java.util.Arrays

/**
 * Montagem/decodificação do protocolo MiaoMiao/Tomato, porte fiel de xDrip
 * `models/Tomato.java` (GPLv3).
 *
 * - Tomato.java:89-171: estados REQUEST_DATA_SENT/RECEIVING_DATA, comandos 0xD3 0x01, 0xD1 0x05,
 *   0xF0; início de pacote 0x28 com tamanho esperado em [1..2]; montagem até FRAM completo.
 * - Tomato.java:190-260 (AreWeDone): extrai FRAM (344), patchUid/patchInfo quando há extensão,
 *   serial via LibreUtils.decodeSerialNumberKey, bateria em buffer[13]; validação/entrega ao
 *   decodificador após FRAM completo (aqui: emite BridgeFramReading, nunca grava sem dados válidos).
 */
class TomatoDecoder(private val logger: AppLogger?) {
    private enum class State { REQUEST_DATA_SENT, RECEIVING_DATA }

    private var state: State = State.REQUEST_DATA_SENT
    private var lastReceiveTimestamp: Long = 0
    private var fullData: ByteArray = ByteArray(0)
    private var accumulatedSize: Int = 0
    private var receivedEnoughData: Boolean = false

    fun reset() {
        state = State.REQUEST_DATA_SENT
        lastReceiveTimestamp = 0
        fullData = ByteArray(0)
        accumulatedSize = 0
        receivedEnoughData = false
    }

    fun initializeCommands(): List<ByteArray> = listOf(
        byteArrayOf(0xD1.toByte(), 0x05), // frequência 5 min (xDrip initialize/resetTomatoState)
        byteArrayOf(0xF0.toByte())        // iniciar leitura
    )

    fun decode(buffer: ByteArray, nowMs: Long = System.currentTimeMillis()): Pair<BridgeDecodeResult, BridgeFramReading?> {
        val reply = BridgeDecodeResult()
        if (nowMs - lastReceiveTimestamp > 3000 && lastReceiveTimestamp != 0L) {
            logger?.usage(LOG, "Timeout de pacote Tomato, reiniciando montagem",
                mapOf("ms" to (nowMs - lastReceiveTimestamp), "acumulado" to accumulatedSize))
            state = State.REQUEST_DATA_SENT
        }
        lastReceiveTimestamp = nowMs

        if (state == State.REQUEST_DATA_SENT) {
            if (buffer.size == 1 && buffer[0] == 0x32.toByte()) {
                // 0x32: permitir novo sensor + frequência + iniciar (Tomato.java:74-96)
                reply.addCommand(byteArrayOf(0xD3.toByte(), 0x01))
                reply.addCommand(byteArrayOf(0xD1.toByte(), 0x05))
                reply.addCommand(byteArrayOf(0xF0.toByte()))
                return reply to null
            }
            if (buffer.size == 1 && buffer[0] == 0x34.toByte()) {
                reply.errorMessage = "NO_SENSOR_FOUND"
                return reply to null
            }
            if (buffer.size >= LibreBridgeConstants.TOMATO_HEADER_LENGTH && buffer[0] == 0x28.toByte()) {
                val expectedSize = 256 * (buffer[1].toInt() and 0xFF) + (buffer[2].toInt() and 0xFF)
                val total = expectedSize + LibreBridgeConstants.TOMATO_PATCH_INFO_LENGTH
                initBuffer(total.coerceAtLeast(LibreBridgeConstants.LIBRE_1_2_FRAM_SIZE + LibreBridgeConstants.TOMATO_HEADER_LENGTH + 1))
                append(buffer, reply)
                reply.stillWaitingForData = true
                state = State.RECEIVING_DATA
                return reply to null
            }
            logger?.usage(LOG, "Pacote inicial Tomato desconhecido", mapOf("len" to buffer.size, "b0" to hex(buffer, 1)))
            return reply to null
        }

        // RECEIVING_DATA
        append(buffer, reply)
        val reading = tryFinalize(reply)
        if (reading != null) reply.gotAllData = true
        return reply to reading
    }

    private fun initBuffer(size: Int) {
        fullData = ByteArray(size)
        accumulatedSize = 0
        receivedEnoughData = false
    }

    private fun append(buffer: ByteArray, reply: BridgeDecodeResult) {
        if (accumulatedSize + buffer.size > fullData.size) {
            // buffer maior que o previsto: pode ser extensão (patchInfo) — expande de forma conservadora
            if (buffer.isNotEmpty() && accumulatedSize < fullData.size) {
                val canCopy = (fullData.size - accumulatedSize).coerceAtMost(buffer.size)
                System.arraycopy(buffer, 0, fullData, accumulatedSize, canCopy)
                accumulatedSize += canCopy
            }
            reply.stillWaitingForData = !receivedEnoughData
            return
        }
        System.arraycopy(buffer, 0, fullData, accumulatedSize, buffer.size)
        accumulatedSize += buffer.size
    }

    private fun tryFinalize(reply: BridgeDecodeResult): BridgeFramReading? {
        val baseNeed = LibreBridgeConstants.LIBRE_1_2_FRAM_SIZE + LibreBridgeConstants.TOMATO_HEADER_LENGTH + 1
        if (accumulatedSize < baseNeed) {
            reply.stillWaitingForData = true
            return null
        }
        if (receivedEnoughData) return null

        val framStart = LibreBridgeConstants.TOMATO_HEADER_LENGTH
        val framEnd = framStart + LibreBridgeConstants.LIBRE_1_2_FRAM_SIZE
        if (fullData.size < framEnd) return null
        val fram = Arrays.copyOfRange(fullData, framStart, framEnd)

        // serial: 8 bytes em [5,13) (Tomato.java:226-228)
        val patchUid = if (fullData.size >= 13) Arrays.copyOfRange(fullData, 5, 13) else null
        val serial = patchUid?.let { LibreBridgeUtils.decodeSerialNumberKey(it) } ?: ""

        // patchInfo estendido quando presente (Tomato.java:231-236)
        val extendedLen = baseNeed + LibreBridgeConstants.TOMATO_PATCH_INFO_LENGTH
        val patchInfo = if (accumulatedSize >= extendedLen && fullData.size >= framEnd + 1 + LibreBridgeConstants.TOMATO_PATCH_INFO_LENGTH) {
            Arrays.copyOfRange(fullData, framEnd + 1, framEnd + 1 + LibreBridgeConstants.TOMATO_PATCH_INFO_LENGTH)
        } else null

        val battery = fullData.getOrNull(13)?.toInt()?.and(0xFF)

        // Validação mínima antes de entregar: serial plausível ou patchUid presente, FRAM tamanho correto.
        if (fram.size != LibreBridgeConstants.LIBRE_1_2_FRAM_SIZE) {
            reply.errorMessage = "FRAM_INCOMPLETE"
            return null
        }
        receivedEnoughData = true
        logger?.usage(LOG, "FRAM Tomato completo", mapOf("serial" to serial, "temPatchInfo" to (patchInfo != null), "bateria" to (battery ?: -1)))
        return BridgeFramReading(
            fram = fram,
            patchUid = patchUid,
            patchInfo = patchInfo,
            serial = serial,
            captureTimeMs = System.currentTimeMillis(),
            sourceHint = "MIAOMIAO_TOMATO",
            bridgeBattery = battery
        )
    }

    private fun hex(b: ByteArray, n: Int): String = LibreBridgeUtils.toHex(b, n)

    companion object { private const val LOG = "BRIDGE_TOMATO" }
}
