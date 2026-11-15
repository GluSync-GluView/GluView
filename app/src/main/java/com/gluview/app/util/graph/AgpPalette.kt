package com.gluview.app.util.graph

/**
 * PT-4 — cores do AGP (Perfil Ambulatorial de Glicose), as mesmas na tela e no PDF. Ficam aqui, em
 * util/graph, como manda a arquitetura dos gráficos (GraphArchitectureTest): telas e exportação
 * leem daqui em vez de escrever hex próprio.
 */
object AgpPalette {
    const val VERY_LOW: Int  = 0xFFB71C1C.toInt()
    const val LOW: Int       = 0xFFE53935.toInt()
    const val TARGET: Int    = 0xFF2E7D32.toInt()
    const val HIGH: Int      = 0xFFF9A825.toInt()
    const val VERY_HIGH: Int = 0xFFEF6C00.toInt()
    const val BAND_95: Int   = 0xFF90CAF9.toInt()
    const val BAND_75: Int   = 0xFF1E88E5.toInt()
    const val MEDIAN: Int    = 0xFF0D47A1.toInt()

    /** [color] com alfa [alpha] (0..255). */
    fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)
}
