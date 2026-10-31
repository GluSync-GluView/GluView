package com.gluview.app.data.sibionics.oracle

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ponte de VALIDAÇÃO (não invasiva) entre a `.so` proprietária e o [ExperimentalSibionicsDecoder].
 *
 * ## Contrato de segurança (prioridade máxima)
 *  - NÃO altera o fluxo do app: os protocolos chamam a `.so` normalmente e continuam usando o
 *    resultado dela; esta classe só OBSERVA (mesmo payload → decoder experimental) e grava logs.
 *  - NÃO alimenta o app, NÃO grava em Room, NÃO chama rede. Só escreve nos arquivos do [OracleLog].
 *  - NUNCA lança para o chamador: cada `record*` copia os dados e despacha para uma thread própria;
 *    todo o trabalho roda dentro de try/catch. Se algo falhar aqui, o app não percebe.
 *  - NÃO toca no contexto nativo: recebe o `ctxJson` JÁ serializado pela thread do protocolo.
 *
 * Objetivo: acumular dados suficientes (payload bruto + entrada/saída da `.so` + estado interno em
 * JSON + passos do experimental + diffs) para, no futuro, reimplementar a calibração em Kotlin puro.
 */
@Singleton
class SibionicsOracle @Inject constructor(
    private val log: OracleLog
) {
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "sibionics-oracle").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }
    private val decoder = ExperimentalSibionicsDecoder()

    // `seq` baseado em relógio de parede (não num contador em memória): sobrevive a reinícios do
    // processo sem colidir — um AtomicLong puro reiniciava em 1 a cada restart, gerando IDs
    // duplicados entre sessões no mesmo dia (achado analisando os logs). O sub-contador só
    // desempata leituras dentro do mesmo milissegundo (nunca acontece no nosso 1 pacote/min, mas
    // é grátis e protege multi-registro no mesmo pacote).
    private val seqSub = AtomicLong(0)
    private fun nextSeq(): Long = System.currentTimeMillis() * 1000 + (seqSub.incrementAndGet() % 1000)

    // Amostragem do contexto nativo completo: grava-o por inteiro só periodicamente ou quando
    // cgm_warning muda — nos demais pacotes só um resumo compacto (bem mais barato em disco,
    // ~6-8MB/dia hoje). Estado por protocolo (v1.1.2/v1.1.0), tocado só dentro do `worker`.
    private class CtxSampling(var packetsSinceFull: Int = 0, var lastWarning: Int? = null)
    private val ctxSampling = mutableMapOf<String, CtxSampling>()
    private fun shouldIncludeFullContext(protocolo: String, warnNow: Int?): Boolean {
        val st = ctxSampling.getOrPut(protocolo) { CtxSampling() }
        val warnChanged = st.lastWarning != null && warnNow != null && st.lastWarning != warnNow
        val include = st.lastWarning == null || warnChanged || st.packetsSinceFull >= FULL_CTX_EVERY_N
        st.packetsSinceFull = if (include) 0 else st.packetsSinceFull + 1
        if (warnNow != null) st.lastWarning = warnNow
        return include
    }

    /**
     * Extrai só os campos DECISIVOS do contexto nativo (cgm_warning + contadores de confirmação
     * de tendência de notas internas do projeto) — grátis de calcular, e vira o campo
     * `contextoResumo`, presente em TODO pacote (mesmo quando o contexto completo não é gravado).
     */
    private fun extractCompactContext(ctxJson: String?): JSONObject? {
        if (ctxJson.isNullOrBlank()) return null
        return runCatching {
            val full = JSONObject(ctxJson)
            val calib = full.optJSONObject("mCGMCalibrationContext")
            JSONObject()
                .put("cgm_warning", full.opt("cgm_warning"))
                .put("cgm_current_abnormal", full.opt("cgm_current_abnormal"))
                .put("cgm_temp_abnormal", full.opt("cgm_temp_abnormal"))
                .put("IG_data", full.opt("IG_data"))
                .put("cgm_blood_glucose_trend", full.opt("cgm_blood_glucose_trend"))
                .put("up_trend_continue_count", calib?.opt("up_trend_continue_count"))
                .put("down_trend_continue_count", calib?.opt("down_trend_continue_count"))
        }.getOrNull()
    }

    /** Uma chamada da `.so` de calibração v1.1.2 (`processAlgorithmContext`) já executada. */
    data class SoReadingV112(
        val index: Int,
        val current: Double,
        val temp: Double,
        val mmol: Double,
        val trend: Int,
        val batteryMv: Int,
        val procNanos: Long
    )

    // ── v1.1.2 (Chinês / Sugar Joy) ──────────────────────────────────────────────

    /**
     * Registra um pacote v1.1.2 já processado pela `.so`. Chamado no fim de `onPacket`, na thread
     * BLE. Copia tudo e devolve o controle imediatamente — o processamento é assíncrono.
     */
    fun recordV112(
        raw: ByteArray,
        soReadings: List<SoReadingV112>,
        targetLow: Double,
        targetHigh: Double,
        ctxJson: String?
    ) {
        val rawCopy = raw.copyOf()
        val readings = ArrayList(soReadings)
        submit {
            val seq = nextSeq()

            // Resumo compacto (barato) + decisão de incluir o contexto completo (caro) desta vez.
            val compact = extractCompactContext(ctxJson)
            val warnNow = (compact?.opt("cgm_warning") as? Int)
            val includeFull = ctxJson != null && shouldIncludeFullContext("v1.1.2", warnNow)

            // (1) Log da interceção + retorno da `.so`.
            val soArr = JSONArray()
            readings.forEach { r ->
                soArr.put(JSONObject()
                    .put("index", r.index)
                    .put("entrada", JSONObject()
                        .put("corrente", r.current).put("temp", r.temp)
                        .put("alvoBaixo", targetLow).put("alvoAlto", targetHigh))
                    .put("saida", JSONObject()
                        .put("mmol", r.mmol).put("mgdl", Math.round(r.mmol * 18.0182).toInt())
                        .put("trend", r.trend).put("bateriaMv", r.batteryMv))
                    .put("procNanos", r.procNanos))
            }
            val soEntry = JSONObject()
                .put("seq", seq).put("protocolo", "v1.1.2")
                .put("funcaoSo", "processAlgorithmContext")
                .put("payload", JSONObject().put("hex", rawCopy.toHex()).put("len", rawCopy.size))
                .put("leiturasSo", soArr)
                .put("contextoResumo", compact)
            if (includeFull) soEntry.put("contextoNativo", ctxJson?.let { safeJson(it) })
            log.append(OracleLog.Kind.SO, soEntry)

            // (2) Roda o decoder experimental sobre o MESMO payload.
            val exp = decoder.runV112(rawCopy)
            log.append(OracleLog.Kind.EXP, JSONObject()
                .put("seq", seq).put("protocolo", "v1.1.2")
                .put("passos", exp.steps)
                .put("leiturasExperimental", readingsToJson(exp.readings)))

            // (3) Compara por índice.
            compareAndLog(seq, "v1.1.2",
                so = readings.map { Cmp(it.index, Math.round(it.mmol * 18.0182).toInt(), it.batteryMv, it.trend) },
                exp = exp.readings.map { Cmp(it.index, it.mgdl, it.batteryMv, it.trend) },
                preliminary = true, contextoResumo = compact)
        }
    }

    // ── v1.1.0 (Nacional) ────────────────────────────────────────────────────────

    /** Uma leitura da `.so` v1.1.0 (`spiltReplyData` → `processEncryptAlgorithmConvert`). */
    data class SoReadingV110(val index: Int, val mmol: Double, val mgdl: Int, val trend: Int)

    fun recordV110(raw: ByteArray, serial: String, soReadings: List<SoReadingV110>, ctxJson: String?) {
        val rawCopy = raw.copyOf()
        val readings = ArrayList(soReadings)
        submit {
            val seq = nextSeq()
            val compact = extractCompactContext(ctxJson)
            val warnNow = (compact?.opt("cgm_warning") as? Int)
            val includeFull = ctxJson != null && shouldIncludeFullContext("v1.1.0", warnNow)

            val soArr = JSONArray()
            readings.forEach { r ->
                soArr.put(JSONObject().put("index", r.index)
                    .put("saida", JSONObject().put("mmol", r.mmol).put("mgdl", r.mgdl).put("trend", r.trend)))
            }
            val soEntry = JSONObject()
                .put("seq", seq).put("protocolo", "v1.1.0")
                .put("funcaoSo", "spiltReplyData+processEncryptAlgorithmConvert")
                .put("serial", serial)
                .put("payload", JSONObject().put("hex", rawCopy.toHex()).put("len", rawCopy.size))
                .put("leiturasSo", soArr)
                .put("contextoResumo", compact)
            if (includeFull) soEntry.put("contextoNativo", ctxJson?.let { safeJson(it) })
            log.append(OracleLog.Kind.SO, soEntry)

            val exp = decoder.runV110(rawCopy)
            log.append(OracleLog.Kind.EXP, JSONObject()
                .put("seq", seq).put("protocolo", "v1.1.0")
                .put("passos", exp.steps)
                .put("leiturasExperimental", readingsToJson(exp.readings)))

            compareAndLog(seq, "v1.1.0",
                so = readings.map { Cmp(it.index, it.mgdl, 0, it.trend) },
                exp = exp.readings.map { Cmp(it.index, it.mgdl, it.batteryMv, it.trend) },
                preliminary = true, contextoResumo = compact)
        }
    }

    // ── Internos ─────────────────────────────────────────────────────────────────

    private data class Cmp(val index: Int, val mgdl: Int, val batteryMv: Int, val trend: Int)

    private fun compareAndLog(
        seq: Long, proto: String, so: List<Cmp>, exp: List<Cmp>, preliminary: Boolean,
        contextoResumo: JSONObject? = null
    ) {
        val expByIdx = exp.associateBy { it.index }
        val linhas = JSONArray()
        so.forEach { s ->
            val e = expByIdx[s.index]
            val obj = JSONObject().put("index", s.index)
            if (e == null) {
                obj.put("status", "SEM_PAR_EXPERIMENTAL")
            } else {
                val dMgdl = e.mgdl - s.mgdl
                val dBat = e.batteryMv - s.batteryMv
                val glicOk = kotlin.math.abs(dMgdl) <= 2
                val batOk = dBat == 0
                obj.put("glicemia", JSONObject().put("so", s.mgdl).put("exp", e.mgdl).put("dif", dMgdl))
                    .put("bateria", JSONObject().put("so", s.batteryMv).put("exp", e.batteryMv).put("dif", dBat))
                    .put("trend", JSONObject().put("so", s.trend).put("exp", e.trend))
                    .put("status", if (glicOk && batOk) "COINCIDE" else "DIVERGENTE")
            }
            linhas.put(obj)
        }
        // contextoResumo (cgm_warning + contadores de confirmação de tendência) exposto direto
        // aqui — antes exigia reabrir o contextoNativo bruto do oracle_so pra investigar isso.
        log.append(OracleLog.Kind.CMP, JSONObject()
            .put("seq", seq).put("protocolo", proto)
            .put("calibracaoPreliminar", preliminary)
            .put("contextoResumo", contextoResumo)
            .put("comparacoes", linhas))
    }

    private fun readingsToJson(rs: List<ExperimentalSibionicsDecoder.ExpReading>): JSONArray =
        JSONArray().also { arr ->
            rs.forEach { r ->
                arr.put(JSONObject().put("index", r.index)
                    .put("mmol", r.mmol).put("mgdl", r.mgdl).put("trend", r.trend)
                    .put("bateriaMv", r.batteryMv).put("preliminar", r.preliminary))
            }
        }

    private fun safeJson(s: String): Any = runCatching { JSONObject(s) }.getOrElse { s }

    /** Despacha para a thread própria; qualquer exceção morre aqui, nunca sobe pro app. */
    private fun submit(block: () -> Unit) {
        runCatching {
            worker.execute { runCatching { block() } }
        }
    }

    private fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it) }

    private companion object {
        /** Intervalo (em pacotes) entre gravações do contexto nativo completo — ver [shouldIncludeFullContext]. */
        const val FULL_CTX_EVERY_N = 10
    }
}
