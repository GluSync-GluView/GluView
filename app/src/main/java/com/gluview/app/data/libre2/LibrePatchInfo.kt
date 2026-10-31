package com.gluview.app.data.libre2

import com.gluview.app.R
import com.gluview.app.data.source.SensorRegion
import com.gluview.app.data.source.SensorVariantCatalog

/**
 * Leitura do patchInfo (6 bytes do NFC) do FreeStyle Libre 2 / 2 Plus. Funções puras, testáveis.
 *
 * - Geração (byte 2), regra do Juggluco (getgeneration): decide o PROTOCOLO. Gen2 = caminho da
 *   biblioteca da Abbott (EUA e Brasil); Gen1 = caminho europeu, ainda não implementado aqui.
 * - Região (byte 3), DiaBLE: 1 Europa, 2 EUA, 4 Austrália, 8 resto do mundo. O sensor brasileiro
 *   testado (2B 0A 3A 08) vem com 8, por isso 8 é mostrado como "SA" — não é exclusivo da América
 *   do Sul, então a pessoa sempre pode corrigir a escolha à mão.
 */
object LibrePatchInfo {

    /** 2 = Gen2 (EUA/Brasil), 1 = Gen1 (Europa), 0 = desconhecido. */
    fun generation(info: ByteArray): Int {
        if (info.size < 6) return 0
        val hi = (info[2].toInt() and 0xff) shr 4
        val lo = info[2].toInt() and 0xF
        return when (hi) {
            3 -> if (lo < 9) 1 else 2
            7 -> if (lo < 4) 1 else 2
            else -> 0
        }
    }

    fun regionByte(info: ByteArray): Int = if (info.size > 3) info[3].toInt() and 0xff else 0

    fun region(info: ByteArray): SensorRegion? = when (regionByte(info)) {
        1    -> SensorRegion.EU
        2    -> SensorRegion.NA
        8    -> SensorRegion.SA
        else -> null
    }

    /**
     * Id da variante do catálogo para este sensor, ou null se não der para afirmar (Gen1, região
     * desconhecida). [currentVariantId] preserva o modelo escolhido (Libre 2 × 2 Plus).
     */
    fun detectedVariantId(info: ByteArray, currentVariantId: String?): String? {
        if (generation(info) != 2) return null
        val region = region(info) ?: return null
        val plus = SensorVariantCatalog.byId(currentVariantId)?.modelRes != R.string.sensor_model_libre2
        return SensorVariantCatalog.libreVariant(region, plus)?.id
    }
}

/** Sensor Libre de geração 1 (Europa): protocolo ainda não implementado no GluView. */
class LibreGen1NotSupportedException : IllegalStateException("Libre Gen1 (Europa) ainda não suportado")
