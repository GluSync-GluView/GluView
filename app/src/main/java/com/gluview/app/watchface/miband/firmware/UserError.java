package com.gluview.app.watchface.miband.firmware;

/** Stub de log para o subsistema portado do WatchDrip — encaminha para android.util.Log. */
public final class UserError {
    private UserError() {}

    public static final class Log {
        public static void d(String tag, String msg) { android.util.Log.d(tag, msg); }
        public static void e(String tag, String msg) { android.util.Log.e(tag, msg); }
        public static void w(String tag, String msg) { android.util.Log.w(tag, msg); }
        public static void i(String tag, String msg) { android.util.Log.i(tag, msg); }
    }
}
