package com.gluview.app.data.libre2

import com.gluview.app.util.AppLogger
import java.util.UUID

/**
 * Decoder experimental e somente observacional.
 *
 * O Libre 2 entrega f002 em 20 + 18 + 8 bytes neste sensor. O decoder monta uma copia
 * de 46 bytes e registra hipoteses de layout/criptografia. Nenhuma hipotese vira leitura
 * do usuario: ate ser validada, o resultado e apenas diagnostico.
 */
class Libre2ExperimentalDecoder(
    private val logger: AppLogger
) {
    private companion object {
        const val LOG = "LIBRE2_EXP_DECODE"
        val DATA_UUID: UUID = Libre2BleManager.DATA_UUID
        const val FRAME_SIZE = 46
    }

    private val frame = ByteArray(FRAME_SIZE)
    private var got20 = false
    private var got18 = false
    // Apenas um indice local de captura; nao e contador do protocolo nem nonce criptografico.
    private var captureIndex = 0

    fun accept(uuid: UUID, bytes: ByteArray) {
        runCatching {
            if (uuid != DATA_UUID) {
                logger.usage(LOG, "Canal nao-f002 observado", mapOf("uuid" to uuid, "bytes" to bytes.size))
                return
            }
            when (bytes.size) {
                20 -> {
                    bytes.copyInto(frame, 0)
                    got20 = true
                    got18 = false
                    logger.usage(LOG, "Fragmento f002 1/3", mapOf("bytes" to bytes.size))
                }
                18 -> {
                    if (!got20) {
                        logger.error(LOG, "Fragmento f002 18B fora de ordem")
                        return
                    }
                    bytes.copyInto(frame, 20)
                    got18 = true
                    logger.usage(LOG, "Fragmento f002 2/3", mapOf("bytes" to bytes.size))
                }
                8 -> {
                    if (!got20 || !got18) {
                        logger.error(LOG, "Fragmento f002 8B fora de ordem")
                        return
                    }
                    bytes.copyInto(frame, 38)
                    got20 = false
                    got18 = false
                    logger.usage(LOG, "Fragmento f002 3/3", mapOf("bytes" to bytes.size))
                    analyzeFrame(frame.copyOf())
                }
                FRAME_SIZE -> analyzeFrame(bytes.copyOf())
                else -> logger.error(LOG, "Tamanho f002 inesperado",
                    mapOf("bytes" to bytes.size, "hex" to bytes.toHex()))
            }
        }.onFailure { error ->
            logger.error(LOG, "Falha isolada no decoder experimental",
                mapOf("erro" to (error.message ?: error::class.simpleName.orEmpty())))
            got20 = false
            got18 = false
        }
    }

    private fun analyzeFrame(bytes: ByteArray) {
        val frameIndex = captureIndex++
        // O corpus HCI/P2 da mesma sessao confirmou somente estes dois bytes como pass-through.
        // O restante do f002 usa um keystream por frame e nao pode ser interpretado sem P2(773).
        val observedHeaderAgeMin = if (bytes.size >= 2) {
            (bytes[0].toInt() and 0xff) or ((bytes[1].toInt() and 0xff) shl 8)
        } else {
            -1
        }

        logger.usage(LOG, "Frame f002 montado: observacao sem decoder",
            mapOf(
                "captureIndex" to frameIndex,
                "bytes" to bytes.size,
                "rawF002Hex" to bytes.toHex(),
                "observedHeaderAgeMin" to observedHeaderAgeMin,
                "encryptedPayloadHex" to bytes.copyOfRange(2, bytes.size).toHex(),
                "decoderAttempted" to false,
                "decodeBlockedBy" to "requires_verified_P1_P2_773_session_state",
                "integrityGate" to "requires_native_plaintext_and_crc_vector",
                "validated" to false,
                "persisted" to false
            ))
    }
}
