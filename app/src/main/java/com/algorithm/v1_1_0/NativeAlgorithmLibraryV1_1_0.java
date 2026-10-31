package com.algorithm.v1_1_0;

import java.util.ArrayList;

/**
 * Pipeline completo do sensor Sibionics, exposto pela libnative-algorithm-jni-v110.so
 * (símbolos Java_com_algorithm_v1_11_10_NativeAlgorithmLibraryV1_11_10_*; "v1_11_10" é só
 * o mangling JNI de "v1_1_0").
 *
 * Fluxo:
 *   ctx  = getAlgorithmContextFromNative()
 *   initEncryptAlgorithmContext(ctx, modo, serial)        // sensor criptografado (internacional)
 *   spiltReplyData(ctx, ?, pacoteBLE, len, lista, ?)      // DECIFRA + fatia → CGMReplyPacketContext[]
 *   processEncryptAlgorithmConvert(ctx, index, pkt, a, b) // registro → ctx.ig_data/ig_trend
 *
 * A decifragem acontece DENTRO da .so (que importa global_app_decrypt/global_spilt_reply_data
 * de libnative-encrypy-decrypt-v110.so). Não há criptografia a quebrar — só chamar.
 *
 * ⚠️ As .so vão em app/src/main/jniLibs/<abi>/ (extraídas do APK SIBIONICS, uso pessoal).
 * Os parâmetros `int` (modo/tipo) e os códigos de sucesso são finalizados no device (SB01).
 */
public class NativeAlgorithmLibraryV1_1_0 {

    static {
        // Dependências antes da JNI. A jni-v110 declara as deps como NEEDED, então em
        // jniLibs/<abi> o linker as resolve; o load explícito evita surpresas de ordem.
        System.loadLibrary("native-sensitivity-v110");
        System.loadLibrary("native-struct2json");
        System.loadLibrary("native-encrypy-decrypt-v110");
        System.loadLibrary("native-algorithm-v1_1_0");
        System.loadLibrary("native-algorithm-jni-v110");
    }

    public static native AlgorithmContext getAlgorithmContextFromNative();

    public static native int initAlgorithmContext(AlgorithmContext ctx, String serial);
    public static native int initEncryptAlgorithmContext(AlgorithmContext ctx, int mode, String serial);

    /**
     * Decifra e fatia um pacote BLE cru em registros.
     * @param out preenchida com CGMReplyPacketContext (um por leitura no pacote)
     * @return nº de registros (>0) ou código de erro
     */
    public static native int spiltReplyData(
            AlgorithmContext ctx, int type, byte[] data, int len, ArrayList<CGMReplyPacketContext> out, int flag);

    /** Processa um registro decifrado → preenche ctx.ig_data (mmol/L) e ctx.ig_trend. */
    public static native int processEncryptAlgorithmConvert(
            AlgorithmContext ctx, int index, CGMReplyPacketContext packet, double targetLow, double targetHigh);

    /** Calibração "plana" (registro já decifrado em valor cru). */
    public static native double processAlgorithmContext(
            AlgorithmContext ctx, int index, double value, double temp, double reserved,
            double targetLow, double targetHigh);

    public static native int releaseAlgorithmContext(AlgorithmContext ctx);

    public static native CGMReplyPacketContext getCGMReplyPacket();
    public static native ArrayList<CGMReplyPacketContext> getCGMReplyPacketList();
}
