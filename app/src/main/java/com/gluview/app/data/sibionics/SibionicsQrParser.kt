package com.gluview.app.data.sibionics

/**
 * Parser do DataMatrix/QR da embalagem do sensor Sibionics (Sugar Joy / Frente B).
 *
 * A embalagem usa GS1 UDI DataMatrix com os Application Identifiers:
 *   (01) GTIN · (11) data de fabricação · (17) validade · (10) lote · (21) serial
 *
 * Formato do serial AI(21): `YYMMxx` + `8-char-连接码` + `suffix`
 *   Exemplo: "2509461A2B3C4DEF12"
 *     └─ chars 6-13 = 连接码 = Customer ID = "1A2B3C4D"
 *
 * O 连接码 (código de conexão) é passado para [GlobalRegisterCustId] e ativa
 * o contexto do dispositivo na libdata-handle-lib.so.
 * O serial completo AI(21) é passado para [V120Activation].
 */
object SibionicsQrParser {

    data class Result(
        val customerId: String,   // 连接码 — para GlobalRegisterCustId
        val serial: String        // serial AI(21) — para V120Activation
    )

    fun parse(raw: String): Result? {
        if (raw.isBlank()) return null

        // 1. GS1 formatado com parênteses: (21)2509461A2B3C4DEF12
        Regex("""\(21\)([A-Z0-9]{6,20})""", RegexOption.IGNORE_CASE).find(raw)
            ?.groupValues?.get(1)
            ?.let { serial -> return Result(customerId = connectionCodeFrom(serial), serial = serial) }

        // 2. GS1 raw com separadores de controle (FNC1/GS = 0x1D)
        //    Cada segmento começa com o AI de 2 dígitos
        val segments = raw.split('', '', '', '')
        for (seg in segments) {
            if (seg.startsWith("21") && seg.length > 8) {
                val candidate = seg.drop(2).filter { it.isLetterOrDigit() }
                if (candidate.length in 6..20) {
                    return Result(customerId = connectionCodeFrom(candidate), serial = candidate)
                }
            }
        }

        // 3. String curta e alfanumérica = o código de conexão direto
        //    (quando o DataMatrix só contém a 连接码, ex.: "1A2B3C4D")
        val trimmed = raw.trim()
        if (trimmed.length in 4..20 && trimmed.all { it.isLetterOrDigit() }) {
            return Result(customerId = trimmed, serial = "")
        }

        return null
    }

    // Extrai a 连接码 do serial: posições 6-13 (8 chars).
    // Ex.: "2509461A2B3C4DEF12" → "1A2B3C4D"
    fun connectionCodeFrom(serial: String): String =
        if (serial.length >= 14) serial.substring(6, 14) else serial
}
