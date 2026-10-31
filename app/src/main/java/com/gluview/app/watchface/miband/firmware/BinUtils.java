package com.gluview.app.watchface.miband.firmware;

/** Conversões little-endian usadas no formato de watchface Huami (porte do WatchDrip, GPLv3). */
public final class BinUtils {
    private BinUtils() {}

    public static byte[] fromUint16(int v) {
        return new byte[]{(byte) (v & 0xff), (byte) ((v >> 8) & 0xff)};
    }

    public static byte[] fromUint32(int v) {
        return new byte[]{
            (byte) (v & 0xff), (byte) ((v >> 8) & 0xff),
            (byte) ((v >> 16) & 0xff), (byte) ((v >> 24) & 0xff)
        };
    }
}
