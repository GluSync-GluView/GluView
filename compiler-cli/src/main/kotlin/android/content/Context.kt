package android.content

import android.content.res.AssetManager

/** Shim mínimo de [android.content.Context] — só o que MiBandPackageBuilder/MiBandCanvasCompiler usam. */
open class Context {
    val assets: AssetManager = AssetManager()
}
