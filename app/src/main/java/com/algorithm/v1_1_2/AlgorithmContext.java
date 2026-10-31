package com.algorithm.v1_1_2;

/**
 * Contexto do algoritmo Sibionics v1.1.2 (sensor "Chinês" / Sugar Joy).
 *
 * Estrutura e nomes de campos extraídos do DEX oficial do Sugar Joy Plus
 * (com.algorithm.v1_1_2.AlgorithmContext). A .so acessa estes campos por NOME
 * via JNI (SetDoubleField/SetIntField) — os nomes e tipos DEVEM bater exatamente.
 *
 *   ig_data      = glicemia em mmol/L (saída de processAlgorithmContext)
 *   ig_trend     = tendência (inteiro; mapeado para seta no SibionicsBleSource)
 *   *Warning     = flags de alerta (corrente/glicose/temperatura)
 *   mNativeContext = ponteiro do contexto C (gerido pela .so; não tocar)
 */
public class AlgorithmContext {
    public int    currentWarning;
    public int    glucoseWarning;
    public double ig_data;
    public int    ig_trend;
    public long   mNativeContext;
    public int    temperatureWarning;

    @Override
    public String toString() {
        return "AlgorithmContext{ig_data=" + ig_data +
                ", ig_trend=" + ig_trend +
                ", currentWarning=" + currentWarning +
                ", glucoseWarning=" + glucoseWarning +
                ", temperatureWarning=" + temperatureWarning +
                ", mNativeContext=" + mNativeContext + '}';
    }
}
