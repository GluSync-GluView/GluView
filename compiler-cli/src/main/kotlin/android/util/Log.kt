package android.util

/** Shim de [android.util.Log] — encaminha pra stderr (stdout é reservado pro contrato de saída do compiler-cli). */
object Log {
    private fun emit(level: String, tag: String, msg: String): Int {
        System.err.println("[$level/$tag] $msg")
        return 0
    }

    @JvmStatic fun d(tag: String, msg: String): Int = emit("D", tag, msg)
    @JvmStatic fun e(tag: String, msg: String): Int = emit("E", tag, msg)
    @JvmStatic fun w(tag: String, msg: String): Int = emit("W", tag, msg)
    @JvmStatic fun i(tag: String, msg: String): Int = emit("I", tag, msg)
}
