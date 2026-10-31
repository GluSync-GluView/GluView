package com.gluview.app.data.sibionics

import android.util.Log
import com.algorithm.v1_1_2.AlgorithmContext as Ctx112
import com.algorithm.v1_1_2.NativeAlgorithmLibraryV1_1_2 as Alg112
import com.gluview.app.data.sibionics.oracle.SibionicsOracle
import com.gluview.app.util.AppLogger

/**
 * Protocolo v1.1.2 — Sibionics Chinês (Sugar Joy), sensor da família LT25xx.
 *
 * Reescrito do zero a partir da engenharia reversa COMPLETA do app oficial
 * (com.sugarjoy.plus → com.sisensing.*). Esta implementação replica fielmente
 * o pipeline do SugarJoy+ — único motivo pelo qual o app oficial é preciso há anos.
 *
 * ## Por que o algoritmo precisa ser tratado com cuidado
 * `processAlgorithmContext` é um FILTRO COM ESTADO. A glicemia retornada depende de:
 *   1. Calibração de fábrica — injetada por `initAlgorithmContext(ctx, 0, "1A2B3C4D")`
 *      (o 连接码 de 8 chars). É o que dá a EXATIDÃO ABSOLUTA.
 *   2. Estado interno acumulado — construído ao ser alimentado com CADA índice,
 *      exatamente uma vez, em ordem estritamente crescente (suavização/tendência).
 *   3. O índice atual (idade do sensor em minutos) + corrente (ISIG) + temperatura.
 *
 * O SugarJoy+ garante a corretude com três regras (todas replicadas aqui):
 *   • Alimenta o algoritmo com índice == lastIndex+1 APENAS (gate de ordem estrita).
 *   • Descarta duplicados (index <= lastIndex) — nunca re-alimenta o filtro.
 *   • Persiste `getJsonAlgorithmContext` após cada índice e restaura no reinício do
 *     processo, mantendo o filtro "quente" entre reconexões e reinícios do app.
 *
 * Referências no app oficial (jadx):
 *   - dq2$a.handleMessage  → parser do pacote AA 55 09 (layout de 14 bytes/registro)
 *   - LocalBleService.E/N  → ordem dos argumentos do algoritmo
 *   - LocalBleService.e.run→ gate de ordem estrita (index == l+1)
 *   - LocalBleService.d.run→ persistência do contexto + avanço de l
 *   - x1 (rf0)             → wrapper init/restore/process/save do JNI
 *   - mo.c().b/a           → faixa-alvo do usuário (4.4 / 11.1 mmol/L, defaults)
 *
 * ## Comando de início (a.b.a.b.a.f do app oficial)
 *   AA 55 07 | índice(2B altos) | MAC invertido(6B) | 00×8 | checksum(complemento de 2)
 *
 * ## Pacote de dados (parser dq2$a.handleMessage)
 *   [0..3] cabeçalho AA 55 09 + bArr[3]=nº registros · cada registro 14 bytes a partir de 4+r*14.
 *   Campos (offset relativo, BE16 sobre o buffer — confirmado via vp2.a):
 *     +4,+5  = índice (min desde o início do sensor)
 *     +6,+7  = temperatura ÷10 (°C)
 *     +8,+9  = bateria (mV)
 *     +10,+11= corrente/ISIG ÷10
 *     +12,+13= estado
 *     +14,+15= numOfUnreceived
 */
class SibionicsV112Protocol private constructor(
    private val mac: String,                 // MAC do sensor — entra no comando de início (invertido)
    private val sensorId: String,            // 连接码 de 8 chars (ex.: "1A2B3C4D") → initAlgorithmContext
    private val serial: String,              // serial GS1 completo (diagnóstico)
    private val restoreContextJson: String?, // JSON do contexto persistido (mesmo sensor) ou null
    private val restoreLastIndex: Int,       // último índice processado persistido, ou -1
    private val logger: AppLogger?,
    // Instrumentação de validação (não invasiva) — espelha o payload p/ o decoder experimental.
    // null = desligada. NUNCA altera o resultado retornado ao app. Ver [SibionicsOracle].
    private val oracle: SibionicsOracle? = null
) : SibionicsProtocol {

    private var ctx: Ctx112? = null

    // Equivalente ao mCurrentInsertIndex do SugarJoy+ (l). -1 = ainda não semeado.
    @Volatile private var lastIndex: Int = -1

    // Último mgdl efetivamente exibível (mmol>0) desta conexão — base da classificação
    // confirmed/provisório (ver Reading.confirmed). null = ainda nenhum valor válido nesta sessão.
    // Reseta a cada reconexão (nova instância deste protocolo), igual ao lastIndex.
    private var lastMgdl: Int? = null

    /** Último índice efetivamente alimentado ao algoritmo (para persistência). */
    override val lastProcessedIndex: Int get() = lastIndex

    // T21 — recuperação de lacunas: re-solicita o histórico faltante e, se não vier após
    // algumas tentativas, ressincroniza para não travar.
    private var lastGapRequestMs = 0L
    private var gapRetries = 0
    private var gapAnchor = -1

    override fun onReady(sendCommand: (ByteArray) -> Unit) {
        // O contexto JNI vive em memória e permanece QUENTE entre reconexões BLE no mesmo
        // processo. Só inicializamos uma vez; em reconexões apenas reenviamos o comando de
        // início, preservando o estado acumulado da sessão.
        if (ctx == null) {
            ctx = runCatching {
                val c = Alg112.getAlgorithmContextFromNative()
                // initAlgorithmContext faz strcpy do 连接码 num buffer de 9 bytes (8 chars + null).
                // O SugarJoy+ EXIGE retorno == 1; caso contrário é "código de conexão inválido".
                val rc = Alg112.initAlgorithmContext(c, 0, sensorId)
                if (rc != 1) {
                    logger?.error(LOG, "initAlgorithmContext NÃO retornou 1 (código de conexão suspeito)",
                        mapOf("sensorId" to sensorId, "rc" to rc))
                }

                // Restaura o estado acumulado APENAS se for do mesmo sensor (validado pela origem).
                // Mantém o filtro quente entre reinícios do processo (app morto/reaberto).
                if (!restoreContextJson.isNullOrBlank() && restoreLastIndex >= 0) {
                    val sc = Alg112.setJsonAlgorithmContext(c, restoreContextJson)
                    lastIndex = restoreLastIndex
                    logger?.usage(LOG, "Contexto restaurado",
                        mapOf("sensorId" to sensorId, "lastIndex" to restoreLastIndex,
                              "setRc" to sc, "ctxLen" to restoreContextJson.length))
                } else {
                    logger?.usage(LOG, "Contexto NOVO (sem estado anterior — haverá aquecimento)",
                        mapOf("sensorId" to sensorId, "initRc" to rc,
                              "algVer" to runCatching { Alg112.getAlgorithmVersion() }.getOrNull().orEmpty()))
                }
                c
            }.onFailure {
                logger?.error(LOG, "Falha ao inicializar contexto v1.1.2",
                    mapOf("error" to (it.message ?: it::class.simpleName.orEmpty())))
            }.getOrNull()
        } else {
            logger?.usage(LOG, "Reconexão BLE — mantendo contexto quente",
                mapOf("lastIndex" to lastIndex))
        }

        // T17 — backfill ativo: pede ao sensor a partir do PRÓXIMO índice ainda não gravado
        // (lastIndex+1), exatamente como o SugarJoy+ (f.c(l+1)).
        //
        // Piso 1, nunca 0: o índice do sensor começa em 1 (o pacote de índice 0 não existe —
        // confirmado ao vivo: pedindo 0, o primeiro registro devolvido foi index=1). As duas
        // referências também nunca pedem 0 — SugarJoy+ `LocalBleService.M()` chama `f.c(i+1)`
        // com i=0 quando não há histórico, e o Juggluco inicializa `lockcount=1` e volta a 1 em
        // `resetSiIndex()`. Numa sessão sem histórico (lastIndex=-1) isto pede 1 = ao vivo.
        val startIdx = (lastIndex + 1).coerceAtLeast(FIRST_SENSOR_INDEX)
        val cmd = buildOpenCommand(mac, startIdx)
        Log.d(LOG, "Comando de início ff32 (de índice $startIdx): ${cmd.toHex()}")
        logger?.usage(LOG, "Comando de início enviado", mapOf("deIndice" to startIdx, "hex" to cmd.toHex()))
        sendCommand(cmd)
    }

    override fun onPacket(raw: ByteArray, sendCommand: (ByteArray) -> Unit): List<SibionicsProtocol.Reading> {
        // Só processamos pacotes de dados AA 55 09; o ACK do comando (AA 55 07) é ignorado.
        if (raw.size < 18 ||
            (raw[0].toInt() and 0xFF) != 0xAA ||
            (raw[1].toInt() and 0xFF) != 0x55 ||
            (raw[2].toInt() and 0xFF) != 0x09) {
            return emptyList()
        }

        val c = ctx ?: run {
            logger?.error(LOG, "Pacote AA5509 sem contexto inicializado — ignorado")
            return emptyList()
        }
        fun be16(hi: Int, lo: Int) = ((raw[hi].toInt() and 0xFF) shl 8) or (raw[lo].toInt() and 0xFF)

        // 1) Extrai TODOS os registros do pacote (pode trazer backfill, bArr[3] registros).
        val count = (raw[3].toInt() and 0xFF).coerceAtLeast(1)
        val records = ArrayList<Rec>(count)
        for (r in 0 until count) {
            val base = r * 14
            if (4 + base + 13 >= raw.size) break
            val index = be16(4 + base, 5 + base)
            if (index <= 0) continue
            records.add(Rec(
                index       = index,
                temperature = be16(6 + base, 7 + base) / 10.0,
                batteryMv   = be16(8 + base, 9 + base),
                current     = be16(10 + base, 11 + base) / 10.0,
                rawCurrent  = be16(10 + base, 11 + base)
            ))
        }
        if (records.isEmpty()) return emptyList()

        // 2) Gate de ordem estrita (replica LocalBleService.e.run do SugarJoy+):
        //    alimenta o algoritmo com CADA índice exatamente uma vez, em ordem crescente.
        records.sortBy { it.index }
        val readings = ArrayList<SibionicsProtocol.Reading>(records.size)
        var gapTarget = -1   // T21 — primeiro índice ALÉM de uma lacuna detectada
        // Instrumentação (não invasiva): acumula as chamadas reais da .so p/ o oráculo comparar.
        val oracleReadings = if (oracle != null) ArrayList<SibionicsOracle.SoReadingV112>(records.size) else null

        for (rec in records) {
            // Duplicado / já processado → NUNCA re-alimenta o filtro (regra de ouro do estado).
            if (rec.index <= lastIndex) continue

            // Primeiro registro de uma sessão sem histórico: semeia para que este seja o "l+1".
            if (lastIndex < 0) lastIndex = rec.index - 1

            // T21 — Lacuna (index > lastIndex+1): NÃO processa fora de ordem. Para aqui e
            // re-solicita o trecho faltante (igual ao V() do SugarJoy+), preservando o horário
            // correto quando os índices perdidos chegarem.
            if (rec.index > lastIndex + 1) {
                gapTarget = rec.index
                break
            }

            // Glicemia = VALOR DE RETORNO de processAlgorithmContext (mmol/L).
            // Ordem confirmada (N→rf0.i): index, corrente, temperatura, 0.0, alvoBaixo, alvoAlto.
            val soT0 = System.nanoTime()
            val mmol = runCatching {
                Alg112.processAlgorithmContext(c, rec.index, rec.current, rec.temperature,
                    0.0, TARGET_LOW, TARGET_HIGH)
            }.onFailure {
                logger?.error(LOG, "processAlgorithmContext falhou",
                    mapOf("index" to rec.index, "error" to (it.message ?: it::class.simpleName.orEmpty())))
            }.getOrDefault(0.0)
            val soProcNanos = System.nanoTime() - soT0

            // Avança o índice MESMO em aquecimento (mmol<=0), igual ao SugarJoy+: o filtro
            // precisa "ver" todo índice para construir estado, ainda que a saída seja 0.
            lastIndex = rec.index

            val trend = c.ig_trend
            // Espelha a entrada/saída REAL da .so p/ o oráculo (sem alterar nada acima).
            oracleReadings?.add(SibionicsOracle.SoReadingV112(
                index = rec.index, current = rec.current, temp = rec.temperature,
                mmol = mmol, trend = trend, batteryMv = rec.batteryMv, procNanos = soProcNanos))
            Log.d(LOG, "v112 index=${rec.index} corrente=%.1f temp=%.1f bat=%dmV → mmol=%.2f trend=%d"
                .format(rec.current, rec.temperature, rec.batteryMv, mmol, trend))
            logger?.usage(LOG, "Leitura v1.1.2",
                mapOf("index" to rec.index, "corrente" to "%.1f".format(rec.current),
                      "temp" to "%.1f".format(rec.temperature), "bateriaMv" to rec.batteryMv,
                      "mmol" to "%.2f".format(mmol),
                      "mgdl" to Math.round(mmol * MMOL_TO_MGDL).toInt(), "trend" to trend))

            // Saída <= 0 é aquecimento/invalidez (clampada). Não vira leitura exibível, mas o
            // índice já foi consumido pelo filtro acima.
            if (mmol > 0.0) {
                val mgdl = Math.round(mmol * MMOL_TO_MGDL).toInt()
                // "Confirmado" = a .so mudou de valor em relação à última leitura válida desta
                // conexão (ver validação empírica no plano: 88% das mudanças reais acontecem em
                // exatamente 5min, batendo com o comportamento do app oficial). Repetir o mesmo
                // valor = ainda na janela de observação interna do algoritmo ("provisório").
                val confirmed = lastMgdl == null || mgdl != lastMgdl
                lastMgdl = mgdl
                readings.add(SibionicsProtocol.Reading(
                    index      = rec.index,
                    mgdl       = mgdl,
                    mmol       = mmol,
                    trend      = trend,
                    rawCurrent = rec.rawCurrent,
                    batteryMv  = rec.batteryMv,
                    confirmed  = confirmed
                ))
            }
        }

        // T21 — se houve lacuna, tenta recuperá-la; senão zera o contador de tentativas.
        if (gapTarget >= 0) recoverGap(gapTarget, sendCommand) else gapRetries = 0

        // Instrumentação (não invasiva): captura o estado nativo JÁ serializado (mesma thread,
        // read-only) e despacha o pacote+resultados p/ o oráculo. Guardado — nunca afeta o retorno.
        val o = oracle
        if (o != null && !oracleReadings.isNullOrEmpty()) {
            val ctxJson = runCatching { Alg112.getJsonAlgorithmContext(c) }.getOrNull()
            runCatching { o.recordV112(raw, oracleReadings, TARGET_LOW, TARGET_HIGH, ctxJson) }
        }
        return readings
    }

    /**
     * T21 — Recuperação de lacuna: re-solicita o histórico a partir de lastIndex+1 (throttle).
     * Após [MAX_GAP_RETRIES] tentativas sem preencher, ressincroniza (aceita a lacuna) para não
     * travar — dali em diante grava com o horário correto.
     */
    private fun recoverGap(gapTarget: Int, sendCommand: (ByteArray) -> Unit) {
        if (gapAnchor != lastIndex) { gapAnchor = lastIndex; gapRetries = 0 }  // lacuna nova

        if (gapRetries >= MAX_GAP_RETRIES) {
            logger?.usage(LOG, "Lacuna não preenchida — ressincronizando",
                mapOf("de" to lastIndex, "para" to gapTarget, "tentativas" to gapRetries))
            lastIndex = gapTarget - 1
            gapRetries = 0
            gapAnchor = -1
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastGapRequestMs < GAP_THROTTLE_MS) return
        lastGapRequestMs = now
        gapRetries++
        val from = (lastIndex + 1).coerceAtLeast(FIRST_SENSOR_INDEX)
        logger?.usage(LOG, "Re-solicitando histórico (lacuna)",
            mapOf("de" to from, "para" to gapTarget, "tentativa" to gapRetries))
        sendCommand(buildOpenCommand(mac, from))
    }

    override fun getContextJson(): String? = runCatching {
        ctx?.let { Alg112.getJsonAlgorithmContext(it) }
    }.onFailure {
        logger?.error(LOG, "getJsonAlgorithmContext falhou",
            mapOf("error" to (it.message ?: it::class.simpleName.orEmpty())))
    }.getOrNull()

    override fun release() {
        runCatching { ctx?.let { Alg112.releaseAlgorithmContext(it) } }
        ctx = null
        logger?.usage(LOG, "Contexto v1.1.2 liberado")
    }

    /** Registro cru extraído de um pacote, antes do gate de ordem. */
    private data class Rec(
        val index: Int,
        val temperature: Double,
        val batteryMv: Int,
        val current: Double,
        val rawCurrent: Int
    )

    /**
     * Comando "abrir broadcast" idêntico ao app oficial (a.b.a.b.a.f):
     *   AA 55 07 + índice(2 bytes BAIXOS, little-endian) + MAC invertido(6B) + 00×8 + checksum.
     *
     * Confirmado por RE: `bArr3 = {vp2.b(i)[0], vp2.b(i)[1]}` = {i & 255, (i ushr 8) & 255}.
     * O índice é o ponto de início que o sensor passa a transmitir (backfill a partir dele).
     */
    private fun buildOpenCommand(mac: String, index: Int): ByteArray {
        val macBytes = runCatching {
            mac.split(":").map { it.toInt(16).toByte() }.toByteArray()
        }.getOrElse { ByteArray(6) }
        val payload = byteArrayOf(0xAA.toByte(), 0x55, 0x07) +
            byteArrayOf((index and 0xFF).toByte(), ((index ushr 8) and 0xFF).toByte()) +
            macBytes.reversedArray() +
            ByteArray(8)
        var sum = 0
        for (b in payload) sum += (b.toInt() and 0xFF)
        return payload + byteArrayOf(((256 - (sum and 0xFF)) and 0xFF).toByte())
    }

    companion object {
        private const val LOG = "SIBIONICS_V112"
        private const val MMOL_TO_MGDL = 18.0182
        // Faixa-alvo (mmol/L) passada ao algoritmo — mesmos defaults do app oficial (mo.b/mo.a).
        private const val TARGET_LOW  = 4.4
        private const val TARGET_HIGH = 11.1
        // T21 — recuperação de lacunas: nº máx. de re-solicitações e intervalo entre elas.
        private const val MAX_GAP_RETRIES = 3
        private const val GAP_THROTTLE_MS = 15_000L
        /** Primeiro índice que existe no sensor — a contagem começa em 1, não em 0. */
        private const val FIRST_SENSOR_INDEX = 1

        fun create(
            mac: String,
            sensorId: String,
            serial: String,
            logger: AppLogger? = null,
            restoreContextJson: String? = null,
            restoreLastIndex: Int = -1,
            oracle: SibionicsOracle? = null
        ): SibionicsV112Protocol? =
            runCatching {
                Class.forName("com.algorithm.v1_1_2.NativeAlgorithmLibraryV1_1_2",
                    true, Alg112::class.java.classLoader)
                logger?.usage(LOG, "libnative-algorithm-jni-v112.so carregada",
                    mapOf("mac" to mac, "sensorId" to sensorId, "serial" to serial,
                          "temContextoSalvo" to (restoreContextJson != null),
                          "restoreLastIndex" to restoreLastIndex))
                SibionicsV112Protocol(mac, sensorId, serial, restoreContextJson, restoreLastIndex, logger, oracle)
            }.onFailure {
                logger?.error(LOG, "Falha ao carregar lib v1.1.2",
                    mapOf("error" to (it.message ?: it::class.simpleName.orEmpty())))
            }.getOrNull()
    }
}

/** Hex formatado para diagnóstico BLE. File-private. */
private fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it) }
