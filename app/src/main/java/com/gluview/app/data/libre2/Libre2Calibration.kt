package com.gluview.app.data.libre2

import org.json.JSONObject

/**
 * Calibração de fábrica do Libre 2 — reimplementação CLEAN-ROOM.
 *
 * Os parâmetros ([i1]..[i6]) são lidos da FRAM na ativação por NFC e convertem a corrente bruta
 * (raw) do sensor em glicemia. Este é o SEAM técnico mais difícil e o único que exige validação
 * com sensor real (o formato exato dos coeficientes só se confirma medindo).
 *
 * Estratégia (igual à já usada no Sibionics):
 *   - Se houver parâmetros de fábrica válidos → aplica a fórmula de fábrica.
 *   - Enquanto os parâmetros não estiverem validados → cai para a ÂNCORA de ponta de dedo
 *     (fator = referência / bruto), a mesma abordagem de `SibionicsBleSource`.
 * O [anchorFactor] multiplica o resultado nos dois casos (recalibração opcional do usuário).
 */
data class Libre2Calibration(
    val i1: Int = 0,
    val i2: Int = 0,
    val i3: Double = 0.0,
    val i4: Double = 0.0,
    val i5: Double = 0.0,
    val i6: Double = 0.0
) {
    /** True quando temos parâmetros de fábrica plausíveis para aplicar a fórmula. */
    val hasFactoryParams: Boolean get() = i3 != 0.0 || i4 != 0.0 || i5 != 0.0 || i6 != 0.0

    /**
     * Converte a corrente bruta [raw] em mg/dL.
     * @param anchorFactor multiplicador de âncora (1.0 = valor de fábrica, sem ajuste do usuário).
     */
    fun rawToMgdl(raw: Int, anchorFactor: Double = 1.0): Double {
        val base = if (hasFactoryParams) {
            // Fórmula de fábrica do Libre (estrutura afim sobre a corrente bruta).
            // TODO(libre2-hw): confirmar os coeficientes/escala contra um sensor real.
            (raw - i3) * i5 / 1000.0 + i6
        } else {
            // Fallback: bruto tratado como proporcional; a âncora de ponta de dedo dá a escala.
            raw.toDouble()
        }
        return base * anchorFactor
    }

    fun toJson(): String = JSONObject().apply {
        put("i1", i1); put("i2", i2)
        put("i3", i3); put("i4", i4); put("i5", i5); put("i6", i6)
    }.toString()

    companion object {
        fun fromJson(json: String): Libre2Calibration = runCatching {
            val o = JSONObject(json)
            Libre2Calibration(
                i1 = o.optInt("i1"), i2 = o.optInt("i2"),
                i3 = o.optDouble("i3"), i4 = o.optDouble("i4"),
                i5 = o.optDouble("i5"), i6 = o.optDouble("i6")
            )
        }.getOrDefault(Libre2Calibration())

        /**
         * Extrai os parâmetros de calibração da FRAM lida por NFC.
         * TODO(libre2-hw): confirmar os offsets exatos dos coeficientes na FRAM do Libre 2 Plus.
         * Enquanto não validado, devolve calibração vazia → o pipeline usa a âncora de ponta de dedo.
         */
        fun fromFram(fram: ByteArray): Libre2Calibration {
            if (fram.size < 26) return Libre2Calibration()
            // Offsets conhecidos da região de calibração da FRAM do Libre (a validar no 2 Plus).
            fun word(at: Int) = ((fram[at + 1].toInt() and 0xff) shl 8) or (fram[at].toInt() and 0xff)
            return Libre2Calibration(
                i1 = word(2),
                i2 = word(4),
                i3 = word(6).toDouble(),
                i4 = word(8).toDouble(),
                i5 = word(10).toDouble(),
                i6 = word(12).toDouble()
            )
        }
    }
}
