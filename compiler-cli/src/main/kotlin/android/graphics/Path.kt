package android.graphics

import java.awt.geom.Path2D

/** Shim de [android.graphics.Path] sobre [Path2D] — só moveTo/lineTo, o único uso real hoje. */
class Path {
    val awtPath: Path2D.Float = Path2D.Float()

    fun moveTo(x: Float, y: Float) { awtPath.moveTo(x.toDouble(), y.toDouble()) }
    fun lineTo(x: Float, y: Float) { awtPath.lineTo(x.toDouble(), y.toDouble()) }
}
