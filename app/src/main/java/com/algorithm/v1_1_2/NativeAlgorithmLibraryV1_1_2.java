package com.algorithm.v1_1_2;

/**
 * Binding JNI da libnative-algorithm-jni-v112.so (protocolo Sibionics v1.1.2,
 * sensor "Chinês" / Sugar Joy). Símbolos exportados:
 *   Java_com_algorithm_v1_11_12_NativeAlgorithmLibraryV1_11_12_*
 *   ("v1_11_12" é o mangling JNI de "v1_1_2").
 *
 * Diferente do v1.1.0, o v1.1.2 NÃO tem spiltReplyData/processEncryptAlgorithmConvert.
 * O parsing do pacote BLE é feito em Java e os valores crus são entregues a
 * processAlgorithmContext(ctx, index, d1..d5) → retorna glicemia (mmol/L) e
 * preenche ctx.ig_data / ctx.ig_trend.
 *
 * Assinaturas confirmadas via dexdump do APK oficial (Sugar Joy Plus).
 * Dependências NEEDED: native-algorithm-v1_1_2, native-encrypy-decrypt-v110,
 *                      native-sensitivity-v110, native-struct2json.
 */
public class NativeAlgorithmLibraryV1_1_2 {

    static {
        // Dependências antes da JNI, na ordem de NEEDED, para evitar surpresas de link.
        System.loadLibrary("native-sensitivity-v110");
        System.loadLibrary("native-struct2json");
        System.loadLibrary("native-encrypy-decrypt-v110");
        System.loadLibrary("native-algorithm-v1_1_2");
        System.loadLibrary("native-algorithm-jni-v112");
    }

    public static native AlgorithmContext getAlgorithmContextFromNative();

    /** Inicializa o contexto com o número de série do sensor. modo: 0 (confirmar no device). */
    public static native int initAlgorithmContext(AlgorithmContext ctx, int mode, String serial);

    /** Variante "faction" (subdivisão/fração) — alternativa de inicialização. */
    public static native int initAlgorithmContextFaction(AlgorithmContext ctx, int mode, String serial);

    /**
     * Converte um registro cru em glicemia. Recebe o índice e 5 valores crus
     * extraídos do pacote BLE. Retorna mmol/L e preenche ctx.ig_data / ctx.ig_trend.
     */
    public static native double processAlgorithmContext(
            AlgorithmContext ctx, int index, double d1, double d2, double d3, double d4, double d5);

    public static native int releaseAlgorithmContext(AlgorithmContext ctx);

    public static native String getAlgorithmVersion();
    public static native String getSensitivityVersion();
    public static native String getMyAlgorithmLibraryVersion();

    public static native String getJsonAlgorithmContext(AlgorithmContext ctx);
    public static native int setJsonAlgorithmContext(AlgorithmContext ctx, String json);
}
