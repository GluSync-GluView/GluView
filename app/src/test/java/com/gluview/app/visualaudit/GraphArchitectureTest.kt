package com.gluview.app.visualaudit

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Teste de arquitetura do gráfico (redesign visual, Fase 2), com linha de base que só pode DIMINUIR
 * (src/test/resources/visualaudit/graph-architecture-baseline.txt):
 *  - hex: cor do gráfico (GraphPalette) escrita fora de util/graph — tem que ler da paleta;
 *  - bitmap: `asImageBitmap` em ui/screen — o app desenha com o mesmo draw() no Canvas do Compose,
 *    sem bitmap (a Fase 3 tira os 3 de hoje: Home, seguidor e acompanhado).
 * Comentários não contam. Para registrar uma melhoria: -Pgluview.visualaudit.updateBaseline=true.
 */
class GraphArchitectureTest {

    private val moduleDir = File(".").absoluteFile
    private val src = File(moduleDir, "src/main/java/com/gluview/app")
    private val engineDir = File(src, "util/graph")
    private val screensDir = File(src, "ui/screen")
    private val baselineFile = File(moduleDir, "src/test/resources/visualaudit/graph-architecture-baseline.txt")

    /** Os hex de cor do GraphPalette (sem o alfa), lidos do próprio arquivo da paleta. */
    private val paletteHex: Set<String> by lazy {
        Regex("""0x[0-9A-Fa-f]{2}([0-9A-Fa-f]{6})""")
            .findAll(File(engineDir, "GraphPalette.kt").readText())
            .map { it.groupValues[1].uppercase() }
            .filterNot { it == "FFFFFF" || it == "000000" }
            .toSet()
    }

    private fun codeLines(f: File) = f.readLines().filterNot { l ->
        val t = l.trim(); t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
    }

    private fun scan(): Map<String, Int> {
        val out = sortedMapOf<String, Int>()
        val hexRe = Regex("""(?:0x[0-9A-Fa-f]{2}|#(?:[0-9A-Fa-f]{2})?)([0-9A-Fa-f]{6})\b""")
        src.walkTopDown().filter { it.isFile && it.extension == "kt" && !it.startsWith(engineDir) }.forEach { f ->
            val rel = f.relativeTo(src).invariantSeparatorsPath
            val lines = codeLines(f)
            val hex = lines.sumOf { l -> hexRe.findAll(l).count { it.groupValues[1].uppercase() in paletteHex } }
            if (hex > 0) out["hex\t$rel"] = hex
            if (f.startsWith(screensDir)) {
                val bmp = lines.count { "asImageBitmap" in it && !it.trim().startsWith("import ") }
                if (bmp > 0) out["bitmap\t$rel"] = bmp
            }
        }
        return out
    }

    private fun readBaseline(): Map<String, Int> =
        if (!baselineFile.exists()) emptyMap()
        else baselineFile.readLines().filter { it.isNotBlank() && !it.startsWith("#") }.associate { line ->
            val parts = line.split('\t'); "${parts[0]}\t${parts[1]}" to parts[2].toInt()
        }

    @Test fun graphArchitectureDoesNotRegress() {
        assertTrue("paleta vazia — o teste não estaria testando nada", paletteHex.size >= 8)
        val now = scan()
        if (System.getProperty("gluview.visualaudit.updateBaseline") == "true") {
            baselineFile.parentFile.mkdirs()
            baselineFile.writeText(buildString {
                appendLine("# Linha de base do GraphArchitectureTest. Só pode DIMINUIR.")
                appendLine("# Regerar: gradlew :app:testDebugUnitTest --tests *GraphArchitectureTest -Pgluview.visualaudit.updateBaseline=true")
                now.forEach { (k, v) -> appendLine("$k\t$v") }
            })
        }
        val base = readBaseline()
        val worse = now.filter { (k, v) -> v > (base[k] ?: 0) }
        assertTrue(
            "Gráfico fora da arquitetura (hex da GraphPalette fora de util/graph, ou asImageBitmap em ui/screen):\n" +
                worse.entries.joinToString("\n") { (k, v) -> "$k: $v (linha de base ${base[k] ?: 0})" },
            worse.isEmpty()
        )
    }
}
