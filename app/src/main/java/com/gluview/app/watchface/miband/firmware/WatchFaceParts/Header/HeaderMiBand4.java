package com.gluview.app.watchface.miband.firmware.WatchFaceParts.Header;

// Porte do WatchDrip+ (GPLv3, github.com/bigdigital/watchdrip): Header/HeaderMiBand4.java — plano 21, F7.2 (21/11/2026).

public class HeaderMiBand4 extends Header {
    private static final String headerSignature = "HMDIAL\0";
    private static final int headerSize = 40;
    private static final int paramOffset = 36;

    @Override
    public int getParamOffset() {
        return paramOffset;
    }

    @Override
    public int getHeaderSize() {
        return headerSize;
    }

    @Override
    public String getSignature() {
        return headerSignature;
    }
}
