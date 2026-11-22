package com.gluview.app.watchface.miband.firmware.WatchFaceParts.Image;

// Porte do WatchDrip+ (GPLv3, github.com/bigdigital/watchdrip): Image/ImageBipPalette.java — plano 21, F7.2 (21/11/2026).

import android.graphics.Bitmap;
import android.graphics.Color;

import com.gluview.app.watchface.miband.firmware.WatchFaceParts.Utils.PnnQuantizer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

public class ImageBipPalette extends ImageMiBandPalette {

    static final Integer[] PALETTE_COLORS = {
            Color.BLACK,
            Color.CYAN,
            Color.GREEN,
            Color.RED,
            Color.BLUE,
            Color.YELLOW,
            Color.WHITE,
            Color.TRANSPARENT,
    };

    static int MAX_PALETTE_COLORS = 8;

    public ImageBipPalette(ByteArrayOutputStream stream) {
        super(stream);
    }

    public ImageBipPalette(ByteArrayOutputStream stream, Bitmap image) {
        super(stream, image);
    }

    Bitmap quantinizeImage(Bitmap src) throws IOException {
        PnnQuantizer pnnQuantizer = new PnnQuantizer(src);
        pnnQuantizer.setPalette(PALETTE_COLORS);
        return pnnQuantizer.convert(MAX_PALETTE_COLORS, false);
    }
}
