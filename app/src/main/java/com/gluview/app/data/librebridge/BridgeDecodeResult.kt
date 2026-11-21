package com.gluview.app.data.librebridge

/**
 * Resultado de decodificação de pacote do leitor (porte conceitual de BridgeResponse do xDrip).
 */
class BridgeDecodeResult {
    val commands: MutableList<ByteArray> = mutableListOf()
    var errorMessage: String? = null
    var delayMs: Long = 0
    var stillWaitingForData: Boolean = false
    var gotAllData: Boolean = false

    val hasError: Boolean get() = errorMessage != null
    val shouldDelay: Boolean get() = delayMs > 0
    fun addCommand(bytes: ByteArray) { commands.add(bytes) }
}

/** Pacote FRAM completo entregue por um leitor acoplado, pronto para tentativa de decodificação. */
data class BridgeFramReading(
    val fram: ByteArray,
    val patchUid: ByteArray?,
    val patchInfo: ByteArray?,
    val serial: String,
    val captureTimeMs: Long,
    val sourceHint: String,
    val bridgeBattery: Int? = null
)
