package com.gluview.app.data.librebridge

import com.gluview.app.data.libre2.Libre2Native
import com.gluview.app.data.libre2.hexToBytes
import com.gluview.app.util.AppLogger
import kotlin.math.roundToInt

/**
 * Decodifica uma leitura de FRAM Libre 1/2 entregue por leitor acoplado (MiaoMiao/Bubble/Atom).
 *
 * Porte fiel do essencial de xDrip NFCReaderX/LibreUtils (GPLv3) para o caso "FRAM completo":
 * - CRC dos blocos 0..24, 24..296, 320..24 (LibreUtils.verify) — NFCReaderX.HandleGoodReading:310-336.
 * - Status do sensor em data[4] (LibreUtils.isSensorReady): 0x02/0x03/0x04 aceitos.
 * - Tempo do sensor em [316,317] e trend ring buffer (index em [26], 16 registros de 6 bytes a partir de 28)
 *   (NFCReaderX: FRAM_RECORD_SIZE=6, TREND_START=28; parseTrendData/parseData:885-961, getGlucoseRaw:997).
 * - Valor bruto: (256*hi + lo) & 0x1FFF; conversão de referência xDrip: raw/8.5 mg/dL
 *   (Constants.LIBRE_MULTIPLIER=117.64705 => mg/dL = raw*1000/8500 = raw/8.5), usada como base
 *   quando não há decodificação proprietária/OOP. GlucoseData.glucoseLevelRaw é o bruto.
 *
 * Libre 2 com memória cifrada: xDrip delega à OOP2 (patchInfo decodificável) e/ou aplica HandleGoodReading
 * após decifrar. Sem OOP2 no GluView, tenta-se decodificação nativa Abbott quando disponível
 * (Libre2Native.processScan com uid/patchInfo/fram) — mesmo caminho usado pelo Libre 2 Plus no app;
 * se indisponível/falhar, a leitura é recusada (sem estimativa inventada). Libre 2 Plus/3: fora de escopo.
 */
object LibreBridgeFramDecoder {
    private const val LOG = "BRIDGE_FRAM"

    data class Decoded(
        val mgdl: Int,
        val raw: Int,
        val sensorTimeMinutes: Int,
        val serial: String,
        val reliableForLoop: Boolean = false // leitores acoplados = fonte não confiável para alça
    )

    /**
     * @param uidHex UID/patchUid em hex (8 bytes) quando conhecido (sessão/ativação Libre 2); opcional.
     * @param patchInfoHex patchInfo em hex quando conhecido; opcional.
     */
    fun decode(
        reading: BridgeFramReading,
        logger: AppLogger?,
        uidHex: String? = null,
        patchInfoHex: String? = null
    ): Decoded? {
        val fram = reading.fram
        if (fram.size != LibreBridgeConstants.LIBRE_1_2_FRAM_SIZE) {
            logger?.error(LOG, "FRAM com tamanho inválido", mapOf("len" to fram.size))
            return null
        }

        val serial = reading.serial.ifBlank {
            reading.patchUid?.let { LibreBridgeUtils.decodeSerialNumberKey(it) } ?: ""
        }

        // Tenta caminho nativo Abbott (Libre 2 decifrado via processScan) quando temos uid+patchInfo e ponte disponível.
        val uid = reading.patchUid ?: uidHex?.let { runCatching { hexToBytes(it) }.getOrNull() }
        val pi = reading.patchInfo ?: patchInfoHex?.let { runCatching { hexToBytes(it) }.getOrNull() }
        if (uid != null && uid.size >= 6 && pi != null && pi.isNotEmpty() && Libre2Native.available) {
            val scan = runCatching { Libre2Native.processScan(uid, pi, fram) }.getOrNull()
            val r = scan?.reading
            if (r != null && r.mgdl in 20..500) {
                val sensorMin = LibreBridgeUtils.sensorTimeMinutes(fram) ?: 0
                logger?.usage(LOG, "FRAM decodificado via ponte nativa (Libre 2)", mapOf("mgdl" to r.mgdl, "serial" to serial))
                return Decoded(r.mgdl, 0, sensorMin, serial)
            }
        }

        // Caminho Libre 1 (FRAM em claro): CRC + status + trend atual.
        if (!LibreBridgeUtils.verifyLibre1Frame(fram)) {
            logger?.error(LOG, "Checksum FRAM inválido — leitura descartada", mapOf("serial" to serial))
            return null
        }
        val status = fram.getOrNull(4) ?: 0
        if (!LibreBridgeUtils.isSensorReady(status)) {
            logger?.usage(LOG, "Sensor não pronto/expirado pelo status FRAM", mapOf("status" to (status.toInt() and 0xFF), "serial" to serial))
            return null
        }
        val raw = LibreBridgeUtils.currentRawFromFram(fram) ?: run {
            logger?.error(LOG, "Não foi possível extrair raw do trend FRAM", mapOf("serial" to serial))
            return null
        }
        val sensorMin = LibreBridgeUtils.sensorTimeMinutes(fram) ?: 0
        val mgdl = (raw / 8.5).roundToInt().coerceIn(20, 500)
        logger?.usage(LOG, "FRAM Libre 1 decodificado", mapOf("mgdl" to mgdl, "raw" to raw, "sensorMin" to sensorMin, "serial" to serial))
        return Decoded(mgdl, raw, sensorMin, serial)
    }
}
