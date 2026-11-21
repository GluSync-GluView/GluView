package com.gluview.app.data.librebridge

import com.gluview.app.util.AppLogger
import java.util.Arrays

/**
 * Montagem/decodificação do protocolo Bubble, porte fiel de xDrip `models/Bubble.java` (GPLv3).
 *
 * - Bubble.java:28-40 (getBubbleResponse): ACK 02 01 00 00 00 2B, com 08... para patchInfo 9D/E5/E6 e fw>=2.6.
 * - Bubble.java:56-131 (decodeBubblePacket): 0x80 info/bateria/fw -> resposta; 0xC0 patchUid/serial;
 *   0xC1 patchInfo (offset depende de firmware <1.35); 0x82 dados FRAM (344 + footer 8) copiados a partir do byte 4.
 * - Bubble.java:137-161 (AreWeDone): FRAM completo -> HandleGoodReading; aqui entrega BridgeFramReading.
 */
class BubbleDecoder(private val logger: AppLogger?) {
    private var fullData: ByteArray? = null
    private var accumulatedSize: Int = 0
    private var patchUid: ByteArray? = null
    private var patchInfo: ByteArray? = null
    private var serial: String = ""
    private var firmwareVersion: Double = 0.0
    private var battery: Int? = null
    private var errorCount: Int = 0

    fun resetSession() {
        fullData = null
        accumulatedSize = 0
        patchUid = null
        patchInfo = null
        serial = ""
        firmwareVersion = 0.0
        battery = null
        errorCount = 0
    }

    fun initializeCommands(): List<ByteArray> = listOf(byteArrayOf(0x00, 0x01, 0x05))

    private fun ackResponse(): ByteArray {
        val ack = byteArrayOf(0x02, 0x01, 0x00, 0x00, 0x00, 0x2B)
        val pi = patchInfo
        if (pi != null && pi.isNotEmpty()) {
            val first = pi[0].toInt() and 0xFF
            if ((first == 0x9D || first == 0xE5 || first == 0xE6) && firmwareVersion >= 2.6) {
                ack[0] = 0x08
            }
        }
        return ack
    }

    fun decode(buffer: ByteArray): Pair<BridgeDecodeResult, BridgeFramReading?> {
        val reply = BridgeDecodeResult()
        if (buffer.isEmpty()) return reply to null
        val first = buffer[0].toInt() and 0xFF
        when (first) {
            0x80 -> {
                if (buffer.size > 4) battery = buffer[4].toInt() and 0xFF
                firmwareVersion = if (buffer.size > 3) parseFirmware(buffer[2], buffer[3]) else firmwareVersion
                fullData = null
                reply.addCommand(ackResponse())
                return reply to null
            }
            0xC0 -> {
                if (buffer.size >= 10) {
                    patchUid = Arrays.copyOfRange(buffer, 2, 10)
                    serial = LibreBridgeUtils.decodeSerialNumberKey(patchUid!!)
                }
                return reply to null
            }
            0xC1 -> {
                patchInfo = when {
                    firmwareVersion < 1.35 && buffer.size >= 9 -> Arrays.copyOfRange(buffer, 3, 9)
                    buffer.size >= 11 -> Arrays.copyOfRange(buffer, 5, 11)
                    buffer.size >= 9 -> Arrays.copyOfRange(buffer, 3, minOf(9, buffer.size))
                    else -> null
                }
                return reply to null
            }
            0x82, 0x88 -> {
                val expected = LibreBridgeConstants.LIBRE_1_2_FRAM_SIZE + LibreBridgeConstants.BUBBLE_FOOTER
                if (fullData == null) initBuffer(expected)
                appendData(buffer)
                val reading = tryFinalize()
                if (reading != null) reply.gotAllData = true
                return reply to reading
            }
            0xBF -> {
                reply.errorMessage = "NO_SENSOR_FOUND"
                fullData = null
                errorCount++
                if (errorCount <= 2) reply.addCommand(ackResponse())
                return reply to null
            }
        }
        return reply to null
    }

    private fun initBuffer(size: Int) {
        fullData = ByteArray(size)
        accumulatedSize = 0
    }

    private fun appendData(buffer: ByteArray) {
        val dst = fullData ?: return
        if (buffer.size <= 4) return
        val toCopy = buffer.size - 4
        if (accumulatedSize + toCopy > dst.size) {
            // ignora excesso (estado inconsistente)
            logger?.usage(LOG, "Excesso de dados Bubble", mapOf("acc" to accumulatedSize, "chunk" to toCopy))
            return
        }
        System.arraycopy(buffer, 4, dst, accumulatedSize, toCopy)
        accumulatedSize += toCopy
    }

    private fun tryFinalize(): BridgeFramReading? {
        val dst = fullData ?: return null
        if (accumulatedSize < LibreBridgeConstants.LIBRE_1_2_FRAM_SIZE) return null
        val fram = Arrays.copyOfRange(dst, 0, LibreBridgeConstants.LIBRE_1_2_FRAM_SIZE)
        val expected = LibreBridgeConstants.LIBRE_1_2_FRAM_SIZE + LibreBridgeConstants.BUBBLE_FOOTER
        // reset buffer (Bubble.java:AreWeDone reinicia)
        initBuffer(expected)
        errorCount = 0
        logger?.usage(LOG, "FRAM Bubble completo", mapOf("serial" to serial, "temPatchInfo" to (patchInfo != null)))
        return BridgeFramReading(
            fram = fram,
            patchUid = patchUid,
            patchInfo = patchInfo,
            serial = serial,
            captureTimeMs = System.currentTimeMillis(),
            sourceHint = "BUBBLE",
            bridgeBattery = battery
        )
    }

    private fun parseFirmware(major: Byte, minor: Byte): Double =
        "${major.toInt() and 0xFF}.${minor.toInt() and 0xFF}".toDoubleOrNull() ?: 0.0

    companion object { private const val LOG = "BRIDGE_BUBBLE" }
}
