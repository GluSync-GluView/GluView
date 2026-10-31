package android.graphics

/** Shim de [android.graphics.RectF] — mesmos campos/métodos usados por MiBandCanvasCompiler. */
class RectF(var left: Float, var top: Float, var right: Float, var bottom: Float) {
    fun width(): Float = right - left
    fun height(): Float = bottom - top
    fun centerX(): Float = (left + right) / 2f
    fun centerY(): Float = (top + bottom) / 2f
}
