package com.gluview.app.util

import android.content.Context
import com.gluview.app.data.local.dao.AppLogDao
import com.gluview.app.data.local.entity.AppLogEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Logger central do app.
 *
 * F1 — Logging:
 *  - T01: formato padronizado (ts, nível, tag, origem, msg, data).
 *  - T02: origem (classe.método:linha) capturada AUTOMATICAMENTE do stack trace.
 *  - T03: grava SEMPRE (sem flag em configurações).
 *  - T04: grava em arquivo de TEXTO e também no BANCO local (Room).
 *  - T05: retenção de 7 dias via [prune] (arquivos datados + linhas do banco).
 */
@Singleton
class AppLogger @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appLogDao: AppLogDao
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val mutex = Mutex()

    private val logDir: File
        get() = (context.getExternalFilesDir("logs") ?: File(context.filesDir, "logs"))
            .also { it.mkdirs() }

    fun usage(tag: String, msg: String, data: Map<String, Any> = emptyMap()) =
        write("usage", tag, callerOrigin(), msg, data)

    fun error(tag: String, msg: String, data: Map<String, Any> = emptyMap()) =
        write("error", tag, callerOrigin(), msg, data)

    private fun write(level: String, tag: String, origin: String, msg: String, data: Map<String, Any>) {
        val ts = System.currentTimeMillis()
        val dataJson = logDataToJson(data)

        scope.launch {
            // T03 — sempre grava, sem depender de flag.
            // Texto: arquivo datado por dia (permite retenção real de 7 dias — T05).
            val entry = buildEntry(ts, level, tag, origin, msg, dataJson)
            mutex.withLock {
                runCatching {
                    File(logDir, "app_${level}-${LocalDate.now()}.jsonl").appendText(entry + "\n")
                }
            }
            // T04 — banco local (Room).
            runCatching {
                appLogDao.insert(
                    AppLogEntity(ts = ts, level = level, tag = tag, origin = origin, msg = msg, data = dataJson)
                )
            }
        }
    }

    /**
     * F1/T02 — descobre a origem (classe.método:linha) do chamador percorrendo o stack trace
     * até o primeiro quadro fora do próprio AppLogger / runtime de corrotinas.
     */
    private fun callerOrigin(): String {
        val frame = Throwable().stackTrace.firstOrNull {
            val c = it.className
            !c.startsWith("com.gluview.app.util.AppLogger") &&
            !c.startsWith("kotlin.") &&
            !c.startsWith("kotlinx.")
        } ?: return ""
        val cls = frame.className.substringAfterLast('.').substringBefore('$')
        return "$cls.${frame.methodName}:${frame.lineNumber}"
    }

    private fun buildEntry(
        ts: Long, level: String, tag: String, origin: String, msg: String, dataJson: String
    ): String = JSONObject().apply {
        put("ts", Instant.ofEpochMilli(ts).toString())
        put("level", level)
        put("tag", tag)
        put("origin", origin)
        put("msg", msg)
        if (dataJson.isNotEmpty()) put("data", JSONObject(dataJson))
    }.toString()

    /**
     * F1/T05 — retenção: apaga do banco e dos arquivos de texto tudo anterior a [cutoffMs].
     * Chamado pela rotina diária de limpeza (GluSyncService.maybeCleanup), com corte de 7 dias.
     */
    suspend fun prune(cutoffMs: Long) {
        runCatching { appLogDao.deleteOlderThan(cutoffMs) }
        mutex.withLock {
            runCatching {
                logDir.listFiles()
                    ?.filter { it.isFile && it.lastModified() < cutoffMs }
                    ?.forEach { it.delete() }
            }
        }
    }

    companion object {
        /** Dias de retenção dos logs (F1/T05). */
        const val RETENTION_DAYS = 7
    }
}

/**
 * Dados do registro em JSON. Registro nunca pode derrubar o app: o org.json recusa NaN e infinito
 * ("Forbidden numeric value") — um sensor manda NaN em leitura com erro e ±∞ em LO/HI (achado pelo
 * SmartGuide virtual, 07/11/2026: o GluView travava na primeira leitura com erro). Esses números viram
 * texto; qualquer outra recusa vira o texto do valor.
 */
internal fun logDataToJson(data: Map<String, Any>): String {
    if (data.isEmpty()) return ""
    val obj = JSONObject()
    data.forEach { (k, v) ->
        val safe: Any = when (v) {
            is Double -> if (v.isFinite()) v else v.toString()
            is Float -> if (v.isFinite()) v else v.toString()
            else -> v
        }
        runCatching { obj.put(k, safe) }.onFailure { obj.put(k, safe.toString()) }
    }
    return obj.toString()
}
