package com.gluview.app.data.sibionics

import com.gluview.app.data.sibionics.oracle.SibionicsOracle
import com.gluview.app.util.AppLogger

/**
 * Protocolo v1.1.0 — Sibionics Nacional.
 *
 * Usa [SibionicsDecoder] → libnative-algorithm-jni-v110.so:
 *   getAlgorithmContextFromNative → initEncryptAlgorithmContext →
 *   spiltReplyData → processEncryptAlgorithmConvert → ctx.ig_data (mmol/L)
 *
 * No v1.1.0 o sensor transmite automaticamente após CCCD habilitado — sem comando de init.
 *
 * TODO(SB01): se não chegar nenhum pacote após CCCD, descomentar `sendCommand` em onReady().
 */
class SibionicsV110Protocol private constructor(
    private val decoder: SibionicsDecoder,
    private val logger: AppLogger?,
    private val serial: String,
    // Instrumentação de validação (não invasiva). null = desligada. Ver [SibionicsOracle].
    private val oracle: SibionicsOracle? = null
) : SibionicsProtocol {

    override fun onReady(sendCommand: (ByteArray) -> Unit) {
        logger?.usage(LOG, "CCCD pronto — aguardando transmissão automática do sensor v1.1.0")
        // TODO(SB01): se o sensor não transmitir, testar enviar um comando inicial:
        // sendCommand(byteArrayOf(0xAA.toByte(), 0x55, 0x09, 0x01, 0x00, 0x00))
        // logger?.usage(LOG, "Comando inicial v1.1.0 enviado")
    }

    override fun onPacket(raw: ByteArray, sendCommand: (ByteArray) -> Unit): List<SibionicsProtocol.Reading> {
        val decoded = decoder.decode(raw).map { r ->
            SibionicsProtocol.Reading(r.index, r.mgdl, r.mmol, r.trend)
        }
        // Detalhe de decodificação já foi logado dentro do SibionicsDecoder.
        // Aqui logamos apenas o resumo final para o SibionicsBleSource.
        if (decoded.isEmpty()) {
            logger?.usage(LOG, "Pacote decodificado mas sem leituras válidas")
        }
        // Instrumentação (não invasiva): espelha payload + saída real da .so p/ o oráculo.
        val o = oracle
        if (o != null && decoded.isNotEmpty()) {
            val soReadings = decoded.map {
                SibionicsOracle.SoReadingV110(it.index, it.mmol, it.mgdl, it.trend)
            }
            runCatching { o.recordV110(raw, serial, soReadings, ctxJson = null) }
        }
        return decoded
    }

    override fun release() {
        decoder.release()
    }

    companion object {
        private const val LOG = "SIBIONICS_V110"

        fun create(serial: String, logger: AppLogger? = null, oracle: SibionicsOracle? = null): SibionicsV110Protocol? =
            SibionicsDecoder.create(serial, logger)?.let { SibionicsV110Protocol(it, logger, serial, oracle) }
    }
}
