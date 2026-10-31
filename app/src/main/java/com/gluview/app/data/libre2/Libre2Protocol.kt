package com.gluview.app.data.libre2

import com.gluview.app.util.AppLogger

/** Uma medição bruta extraída do pacote BLE. [minuteOffset] = minutos desde o início do sensor. */
data class Libre2RawReading(
    val minuteOffset: Int,
    val raw: Int,
    val temperature: Int = 0
)

/**
 * Decodifica o pacote BLE JÁ DESCRIPTOGRAFADO do Libre 2 em leituras brutas — clean-room.
 *
 * O pacote de streaming carrega a medição atual mais um histórico curto de tendência (cada item com
 * seu offset de minutos). Aqui só extraímos raw + offset; a conversão raw→mg/dL fica no
 * [Libre2Calibration] (aplicado pelo [Libre2Source] junto com a âncora).
 *
 * SEAM (a validar com sensor real): o layout exato dos campos no buffer descriptografado. A estrutura
 * de parsing está pronta e logada; os offsets marcados com TODO(libre2-hw) precisam de confirmação.
 */
class Libre2Protocol(private val logger: AppLogger?) {

    private companion object {
        const val LOG = "LIBRE2_PROTO"
        // Nº de itens de tendência esperados no pacote de streaming (a validar).
        const val TREND_COUNT = 8
    }

    /**
     * Extrai as leituras do buffer descriptografado. Nunca lança: em buffer curto/estranho devolve
     * lista vazia e loga. NUNCA filtra por idade do sensor (mesma regra de ouro do Sibionics).
     */
    fun parse(decrypted: ByteArray): List<Libre2RawReading> {
        if (decrypted.size < 6) {
            logger?.error(LOG, "Buffer curto demais para parse", mapOf("bytes" to decrypted.size))
            return emptyList()
        }

        fun word(at: Int): Int =
            if (at + 1 < decrypted.size)
                ((decrypted[at + 1].toInt() and 0xff) shl 8) or (decrypted[at].toInt() and 0xff)
            else 0

        // TODO(libre2-hw): confirmar offsets. Layout provisório:
        //   [0..1] = minuteOffset do sensor (age em min)
        //   [2..3] = raw atual (mascarar 14 bits)
        //   [4..]  = pares (raw) de tendência a cada minuto anterior
        val ageMin = word(0)
        val out = ArrayList<Libre2RawReading>(TREND_COUNT + 1)

        val currentRaw = word(2) and 0x3fff
        if (currentRaw > 0) out += Libre2RawReading(minuteOffset = ageMin, raw = currentRaw)

        var pos = 4
        var i = 1
        while (i <= TREND_COUNT && pos + 1 < decrypted.size) {
            val raw = word(pos) and 0x3fff
            if (raw > 0) out += Libre2RawReading(minuteOffset = ageMin - i, raw = raw)
            pos += 2
            i++
        }

        logger?.usage(LOG, "Pacote decodificado",
            mapOf("ageMin" to ageMin, "leituras" to out.size, "rawAtual" to currentRaw))
        return out
    }
}
