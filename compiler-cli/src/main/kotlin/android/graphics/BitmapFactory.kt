package android.graphics

/** Shim — só existe pra `PnnQuantizer` compilar. Nunca é chamado no pipeline server-side real
 *  (o compiler-cli sempre parte de um [Bitmap] já desenhado, nunca decodifica arquivo de imagem). */
object BitmapFactory {
    @JvmStatic
    fun decodeFile(path: String): Bitmap =
        throw UnsupportedOperationException("BitmapFactory.decodeFile não é usado no compiler-cli: $path")
}
