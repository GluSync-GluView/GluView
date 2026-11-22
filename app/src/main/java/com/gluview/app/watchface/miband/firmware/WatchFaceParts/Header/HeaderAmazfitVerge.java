package com.gluview.app.watchface.miband.firmware.WatchFaceParts.Header;

// Porte do WatchDrip+ (GPLv3, github.com/bigdigital/watchdrip): Header/HeaderAmazfitVerge.java — plano 21, F7.2 (21/11/2026).

public class HeaderAmazfitVerge extends Header {
    private static final String headerSignature = "HMDIAL\0";
    private static final int headerSize = 64;
    private static final int paramOffset = 56;

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
