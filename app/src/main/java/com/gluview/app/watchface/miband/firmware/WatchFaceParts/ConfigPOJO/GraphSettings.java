package com.gluview.app.watchface.miband.firmware.WatchFaceParts.ConfigPOJO;

import android.graphics.Color;

import com.google.gson.annotations.SerializedName;

/** Configuração do gráfico no visor. Simplificado: o desenho usa o renderer do GluView,
 *  então só precisamos de posição, tamanho e cor de fundo. */
public class GraphSettings {
    public Position position = new Position();
    public int width = 120;
    public int height = 80;

    @SerializedName("bg_color")
    private String bgColor = "#FFFFFF";

    public int getBgColor() {
        if (bgColor == null) {
            return Color.parseColor("#FFFFFF");
        }
        return Color.parseColor(bgColor);
    }
}
