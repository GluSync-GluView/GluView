package com.gluview.app.data.librebridge

import com.gluview.app.util.AppLogger
import java.util.Arrays

/**
 * Montagem/decodificação do protocolo Atom, porte fiel de xDrip `models/Atom.java` (GPLv3).
 *
 * - Atom.java:28-38 (getAtomResponse): ACK fixo 02 01 00 00 00 2B.
 * - Atom.java:54-121 (decodeAtomPacket): 0x80 info/bateria/fw + ACK; 0xC0 patchUid/serial;
 *   0xC1 patchInfo (depende de firmware <1.35); 0x82 dados FRAM (344 + footer 8) copiados a partir do byte 4.
 * - Atom.java:126-150 (AreWeDone): FRAM completo -> entrega; nunca grava valor inventado.
 */
class AtomDecoder(private val logger: AppLogger?) {
    private var fullData: ByteArray? = null
    private var accumulatedSize: Int = 0
    private var patchUid: ByteArray? = null
    private var patchInfo: ByteArray? = null
    private var serial: String = ""
    private var firmwareVersion: Double = 0.0
    private var battery: Int? = null
    private var errorCount: Int = 0

    fun resetSession() {
        fullData = null; accumulatedSize = 0; patchUid = null; patchInfo = null
        serial = ""; firmwareVersion = 0.0; battery = null; errorCount = 0
    }

    fun initializeCommands(): List<ByteArray> = listOf(byteArrayOf(0x00, 0x01, 0x05))

    private fun ackResponse(): ByteArray = byteArrayOf(0x02, 0x01, 0x00, 0x00, 0x00, 0x2B)

    fun decode(buffer: ByteArray): Pair<BridgeDecodeResult, BridgeFramReading?> {
        val reply = BridgeDecodeResult()
        if (buffer.isEmpty()) return reply to null
        when (buffer[0].toInt() and 0xFF) {
            0x80 -> {
                if (buffer.size > 4) battery = buffer[4].toInt() and 0xFF
                firmwareVersion = if (buffer.size > 3) parseFirmware(buffer[2], buffer[3]) else firmwareVersion
                fullData = null
                reply.addCommand(ackResponse())
            }
            0xC0 -> {
                if (buffer.size >= 10) {
                    patchUid = Arrays.copyOfRange(buffer, 2, 10)
                    serial = LibreBridgeUtils.decodeSerialNumberKey(patchUid!!)
                }
            }
            0xC1 -> {
                patchInfo = when {
                    firmwareVersion < 1.35 && buffer.size >= 9 -> Arrays.copyOfRange(buffer, 3, 9)
                    buffer.size >= 11 -> Arrays.copyOfRange(buffer, 5, 11)
                    buffer.size >= 9 -> Arrays.copyOfRange(buffer, 3, minOf(9, buffer.size))
                    else -> null
                }
            }
            0x82 -> {
                val expected = LibreBridgeConstants.LIBRE_1_2_FRAM_SIZE + LibreBridgeConstants.ATOM_FOOTER
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
            }
        }
        return reply to null
    }

    private fun initBuffer(size: Int) { fullData = ByteArray(size); accumulatedSize = 0 }

    private fun appendData(buffer: ByteArray) {
        val dst = fullData ?: return
        if (buffer.size <= 4) return
        val toCopy = buffer.size - 4
        if (accumulatedSize + toCopy > dst.size) {
            logger?.usage(LOG, "Excesso de dados Atom", mapOf("acc" to accumulatedSize, "chunk" to toCopy))
            return
        }
        System.arraycopy(buffer, 4, dst, accumulatedSize, toCopy)
        accumulatedSize += toCopy
    }

    private fun tryFinalize(): BridgeFramReading? {
        val dst = fullData ?: return null
        if (accumulatedSize < LibreBridgeConstants.LIBRE_1_2_FRAM_SIZE) return null
        val fram = Arrays.copyOfRange(dst, 0, LibreBridgeConstants.LIBRE_1_2_FRAM_SIZE)
        initBuffer(LibreBridgeConstants.LIBRE_1_2_FRAM_SIZE + LibreBridgeConstants.ATOM_FOOTER)
        errorCount = 0
        logger?.usage(LOG, "FRAM Atom completo", mapOf("serial" to serial, "temPatchInfo" to (patchInfo != null)))
        return BridgeFramReading(
            fram = fram, patchUid = patchUid, patchInfo = patchInfo,
            serial = serial, captureTimeMs = System.currentTimeMillis(),
            sourceHint = "ATOM", bridgeBattery = battery
        )
    }

    private fun parseFirmware(major: Byte, minor: Byte): Double =
        "${major.toInt() and 0xFF}.${minor.toInt() and 0xFF}".toDoubleOrNull() ?: 0.0

    companion object { private const val LOG = "BRIDGE_ATOM" }
}
