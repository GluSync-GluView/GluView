package com.gluview.app.watchface

object WatchFaceComponentLibrary {
    private val fonts = listOf("Default", "Sans", "Serif", "Mono", "Condensed")
    private val alignments = listOf(WatchFaceTextAlign.START, WatchFaceTextAlign.CENTER, WatchFaceTextAlign.END)
    private val colors = listOf(
        0xFFFFFFFF.toLong(),
        0xFF69F0AE.toLong(),
        0xFF64B5F6.toLong(),
        0xFFFFD54F.toLong(),
        0xFFFF8A65.toLong(),
        0xFFE57373.toLong(),
        0xFFBA68C8.toLong(),
        0xFF4DD0E1.toLong(),
        0xFFAED581.toLong(),
        0xFFFFB74D.toLong()
    )
    private val styleTags = listOf(
        "classic",
        "bold",
        "outline",
        "pill",
        "stacked",
        "compact",
        "labelled",
        "minimal",
        "accent",
        "split"
    )

    val definitions: List<WatchFaceComponentDefinition> =
        ComponentRegistry.all.flatMap { kind ->
            (1..10).map { variation ->
                definitionFor(kind, variation)
            }
        }

    fun definitionsFor(kind: ComponentKind): List<WatchFaceComponentDefinition> =
        definitions.filter { it.kind == kind }

    fun byKey(key: String): WatchFaceComponentDefinition? =
        definitions.firstOrNull { it.key == key }

    // Tamanho padrão vem do próprio ComponentKind.baseSize() — nenhum `when` por tipo aqui.
    private fun definitionFor(kind: ComponentKind, variation: Int): WatchFaceComponentDefinition {
        val index = variation - 1
        val size = kind.baseSize(variation)
        return WatchFaceComponentDefinition(
            key = "${kind.id}_v$variation",
            kind = kind,
            variation = variation,
            name = "${kind.label} V$variation",
            defaultWidth = size.width,
            defaultHeight = size.height,
            defaultColor = colors[index % colors.size],
            defaultFont = fonts[index % fonts.size],
            defaultAlignment = alignments[index % alignments.size],
            styleTag = styleTags[index % styleTags.size]
        )
    }
}
