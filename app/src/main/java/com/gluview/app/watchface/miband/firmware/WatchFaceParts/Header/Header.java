package com.gluview.app.watchface.miband.firmware.WatchFaceParts.Header;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Cabeçalho de um arquivo de watchface Huami. Mi Band 6 usa {@link HeaderMiBand5}.
 *  Porte do WatchDrip (GPLv3) — getHeader() removido; instanciamos o header direto. */
public abstract class Header {
    private final int startImageIndex = 0;
    private int parametersSize;
    private String signature;

    public abstract int getParamOffset();

    public abstract int getHeaderSize();

    public abstract String getSignature();

    public boolean isValid() {
        return (signature.equals(getSignature()));
    }

    public int getStartImageIndex() {
        return startImageIndex;
    }

    public int getParametersSize() {
        return parametersSize;
    }

    public Header readFrom(InputStream stream) throws IOException {
        ByteBuffer b;
        byte[] bytes = new byte[getHeaderSize()];
        stream.read(bytes, 0, bytes.length);

        int signatureLen = getSignature().length();
        b = ByteBuffer.allocate(signatureLen);
        b.put(bytes, 0, signatureLen);
        signature = new String(b.array());

        b = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
        b.put(bytes, getParamOffset(), 4);
        b.rewind();
        parametersSize = b.getInt();
        return this;
    }
}
