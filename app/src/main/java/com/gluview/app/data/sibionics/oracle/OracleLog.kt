package com.gluview.app.data.sibionics.oracle

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Logger DEDICADO e ISOLADO da validação do decoder experimental do Sibionics.
 *
 * ⚠️ NÃO tem NADA a ver com o [com.gluview.app.util.AppLogger] do app:
 *  - grava numa pasta própria (`getExternalFilesDir("sibionics_oracle")`), separada de "logs";
 *  - NÃO escreve no banco (Room), NÃO envia nada pra rede;
 *  - existe só para coletar dados que permitam, no futuro, validar uma reimplementação em Kotlin
 *    da calibração hoje feita pela `.so` proprietária. Ver notas internas do projeto.
 *
 * Três arquivos por dia, correlacionáveis pelo campo `seq`:
 *  - `oracle_so-<data>.jsonl`  → payload bruto interceptado + retorno da `.so`
 *  - `oracle_exp-<data>.jsonl` → passos detalhados da implementação experimental
 *  - `oracle_cmp-<data>.jsonl` → comparação `.so` × experimental + status
 *
 * Todas as gravações são best-effort e NUNCA lançam para o chamador (runCatching).
 * A serialização já vem serializada pelo [SibionicsOracle] numa única thread — mesmo assim
 * sincronizamos por segurança.
 */
@Singleton
class OracleLog @Inject constructor(
    @ApplicationContext private val context: Context
) {
    enum class Kind(val file: String) { SO("oracle_so"), EXP("oracle_exp"), CMP("oracle_cmp") }

    private val dir: File
        get() = (context.getExternalFilesDir(SUBDIR) ?: File(context.filesDir, SUBDIR))
            .also { runCatching { it.mkdirs() } }

    private val lock = Any()

    /** Anexa uma linha JSON ao arquivo do tipo indicado. Injeta `ts` (ISO-8601) automaticamente. */
    fun append(kind: Kind, obj: JSONObject) {
        synchronized(lock) {
            runCatching {
                if (!obj.has("ts")) obj.put("ts", Instant.now().toString())
                File(dir, "${kind.file}-${LocalDate.now()}.jsonl").appendText(obj.toString() + "\n")
            }
        }
    }

    /** Retenção: apaga arquivos anteriores a [cutoffMs]. Chamada 1×/dia por GluSyncService.maybeCleanup. */
    fun prune(cutoffMs: Long) {
        synchronized(lock) {
            runCatching {
                dir.listFiles()?.filter { it.isFile && it.lastModified() < cutoffMs }?.forEach { it.delete() }
            }
        }
    }

    companion object {
        const val SUBDIR = "sibionics_oracle"
        /** Dias de retenção dos logs de validação. Temporário — a instrumentação será removida quando
         *  o decoder experimental estiver validado. */
        const val RETENTION_DAYS = 7
    }
}
