package com.algorithm.v1_1_0;

/**
 * Um registro decifrado/fatiado de uma resposta do sensor.
 * spiltReplyData(...) preenche uma ArrayList destes a partir do pacote BLE cru;
 * cada um é então passado a processEncryptAlgorithmConvert(...) para virar glicemia.
 *
 * ⚠️ Campos com nomes EXATOS do APK (a .so depende deles). Não renomear.
 * (`reply_resule_or_index` é o índice/minuto do sensor — o nome tem o typo do original.)
 */
public class CGMReplyPacketContext {
    public byte[] cgm_mac_buffer;
    public byte[] encrypt_packet_data;
    public int    reply_packet_type;
    public int    reply_resule_or_index;

    public CGMReplyPacketContext() {}
}
