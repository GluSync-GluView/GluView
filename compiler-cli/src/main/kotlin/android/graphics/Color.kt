package android.graphics

/**
 * Shim de [android.graphics.Color] pro compiler-cli (Fase 6 do plano 06_watchfaces_v2_arquitetura.md
 * — compilador oficial da loja, processo JVM companheiro do GluSync). Mesma codificação ARGB de
 * sempre (int com 0xAARRGGBB) — só reimplementa o parsing/extração sem depender do android.jar.
 * Cobre só os métodos realmente usados pelo pipeline real (MiBandCanvasCompiler,
 * GlucoseGraphRenderer, PnnQuantizer, ImageMiBandPalette, ConfigPOJO) — ver spike da Fase 6.
 */
object Color {
    const val BLACK: Int = -0x1000000 // 0xFF000000
    const val WHITE: Int = -0x1        // 0xFFFFFFFF

    // @JvmStatic — os .java portados (ImageMiBandPalette, PnnQuantizer, ConfigPOJO/*) chamam
    // Color.parseColor/alpha/red/green/blue/argb como método ESTÁTICO (contrato do android.jar
    // real); sem a anotação, um `object` Kotlin só exporia isso via Color.INSTANCE.xxx pro Java.
    @JvmStatic
    fun argb(alpha: Int, red: Int, green: Int, blue: Int): Int =
        (alpha shl 24) or (red shl 16) or (green shl 8) or blue

    @JvmStatic fun alpha(color: Int): Int = (color ushr 24) and 0xff
    @JvmStatic fun red(color: Int): Int = (color ushr 16) and 0xff
    @JvmStatic fun green(color: Int): Int = (color ushr 8) and 0xff
    @JvmStatic fun blue(color: Int): Int = color and 0xff

    private val NAMED: Map<String, Int> = mapOf(
        "black" to BLACK, "white" to WHITE,
        "red" to argb(255, 255, 0, 0), "green" to argb(255, 0, 255, 0), "blue" to argb(255, 0, 0, 255),
        "gray" to argb(255, 128, 128, 128), "darkgray" to argb(255, 68, 68, 68), "lightgray" to argb(255, 211, 211, 211),
        "transparent" to 0
    )

    /** Mesmo formato aceito pelo Android: "#RRGGBB", "#AARRGGBB" ou nome (subconjunto usado no app). */
    @JvmStatic
    fun parseColor(colorString: String): Int {
        if (colorString.startsWith("#")) {
            val hex = colorString.substring(1)
            return when (hex.length) {
                6 -> (0xFF shl 24) or hex.toLong(16).toInt()
                8 -> hex.toLong(16).toInt()
                else -> throw IllegalArgumentException("Unknown color: $colorString")
            }
        }
        return NAMED[colorString.lowercase()] ?: throw IllegalArgumentException("Unknown color: $colorString")
    }
}
