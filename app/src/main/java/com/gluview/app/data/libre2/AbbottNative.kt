package com.gluview.app.data.libre2

import com.abbottdiabetescare.flashglucose.sensorabstractionservice.dataprocessing.DataProcessingNative
import com.gluview.app.util.AppLogger
import java.io.File

/**
 * Ponte para a biblioteca nativa proprietária da Abbott (`libDataProcessing.so`), extraída do app
 * oficial FreeStyle Libre 2 BR e empacotada em jniLibs.
 *
 * A lib registra ~47 métodos native via `RegisterNatives` no `JNI_OnLoad`, casando contra a classe
 * [DataProcessingNative] (nome/pacote/assinaturas idênticos aos do app oficial). Como o GluView roda
 * na JVM, deixamos o próprio runtime Android fazer a vinculação — não fabricamos JNIEnv como o Juggluco.
 *
 * MARCO 0 (feito): [probe] carrega a lib e confirma que o `JNI_OnLoad` completou (anti-tamper não bloqueou).
 * MARCO 1 (aqui): [testCall] executa a sequência de init da Abbott e chama [getProductFamily] com o
 * patchInfo real do sensor — valida que conseguimos INVOCAR os métodos com o token de proteção correto.
 *
 * Constantes do protocolo (descobertas via Juggluco `libre2.cpp`):
 *  - [PARSERTYPE] = 1095774808 (constante mágica passada como 1º arg dos métodos)
 *  - [TOKEN] = 72 bytes fixos (o "TokenKey" que o SecureKeyBox valida)
 *
 * Ver notas internas do projeto.
 */
object AbbottNative {
    private const val LOG = "LIBRE2_NATIVE"

    const val PARSERTYPE = 1095774808

    /** Token de 72 bytes que a proteção SecureKeyBox exige (hardcoded no Juggluco libre2.cpp). */
    val TOKEN: ByteArray = hexToBytes(
        "0000000000000000" + "272BCD3BF3CCA0B8" + "56CE961FDBA56BB6" + "17B21BD6570DF35F" +
        "006D4CD462662654" + "1FF95C87AE63D97F" + "536EB680E7712E59" + "CFE450C0C8531A33" +
        "61E2DB32F22AC1B6"
    )

    @Volatile private var loadResult: Result<Unit>? = null

    val isAvailable: Boolean get() = loadResult?.isSuccess == true

    /** MARCO 0 — força a carga da lib e loga se o JNI_OnLoad completou. Idempotente. */
    fun probe(appLogger: AppLogger): Boolean {
        loadResult?.let { return it.isSuccess }
        // Carrega as libs de suporte/proteção ANTES da DataProcessing. A thread de background do
        // initializeNative provavelmente depende do SecureKeyBox — sem essas libs, acessa ponteiros
        // inválidos e corrompe a pilha. Ordem: base (sas/math) → proteção (SecureKeyBoxJava).
        for (lib in listOf("sas", "MathRuntimeChecks", "SecureKeyBoxJava")) {
            runCatching { System.loadLibrary(lib) }
                .onSuccess { appLogger.usage(LOG, "loadLibrary $lib OK") }
                .onFailure { e ->
                    appLogger.error(LOG, "loadLibrary $lib falhou",
                        mapOf("erro" to (e::class.simpleName ?: "?"),
                              "causa" to (e.cause?.let { "${it::class.simpleName}: ${it.message}" } ?: (e.message ?: "-"))))
                }
        }
        val r = runCatching {
            Class.forName(
                "com.abbottdiabetescare.flashglucose.sensorabstractionservice.dataprocessing.DataProcessingNative",
                true,
                AbbottNative::class.java.classLoader
            )
            Unit
        }
        loadResult = r
        r.onSuccess {
            appLogger.usage(LOG, "libDataProcessing.so CARREGADA — JNI_OnLoad OK (anti-tamper não bloqueou)")
        }.onFailure { e ->
            appLogger.error(LOG, "Falha ao carregar libDataProcessing.so",
                mapOf("erro" to (e::class.simpleName ?: "?"),
                      "causa" to (e.cause?.let { "${it::class.simpleName}: ${it.message}" } ?: "-")))
        }
        return r.isSuccess
    }

    /**
     * MARCO 1 — executa a sequência de init da Abbott e chama getProductFamily com o patchInfo real.
     *
     * Protegido por um arquivo-sentinela: se uma chamada nativa crashar (SIGSEGV não é capturável em
     * try/catch e derrubaria o app), o sentinela permanece e o teste é pulado nas próximas vezes,
     * evitando loop de crash. Para re-testar após um ajuste, apague o arquivo via adb:
     *   adb shell run-as com.gluview.app rm files/abbott_test_done   (ou reinstale limpo)
     *
     * Cada passo é logado ANTES da chamada nativa, para que — se crashar — o último log indique
     * exatamente qual chamada derrubou.
     */
    fun testCall(appLogger: AppLogger, filesDir: File, uidHex: String, infoHex: String) {
        if (!probe(appLogger)) {
            appLogger.error(LOG, "testCall abortado — lib não carregou")
            return
        }
        val sentinel = File(filesDir, "abbott_test_done")
        if (sentinel.exists()) {
            appLogger.usage(LOG, "testCall pulado (sentinela existe — apague abbott_test_done p/ re-testar)")
            return
        }
        runCatching { sentinel.writeText("attempt ${System.currentTimeMillis()}") }

        // Breadcrumb SÍNCRONO (flush imediato): o AppLogger é assíncrono e perde os últimos logs num
        // crash nativo (SIGSEGV). Este arquivo grava cada passo antes da chamada — após o crash, o
        // conteúdo revela exatamente qual chamada matou o app. Puxar via adb: files/abbott_trace.txt
        val trace = File(filesDir, "abbott_trace.txt")
        fun tr(s: String) { runCatching { trace.appendText("+${System.currentTimeMillis() % 1000000} $s\n") } }
        runCatching { trace.writeText("") }

        val info = runCatching { hexToBytes(infoHex) }.getOrElse {
            appLogger.error(LOG, "patchInfo inválido", mapOf("hex" to infoHex)); return
        }
        val uid = runCatching { hexToBytes(uidHex) }.getOrElse {
            appLogger.error(LOG, "uid inválido", mapOf("hex" to uidHex)); return
        }
        fun hex(b: ByteArray?) = b?.joinToString(" ") { "%02X".format(it) } ?: "null"

        // ORDEM IMPORTANTE: chamamos os métodos de payload PRIMEIRO, SEM initializeNative.
        // O initializeNative planta uma thread de background que corrompe a própria pilha e crasha
        // com SIGBUS ~190ms depois (tombstone: PC Alignment). Se os payloads funcionarem sem init,
        // evitamos a thread por completo. getProductFamily já provou funcionar sem crash.
        // TESTE PATCHINFO: getProductFamily com múltiplos patchInfos, SEM init (evita a thread que
        // crasha). Se um exemplo válido (0x9D/0x76) retornar família != 0, o problema é o patchInfo
        // do sensor (leitura NFC errada), não o init/SecureKeyBox.
        appLogger.usage(LOG, "TESTE PATCHINFO — trace síncrono", mapOf("uid" to uidHex, "infoSensor" to infoHex))
        try {
            val candidatos = linkedMapOf(
                "sensor($infoHex)" to info,
                "exemplo_nonUS_9D083001716..." to hexToBytes("9D0830017136"),
                "exemplo_US_760939023312" to hexToBytes("760939023312")
            )
            for ((nome, pi) in candidatos) {
                tr("call getProductFamily $nome")
                val fam = DataProcessingNative.getProductFamily(PARSERTYPE, pi, TOKEN)
                tr("  = $fam")
                appLogger.usage(LOG, "getProductFamily", mapOf("patchInfo" to nome, "family" to fam))
            }
            tr("ALL DONE patchinfo test")
            runCatching { sentinel.delete() }
        } catch (e: Throwable) {
            tr("EXCEPTION ${e::class.simpleName}: ${e.message}")
            appLogger.error(LOG, "TESTE PATCHINFO falhou",
                mapOf("erro" to (e::class.simpleName ?: "?"), "msg" to (e.message ?: "")))
        }
    }
}
