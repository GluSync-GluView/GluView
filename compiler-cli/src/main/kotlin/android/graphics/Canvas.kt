package android.graphics

import java.awt.BasicStroke
import java.awt.Color as AwtColor
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.geom.RoundRectangle2D

/**
 * Shim de [android.graphics.Canvas] sobre [Graphics2D]. Cobre só os métodos usados de verdade por
 * MiBandCanvasCompiler/GlucoseGraphRenderer (ver spike da Fase 6, plano
 * 06_watchfaces_v2_arquitetura.md) — desenho 2D puro, sem GPU/hardware, determinístico.
 */
class Canvas(private val bitmap: Bitmap) {
    private val g: Graphics2D = bitmap.awtImage.createGraphics().apply {
        setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
    }
    private val transformStack = ArrayDeque<AffineTransform>()

    private fun awtColor(paint: Paint): AwtColor = AwtColor(paint.color, true)

    private fun applyStroke(g: Graphics2D, paint: Paint) {
        val cap = when (paint.strokeCap) {
            Paint.Cap.ROUND -> BasicStroke.CAP_ROUND
            Paint.Cap.SQUARE -> BasicStroke.CAP_SQUARE
            Paint.Cap.BUTT -> BasicStroke.CAP_BUTT
        }
        val join = when (paint.strokeJoin) {
            Paint.Join.ROUND -> BasicStroke.JOIN_ROUND
            Paint.Join.BEVEL -> BasicStroke.JOIN_BEVEL
            Paint.Join.MITER -> BasicStroke.JOIN_MITER
        }
        val width = paint.strokeWidth.coerceAtLeast(0.01f)
        val dash = (paint.pathEffect as? DashPathEffect)?.intervals
        g.stroke = if (dash != null) {
            BasicStroke(width, cap, join, 10f, dash, (paint.pathEffect as DashPathEffect).phase)
        } else {
            BasicStroke(width, cap, join)
        }
    }

    fun drawColor(color: Int) {
        g.color = AwtColor(color, true)
        g.fillRect(0, 0, bitmap.getWidth(), bitmap.getHeight())
    }

    fun save() { transformStack.addLast(g.transform) }

    fun restore() {
        transformStack.removeLastOrNull()?.let { g.transform = it }
    }

    fun rotate(degrees: Float, px: Float, py: Float) {
        g.rotate(Math.toRadians(degrees.toDouble()), px.toDouble(), py.toDouble())
    }

    fun scale(sx: Float, sy: Float, px: Float, py: Float) {
        val at = AffineTransform()
        at.translate(px.toDouble(), py.toDouble())
        at.scale(sx.toDouble(), sy.toDouble())
        at.translate(-px.toDouble(), -py.toDouble())
        g.transform(at)
    }

    fun drawRoundRect(rect: RectF, rx: Float, ry: Float, paint: Paint) {
        val shape = RoundRectangle2D.Float(rect.left, rect.top, rect.width(), rect.height(), rx * 2, ry * 2)
        g.color = awtColor(paint)
        if (paint.style == Paint.Style.STROKE) { applyStroke(g, paint); g.draw(shape) } else g.fill(shape)
    }

    fun drawRect(left: Float, top: Float, right: Float, bottom: Float, paint: Paint) {
        g.color = awtColor(paint)
        val shape = java.awt.geom.Rectangle2D.Float(left, top, right - left, bottom - top)
        if (paint.style == Paint.Style.STROKE) { applyStroke(g, paint); g.draw(shape) } else g.fill(shape)
    }

    fun drawLine(x1: Float, y1: Float, x2: Float, y2: Float, paint: Paint) {
        g.color = awtColor(paint)
        applyStroke(g, paint)
        g.draw(Line2D.Float(x1, y1, x2, y2))
    }

    fun drawCircle(cx: Float, cy: Float, radius: Float, paint: Paint) {
        val shape = Ellipse2D.Float(cx - radius, cy - radius, radius * 2, radius * 2)
        g.color = awtColor(paint)
        if (paint.style == Paint.Style.STROKE) { applyStroke(g, paint); g.draw(shape) } else g.fill(shape)
    }

    fun drawPath(path: Path, paint: Paint) {
        g.color = awtColor(paint)
        if (paint.style == Paint.Style.STROKE) { applyStroke(g, paint); g.draw(path.awtPath) } else g.fill(path.awtPath)
    }

    fun drawBitmap(bmp: Bitmap, left: Float, top: Float, paint: Paint?) {
        val originalComposite = g.composite
        if (paint != null && paint.alpha < 255) {
            g.composite = java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, paint.alpha / 255f)
        }
        g.drawImage(bmp.awtImage, left.toInt(), top.toInt(), null)
        g.composite = originalComposite
    }

    fun drawText(text: String, x: Float, y: Float, paint: Paint) {
        val font = paint.typeface.awtFont(paint.textSize, bold = false)
        g.font = font
        g.color = awtColor(paint)
        val metrics = g.getFontMetrics(font)
        val width = metrics.stringWidth(text)
        val drawX = when (paint.textAlign) {
            Paint.Align.LEFT -> x
            Paint.Align.CENTER -> x - width / 2f
            Paint.Align.RIGHT -> x - width
        }
        g.drawString(text, drawX, y)
    }
}
