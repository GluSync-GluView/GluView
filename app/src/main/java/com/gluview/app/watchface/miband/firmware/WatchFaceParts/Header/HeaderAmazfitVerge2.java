package com.gluview.app.watchface.miband.firmware.WatchFaceParts.Header;

// Porte do WatchDrip+ (GPLv3, github.com/bigdigital/watchdrip): Header/HeaderAmazfitVerge2.java — plano 21, F7.2 (21/11/2026).

public class HeaderAmazfitVerge2 extends Header {
    private static final String headerSignature = "UIHH\2\0";
    private static final int headerSize = 88;
    private static final int paramOffset = 80;
    private final int startImageIndex = 1;

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

    @Override
    public int getStartImageIndex() {
        return startImageIndex;
    }
}
