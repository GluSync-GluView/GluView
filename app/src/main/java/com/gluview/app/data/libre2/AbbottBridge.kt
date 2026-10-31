package com.gluview.app.data.libre2

import com.gluview.app.util.AppLogger

/**
 * Ponte para o módulo nativo `libgluviewabbott.so`, que carrega a `libDataProcessing.so` +
 * `libSecureKeyBoxJava.so` da Abbott com uma JavaVM/JNIEnv FABRICADA (portada do Juggluco).
 *
 * O objetivo é fazer o WhiteCryption SKB inicializar sem precisar das 134 classes ofuscadas reais:
 * a fake FindClass devolve o nome como handle e a fake RegisterNatives captura os ponteiros dos
 * métodos nativos — o SKB "pensa" que registrou tudo e inicializa.
 *
 * FASE 1 (aqui): [loadAndInit] só carrega as libs com o fake VM e conta quantos métodos nativos
 * foram capturados via RegisterNatives. Se o JNI_OnLoad do SecureKeyBox completar (sem crash) e
 * capturarmos os métodos, é o sinal de que a abordagem tem chance. Logs vão para logcat tag LIBRE2_CPP.
 */
object AbbottBridge {
    private const val LOG = "LIBRE2_BRIDGE"

    @Volatile private var moduleLoaded = false

    private fun ensureModule(appLogger: AppLogger): Boolean {
        if (moduleLoaded) return true
        return runCatching { System.loadLibrary("gluviewabbott") }
            .onSuccess { moduleLoaded = true; appLogger.usage(LOG, "libgluviewabbott.so carregada") }
            .onFailure { appLogger.error(LOG, "Falha ao carregar libgluviewabbott.so", mapOf("erro" to (it.message ?: "?"))) }
            .isSuccess
    }

    /**
     * Carrega e inicializa as libs da Abbott com o fake VM. [nativeLibDir] é o diretório das .so
     * (applicationInfo.nativeLibraryDir). [ourApkDir]/[officialApkDir] são os diretórios de
     * instalação (o nosso e o do app oficial real) que o tracer usa p/ falsificar o /proc/self/maps.
     * Retorna o nº de métodos nativos capturados (>0 = progresso).
     */
    fun loadAndInit(appLogger: AppLogger, nativeLibDir: String, ourApkDir: String, officialApkDir: String): Int {
        if (!ensureModule(appLogger)) return -1
        runCatching { nativeSetDirs(ourApkDir, officialApkDir) }
            .onFailure { appLogger.error(LOG, "nativeSetDirs lançou", mapOf("erro" to (it.message ?: "?"))) }
        val n = runCatching { nativeLoadAndInit(nativeLibDir) }.getOrElse {
            appLogger.error(LOG, "nativeLoadAndInit lançou", mapOf("erro" to (it.message ?: "?"))); -1
        }
        appLogger.usage(LOG, "nativeLoadAndInit concluído", mapOf("metodosCapturados" to n))
        return n
    }

    private external fun nativeSetDirs(ourApkDir: String, officialApkDir: String)
    private external fun nativeLoadAndInit(nativeLibDir: String): Int
}
