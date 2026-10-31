package com.gluview.app.watchface.miband.firmware;

/** Tipos suportados pelo gerador. Mínimo para o porte: foco na Mi Band 6 (usa HeaderMiBand5). */
public enum MiBandType {
    UNKNOWN, MI_BAND4, MI_BAND5, MI_BAND6, AMAZFIT5, AMAZFITGTS2_MINI, AMAZFIT_TREX_PRO, AMAZFITBIP, AMAZFITBIPS;

    public static boolean isBip(MiBandType t)    { return t == AMAZFITBIP; }
    public static boolean isBipS(MiBandType t)   { return t == AMAZFITBIPS; }
    public static boolean isVerge1(MiBandType t) { return false; }
    public static boolean isVerge2(MiBandType t) { return false; }
}
