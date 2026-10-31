package android.graphics

import java.awt.image.BufferedImage

/**
 * Shim de [android.graphics.Paint]. `color`/`alpha` reproduzem o desacoplamento real do Android:
 * `color = x` grava RGB E alfa (extraídos de x); `alpha = a` depois só troca o alfa, mantendo o
 * RGB — é a ordem usada em MiBandCanvasCompiler (`color = ...; alpha = ...`), então precisa bater
 * exatamente com esse comportamento.
 */
class Paint(@Suppress("UNUSED_PARAMETER") flags: Int = 0) {

    constructor(other: Paint) : this(ANTI_ALIAS_FLAG) {
        rgb = other.rgb
        alphaValue = other.alphaValue
        textSize = other.textSize
        typeface = other.typeface
        textAlign = other.textAlign
        style = other.style
        strokeWidth = other.strokeWidth
        strokeJoin = other.strokeJoin
        strokeCap = other.strokeCap
        pathEffect = other.pathEffect
    }

    enum class Align { LEFT, CENTER, RIGHT }
    enum class Style { FILL, STROKE, FILL_AND_STROKE }
    enum class Join { ROUND, MITER, BEVEL }
    enum class Cap { ROUND, BUTT, SQUARE }

    class FontMetrics(val ascent: Float, val descent: Float)

    private var rgb: Int = 0
    private var alphaValue: Int = 255

    var color: Int
        get() = (alphaValue shl 24) or rgb
        set(value) {
            alphaValue = (value ushr 24) and 0xff
            rgb = value and 0x00ffffff
        }

    var alpha: Int
        get() = alphaValue
        set(value) { alphaValue = value.coerceIn(0, 255) }

    var textSize: Float = 12f
    var typeface: Typeface = Typeface.DEFAULT
    var textAlign: Align = Align.LEFT
    var style: Style = Style.FILL
    var strokeWidth: Float = 1f
    var strokeJoin: Join = Join.MITER
    var strokeCap: Cap = Cap.BUTT
    var pathEffect: PathEffect? = null

    val fontMetrics: FontMetrics
        get() {
            val font = typeface.awtFont(textSize, bold = false)
            val fm = MEASURE_GRAPHICS.getFontMetrics(font)
            // Android: ascent é NEGATIVO (acima da baseline); descent é POSITIVO. java.awt.FontMetrics
            // usa os dois sempre positivos — precisa inverter o sinal do ascent pra bater com o
            // contrato que MiBandCanvasCompiler/GlucoseGraphRenderer esperam (fm.descent - fm.ascent).
            return FontMetrics(ascent = -fm.ascent.toFloat(), descent = fm.descent.toFloat())
        }

    companion object {
        const val ANTI_ALIAS_FLAG = 1

        // BufferedImage 1x1 só para medir fonte fora de tela (headless-safe, não precisa de display).
        private val MEASURE_GRAPHICS = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics()
    }
}
