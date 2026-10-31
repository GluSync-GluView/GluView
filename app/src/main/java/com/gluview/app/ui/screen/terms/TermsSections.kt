package com.gluview.app.ui.screen.terms

/**
 * Os termos divididos para exibição, SEM mudar o texto (mudar o texto exigiria subir
 * [TERMS_VERSION] e novo aceite de todos): as 2 primeiras linhas são o título e a versão, o que
 * vem antes do "1." é a introdução e cada linha "N. TÍTULO" abre uma seção.
 */
internal data class TermsDoc(
    val title: String,
    val version: String,
    val intro: List<String>,
    val sections: List<TermsSection>
)

internal data class TermsSection(val title: String, val paragraphs: List<String>)

private val SECTION_START = Regex("""^\d+\.\s""")

internal fun parseTerms(text: String): TermsDoc {
    val lines = text.lines()
    val intro = mutableListOf<String>()
    val sections = mutableListOf<TermsSection>()
    var current: Pair<String, MutableList<String>>? = null
    for (line in lines.drop(2)) {
        if (line.isBlank()) continue
        if (SECTION_START.containsMatchIn(line)) {
            current?.let { sections += TermsSection(it.first, it.second) }
            current = line to mutableListOf()
        } else {
            current?.second?.add(line) ?: intro.add(line)
        }
    }
    current?.let { sections += TermsSection(it.first, it.second) }
    return TermsDoc(lines.getOrElse(0) { "" }, lines.getOrElse(1) { "" }, intro, sections)
}

/** Todas as linhas não vazias, na ordem: serve para provar (em teste) que nada se perdeu. */
internal fun TermsDoc.allLines(): List<String> =
    listOf(title, version) + intro + sections.flatMap { listOf(it.title) + it.paragraphs }
