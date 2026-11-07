package com.gluview.app.data.accuchek.cgm

/**
 * IEEE 11073-20601 SFLOAT de 16 bits: expoente de 4 bits e mantissa de 12 bits, ambos com sinal
 * (complemento de 2); valor = mantissa × 10^expoente.
 *
 * No serviço CGM: glicose (mg/dL), tendência (mg/dL/min) e qualidade (%).
 * Valores especiais: NaN (`0x07FF`), NRes (`0x0800`), reservado (`0x0801`) → NaN;
 * +∞ (`0x07FE`) = acima da faixa ("HI"); −∞ (`0x0802`) = abaixo da faixa ("LO").
 */
object SFloat {
    private const val NAN = 0x07FF
    private const val NRES = 0x0800
    private const val RESERVED = 0x0801
    private const val POS_INF = 0x07FE
    private const val NEG_INF = 0x0802

    /** [raw] = os 16 bits já montados (LSB primeiro no fio). */
    fun decode(raw: Int): Float {
        val mantissaRaw = raw and 0x0FFF
        return when (mantissaRaw) {
            NAN, NRES, RESERVED -> Float.NaN
            POS_INF -> Float.POSITIVE_INFINITY
            NEG_INF -> Float.NEGATIVE_INFINITY
            else -> {
                val mantissa = signExtend(mantissaRaw, 12)
                val exponent = signExtend((raw ushr 12) and 0x0F, 4)
                (mantissa * Math.pow(10.0, exponent.toDouble())).toFloat()
            }
        }
    }

    /** Lê o SFLOAT little-endian em [offset]. */
    fun read(bytes: ByteArray, offset: Int): Float =
        decode((bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8))

    /**
     * Codifica um inteiro pequeno (expoente 0), o caso dos comandos que escrevemos (ex. glicemia de
     * calibração em mg/dL). Faixa da mantissa: −2045..2045 (fora disso cai nos valores especiais).
     */
    fun encodeInt(value: Int): Int {
        require(value in -2045..2045) { "SFLOAT inteiro fora da faixa: $value" }
        return value and 0x0FFF
    }

    private fun signExtend(value: Int, bits: Int): Int {
        val shift = 32 - bits
        return (value shl shift) shr shift
    }
}
