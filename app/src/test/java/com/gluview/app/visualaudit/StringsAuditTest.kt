package com.gluview.app.visualaudit

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * check-strings do harness visual (equivalente ao check-css.cjs do portal).
 *
 * Mede dois problemas de i18n e falha se algum PIORAR em relação à linha de base versionada
 * (src/test/resources/visualaudit/strings-baseline.txt):
 *  1. chaves de values/strings.xml (pt) que não existem em values-en/strings.xml;
 *  2. texto em português fixo no código de interface (ui/, widget/, notification/, dream/,
 *     Android Auto): literais Kotlin com letra acentuada fora de comentário e de log.
 *
 * Relatório completo em app/build/visual-audit/strings-report.md. As fases do redesign zeram a
 * linha de base aos poucos; para registrar uma melhoria: -Pgluview.visualaudit.updateBaseline=true.
 */
class StringsAuditTest {

    private val moduleDir = File(".").absoluteFile
    private val res = File(moduleDir, "src/main/res")
    private val src = File(moduleDir, "src/main/java/com/gluview/app")
    private val baselineFile = File(moduleDir, "src/test/resources/visualaudit/strings-baseline.txt")
    private val reportFile = File(moduleDir, "build/visual-audit/strings-report.md")

    private val uiRoots = listOf(
        "ui", "widget", "notification", "dream", "service/GluAutoService.kt",
        // Plano OTA (E6/E7): o atualizador também produz texto visível (notificações e estados).
        "service/UpdateCheckWorker.kt", "service/PostUpdateCheckWorker.kt", "data/update"
    )

    private fun keys(file: File): Set<String> =
        Regex("""<(?:string|plurals|string-array)\s+name="([^"]+)"""")
            .findAll(file.readText()).map { it.groupValues[1] }.toSet()

    private val accented = Regex("[ãáâàéêíóôõúçÃÁÂÀÉÊÍÓÔÕÚÇ]")
    private val literal = Regex(""""((?:[^"\\]|\\.)*)"""")
    private val ignoredLine = Regex("""^\s*(//|\*|/\*)|appLogger\.|Log\.[dewiv]\(|logger\.|@Suppress|require\(|check\(|error\(""")

    // Literal passado direto a um "ralo" de interface: texto visível ou lido pelo TalkBack, ou
    // mensagem de estado que a tela exibe. Pega os textos pt sem acento ("Conectado", "Sem sinal").
    private val uiSink = Regex(
        """(?:\bText\(\s*|contentDescription\s*=\s*|\btext\s*=\s*|\btitle\s*=\s*|\blabel\s*=\s*|""" +
            """\b(?:error|message|infoMessage|status|subtitle)\s*=\s*|setContent(?:Text|Title)\(\s*|""" +
            """setTextViewText\([^,]+,\s*|\.emit\(\s*|showSnackbar\(\s*)"((?:[^"\\]|\\.)*)""""
    )

    /** Nomes próprios que não se traduzem. */
    private val properNouns = setOf("GluView", "GluSync", "Mi Band", "Android Auto", "Pushover", "IFTTT")

    /** Tem palavra de verdade (3+ letras) e não é só formato, chave ou rota. */
    private fun isProse(s: String): Boolean {
        if (s in properNouns || Regex("""^Mi Band \d+$""").matches(s)) return false
        if ("\${" in s && "}" !in s) return false // template cortado por aspas aninhadas
        val stripped = s.replace(Regex("""\$\{[^}]*}|\$\w+|%\d*\$?[sdf.0-9]*"""), " ")
        if (!Regex("[A-Za-zÀ-ú]{3,}").containsMatchIn(stripped)) return false
        if (Regex("""^[a-z0-9_.:/-]+$""").matches(s)) return false   // chave, rota, id
        if (Regex("""^[HhmsdMyaEZ:./ -]+$""").matches(s)) return false // padrão de data/hora
        return true
    }

    /** arquivo relativo -> literais pt encontrados (texto). */
    private fun ptLiterals(): Map<String, List<String>> {
        val files = uiRoots.flatMap { root ->
            val f = File(src, root)
            if (f.isFile) listOf(f) else f.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        }
        val out = sortedMapOf<String, List<String>>()
        for (f in files) {
            val hits = mutableListOf<String>()
            f.readLines().forEach { line ->
                if (ignoredLine.containsMatchIn(line)) return@forEach
                val code = line.substringBefore(" // ")
                val found = linkedSetOf<String>()
                literal.findAll(code).map { it.groupValues[1] }
                    .filter { accented.containsMatchIn(it) }
                    .forEach { found += it }
                uiSink.findAll(code).map { it.groupValues[1] }
                    .filter { isProse(it) }
                    .forEach { found += it }
                hits += found
            }
            if (hits.isNotEmpty()) out[f.relativeTo(src).invariantSeparatorsPath] = hits
        }
        return out
    }

    private fun readBaseline(): Pair<Set<String>, Map<String, Int>> {
        if (!baselineFile.exists()) return emptySet<String>() to emptyMap()
        val missing = mutableSetOf<String>(); val counts = mutableMapOf<String, Int>()
        baselineFile.readLines().filter { it.isNotBlank() && !it.startsWith("#") }.forEach { line ->
            val (kind, a, b) = (line.split("\t") + listOf("", "")).take(3)
            when (kind) {
                "missing-en" -> missing += a
                "pt-literals" -> counts[a] = b.toInt()
            }
        }
        return missing to counts
    }

    @Test fun i18nDoesNotRegress() {
        val pt = keys(File(res, "values/strings.xml"))
        val en = keys(File(res, "values-en/strings.xml"))
        val missingEn = (pt - en).sorted()
        val onlyEn = (en - pt).sorted()
        val literals = ptLiterals()
        val total = literals.values.sumOf { it.size }

        reportFile.parentFile.mkdirs()
        reportFile.writeText(buildString {
            appendLine("# check-strings")
            appendLine()
            appendLine("- Chaves pt: ${pt.size} · en: ${en.size}")
            appendLine("- Faltando em en: ${missingEn.size} · só em en: ${onlyEn.size}")
            appendLine("- Literais fixos no código de interface (pt com acento, ou texto passado direto a Text/contentDescription/title/label/mensagem): $total em ${literals.size} arquivos")
            appendLine()
            appendLine("## Faltando em values-en")
            missingEn.forEach { appendLine("- `$it`") }
            if (onlyEn.isNotEmpty()) { appendLine(); appendLine("## Só em values-en"); onlyEn.forEach { appendLine("- `$it`") } }
            appendLine()
            appendLine("## Literais pt por arquivo")
            literals.entries.sortedByDescending { it.value.size }.forEach { (file, hits) ->
                appendLine("### $file (${hits.size})")
                hits.forEach { appendLine("- \"$it\"") }
            }
        })

        if (System.getProperty("gluview.visualaudit.updateBaseline") == "true") {
            baselineFile.parentFile.mkdirs()
            baselineFile.writeText(buildString {
                appendLine("# Linha de base do check-strings (StringsAuditTest). Só pode DIMINUIR.")
                appendLine("# Regerar: gradlew :app:testDebugUnitTest --tests *StringsAuditTest -Pgluview.visualaudit.updateBaseline=true")
                missingEn.forEach { appendLine("missing-en\t$it") }
                literals.forEach { (file, hits) -> appendLine("pt-literals\t$file\t${hits.size}") }
            })
            return
        }

        val (baseMissing, baseCounts) = readBaseline()
        val newMissing = missingEn.filterNot { it in baseMissing }
        val worse = literals.mapNotNull { (file, hits) ->
            val base = baseCounts[file] ?: 0
            if (hits.size > base) "$file: ${hits.size} literais pt (linha de base $base)" else null
        }
        assertTrue(
            "i18n piorou (ver ${reportFile.path}):\n" +
                newMissing.joinToString("\n") { "chave sem tradução en: $it" } +
                (if (newMissing.isNotEmpty() && worse.isNotEmpty()) "\n" else "") +
                worse.joinToString("\n"),
            newMissing.isEmpty() && worse.isEmpty()
        )
    }
}
