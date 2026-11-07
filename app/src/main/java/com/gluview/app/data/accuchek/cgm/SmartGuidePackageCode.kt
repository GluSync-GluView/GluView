package com.gluview.app.data.accuchek.cgm

import com.gluview.app.data.sensor.SensorCode
import com.gluview.app.data.sensor.SensorIdentityMatcher
import java.time.LocalDate
import java.time.YearMonth

/**
 * O que o app tira do DataMatrix GS1 (UDI) da embalagem do SmartGuide.
 *
 * Embalagem do Felipe (07/11/2026): `(01)04015630089215 (10)400002260A (21)1R001165828`, fabricação
 * 2026-03-30, validade 2026-12-11. O sensor anuncia como `AC-<serial>` (Juggluco
 * `AccuGattCallback.matchDeviceName`), então o serial AI(21) é o que liga a caixa ao rádio.
 */
data class SmartGuidePackageCode(
    val gtin: String,
    val serial: String,
    val lot: String? = null,
    val productionDate: LocalDate? = null,
    val expiryDate: LocalDate? = null
) {
    fun toSensorCode(): SensorCode = SensorCode(connectionCode = serial, serial = serial)

    /** Nome Bluetooth esperado para este sensor. */
    val advertisedName: String get() = SmartGuideGatt.NAME_PREFIX + serial
}

/**
 * Leitor do código GS1 da embalagem.
 *
 * Aceita as formas que o leitor de código pode devolver: com identificador de simbologia (`]d2`),
 * com FNC1/GS (0x1D) separando campos de tamanho variável, ou o texto legível com parênteses.
 * Não depende de ordem nem de tamanho total — o Juggluco exige exatamente 46 caracteres sem lote,
 * mas a embalagem brasileira traz o lote (10), então aqui cada AI é lido pela tabela GS1.
 *
 * Reconhece o SmartGuide pelo prefixo de empresa GS1 da Roche no GTIN, `4015630` (o mesmo que o
 * Juggluco usa em `sensoren.hpp`).
 */
object SmartGuidePackageParser {
    const val ROCHE_COMPANY_PREFIX = "4015630"
    private const val GS = '\u001D'

    private val SYMBOLOGY_PREFIXES = listOf("]d2", "]C1", "]Q3", "]e0", "]d1")

    /** AIs de tamanho fixo (sem separador) — tabela GS1. */
    private val FIXED = mapOf(
        "00" to 18, "01" to 14, "02" to 14, "03" to 14,
        "11" to 6, "12" to 6, "13" to 6, "15" to 6, "16" to 6, "17" to 6, "20" to 2
    )

    /** AIs de 2 dígitos de tamanho variável (terminam no GS ou no fim). */
    private val VARIABLE_2 = setOf("10", "21", "22", "30", "37") + (90..99).map { it.toString() }

    /** AIs de 3 e 4 dígitos (variáveis) que podem aparecer em UDI de dispositivos médicos. */
    private val VARIABLE_3 = setOf("240", "241", "250", "251", "400", "401", "402")
    private val FIXED_4 = mapOf("7003" to 10)
    private val VARIABLE_4 = setOf("7240", "8020", "8200")

    /** Devolve o código lido, ou null se não for uma embalagem do SmartGuide. */
    fun parse(raw: String?): SmartGuidePackageCode? {
        val fields = parseElementString(raw ?: return null) ?: return null
        val gtin = fields["01"] ?: return null
        if (!isRocheGtin(gtin)) return null
        val serial = fields["21"]?.let(::normalizeSerial) ?: return null
        return SmartGuidePackageCode(
            gtin = gtin,
            serial = serial,
            lot = fields["10"]?.trim()?.ifEmpty { null },
            productionDate = fields["11"]?.let(::gs1Date),
            expiryDate = fields["17"]?.let(::gs1Date)
        )
    }

    /**
     * Serial digitado à mão (embalagem rasgada, câmera sem foco): só letras e números, 6 a 20
     * caracteres, em maiúsculas. Aceita também o nome Bluetooth inteiro (`AC-1R001165828`).
     */
    fun normalizeSerial(text: String): String? {
        val s = text.trim().uppercase().removePrefix(SmartGuideGatt.NAME_PREFIX)
        return s.takeIf { it.length in 6..20 && it.all { c -> c in 'A'..'Z' || c in '0'..'9' } }
    }

    fun isRocheGtin(gtin: String): Boolean =
        gtin.length == 14 && gtin.all(Char::isDigit) && gtin.substring(1, 8) == ROCHE_COMPANY_PREFIX && gtinCheckDigitOk(gtin)

    internal fun gtinCheckDigitOk(gtin: String): Boolean {
        val digits = gtin.map { it - '0' }
        val sum = digits.dropLast(1).reversed().mapIndexed { i, d -> if (i % 2 == 0) d * 3 else d }.sum()
        return (10 - sum % 10) % 10 == digits.last()
    }

    /** Data GS1 `AAMMDD`; dia `00` = último dia do mês. Séculos pela regra GS1 simplificada (20xx). */
    internal fun gs1Date(v: String): LocalDate? {
        if (v.length != 6 || !v.all(Char::isDigit)) return null
        val year = 2000 + v.substring(0, 2).toInt()
        val month = v.substring(2, 4).toInt()
        val day = v.substring(4, 6).toInt()
        if (month !in 1..12) return null
        return runCatching {
            if (day == 0) YearMonth.of(year, month).atEndOfMonth() else LocalDate.of(year, month, day)
        }.getOrNull()
    }

    /** Separa os campos por AI. Null quando nada reconhecível foi lido. */
    internal fun parseElementString(raw: String): Map<String, String>? {
        var s = raw.trim()
        if (s.isEmpty()) return null
        if (s.contains('(')) return parseHumanReadable(s)
        SYMBOLOGY_PREFIXES.firstOrNull { s.startsWith(it) }?.let { s = s.removePrefix(it) }

        val out = linkedMapOf<String, String>()
        var i = 0
        while (i < s.length) {
            if (s[i] == GS) { i++; continue }
            val rest = s.substring(i)
            val ai2 = rest.take(2)
            val ai3 = rest.take(3)
            val ai4 = rest.take(4)
            val (ai, fixedLen) = when {
                ai2 in FIXED -> ai2 to FIXED.getValue(ai2)
                ai2 in VARIABLE_2 -> ai2 to null
                ai3 in VARIABLE_3 -> ai3 to null
                ai4 in FIXED_4 -> ai4 to FIXED_4.getValue(ai4)
                ai4 in VARIABLE_4 -> ai4 to null
                else -> break // AI desconhecido: fica com o que já foi lido
            }
            val start = i + ai.length
            val end = if (fixedLen != null) {
                (start + fixedLen).takeIf { it <= s.length } ?: break
            } else {
                s.indexOf(GS, start).let { if (it < 0) s.length else it }
            }
            out.putIfAbsent(ai, s.substring(start, end))
            i = end
        }
        return out.ifEmpty { null }
    }

    private fun parseHumanReadable(s: String): Map<String, String>? {
        val out = linkedMapOf<String, String>()
        Regex("""\((\d{2,4})\)\s*([^(]+)""").findAll(s).forEach { m ->
            out.putIfAbsent(m.groupValues[1], m.groupValues[2].trim().filter { it != GS })
        }
        return out.ifEmpty { null }
    }
}

/**
 * Regra de identidade do SmartGuide: o nome anunciado é `AC-` seguido do serial da embalagem
 * (Juggluco `matchDeviceName`: `startsWith("AC-") && endsWith(serial)`; cgm-probe: `AC-<serial>`).
 * Exige o serial inteiro (não só um pedaço), para não confundir sensores próximos.
 */
class SmartGuideIdentityMatcher : SensorIdentityMatcher {
    override fun matches(advertisedName: String?, code: SensorCode): Boolean {
        val name = advertisedName?.trim().orEmpty()
        if (!SmartGuideGatt.looksLikeSmartGuide(name)) return false
        val serial = code.serial.ifEmpty { code.connectionCode }.trim()
        if (serial.length < MIN_SERIAL_LENGTH) return false
        return name.endsWith(serial, ignoreCase = true)
    }

    companion object {
        const val MIN_SERIAL_LENGTH = 6
    }
}
