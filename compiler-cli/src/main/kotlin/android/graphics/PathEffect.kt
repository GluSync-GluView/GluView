package android.graphics

/** Shim de [android.graphics.PathEffect]/[android.graphics.DashPathEffect]. */
open class PathEffect

class DashPathEffect(val intervals: FloatArray, val phase: Float) : PathEffect()
