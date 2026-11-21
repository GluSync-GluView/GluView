package com.gluview.app.data.librebridge

import com.gluview.app.data.libre2.Libre2Native
import com.gluview.app.data.libre2.hexToBytes
import com.gluview.app.util.AppLogger

/**
 * Decodifica uma leitura de FRAM Libre 1/2 entregue por leitor acoplado (MiaoMiao/Bubble/Atom).
 *
 * Porte fiel do essencial de xDrip NFCReaderX/LibreUtils (GPLv3) para o caso "FRAM completo":
 * - CRC dos blocos 0..24, 24..296, 320..24 (LibreUtils.verify) — NFCReaderX.HandleGoodReading:310-336.
 * - Status do sensor em data[4] (LibreUtils.isSensorReady): 0x02/0x03/0x04 aceitos.
 * - Tempo do sensor em [316,317] e trend ring buffer (index em [26], 16 registros de 6 bytes a partir de 28)
 *   (NFCReaderX: FRAM_RECORD_SIZE=6, TREND_START=28; parseTrendData/parseData:885-961, getGlucoseRaw:997).
 * - Valor bruto: (256*hi + lo) & 0x1FFF — é BRUTO, não glicemia (xDrip só o usa com calibração ou OOP);
 *   por isso o GluView não converte bruto em glicemia (auditoria 21/11).
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

        // Auditoria 21/11 (Claude): NÃO há mais o caminho "bruto ÷ 8,5". No xDrip esse valor é o BRUTO do
        // sensor e só vira glicemia com calibração por ponta de dedo ou com o algoritmo oficial (OOP);
        // gravá-lo como glicemia podia errar muito. Sem o algoritmo oficial, a leitura é recusada.
        // (CRC e status continuam conferidos para o log dizer o motivo certo.)
        val motivo = when {
            !LibreBridgeUtils.verifyLibre1Frame(fram) -> "checksum da memória inválido"
            !LibreBridgeUtils.isSensorReady(fram.getOrNull(4) ?: 0) -> "sensor não pronto ou vencido"
            uid == null || pi == null -> "leitor não informou UID/patchInfo do sensor"
            !Libre2Native.available -> "algoritmo oficial (biblioteca da Abbott) indisponível neste aparelho"
            else -> "algoritmo oficial não decodificou esta memória"
        }
        logger?.error(LOG, "Leitura recusada: sem algoritmo oficial não há glicemia confiável", mapOf("motivo" to motivo, "serial" to serial))
        return null
    }
}
