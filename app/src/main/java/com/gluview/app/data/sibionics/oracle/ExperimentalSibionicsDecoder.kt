package com.gluview.app.data.sibionics.oracle

import org.json.JSONArray
import org.json.JSONObject

/**
 * Implementação EXPERIMENTAL, 100% Kotlin, do pipeline do sensor Sibionics — a candidata a,
 * um dia, substituir a `.so` proprietária. **NÃO alimenta o app**: só existe para o
 * [SibionicsOracle] comparar contra a saída real da `.so` e gravar logs de validação.
 *
 * Baseada na engenharia reversa das `.so` (ver notas internas do projeto):
 *  - descriptografia = XOR de chave repetida de 16 bytes (chaves extraídas do .rodata);
 *  - checksum = soma dos bytes, complemento de 2 (byte baixo);
 *  - parse do pacote AA 55 09 (registros de 14 bytes, big-endian);
 *  - calibração: pipeline de processamento de sinal (temperature_compensation, Kalman, etc.).
 *
 * ⚠️ ESTADO ATUAL: descriptografia/checksum/parse são FIÉIS e imediatamente validáveis (100% de
 * acerto contra a `.so` em centenas de pacotes reais). A calibração NÃO É — é um AJUSTE ESTATÍSTICO
 * (regressão linear corrente+temp) contra a saída real da `.so` de UMA sessão/UM sensor (ver
 * [FIT_A] para a proveniência exata e os limites). O pipeline com estado de verdade (Kalman,
 * Calibration_baseline, ESA_Compensate, Regular_deconvolution) ainda NÃO foi portado.
 *
 * ⚠️⚠️ ESTE CÓDIGO É CANDIDATO A UM DIA ALIMENTAR GLICEMIA REAL DE UM PACIENTE (DM1). Antes de
 * promover qualquer estágio daqui para o caminho de produção ([SibionicsV112Protocol] /
 * [SibionicsV110Protocol]), ele precisa: (1) reproduzir os estágios com estado da `.so`, não só
 * aproximá-los estatisticamente; (2) ser validado contra MÚLTIPLOS sensores/sessões, cobrindo
 * hipo, hiper e transições rápidas de subida/descida (é exatamente aí que um ajuste estático como
 * o atual falha); (3) bater com a `.so` real dentro de uma tolerância clínica, não só ter R² alto.
 * Cada valor intermediário é logado para permitir esse trabalho, comparando com o
 * `getJsonAlgorithmContext` da `.so` (o estado interno real).
 */
class ExperimentalSibionicsDecoder {

    // ── Tipos de saída ───────────────────────────────────────────────────────────

    /** Registro cru extraído de um pacote (idêntico ao layout que a `.so` recebe). */
    data class ExpRecord(
        val index: Int,
        val temperature: Double,
        val batteryMv: Int,
        val current: Double,
        val rawCurrent: Int,
        val status: Int
    )

    /** Uma leitura calibrada pela implementação experimental. */
    data class ExpReading(
        val index: Int,
        val mmol: Double,
        val mgdl: Int,
        val trend: Int,
        val batteryMv: Int,
        val preliminary: Boolean
    )

    /** Resultado completo de um pacote: passos detalhados + leituras. */
    class ExpResult {
        val steps = JSONArray()
        val readings = ArrayList<ExpReading>()
        fun step(name: String, detail: JSONObject) { steps.put(detail.put("etapa", name)) }
    }

    // ── v1.1.2 (Chinês / Sugar Joy) — pacote EM CLARO ────────────────────────────

    /**
     * Decodifica um pacote AA 55 09 sem criptografia (v1.1.2). É o caminho ativo em uso.
     * Reproduz o parsing EXATO do [com.gluview.app.data.sibionics.SibionicsV112Protocol] a partir
     * do payload bruto, de forma independente (validação do parsing), e calibra preliminarmente.
     */
    fun runV112(raw: ByteArray): ExpResult {
        val res = ExpResult()
        res.step("recebido", JSONObject()
            .put("hex", raw.toHex()).put("len", raw.size))

        if (!headerOk(raw)) {
            res.step("header", JSONObject().put("ok", false).put("motivo", "esperado AA 55 09"))
            return res
        }
        val chk = checksumInfo(raw)
        res.step("checksum", chk)

        val records = parseRecords(raw)
        res.step("parse", JSONObject()
            .put("nRegistros", records.size)
            .put("registros", JSONArray().also { arr ->
                records.forEach { r ->
                    arr.put(JSONObject()
                        .put("index", r.index).put("temp", r.temperature)
                        .put("bateriaMv", r.batteryMv).put("corrente", r.current)
                        .put("correnteBruta", r.rawCurrent).put("estado", r.status))
                }
            }))

        records.forEach { rec ->
            val calib = calibratePreliminary(rec, res)
            res.readings.add(calib)
        }
        return res
    }

    // ── v1.1.0 (Nacional) — pacote CRIPTOGRAFADO ─────────────────────────────────

    /**
     * Tenta decifrar (XOR) e decodificar um pacote v1.1.0. O envelope cru do v1.1.0 ainda não é
     * 100% conhecido (a `.so` decifra internamente em `spiltReplyData`); aqui tentamos as duas
     * chaves e validamos por checksum/header, logando o que der. Exploratório.
     */
    fun runV110(raw: ByteArray): ExpResult {
        val res = ExpResult()
        res.step("recebido", JSONObject().put("hex", raw.toHex()).put("len", raw.size))

        for ((nome, key) in listOf("keyA" to KEY_A, "keyB" to KEY_B)) {
            val dec = xorDecrypt(raw, key)
            val info = JSONObject()
                .put("chave", nome)
                .put("hexDecifrado", dec.toHex())
                .put("headerOk", headerOk(dec))
                .put("checksum", checksumInfo(dec))
            res.step("tentativaDecifra", info)
            if (headerOk(dec)) {
                val records = parseRecords(dec)
                res.step("parse", JSONObject().put("chave", nome).put("nRegistros", records.size))
                records.forEach { rec -> res.readings.add(calibratePreliminary(rec, res)) }
                break
            }
        }
        return res
    }

    // ── Blocos reutilizáveis (todos fiéis à RE) ──────────────────────────────────

    private fun headerOk(b: ByteArray): Boolean =
        b.size >= 4 && (b[0].toInt() and 0xFF) == 0xAA &&
            (b[1].toInt() and 0xFF) == 0x55 && (b[2].toInt() and 0xFF) == 0x09

    /** XOR de chave repetida de 16 bytes: out[i] = in[i] ^ key[i % 16] (global_app_decrypt). */
    fun xorDecrypt(data: ByteArray, key: ByteArray): ByteArray =
        ByteArray(data.size) { i -> (data[i].toInt() xor key[i % key.size].toInt()).toByte() }

    /**
     * Checksum do protocolo (global_app_get_checksum): soma de todos os bytes menos o último,
     * complemento de 2 no byte baixo, comparado ao último byte.
     */
    private fun checksumInfo(b: ByteArray): JSONObject {
        if (b.size < 2) return JSONObject().put("ok", false).put("motivo", "curto")
        var sum = 0
        for (i in 0 until b.size - 1) sum += (b[i].toInt() and 0xFF)
        val calc = ((256 - (sum and 0xFF)) and 0xFF)
        val stored = b[b.size - 1].toInt() and 0xFF
        return JSONObject().put("calculado", calc).put("armazenado", stored).put("ok", calc == stored)
    }

    /** Parse dos registros de 14 bytes do pacote AA 55 09 (layout confirmado no V112Protocol). */
    fun parseRecords(b: ByteArray): List<ExpRecord> {
        if (!headerOk(b) || b.size < 18) return emptyList()
        fun be16(hi: Int, lo: Int) = ((b[hi].toInt() and 0xFF) shl 8) or (b[lo].toInt() and 0xFF)
        val count = (b[3].toInt() and 0xFF).coerceAtLeast(1)
        val out = ArrayList<ExpRecord>(count)
        for (r in 0 until count) {
            val base = r * 14
            if (4 + base + 13 >= b.size) break
            val index = be16(4 + base, 5 + base)
            if (index <= 0) continue
            out.add(ExpRecord(
                index       = index,
                temperature = be16(6 + base, 7 + base) / 10.0,
                batteryMv   = be16(8 + base, 9 + base),
                current     = be16(10 + base, 11 + base) / 10.0,
                rawCurrent  = be16(10 + base, 11 + base),
                status      = be16(12 + base, 13 + base)
            ))
        }
        return out
    }

    /**
     * Calibração por AJUSTE ESTATÍSTICO (não é a calibração real — ver aviso completo em [FIT_A]).
     * Mantém `temperature_compensation` (porte fiel da v1.1.0, via RE) só como diagnóstico logado,
     * lado a lado, para comparação futura — mas o resultado abaixo usa a regressão linear direta
     * (corrente + temp) ajustada contra a saída real da `.so`, que hoje explica a variância melhor
     * que a composição via temperature_compensation isolada. A bateria vem direto do pacote
     * (não da calibração), então bate exato independente do estágio de calibração.
     */
    private fun calibratePreliminary(rec: ExpRecord, res: ExpResult): ExpReading {
        val tempCompV110Diagnostico = temperatureCompensationV110(rec.temperature, rec.current)

        val mmol = (FIT_A * rec.current + FIT_B * rec.temperature + FIT_C).coerceAtLeast(0.0)
        val mgdl = Math.round(mmol * MMOL_TO_MGDL).toInt()

        res.step("calibracao", JSONObject()
            .put("index", rec.index)
            .put("entrada", JSONObject()
                .put("corrente", rec.current).put("temp", rec.temperature))
            .put("temperature_compensation_v110_diagnostico", tempCompV110Diagnostico)
            .put("modelo", FIT_DESCRICAO)
            .put("mmol", mmol).put("mgdl", mgdl))

        return ExpReading(
            index = rec.index, mmol = mmol, mgdl = mgdl,
            trend = 0, batteryMv = rec.batteryMv, preliminary = true
        )
    }

    /**
     * Porte FIEL de `AlgorithmV1_1_0::temperature_compensation(double temp, double current)`
     * (desmontagem ARM64 em `algorithm-v1_1_0.so`). Fator de correção de temperatura aplicado à
     * corrente. Constantes lidas do `.rodata`. NOTA: a lib v1.1.2 usa constantes diferentes.
     */
    fun temperatureCompensationV110(temp: Double, current: Double): Double {
        var t = maxOf(temp, 20.0)
        if (t > 41.0) t = 32.0
        // Polinômio de 6º grau em t (coeficientes 0x9d78..0x9da0 do .rodata).
        var poly = K1 * t + K2 * t * t
        poly += K3 * Math.pow(t, 3.0)
        poly += K4 * Math.pow(t, 4.0)
        poly += K5 * Math.pow(t, 5.0)
        poly += K6 * Math.pow(t, 6.0)
        var s = poly + C_ADD          // + 360.13987401
        s = s * C_MUL                 // * 0.7796610169491518
        s = s + C_ADD2                // + 9.727118644067819
        s = s + 1.0
        s = 37.0 - s
        s = s * C_SCALE               // * 0.02
        return (current - 0.5) * s + current
    }

    private fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it) }

    companion object {
        const val MMOL_TO_MGDL = 18.0182

        // Chaves XOR extraídas de libnative-encrypy-decrypt-v110.so (.rodata). Seletor 0x4e2b → keyB.
        val KEY_A = hex("b08efcfc205cf21ecd9ec3990937aae8")
        val KEY_B = hex("c08efcfc205cf23edd9ec3990937aae8")

        // temperature_compensation (v1.1.0) — constantes do .rodata (0x9d78..).
        private const val K1 = -0.588016277
        private const val K2 = -6.92303637
        private const val K3 = 0.673081791
        private const val K4 = -0.0272482356
        private const val K5 = 0.00051947842
        private const val K6 = -3.84905329e-06
        private const val C_ADD = 360.13987401
        private const val C_MUL = 0.7796610169491518
        private const val C_ADD2 = 9.727118644067819
        private const val C_SCALE = 0.02

        // ── Ajuste estatístico corrente+temp → mmol/L ───────────────────────────────
        //
        // Regressão linear (mínimos quadrados) contra a saída REAL de `processAlgorithmContext`
        // da `.so`, derivada de N=728 pacotes capturados AO VIVO em 2026-07-04 (SM-S711B, sensor
        // Sibionics Chinês v1.1.2, 連接código de um sensor de teste, ~12,3h contínuas, faixa 85–301 mg/dL,
        // corrente 6,5–19,0, temp 28,7–35,7°C). R² = 0,963 NESSA amostra.
        //
        // ⚠️⚠️ NÃO É A CALIBRAÇÃO REAL. É um checkpoint estatístico, com limites sérios:
        //  - é o ajuste de UM sensor/UMA sessão. A calibração de fábrica é injetada por
        //    initAlgorithmContext a partir do 連接código/serial (ver SibionicsV112Protocol) — cada
        //    sensor tem a sua. Este fit NUNCA foi testado noutro sensor e é esperado que erre em
        //    sensores diferentes deste.
        //  - não captura os ~3,7% de variância restante, que vêm do pipeline com estado real da
        //    `.so` (mKalmanContext, mCalBaselineContext, mESACompensateContext,
        //    mRegularDeconvolutionContext — todos vistos no `contextoNativo` capturado). É
        //    justamente nessa fração que mora a robustez a ruído e a resposta correta em subidas/
        //    quedas rápidas — as situações clinicamente mais sensíveis (hipo, hiper, correção).
        //  - existe SÓ para os logs de comparação (oracle_cmp) pararem de divergir grosseiramente
        //    enquanto o pipeline com estado não é portado de verdade. NÃO promover isto ao caminho
        //    de produção como está — antes, portar os estágios com estado e revalidar com múltiplos
        //    sensores/sessões cobrindo hipo/hiper/transições rápidas (ver aviso na doc da classe).
        private const val FIT_A = 0.8581   // coeficiente da corrente
        private const val FIT_B = -0.3444  // coeficiente da temperatura
        private const val FIT_C = 10.4996  // intercepto
        private const val FIT_DESCRICAO =
            "AJUSTE ESTATÍSTICO (NÃO é a calibração real): mmol = $FIT_A*corrente + $FIT_B*temp + $FIT_C " +
            "— regressão de N=728 pacotes, 1 sensor/sessão, 2026-07-04, R²=0.963. Ver aviso em FIT_A."

        private fun hex(s: String): ByteArray =
            ByteArray(s.length / 2) { ((s[it * 2].digitToInt(16) shl 4) or s[it * 2 + 1].digitToInt(16)).toByte() }
    }
}
