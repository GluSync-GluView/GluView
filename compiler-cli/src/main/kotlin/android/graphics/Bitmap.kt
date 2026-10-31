package android.graphics

import java.awt.image.BufferedImage

/**
 * Shim de [android.graphics.Bitmap] sobre [BufferedImage]. `TYPE_INT_ARGB` empacota cada pixel
 * como 0xAARRGGBB — o MESMO formato de int que o Android usa em getPixel/Color — por isso
 * getRGB/setRGB batem direto com o código real (ImageMiBandPalette, PnnQuantizer) sem nenhuma
 * conversão de canal.
 */
class Bitmap private constructor(val awtImage: BufferedImage) {

    enum class Config { ARGB_8888 }

    fun getWidth(): Int = awtImage.width
    fun getHeight(): Int = awtImage.height
    fun getConfig(): Config = Config.ARGB_8888

    fun getPixel(x: Int, y: Int): Int = awtImage.getRGB(x, y)

    fun getPixels(pixels: IntArray, offset: Int, stride: Int, x: Int, y: Int, width: Int, height: Int) {
        awtImage.getRGB(x, y, width, height, pixels, offset, stride)
    }

    fun copy(@Suppress("UNUSED_PARAMETER") config: Config, @Suppress("UNUSED_PARAMETER") isMutable: Boolean): Bitmap {
        val copy = BufferedImage(awtImage.width, awtImage.height, BufferedImage.TYPE_INT_ARGB)
        val g = copy.createGraphics()
        g.drawImage(awtImage, 0, 0, null)
        g.dispose()
        return Bitmap(copy)
    }

    /** No-op — sem memória nativa a liberar nesta plataforma (BufferedImage é coletado pelo GC normal). */
    fun recycle() {}

    companion object {
        // @JvmStatic — PnnQuantizer.java (portado) chama Bitmap.createBitmap(...) como estático.
        @JvmStatic
        fun createBitmap(width: Int, height: Int, @Suppress("UNUSED_PARAMETER") config: Config): Bitmap =
            Bitmap(BufferedImage(width.coerceAtLeast(1), height.coerceAtLeast(1), BufferedImage.TYPE_INT_ARGB))

        @JvmStatic
        fun createBitmap(pixels: IntArray, width: Int, height: Int, @Suppress("UNUSED_PARAMETER") config: Config): Bitmap {
            val img = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
            img.setRGB(0, 0, width, height, pixels, 0, width)
            return Bitmap(img)
        }
    }
}
