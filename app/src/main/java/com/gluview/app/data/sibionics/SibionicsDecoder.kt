package com.gluview.app.data.sibionics

import com.algorithm.v1_1_0.AlgorithmContext
import com.algorithm.v1_1_0.CGMReplyPacketContext
import com.algorithm.v1_1_0.NativeAlgorithmLibraryV1_1_0 as Alg
import com.gluview.app.util.AppLogger

/**
 * Decodificador de pacotes do sensor Sibionics protocolo v1.1.0 (Nacional).
 *
 * Reusa as bibliotecas proprietárias da Sibionics (libnative-*.so byte-idênticas ao app oficial).
 * Pipeline: pacote BLE cru → spiltReplyData → processEncryptAlgorithmConvert → ig_data (mmol/L)
 *
 * Regra de ouro: **sem teto de vida**. Decodifica qualquer `index` — GluView nunca corta por idade.
 *
 * ⚠️ Requer as .so em jniLibs e device real (TODO SB01). Sem elas, `create()` retorna null.
 */
class SibionicsDecoder private constructor(
    private val ctx: AlgorithmContext,
    private val logger: AppLogger?
) {

    /** Uma leitura calibrada. `index` = minutos desde o início do sensor. */
    data class Reading(
        val index: Int,
        val mgdl: Int,
        val mmol: Double,
        val trend: Int
    ) {
        fun sensorStartMs(nowMs: Long = System.currentTimeMillis()): Long =
            nowMs - index.toLong() * 60_000L
    }

    /**
     * Decodifica um pacote BLE cru recebido na FF31. Retorna 0..N leituras.
     * NUNCA filtra por idade do sensor.
     */
    fun decode(raw: ByteArray): List<Reading> {
        val result = runCatching {
            val out = ArrayList<CGMReplyPacketContext>()
            // TODO(SB01): confirmar os ints `type`/`flag` no device (provável 0).
            val n = Alg.spiltReplyData(ctx, 0, raw, raw.size, out, 0)
            logger?.usage("SIBIONICS_V110", "spiltReplyData",
                mapOf("n" to n, "pacotes" to out.size, "rawHex" to raw.toHex(20)))

            if (n <= 0 || out.isEmpty()) return emptyList()

            out.mapNotNull { pkt ->
                val index = pkt.reply_resule_or_index
                // TODO(SB01): confirmar código de sucesso; alvos 3.9–10.0 mmol/L são do app oficial.
                val rc = Alg.processEncryptAlgorithmConvert(ctx, index, pkt, TARGET_LOW, TARGET_HIGH)
                val mmol = ctx.ig_data
                if (mmol <= 0.0) {
                    logger?.usage("SIBIONICS_V110", "Registro descartado (mmol<=0)",
                        mapOf("index" to index, "rc" to rc, "mmol" to mmol))
                    return@mapNotNull null
                }
                logger?.usage("SIBIONICS_V110", "Leitura decodificada",
                    mapOf(
                        "index" to index,
                        "mmol"  to "%.2f".format(mmol),
                        "mgdl"  to Math.round(mmol * MMOL_TO_MGDL).toInt(),
                        "trend" to ctx.ig_trend,
                        "rc"    to rc
                    ))
                Reading(
                    index = index,
                    mgdl  = Math.round(mmol * MMOL_TO_MGDL).toInt(),
                    mmol  = mmol,
                    trend = ctx.ig_trend
                )
            }
        }

        result.exceptionOrNull()?.let { e ->
            logger?.error("SIBIONICS_V110", "Decode falhou",
                mapOf(
                    "error"  to (e.message ?: e::class.simpleName.orEmpty()),
                    "type"   to e::class.simpleName.orEmpty(),
                    "rawHex" to raw.toHex(20)
                ))
        }

        return result.getOrElse { emptyList() }
    }

    fun release() {
        runCatching { Alg.releaseAlgorithmContext(ctx) }
        logger?.usage("SIBIONICS_V110", "Contexto v1.1.0 liberado")
    }

    companion object {
        const val MMOL_TO_MGDL = 18.0182
        const val TARGET_LOW   = 3.9
        const val TARGET_HIGH  = 10.0

        fun create(serial: String, logger: AppLogger? = null): SibionicsDecoder? {
            val result = runCatching {
                val ctx = Alg.getAlgorithmContextFromNative()
                if (ctx == null) {
                    logger?.error("SIBIONICS_V110", "getAlgorithmContextFromNative() retornou null")
                    return null
                }
                // TODO(SB01): confirmar modo (0?) e interpretar o código de retorno no device.
                val rc = Alg.initEncryptAlgorithmContext(ctx, 0, serial)
                logger?.usage("SIBIONICS_V110", "Contexto v1.1.0 inicializado",
                    mapOf("serial" to serial, "initRc" to rc))
                SibionicsDecoder(ctx, logger)
            }

            result.exceptionOrNull()?.let { e ->
                logger?.error("SIBIONICS_V110", "Falha ao criar decoder",
                    mapOf("error" to (e.message ?: e::class.simpleName.orEmpty())))
            }

            return result.getOrNull()
        }

    }
}

/** Hex formatado dos primeiros [maxBytes] bytes. File-private — só para diagnóstico BLE. */
private fun ByteArray.toHex(maxBytes: Int = size): String =
    take(maxBytes).joinToString(" ") { "%02X".format(it) } +
    if (size > maxBytes) " …+${size - maxBytes}" else ""
