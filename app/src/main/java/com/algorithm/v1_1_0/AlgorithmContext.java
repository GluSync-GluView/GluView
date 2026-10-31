package com.algorithm.v1_1_0;

/**
 * Espelho EXATO da classe que a biblioteca proprietária da Sibionics
 * (libnative-algorithm-jni-v110.so) lê/escreve via JNI.
 *
 * ⚠️ NÃO mudar pacote/classe/campos (nome + tipo) — a .so usa GetFieldID por estes nomes.
 * Extraída do APK SIBIONICS (uso pessoal; ver 04b_sugarjoy_analise.md).
 *
 * Após processEncryptAlgorithmConvert(...)/processAlgorithmContext(...), os campos
 * ig_data (glicemia mmol/L) e ig_trend (tendência) ficam preenchidos.
 */
public class AlgorithmContext {
    public int    currentWarning;
    public int    glucoseWarning;
    public double ig_data;
    public int    ig_trend;
    public long   mNativeContext;
    public int    temperatureWarning;

    public AlgorithmContext() {}
}
