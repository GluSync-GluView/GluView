package android.graphics

import android.content.res.AssetManager
import java.awt.Font as AwtFont

/**
 * Shim de [android.graphics.Typeface] sobre [java.awt.Font]. `createFromAsset` carrega o .ttf de
 * verdade do classpath (mesmo Roboto usado no app) — texto do visor gerado no servidor usa a
 * mesma fonte, não uma substituta genérica.
 */
class Typeface private constructor(private val base: AwtFont) {

    fun awtFont(size: Float, bold: Boolean): AwtFont {
        val style = if (bold) AwtFont.BOLD else AwtFont.PLAIN
        return base.deriveFont(style, size)
    }

    companion object {
        const val NORMAL = 0
        const val BOLD = 1

        val DEFAULT = Typeface(AwtFont("SansSerif", AwtFont.PLAIN, 12))
        val DEFAULT_BOLD = Typeface(AwtFont("SansSerif", AwtFont.BOLD, 12))
        val SERIF = Typeface(AwtFont("Serif", AwtFont.PLAIN, 12))
        val MONOSPACE = Typeface(AwtFont("Monospaced", AwtFont.PLAIN, 12))
        val SANS_SERIF = Typeface(AwtFont("SansSerif", AwtFont.PLAIN, 12))

        fun create(base: Typeface, style: Int): Typeface =
            Typeface(base.base.deriveFont(if (style == BOLD) AwtFont.BOLD else AwtFont.PLAIN))

        fun createFromAsset(assets: AssetManager, path: String): Typeface {
            val loaded = assets.open(path).use { AwtFont.createFont(AwtFont.TRUETYPE_FONT, it) }
            return Typeface(loaded.deriveFont(12f))
        }
    }
}
